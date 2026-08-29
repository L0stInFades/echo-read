package app.echoread.tts

import app.echoread.core.BookMeta
import app.echoread.core.PlayerState
import app.echoread.data.LibraryRepo
import app.echoread.data.SettingsStore
import app.echoread.ui.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 睡眠定时模式：关闭 / 分钟数 / 播完本章 */
sealed interface SleepMode {
    data object Off : SleepMode
    data class Minutes(val minutes: Int) : SleepMode
    data object Chapter : SleepMode
}

/**
 * 播放控制器（对应网页版 store/player.ts）：引擎桥接 + 设置同步 + 进度持久化 + 睡眠定时。
 * 进度写库放在这里而非界面层，锁屏后台连播时进度同样落库。
 */
@OptIn(FlowPreview::class)
class PlayerController(
    val engine: TtsEngine,
    private val library: LibraryRepo,
    private val settings: SettingsStore,
    private val scope: CoroutineScope
) {
    private val _book = MutableStateFlow<BookMeta?>(null)
    /** 引擎当前装载的书（锁屏元数据用） */
    val book: StateFlow<BookMeta?> = _book

    private val _titles = MutableStateFlow<List<String>>(emptyList())
    val titles: StateFlow<List<String>> = _titles

    val sleepMode = MutableStateFlow<SleepMode>(SleepMode.Off)
    /** 分钟模式的剩余秒数（其余模式恒为 0） */
    val sleepRemaining = MutableStateFlow(0)

    private var sleepDeadline = 0L
    private var sleepJob: Job? = null
    private var sleepBookId = ""
    private var sleepChapter = -1

    private var lastSave = 0L
    private var lastSnapshot = EngineSnapshot()

    init {
        engine.updateConfig(settings.tts.value)
        scope.launch { settings.tts.collect { engine.updateConfig(it) } }
        // 片段长度变化 → 引擎按当前偏移原地重载（滑块每 tick 都触发，去抖收敛）
        scope.launch {
            settings.tts.map { it.maxChunkChars }.distinctUntilChanged().drop(1).debounce(250).collect { engine.reload() }
        }
        scope.launch { engine.snapshot.collect { onSnapshot(it) } }
    }

    /** 装载书籍到引擎，成功返回 true（供调用方决定是否起播） */
    suspend fun loadBook(bookId: String, chapterIndex: Int, offset: Int): Boolean {
        val meta = library.book(bookId) ?: throw IllegalStateException("书籍不存在")
        if (_book.value?.id != bookId) {
            _book.value = meta
            _titles.value = library.chapterTitles(bookId)
        } else {
            _book.value = meta
        }
        return engine.load(bookId, chapterIndex, offset, meta.chapterCount)
    }

    /** 锁屏 / 通知栏的上一章下一章 */
    fun gotoChapter(index: Int, autoplay: Boolean) {
        scope.launch { engine.gotoChapter(index, autoplay) }
    }

    fun saveProgressNow() {
        val s = engine.current
        if (s.bookId.isNotEmpty() && s.chapterIndex >= 0) {
            lastSave = System.currentTimeMillis()
            scope.launch { runCatching { library.saveProgress(s.bookId, s.chapterIndex, s.segmentStart) } }
        }
    }

    private fun onSnapshot(s: EngineSnapshot) {
        val prev = lastSnapshot
        lastSnapshot = s
        // 进度：暂停/停播立即落库；播放中片段推进按 4s 节流；跨章首片段立即落库
        if (s.bookId.isNotEmpty() && s.chapterIndex >= 0 && (s.state == PlayerState.PLAYING || s.state == PlayerState.PAUSED || s.state == PlayerState.ERROR)) {
            val chapterChanged = prev.chapterIndex >= 0 && prev.chapterIndex != s.chapterIndex && prev.bookId == s.bookId
            val leftPlaying = prev.state == PlayerState.PLAYING && s.state != PlayerState.PLAYING
            val moved = s.segmentIndex != prev.segmentIndex || s.chapterIndex != prev.chapterIndex
            val now = System.currentTimeMillis()
            if (chapterChanged || leftPlaying || (moved && now - lastSave >= 4000)) {
                lastSave = now
                scope.launch { runCatching { library.saveProgress(s.bookId, s.chapterIndex, s.segmentStart) } }
            }
        }
        // 睡眠定时（本章模式）：章节切换即暂停；等新章真正 playing 时再触发，暂停才实际生效
        if (sleepMode.value === SleepMode.Chapter && s.chapterIndex >= 0) {
            if (sleepChapter < 0) {
                sleepBookId = s.bookId
                sleepChapter = s.chapterIndex
            } else if ((s.bookId != sleepBookId || s.chapterIndex != sleepChapter) && s.state == PlayerState.PLAYING) {
                fireSleep()
            }
        }
    }

    /* ---------- 睡眠定时（会话级，不持久化；手动暂停/继续不取消） ---------- */

    private fun fireSleep() {
        engine.pause()
        if (engine.current.state == PlayerState.LOADING) {
            // 装载窗口期 pause 空转，留给下个 tick / 快照重试
            sleepRemaining.value = 0
            return
        }
        setSleepTimer(SleepMode.Off)
        Toaster.show("睡眠定时结束，已暂停")
    }

    fun setSleepTimer(mode: SleepMode) {
        sleepJob?.cancel()
        sleepJob = null
        sleepMode.value = mode
        sleepRemaining.value = 0
        sleepBookId = ""
        sleepChapter = -1
        when (mode) {
            SleepMode.Chapter -> {
                val s = engine.current
                if (s.chapterIndex >= 0) {
                    sleepBookId = s.bookId
                    sleepChapter = s.chapterIndex
                }
            }
            is SleepMode.Minutes -> {
                sleepDeadline = System.currentTimeMillis() + mode.minutes * 60_000L
                sleepRemaining.value = mode.minutes * 60
                sleepJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        val left = ((sleepDeadline - System.currentTimeMillis()) / 1000.0).toInt()
                        if (left <= 0) fireSleep() else sleepRemaining.value = left
                    }
                }
            }
            SleepMode.Off -> {}
        }
    }
}
