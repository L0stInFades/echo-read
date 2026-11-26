package app.echoread

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.Range
import app.echoread.core.TtsSettings
import app.echoread.core.net.NetworkStatus
import app.echoread.data.BookDao
import app.echoread.data.BookEntity
import app.echoread.data.BookmarkEntity
import app.echoread.data.ChapterCache
import app.echoread.data.ChapterEntity
import app.echoread.data.ChapterTitleRow
import app.echoread.tts.AbortedException
import app.echoread.tts.AudioCache
import app.echoread.tts.AudioOutput
import app.echoread.tts.AudioPlatform
import app.echoread.tts.PlayHandle
import app.echoread.tts.Providers
import app.echoread.tts.SynthPipeline
import app.echoread.tts.SynthTarget
import app.echoread.tts.SystemTts
import app.echoread.tts.TtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File

/*
 * 朗读引擎在 JVM 上的装配与替身（EngineTest / PipelineTest / PlaybackTest / PlayerControllerTest 共用）。
 * 引擎类碰的 AudioManager、TextToSpeech、Media3 在 JVM 上都起不来，这里只替换平台那一层，引擎本身原样跑。
 */

/** 一章正文：每句六十多个字。单片段上限 120 字时两句拼不进一段，于是一句就是一段，段号可预期 */
fun chapterText(chapter: Int, sentences: Int): String =
    (0 until sentences).joinToString("") { "第${chapter}章第${it}句" + "字".repeat(60) + "。" }

/** 段落开头的标记，用来认出某次合成请求是哪一段 */
fun segTag(chapter: Int, index: Int) = "第${chapter}章第${index}句"

/** 只有书目、章节与进度写入的内存书库；进度写入按顺序记下 (章, 偏移) */
class FakeDao(private val chapters: List<String>, val bookId: String = "b") : BookDao {
    val progress = ArrayList<Pair<Int, Int>>()

    override suspend fun chapter(bookId: String, index: Int): ChapterEntity? =
        chapters.getOrNull(index)?.takeIf { bookId == this.bookId }?.let { ChapterEntity(bookId, index, "第 ${index + 1} 章", it) }

    override suspend fun book(id: String): BookEntity? = if (id != bookId) null else
        BookEntity(id, "书", "作者", "txt", null, null, chapters.size, chapters.sumOf { it.length }, 0L, null, 0, 0)

    override suspend fun chapterTitles(bookId: String): List<ChapterTitleRow> = chapters.indices.map { ChapterTitleRow(it, "第 ${it + 1} 章") }

    override suspend fun updateProgress(id: String, chapterIndex: Int, offset: Int, at: Long) {
        progress += chapterIndex to offset
    }

    override suspend fun allBooks(): List<BookEntity> = emptyList()
    override suspend fun insertBook(book: BookEntity) {}
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
    override suspend fun deleteBookRow(id: String) {}
    override suspend fun deleteChapters(id: String) {}
    override suspend fun bookmarks(bookId: String): List<BookmarkEntity> = emptyList()
    override suspend fun insertBookmark(bookmark: BookmarkEntity): Long = -1
    override suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>) {}
    override suspend fun deleteBookmarks(ids: List<Long>) {}
    override suspend fun deleteBookmarksOf(bookId: String) {}
}

/** 片段句柄替身：不出声，什么时候「播完」由测试决定 */
class FakeHandle(val file: File) : PlayHandle {
    private val done = CompletableDeferred<Unit>()
    var paused = false
        private set
    var stopped = false
        private set

    override suspend fun awaitEnded() = done.await()
    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun stop() {
        stopped = true
        done.completeExceptionally(AbortedException())
    }

    override fun setRate(rate: Float) {}

    /** 这段自然播完 */
    fun finish() {
        done.complete(Unit)
    }
}

/** 引擎看到的播放层替身：记下焦点状态与每一个句柄 */
class FakeOutput : AudioOutput {
    override var onInterrupt: ((transient: Boolean) -> Unit)? = null
    override var onResumeAfterInterrupt: (() -> Unit)? = null
    var active = false
        private set
    val handles = ArrayList<FakeHandle>()
    val current: FakeHandle get() = handles.last()

    override fun setActive(active: Boolean) {
        this.active = active
    }

