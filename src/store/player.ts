import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { TTSEngine, type EngineSnapshot } from '../tts/engine'
import { useSettingsStore } from './settings'
import { getBook } from '../lib/db'
import { toast } from '../lib/toast'

/** 睡眠定时模式：off = 关闭；数字 = 分钟数；chapter = 播完本章 */
export type SleepMode = 'off' | number | 'chapter'

export const usePlayerStore = defineStore('player', () => {
  const settings = useSettingsStore()
  const engine = new TTSEngine(settings.tts, settings.openaiConfig())
  const snap = ref<EngineSnapshot>(engine.snapshot())
  const bookTitle = ref('')

  engine.on(s => {
    snap.value = s
    updateMediaSession(s)
  })

  // 设置变化时同步给引擎（模型/音色/倍速/片段长度等）
  watch(
    settings.tts,
    () => engine.updateConfig(settings.tts, settings.openaiConfig()),
    { deep: true }
  )
  // 片段长度变化 → 引擎按当前偏移原地重载（视图经派生缓存自动跟进）。
  // 滑块每 tick 都触发，去抖收敛，避免 reload 连发竞态
  let reloadTimer: ReturnType<typeof setTimeout> | null = null
  watch(
    () => settings.tts.maxChunkChars,
    () => {
      if (reloadTimer) clearTimeout(reloadTimer)
      reloadTimer = setTimeout(() => void engine.reload(), 250)
    }
  )

  /* 锁屏/控制中心媒体控制（移动端必备） */
  function updateMediaSession(s: EngineSnapshot) {
    if (!('mediaSession' in navigator)) return
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: s.chapterTitle || bookTitle.value || 'EchoRead',
        artist: bookTitle.value || 'EchoRead',
        album: 'AI 听书'
      })
      navigator.mediaSession.playbackState =
        s.state === 'playing' ? 'playing' : s.state === 'paused' ? 'paused' : 'none'
    } catch {
      /* ignore */
    }
  }

  function bindMediaSessionHandlers() {
    if (!('mediaSession' in navigator)) return
    try {
      navigator.mediaSession.setActionHandler('play', () => void engine.play())
      navigator.mediaSession.setActionHandler('pause', () => engine.pause())
      navigator.mediaSession.setActionHandler('previoustrack', () => {
        if (engine.hasPrevChapter) void engine.gotoChapter(snap.value.chapterIndex - 1, true)
      })
      navigator.mediaSession.setActionHandler('nexttrack', () => {
        if (engine.hasNextChapter) void engine.gotoChapter(snap.value.chapterIndex + 1, true)
      })
    } catch {
      /* ignore */
    }
  }

  /* ---------- 睡眠定时（会话级，不持久化；手动暂停/继续不取消） ---------- */

  const sleepMode = ref<SleepMode>('off')
  /** 分钟模式的剩余秒数（其余模式恒为 0） */
  const sleepRemaining = ref(0)
  let sleepDeadline = 0
  let sleepTick: ReturnType<typeof setInterval> | null = null
  // chapter 模式装订的目标书与章（chapter=-1 表示引擎尚未装载，待快照捕获）
  let sleepBookId = ''
  let sleepChapter = -1

  function clearSleepTick() {
    if (sleepTick) {
      clearInterval(sleepTick)
      sleepTick = null
    }
  }

  /** 定时到达：暂停 + 复位 + 提示（若已在暂停态，pause 为空操作，仅复位提示）。
   * 引擎在装载窗口（loading）时 pause 空转且装载完成后会自行回到 playing——
   * 此时不解除定时，留给下个 tick（分钟模式）或快照（chapter 模式）重试，
   * 直到引擎离开 loading、暂停真正落地才复位提示 */
  function fireSleep() {
    engine.pause()
    if (engine.snapshot().state === 'loading') {
      sleepRemaining.value = 0
      return
    }
    setSleepTimer('off')
    toast('睡眠定时结束，已暂停')
  }

  /** 设置/切换睡眠定时；重复设置先清干净旧的 interval 与装订状态 */
  function setSleepTimer(mode: SleepMode) {
    clearSleepTick()
    sleepMode.value = mode
    sleepRemaining.value = 0
    sleepBookId = ''
    sleepChapter = -1
    if (mode === 'chapter') {
      // 装订当前在播章节；引擎未装载（chapterIndex<0）时由快照 watcher 捕获首个有效章
      if (snap.value.chapterIndex >= 0) {
        sleepBookId = snap.value.bookId
        sleepChapter = snap.value.chapterIndex
      }
    } else if (typeof mode === 'number') {
      // 记截止时间戳而非倒数递减，后台节流下到点判断依然准确
      sleepDeadline = Date.now() + mode * 60 * 1000
      sleepRemaining.value = mode * 60
      sleepTick = setInterval(() => {
        const left = Math.round((sleepDeadline - Date.now()) / 1000)
        if (left <= 0) fireSleep()
        else sleepRemaining.value = left
      }, 1000)
    }
  }

  // chapter 模式：快照对象每次 emit 都被整体替换，借 watch(snap) 观察章节切换。
  // 语义 =「章节切换即暂停」：自动跨章、手动跳章、换书都会触发（接受的权衡）。
  // 时序注意：换章会先经过 chapterIndex=-1（loading），新章起播前 state 还会短暂为
  // paused，而 engine.pause() 仅在 playing 态生效——因此发现装订章不符后，等新章
  // 真正进入 playing 的那次快照再触发，暂停才实际生效（手动跳章后的自动续播同理）。
  watch(snap, s => {
    if (sleepMode.value !== 'chapter') return
    if (s.chapterIndex < 0) return // 装载瞬态，跳过
    if (sleepChapter < 0) {
      // 设定时引擎尚未装载：首个有效章节即视为「本章」
      sleepBookId = s.bookId
      sleepChapter = s.chapterIndex
      return
    }
    if ((s.bookId !== sleepBookId || s.chapterIndex !== sleepChapter) && s.state === 'playing') {
      fireSleep()
    }
  })

  /** 装载书籍到引擎，成功返回 true（供调用方决定是否起播） */
  async function loadBook(bookId: string, chapterIndex: number, offset: number): Promise<boolean> {
    const meta = await getBook(bookId)
    if (!meta) throw new Error('书籍不存在')
    bookTitle.value = meta.title
    bindMediaSessionHandlers()
    return engine.load(bookId, chapterIndex, offset, meta.chapterCount)
  }

  return { engine, snap, bookTitle, sleepMode, sleepRemaining, setSleepTimer, loadBook }
})
