/** 一个正在播放的片段句柄：Blob 音频与 WebSpeech 共用同一抽象 */
export interface PlayHandle {
  /** 自然播完时 resolve；被 stop/出错时 reject */
  readonly ended: Promise<void>
  pause(): void
  resume(): void
  stop(): void
  setRate(rate: number): void
}

export class AudioHandle implements PlayHandle {
  private audio: HTMLAudioElement
  private objectUrl: string
  readonly ended: Promise<void>
  private settle!: { resolve: () => void; reject: (e: Error) => void }

  constructor(blob: Blob, rate: number) {
    this.objectUrl = URL.createObjectURL(blob)
    this.audio = new Audio(this.objectUrl)
    this.audio.playbackRate = rate
    this.ended = new Promise<void>((resolve, reject) => {
      this.settle = { resolve, reject }
    })
    this.ended.catch(() => {})
    this.audio.onended = () => {
      this.cleanup()
      this.settle.resolve()
    }
    this.audio.onerror = () => {
      this.cleanup()
      this.settle.reject(new Error('音频播放出错'))
    }
    void this.audio.play().catch(e => {
      this.cleanup()
      this.settle.reject(e instanceof Error ? e : new Error(String(e)))
    })
  }
  private cleanup() {
    URL.revokeObjectURL(this.objectUrl)
  }
  pause() { this.audio.pause() }
  resume() { void this.audio.play().catch(() => {}) }
  stop() {
    this.audio.pause()
    this.cleanup()
    this.settle.reject(new Error('aborted'))
  }
  setRate(rate: number) { this.audio.playbackRate = rate }
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
