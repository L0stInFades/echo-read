package app.echoread

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.echoread.core.i18n.S
import app.echoread.tts.SystemTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 系统语音的生命周期（回归 10）：初始化失败不记死、构造函数里的同步回调、坏掉的引擎不能让播放永远挂着 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemTtsTest {
    private val root = Files.createTempDirectory("system-tts-test").toFile()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    /** 平台 TextToSpeech 的替身：初始化结果、合成之后回不回调都由测试决定 */
    private class FakeTts(private val onInit: TextToSpeech.OnInitListener) : TextToSpeech(null, null) {
        var listener: UtteranceProgressListener? = null
        var answers = true
        var stops = 0
        var shutdowns = 0

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return SUCCESS
        }

        override fun synthesizeToFile(text: CharSequence?, params: Bundle?, file: File?, utteranceId: String?): Int {
            if (answers) {
                file!!.writeBytes(ByteArray(128))
                listener!!.onDone(utteranceId)
            }
            return SUCCESS
        }

        override fun stop(): Int {
            stops++
            return SUCCESS
        }

        override fun shutdown() {
            shutdowns++
        }

        fun init(status: Int) = onInit.onInit(status)
    }

    /**
     * 初始化失败一次不记死：下一段重新建引擎。有的 ROM 在构造函数里就同步回调（失败或成功），
     * 那一刻实例还没赋值 —— 成功的同步回调也得接住，监听器装在手上这个实例上。
     */
    @Test
    fun failedInitIsRetriedAndEarlyCallbacksAreHandled() = runTest {
        val created = ArrayList<FakeTts>()
        var status = TextToSpeech.ERROR
        val sys = SystemTts(TestContext()) { _, onInit -> FakeTts(onInit).also { created += it; it.init(status) } }
        val first = runCatching { sys.synthesizeToFile("你好", File(root, "a.wav")) }.exceptionOrNull()
        assertEquals(S.playback.systemTtsMissingEngine, first?.message)
        assertEquals("失败的实例被关掉", 1, created.single().shutdowns)

        status = TextToSpeech.SUCCESS
        val out = File(root, "b.wav")
        sys.synthesizeToFile("你好", out)
        assertEquals("没有记死：这一段重新建了引擎", 2, created.size)
        assertNotNull("监听器装在手上这个实例上", created[1].listener)
        assertTrue(out.length() >= 64)
        sys.synthesizeToFile("再来一段", File(root, "c.wav"))
        assertEquals("就绪之后不再重建", 2, created.size)
    }

    /** 合成之后永远不回调的引擎（坏掉的 ROM）：等满上限就按这一段失败处理，并叫停引擎 */
    @Test
    fun silentEngineTimesOut() = runTest {
        var tts: FakeTts? = null
        val sys = SystemTts(TestContext()) { _, onInit ->
            FakeTts(onInit).also { tts = it; it.answers = false; it.init(TextToSpeech.SUCCESS) }
        }
        val e = runCatching { sys.synthesizeToFile("你好", File(root, "a.wav")) }.exceptionOrNull()
        assertEquals(S.playback.systemTtsTimedOut, e?.message)
        assertEquals(SystemTts.SYNTH_TIMEOUT_MS, currentTime)
        assertEquals(1, tts!!.stops)
    }

    /** 初始化迟迟不回调：等满上限放弃、关掉这个实例，下一段从头再来；作废的实例晚到的回调不再碰它 */
    @Test
    fun hungInitTimesOutAndStartsOver() = runTest {
        val created = ArrayList<FakeTts>()
        val sys = SystemTts(TestContext()) { _, onInit ->
            FakeTts(onInit).also { created += it; if (created.size > 1) it.init(TextToSpeech.SUCCESS) }
        }
        val e = runCatching { sys.synthesizeToFile("你好", File(root, "a.wav")) }.exceptionOrNull()
        assertEquals(S.playback.systemTtsMissingEngine, e?.message)
        assertEquals(SystemTts.INIT_TIMEOUT_MS, currentTime)
        assertEquals(1, created[0].shutdowns)

        sys.synthesizeToFile("你好", File(root, "b.wav"))
        assertEquals(2, created.size)
        created[0].init(TextToSpeech.SUCCESS)
        assertNull("作废的实例晚到的回调不再装监听器", created[0].listener)
        sys.synthesizeToFile("再来一段", File(root, "c.wav"))
        assertEquals(2, created.size)
    }
}
