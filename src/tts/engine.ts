import type { OpenAISpeechConfig, PlayerState, Range, TTSSettings } from '../types'
import { audioGet, audioPut } from '../lib/db'
import { getDerivedChapter, type DerivedChapter } from '../lib/chapters'
import { segmentIndexAt } from '../lib/segment'
import { cyrb53 } from '../lib/hash'
import { synthesizeOpenAI } from './providers/openai-speech'
import { AudioHandle, UtteranceHandle, type PlayHandle } from './handles'

export interface EngineSnapshot {
  state: PlayerState
  bookId: string
  chapterIndex: number
  chapterTitle: string
  segmentIndex: number
  segmentCount: number
  segmentStart: number
  segmentEnd: number
  /** 正在调用 TTS 接口合成（尚未开始出声） */
  synthesizing: boolean
  error?: string
}

type Listener = (snap: EngineSnapshot) => void

const MAX_RETRIES = 2

/**
 * 朗读引擎：只持有 派生章节引用 + 当前片段下标 两个核心状态。
 * 并发模型：所有可能产生在途异步工作的方法（load/seek/合成中暂停）
 * 都递增 generation；loop 的每次恢复执行先校验代际，过期即退出。
 */
export class TTSEngine {
  private settings: TTSSettings
  private openaiCfg: OpenAISpeechConfig

  private listeners = new Set<Listener>()

  private state: PlayerState = 'idle'
  private bookId = ''
  private chapterCount = 0
  private derived: DerivedChapter | null = null
  private chapterIndex = -1
  private segmentIndex = 0

  private handle: PlayHandle | null = null
  private aborter: AbortController | null = null
  private generation = 0
  private synthesizing = false
  private errorMsg = ''
  private prefetching = new Set<string>()

  constructor(settings: TTSSettings, openaiCfg: OpenAISpeechConfig) {
    this.settings = settings
    this.openaiCfg = openaiCfg
  }

  /* ---------- 事件 ---------- */

  on(l: Listener) {
    this.listeners.add(l)
    l(this.snapshot())
    return () => this.listeners.delete(l)
  }

  private emit() {
    const snap = this.snapshot()
    this.listeners.forEach(l => l(snap))
  }

  snapshot(): EngineSnapshot {
    const seg = this.derived?.segments[this.segmentIndex]
    return {
      state: this.state,
      bookId: this.bookId,
      chapterIndex: this.chapterIndex,
      chapterTitle: this.derived?.title ?? '',
      segmentIndex: this.segmentIndex,
      segmentCount: this.derived?.segments.length ?? 0,
      segmentStart: seg?.start ?? 0,
      segmentEnd: seg?.end ?? 0,
      synthesizing: this.synthesizing,
      error: this.errorMsg || undefined
    }
  }

  updateConfig(settings: TTSSettings, openaiCfg: OpenAISpeechConfig) {
    this.settings = settings
    this.openaiCfg = openaiCfg
    this.handle?.setRate(settings.rate)
  }

  /** 片段长度等分段参数变化后，原地按当前偏移重载（保持播放状态） */
  async reload() {
    if (this.state === 'idle' || this.state === 'loading' || this.chapterIndex < 0) return
    const offset = this.derived?.segments[this.segmentIndex]?.start ?? 0
    const playing = this.state === 'playing'
    if ((await this.load(this.bookId, this.chapterIndex, offset, this.chapterCount)) && playing) {
      await this.play()
    }
  }

  /* ---------- 章节装载 ---------- */

