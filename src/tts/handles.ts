/** 一个正在播放的片段句柄：Blob 音频与 WebSpeech 共用同一抽象 */
export interface PlayHandle {
  /** 自然播完时 resolve；被 stop/出错时 reject */
  readonly ended: Promise<void>
  pause(): void
  resume(): void
  stop(): void
  setRate(rate: number): void
}

/** 极短静音 mp3：手势解锁与段间保活共用的占位音源 */
const SILENT_AUDIO =
  'data:audio/mp3;base64,//uQZAAAAAAAAAAAAAAAAAAAAAAAWGluZwAAAA8AAAACAAACcQCA' +
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' +
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAA//sQZAAP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADS' +
  'AAAAETEFNRTMuMTAwVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV'

/**
 * 模块级共享音频元素：iOS 后台/锁屏下新建元素的 play() 会被自动播放策略
 * 拦截，只有被用户手势激活过的元素不受限，故所有 Blob 片段复用同一元素
 */
let sharedAudio: HTMLAudioElement | null = null
let primed = false
/** 保活窗口计数：代际交替时新旧窗口可能短暂重叠，归零才真正暂停 */
let keepAliveCount = 0

function getSharedAudio(): HTMLAudioElement | null {
  if (typeof Audio === 'undefined') return null
  if (!sharedAudio) {
    try {
      sharedAudio = new Audio()
    } catch {
      return null
    }
  }
  return sharedAudio
}

/** 在用户手势内调用：静音播放一次为共享元素取得播放授权（幂等） */
export function primeSharedAudio() {
  if (primed) return
  primed = true
  const audio = getSharedAudio()
  if (!audio) return
  try {
    audio.loop = false
    audio.volume = 0
    audio.src = SILENT_AUDIO
    void audio.play().catch(() => {})
  } catch {
    /* ignore */
  }
}

/** 段间保活：合成窗口内共享元素静音循环，防止 iOS 因静默收回后台音频权 */
export function keepAliveStart() {
  keepAliveCount++
  if (keepAliveCount > 1) return
  const audio = getSharedAudio()
  if (!audio) return
  try {
    audio.loop = true
    audio.volume = 0
    audio.src = SILENT_AUDIO
    void audio.play().catch(() => {})
  } catch {
    /* ignore */
  }
}

/** 结束保活：仅当静音循环仍归保活持有时暂停（已被 AudioHandle 接管则为空操作） */
export function keepAliveStop() {
  if (keepAliveCount === 0) return
  keepAliveCount--
  if (keepAliveCount > 0) return
  const audio = getSharedAudio()
  if (!audio) return
  try {
    audio.pause()
    audio.loop = false
  } catch {
    /* ignore */
  }
}

/**
 * Blob 音频句柄：复用共享元素播放（后台新建元素会被自动播放策略拦截）。
 * 引擎保证同一时刻至多一个 AudioHandle 活跃（上一句柄 stop/ended 之后才
 * 构造下一个），故共享元素的 src 与事件处理器归当前句柄独占；cleanup 必须
 * 摘除处理器，防止残留回调误伤下一任占用者
 */
export class AudioHandle implements PlayHandle {
  private audio: HTMLAudioElement | null
  private objectUrl = ''
  readonly ended: Promise<void>
  private settle!: { resolve: () => void; reject: (e: Error) => void }

  constructor(blob: Blob, rate: number) {
    this.ended = new Promise<void>((resolve, reject) => {
      this.settle = { resolve, reject }
    })
    this.ended.catch(() => {})
    // 接管共享元素：结束保活占用，并复位保活遗留的循环静音状态
    keepAliveCount = 0
    const audio = getSharedAudio()
    this.audio = audio
    if (!audio) {
      this.settle.reject(new Error('音频不可用'))
      return
    }
    try {
      this.objectUrl = URL.createObjectURL(blob)
      audio.loop = false
      audio.volume = 1
      audio.src = this.objectUrl
      audio.playbackRate = rate
      audio.onended = () => {
        this.cleanup()
        this.settle.resolve()
      }
      audio.onerror = () => {
        this.cleanup()
        this.settle.reject(new Error('音频播放出错'))
      }
      void audio.play().catch(e => {
        this.cleanup()
        this.settle.reject(e instanceof Error ? e : new Error(String(e)))
      })
    } catch (e) {
      this.cleanup()
      this.settle.reject(e instanceof Error ? e : new Error(String(e)))
    }
  }
  private cleanup() {
    const audio = this.audio
    this.audio = null
    if (audio) {
      audio.onended = null
      audio.onerror = null
    }
    if (this.objectUrl) {
      try {
        URL.revokeObjectURL(this.objectUrl)
      } catch {
        /* ignore */
      }
      this.objectUrl = ''
    }
  }
  pause() {
    try {
      this.audio?.pause()
    } catch {
      /* ignore */
    }
  }
  resume() {
    try {
      void this.audio?.play().catch(() => {})
    } catch {
      /* ignore */
    }
  }
  stop() {
    try {
      this.audio?.pause()
    } catch {
      /* ignore */
    }
    this.cleanup()
    this.settle.reject(new Error('aborted'))
  }
  setRate(rate: number) {
    try {
      if (this.audio) this.audio.playbackRate = rate
    } catch {
      /* ignore */
    }
  }
}

/** 音色列表异步加载：模块级缓存 + voiceschanged 更新（首次调用常为空数组） */
let cachedVoices: SpeechSynthesisVoice[] = []
if (typeof speechSynthesis !== 'undefined') {
  const refresh = () => {
    const v = speechSynthesis.getVoices()
    if (v.length) cachedVoices = v
  }
  refresh()
  speechSynthesis.onvoiceschanged = refresh
}

export class UtteranceHandle implements PlayHandle {
  private utter: SpeechSynthesisUtterance
  readonly ended: Promise<void>
  private settle!: { resolve: () => void; reject: (e: Error) => void }

  constructor(text: string, rate: number) {
    this.ended = new Promise<void>((resolve, reject) => {
      this.settle = { resolve, reject }
    })
    this.ended.catch(() => {})
    this.utter = new SpeechSynthesisUtterance(text)
    this.utter.rate = rate
    const zh = cachedVoices.find(v => /zh|cmn/i.test(v.lang))
    if (zh) this.utter.voice = zh
    this.utter.onend = () => this.settle.resolve()
    this.utter.onerror = e => {
      if (e.error === 'interrupted' || e.error === 'canceled') this.settle.reject(new Error('aborted'))
      else this.settle.reject(new Error(`语音合成出错：${e.error}`))
    }
    speechSynthesis.speak(this.utter)
  }
  pause() { speechSynthesis.pause() }
  resume() { speechSynthesis.resume() }
  stop() {
    speechSynthesis.cancel()
    this.settle.reject(new Error('aborted'))
  }
  setRate(rate: number) { this.utter.rate = rate }
}
