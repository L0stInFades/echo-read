import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { TTSEngine, type EngineSnapshot } from '../tts/engine'
import { useSettingsStore } from './settings'
import { getBook } from '../lib/db'

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

  /** 装载书籍到引擎，成功返回 true（供调用方决定是否起播） */
  async function loadBook(bookId: string, chapterIndex: number, offset: number): Promise<boolean> {
    const meta = await getBook(bookId)
    if (!meta) throw new Error('书籍不存在')
    bookTitle.value = meta.title
    bindMediaSessionHandlers()
    return engine.load(bookId, chapterIndex, offset, meta.chapterCount)
  }

  return { engine, snap, bookTitle, loadBook }
})