  /** 装载成功返回 true；被更新的装载取代或章节缺失返回 false */
  async load(bookId: string, chapterIndex: number, offset: number, chapterCount: number): Promise<boolean> {
    this.generation++
    const gen = this.generation
    this.stopHandle()
    this.state = 'loading'
    this.errorMsg = ''
    this.bookId = bookId
    this.chapterCount = chapterCount
    // 装载期间快照归零，避免新旧章节混杂
    this.derived = null
    this.chapterIndex = -1
    this.emit()

    const derived = await getDerivedChapter(bookId, chapterIndex, this.settings.maxChunkChars)
    if (gen !== this.generation) return false
    if (!derived) {
      this.state = 'error'
      this.errorMsg = '章节内容缺失'
      this.emit()
      return false
    }
    this.derived = derived
    this.chapterIndex = chapterIndex
    this.segmentIndex = derived.segments.length ? segmentIndexAt(derived.segments, offset) : 0
    this.state = 'paused'
    this.emit()
    return true
  }

  /* ---------- 播放控制 ---------- */

  /** iOS/Safari 音频自动播放限制：在用户手势内先解锁音频栈 */
  private static audioUnlocked = false
  private unlockAudio() {
    if (TTSEngine.audioUnlocked) return
    TTSEngine.audioUnlocked = true
    try {
      const a = new Audio(
        'data:audio/mp3;base64,//uQZAAAAAAAAAAAAAAAAAAAAAAAWGluZwAAAA8AAAACAAACcQCA' +
        'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
        'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
        'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
        'AAAAAAAAAAAAAAAAAAAAAAAAAAAA//sQZAAP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADS' +
        'AAAAETEFNRTMuMTAwVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV'
      )
      a.volume = 0
      void a.play().catch(() => {})
    } catch {
      /* ignore */
    }
  }

  async play() {
    if (this.state !== 'paused' && this.state !== 'error') return
    if (!this.derived) return
    this.unlockAudio()
    if (this.handle) {
      this.handle.resume()
      this.state = 'playing'
      this.emit()
      return
    }
    this.errorMsg = ''
    this.state = 'playing'
    this.emit()
    void this.loop(this.generation)
  }

  pause() {
    if (this.state !== 'playing') return
    if (this.handle) {
      // 正常播放中：句柄级暂停，可被 resume 续上，loop 仍在等 ended
      this.handle.pause()
    } else if (this.synthesizing) {
      // 合成窗口期：中止在途请求并换代，旧 loop 经代际守卫退出（防止暂停后仍出声）
      this.generation++
      this.stopHandle()
    }
    this.state = 'paused'
    this.emit()
  }

  async toggle() {
    if (this.state === 'playing') this.pause()
    else await this.play()
  }

  /** 任意字跳转：将朗读位置定位到章节文本的指定字符偏移 */
  seekToOffset(offset: number) {
    const segments = this.derived?.segments
    if (!segments?.length) return
    this.generation++
    const wasPlaying = this.state === 'playing'
    this.stopHandle()
    this.segmentIndex = segmentIndexAt(segments, offset)
    this.errorMsg = ''
    if (wasPlaying) {
      this.state = 'playing'
      this.emit()
      void this.loop(this.generation)
    } else {
      if (this.state !== 'idle') this.state = 'paused'
      this.emit()
    }
  }

  /** 跳章节并从头朗读（或停在开头） */
  async gotoChapter(chapterIndex: number, autoplay: boolean) {
    const wasPlaying = autoplay || this.state === 'playing'
    if ((await this.load(this.bookId, chapterIndex, 0, this.chapterCount)) && wasPlaying) {
      await this.play()
    }
  }

  get hasNextChapter() {
    return this.chapterIndex >= 0 && this.chapterIndex < this.chapterCount - 1
  }
  get hasPrevChapter() {
    return this.chapterIndex > 0
  }

  /* ---------- 主循环 ---------- */

  private stopHandle() {
    this.synthesizing = false
    this.aborter?.abort()
    this.aborter = null
    this.handle?.stop()
    this.handle = null
  }

  private slice(seg: Range): string {
    return this.derived!.text.slice(seg.start, seg.end)
  }