    override fun progressFraction(): Float = 0f
    override fun play(file: File, rate: Float, deleteAfter: Boolean): PlayHandle = FakeHandle(file).also { handles += it }
}

/** 音频焦点与「拔耳机」广播的替身：测试替系统发焦点变化 */
class FakeAudio : AudioPlatform {
    var requests = 0
        private set
    var abandons = 0
        private set
    var noisyRegistered = false
        private set
    private var onChange: ((Int) -> Unit)? = null
    private var onNoisy: (() -> Unit)? = null

    override fun requestFocus(onChange: (Int) -> Unit): Boolean {
        requests++
        this.onChange = onChange
        return true
    }

    override fun abandonFocus() {
        abandons++
    }

    override fun registerNoisy(onNoisy: () -> Unit) {
        noisyRegistered = true
        this.onNoisy = onNoisy
    }

    override fun unregisterNoisy() {
        noisyRegistered = false
    }

    /** 系统发来一次焦点变化（AudioManager.AUDIOFOCUS_*） */
    fun focus(change: Int) = onChange!!.invoke(change)

    /** 耳机被拔掉 */
    fun unplug() = onNoisy!!.invoke()
}

/** 合成替身：默认每段耗时 1 秒、返回一段假 MP3；[respond] 按正文决定耗时与成败。记下每一次请求的正文 */
class FakeSynth {
    val requests = ArrayList<String>()
    var respond: suspend (String) -> ByteArray = { delay(1000); AUDIO }

    suspend fun invoke(@Suppress("UNUSED_PARAMETER") target: SynthTarget, text: String): ByteArray {
        requests += text
        return respond(text)
    }

    fun count(tag: String) = requests.count { it.startsWith(tag) }

    companion object {
        val AUDIO = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(64)
    }
}

/** applicationContext 指向自己、SharedPreferences 放在内存里的 Context（其余调用走 mockable android.jar 的默认值） */
class TestContext(private val prefs: SharedPreferences = MemoryPrefs()) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

/**
 * 测试里的「应用级」作用域：Job 挂在 backgroundScope 下（测试结束时随之取消），却不带它的 BackgroundWork 标记 ——
 * advanceUntilIdle 只推进前台任务，带着标记的话引擎的活一件都不会跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.appScope(): CoroutineScope =
    CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + UnconfinedTestDispatcher(testScheduler))

/**
 * 引擎的 JVM 装配。主线程用「立即执行」的测试调度器（对应生产的 Main.immediate），IO 用「排队执行」的那个，
 * 两者共用 [test] 的虚拟时钟；章节预先派生进缓存，装载时不碰真实线程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineRig(
    val test: TestScope,
    root: File,
    val out: AudioOutput = FakeOutput(),
    chapterTexts: List<String> = listOf(chapterText(0, 6), chapterText(1, 6)),
    /** prefetch = 1：前瞻窗口只有下一段，请求的先后好推 */
    val settings: TtsSettings = TtsSettings(openai = OpenAISpeechConfig(apiKey = "k"), prefetch = 1)
) {
    val main = test.appScope()
    val io = StandardTestDispatcher(test.testScheduler)
    val dao = FakeDao(chapterTexts)
    val chapters = ChapterCache(dao)
    val audioCache = AudioCache(File(root, "audio"), io)
    val synth = FakeSynth()
    val pipeline = SynthPipeline(main, audioCache, io, { test.testScheduler.currentTime }) { t, text -> synth.invoke(t, text) }
    val engine = TtsEngine(main, chapters, audioCache, SystemTts(TestContext()), out, File(root, "tmp"), object : NetworkStatus {}, pipeline)
    val fake: FakeOutput get() = out as FakeOutput
    val chapterCount = chapterTexts.size

    init {
        engine.updateConfig(settings)
        runBlocking { chapterTexts.indices.forEach { chapters.get(dao.bookId, it, Providers.chunkChars(settings)) } }
    }

    fun segments(chapter: Int): List<Range> = runBlocking { chapters.get(dao.bookId, chapter, Providers.chunkChars(settings))!!.segments }

    suspend fun load(chapter: Int = 0, offset: Int = 0) = engine.load(dao.bookId, chapter, offset, chapterCount)
}
