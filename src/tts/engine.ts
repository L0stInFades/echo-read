import type { OpenAISpeechConfig, PlayerState, Range, TTSSettings } from '../types'
import { audioGet, audioPut } from '../lib/db'
import { getDerivedChapter, type DerivedChapter } from '../lib/chapters'
import { segmentIndexAt } from '../lib/segment'
import { cyrb53 } from '../lib/hash'
import { isFatalSpeechError, synthesizeOpenAI } from './providers/openai-speech'
import { AudioHandle, UtteranceHandle, keepAliveStart, keepAliveStop, primeSharedAudio, type PlayHandle } from './handles'

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
  /** 自愈进行时的可见提示（退避倒计时/跳段说明），空闲时缺省 */
  retryNote?: string
  error?: string
}

type Listener = (snap: EngineSnapshot) => void

/** 单个片段的合成尝试总数上限（含首次） */
const MAX_ATTEMPTS = 8
/** 连续多少个片段合成失败（各自穷尽重试）才停播报错 */
const MAX_FAIL_STREAK = 3

/** 指数退避延迟（毫秒）：1s 起倍增、30s 封顶、±20% 抖动；attempt 从 0 计，rand 可注入便于测试 */
export function backoffDelay(attempt: number, rand: () => number = Math.random): number {
  const base = Math.min(1000 * 2 ** attempt, 30000)
  return Math.round(base * (1 + (rand() * 2 - 1) * 0.2))
}

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
  private retryNote = ''
  /** 连续合成失败的片段数，任一片段成功播出即清零 */
  private failStreak = 0
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
      retryNote: this.retryNote || undefined,
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
    primeSharedAudio()
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
    this.failStreak = 0
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
    this.failStreak = 0
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
    this.retryNote = ''
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
      // 合成窗口保活：Blob 路径以静音循环占住后台音频权；webspeech 无此需要
      const keepAlive = this.settings.provider !== 'webspeech'
      try {
        this.synthesizing = true
        this.emit()
        if (keepAlive) keepAliveStart()
        handle = await this.createHandle(this.slice(seg), gen)
      } catch (e: any) {
        // 僵尸 loop（已换代）不得回写共享合成状态；keepAliveStop 与本次 keepAliveStart 配对，必须无条件执行
        if (gen === this.generation) this.synthesizing = false
        if (keepAlive) keepAliveStop()
        if (gen !== this.generation) return
        if (e?.message === 'aborted') return
        if (isFatalSpeechError(e)) {
          // 配置类错误（无效 Key 等）：跳段无意义，立即停播暴露给用户
          this.state = 'error'
          this.errorMsg = e?.message ?? String(e)
          this.retryNote = ''
          this.emit()
          return
        }
        // 单段重试穷尽：跳过本段续播，连续多段失败才认定环境不可用
        this.failStreak++
        if (this.failStreak >= MAX_FAIL_STREAK) {
          this.state = 'error'
          this.errorMsg = '连续多段合成失败，请检查网络或 TTS 配置'
          this.retryNote = ''
          this.emit()
          return
        }
        this.retryNote = '本段合成失败，已跳过'
        this.segmentIndex++
        this.emit()
        continue
      }
      if (gen === this.generation) this.synthesizing = false
      if (keepAlive) keepAliveStop()
      // 合成窗口期被 pause/seek/换章：句柄不落地，直接停掉（防孤儿音频）
      if (gen !== this.generation || this.state !== 'playing') {
        handle.stop()
        return
      }
      this.handle = handle
      this.failStreak = 0
      this.emit() // 句柄落地：广播合成结束，出声期间不再显示「合成中」
      this.prefetchFrom(this.segmentIndex + 1)

      try {
        await handle.ended
      } catch {
        // 被打断（seek/pause 内部 stop）或播放错误；死句柄必须摘除，否则 play() 误走 resume 分支成假播放
        if (this.handle === handle) this.handle = null
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

  private cacheKey(text: string, c: OpenAISpeechConfig = this.openaiCfg) {
    return cyrb53(`${this.settings.provider}|${c.model}|${c.voice}|${c.format}|${c.instructions ?? ''}|${text}`)
  }

  /** 合成一个片段并返回可播放句柄（带缓存、指数退避重试与可中止等待） */
  private async createHandle(text: string, gen: number): Promise<PlayHandle> {
    if (this.settings.provider === 'webspeech') {
      return new UtteranceHandle(text, this.settings.rate)
    }

    // 退避重试窗口可达分钟级，期间 updateConfig 改音色/模型不换代：
    // 缓存键与全部重试锚定入口配置快照，防止新配置的音频写入旧配置的缓存键
    const cfg = this.openaiCfg
    const key = this.cacheKey(text, cfg)
    // 缓存读失败只当 miss（与写侧 audioPut 的尽力而为对称），不拦合成
    const cached = await audioGet(key).catch(() => undefined)
    if (gen !== this.generation) throw new Error('aborted')
    if (cached) {
      this.setRetryNote('', gen)
      return new AudioHandle(cached, this.settings.rate)
    }

    const aborter = (this.aborter = new AbortController())
    let lastErr: any = null
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      if (gen !== this.generation) throw new Error('aborted')
      try {
        const blob = await synthesizeOpenAI(cfg, text, aborter.signal)
        if (gen !== this.generation) throw new Error('aborted')
        void audioPut(key, blob).catch(() => {})
        this.setRetryNote('', gen)
        return new AudioHandle(blob, this.settings.rate)
      } catch (e: any) {
        if (e?.message === 'aborted' || e?.name === 'AbortError') throw new Error('aborted')
        // 配置类错误（401/404 等）重试救不了：立即上抛，保证无 Key 场景快速报错
        if (isFatalSpeechError(e)) throw e
        lastErr = e
      }
      if (attempt < MAX_ATTEMPTS - 1) {
        const delay = backoffDelay(attempt)
        this.setRetryNote(`网络异常，${Math.round(delay / 1000)} 秒后重试（第 ${attempt + 2}/${MAX_ATTEMPTS} 次）`, gen)
        await this.abortableSleep(delay, gen)
      }
    }
    throw lastErr
  }

  /** 更新自愈提示并广播；换代后（暂停/跳转）静默忽略，防僵尸 loop 污染新状态 */
  private setRetryNote(note: string, gen: number) {
    if (gen !== this.generation || this.retryNote === note) return
    this.retryNote = note
    this.emit()
  }

  /** 可中止睡眠：拆成 ≤500ms 分片，每次醒来查代际，暂停/跳转后最迟 500ms 退出 */
  private async abortableSleep(ms: number, gen: number) {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      const chunk = Math.min(deadline - Date.now(), 500)
      await new Promise(r => setTimeout(r, chunk))
      if (gen !== this.generation) throw new Error('aborted')
    }
  }

  /** 后台预取后续 N 个片段到缓存（换代后静默丢弃结果） */
  private prefetchFrom(index: number) {
    if (this.settings.provider === 'webspeech' || !this.derived) return
    const gen = this.generation
    const cfg = this.openaiCfg
    const segments = this.derived.segments
    const n = this.settings.prefetch
    for (let i = index; i < Math.min(index + n, segments.length); i++) {
      const text = this.slice(segments[i])
      const key = this.cacheKey(text, cfg)
      if (this.prefetching.has(key)) continue
      this.prefetching.add(key)
      void (async () => {
        try {
          const cached = await audioGet(key)
          if (cached) return
          const blob = await synthesizeOpenAI(cfg, text)
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