  private async loop(startGen: number) {
    let gen = startGen
    while (true) {
      if (gen !== this.generation || this.state !== 'playing' || !this.derived) return
      const segments = this.derived.segments
      if (this.segmentIndex >= segments.length) {
        // 本章播完 → 自动下一章（load 内部换代，成功后更新本地 gen 续播）
        if (this.hasNextChapter) {
          if (!(await this.load(this.bookId, this.chapterIndex + 1, 0, this.chapterCount))) return
          gen = this.generation
          this.state = 'playing'
          this.emit()
          continue
        }
        this.state = 'paused'
        this.emit()
        return
      }

      const seg = segments[this.segmentIndex]
      this.emit() // 更新高亮

      let handle: PlayHandle
      try {
        this.synthesizing = true
        this.emit()
        handle = await this.createHandle(this.slice(seg), gen)
      } catch (e: any) {
        this.synthesizing = false
        if (gen !== this.generation) return
        if (e?.message === 'aborted') return
        this.state = 'error'
        this.errorMsg = e?.message ?? String(e)
        this.emit()
        return
      }
      this.synthesizing = false
      // 合成窗口期被 pause/seek/换章：句柄不落地，直接停掉（防孤儿音频）
      if (gen !== this.generation || this.state !== 'playing') {
        handle.stop()
        return
      }
      this.handle = handle
      this.prefetchFrom(this.segmentIndex + 1)

      try {
        await handle.ended
      } catch {
        // 被打断（seek/pause 内部 stop）或播放错误
        if (gen !== this.generation) return
        if (this.state === 'playing') {
          this.state = 'error'
          this.errorMsg = '播放中断'
          this.emit()
        }
        return
      }
      if (gen !== this.generation) return
      if (this.handle === handle) this.handle = null
      this.segmentIndex++
      this.emit()
    }
  }

  private cacheKey(text: string) {
    const c = this.openaiCfg
    return cyrb53(`${this.settings.provider}|${c.model}|${c.voice}|${c.format}|${text}`)
  }

  /** 合成一个片段并返回可播放句柄（带缓存、重试与中止） */
  private async createHandle(text: string, gen: number): Promise<PlayHandle> {
    if (this.settings.provider === 'webspeech') {
      return new UtteranceHandle(text, this.settings.rate)
    }

    const key = this.cacheKey(text)
    const cached = await audioGet(key)
    if (gen !== this.generation) throw new Error('aborted')
    if (cached) return new AudioHandle(cached, this.settings.rate)

    const aborter = (this.aborter = new AbortController())
    let lastErr: any = null
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      if (gen !== this.generation) throw new Error('aborted')
      try {
        const blob = await synthesizeOpenAI(this.openaiCfg, text, aborter.signal)
        if (gen !== this.generation) throw new Error('aborted')
        void audioPut(key, blob).catch(() => {})
        return new AudioHandle(blob, this.settings.rate)
      } catch (e: any) {
        if (e?.message === 'aborted' || e?.name === 'AbortError') throw new Error('aborted')
        lastErr = e
        if (attempt < MAX_RETRIES) {
          await new Promise(r => setTimeout(r, 800 * (attempt + 1)))
        }
      }
    }
    throw lastErr
  }

  /** 后台预取后续 N 个片段到缓存（换代后静默丢弃结果） */
  private prefetchFrom(index: number) {
    if (this.settings.provider === 'webspeech' || !this.derived) return
    const gen = this.generation
    const segments = this.derived.segments
    const n = this.settings.prefetch
    for (let i = index; i < Math.min(index + n, segments.length); i++) {
      const text = this.slice(segments[i])
      const key = this.cacheKey(text)
      if (this.prefetching.has(key)) continue
      this.prefetching.add(key)
      void (async () => {
        try {
          const cached = await audioGet(key)
          if (cached) return
          const blob = await synthesizeOpenAI(this.openaiCfg, text)
          if (gen !== this.generation) return
          await audioPut(key, blob)
        } catch {
          /* 预取失败静默 */
        } finally {
          this.prefetching.delete(key)
        }
      })()
    }
  }
}
