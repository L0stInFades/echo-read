package app.echoread

import android.speech.tts.TextToSpeech
import app.echoread.core.i18n.S
import app.echoread.tts.AbortedException
import app.echoread.tts.SystemTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 只肯自己出声的引擎（联想平板自带的云端语音）：合成到文件只写个空 WAV 头，自己花几秒把这段读出来。
     * 这段算听过，之后改让它直接朗读；句柄跟着它的回调走：暂停即停下、续播重读这一句，停下的那句晚到的回调不算数
     */
    @Test
    fun engineThatSpeaksAloudIsLetToSpeak() = runTest {
        var now = 0L
        var tts: FakeTts? = null
        val sys = SystemTts(TestContext(), clock = { now }) { _, onInit ->
            FakeTts(onInit).also { tts = it; it.headerOnly = true; it.aloud = { now += 6_000 }; it.init(TextToSpeech.SUCCESS) }
        }
        val out = File(root, "a.wav")
        assertFalse("没写出音频：这段已经由引擎自己读完了", sys.synthesizeToFile("你好", out))
        assertTrue(sys.speaksAloud)

        val h = sys.speak("下一句", 1.5f)
        val first = tts!!.spoken.single()
        assertEquals("倍速交给引擎自己的语速", 1.5f, tts!!.rate)
        h.pause()
        assertEquals(1, tts!!.stops)
        tts!!.listener!!.onDone(first)
        assertNull("停下的那一句晚到的回调不算数", withTimeoutOrNull(1) { h.awaitEnded() })
        h.resume()
        assertEquals("续播从这一句开头重读", 2, tts!!.spoken.size)
        tts!!.listener!!.onDone(tts!!.spoken.last())
        h.awaitEnded()

        val gone = sys.speak("再下一句", 1f)
        gone.stop()
        assertTrue(runCatching { gone.awaitEnded() }.exceptionOrNull() is AbortedException)
    }

    /** 离线时联想的云端语音什么也不读就说「读完了」：不能当成读过一段，否则整本书会被无声地一路「读」完、进度跟着跑 */
    @Test
    fun speechThatEndsAtOnceIsAFailure() = runTest {
        var now = 0L
        var tts: FakeTts? = null
        val sys = SystemTts(TestContext(), clock = { now }) { _, onInit ->
            FakeTts(onInit).also { tts = it; it.headerOnly = true; it.aloud = { now += 5_000 }; it.init(TextToSpeech.SUCCESS) }
        }
        assertFalse(sys.synthesizeToFile("你好", File(root, "a.wav")))
        val result = async { runCatching { sys.speak("离线了", 1f) }.exceptionOrNull() }
        runCurrent()
        tts!!.listener!!.onDone(tts!!.spoken.last())
        assertEquals(S.playback.systemTtsProducedNo, result.await()?.message)
    }

    /**
     * 暂停再续播：从暂停处那一句的开头接着读，而不是整段从头来。读到第几个字按引擎开口以来的时长估：
     * 没量过用常见语速，读完一口之后按它校准
     */
    @Test
    fun resumeContinuesFromTheClauseThatWasPlaying() = runTest {
        var now = 0L
        var tts: FakeTts? = null
        val sys = SystemTts(TestContext(), clock = { now }) { _, onInit ->
            FakeTts(onInit).also { tts = it; it.headerOnly = true; it.aloud = { now += 5_000 }; it.init(TextToSpeech.SUCCESS) }
        }
        assertFalse(sys.synthesizeToFile("你好", File(root, "a.wav")))
        val text = "第一句话，第二句话。第三句话，第四句话。"

        val h = sys.speak(text, 1f)
        sys.engineAudible()
        now += 230L * 7 // 按常见语速约读到第 7 个字：第二小句当中
        h.pause()
        h.resume()
        assertEquals("从第二小句的开头接着读", text.substring(5), tts!!.texts.last())
        tts!!.listener!!.onDone(tts!!.spoken.last())
        h.awaitEnded()

        // 读完一口就校准：这台引擎每字 100 毫秒
        val calib = sys.speak("一二三四五六七八九十，一二三四五六七八九十", 1f)
        sys.engineAudible()
        now += 100L * 21
        tts!!.listener!!.onDone(tts!!.spoken.last())
        calib.awaitEnded()
        val h2 = sys.speak(text, 1f)
        sys.engineAudible()
        now += 100L * 12 // 第三小句当中
        h2.pause()
        h2.resume()
        assertEquals(text.substring(10), tts!!.texts.last())
    }

    /** 续播位置：退回那一小句的开头；估过头了也至少留最后一句 */
    @Test
    fun resumePointBacksUpToTheClause() {
        val t = "第一句话，第二句话。第三句话，第四句话。"
        assertEquals(0, SystemTts.resumeFrom(t, 0))
        assertEquals("刚读完第一小句的最后一个字：还没到逗号，从头来", 0, SystemTts.resumeFrom(t, 4))
        assertEquals(5, SystemTts.resumeFrom(t, 5))
        assertEquals(5, SystemTts.resumeFrom(t, 8))
        assertEquals(15, SystemTts.resumeFrom(t, 99))
        assertEquals(0, SystemTts.resumeFrom("", 3))
    }

    /** 秒回的空文件是真没合成出来（不是引擎自己读出了声）：照旧按这一段失败处理，不改走直接朗读 */
    @Test
    fun quickEmptyFileIsStillAFailure() = runTest {
        val sys = SystemTts(TestContext(), clock = { 0L }) { _, onInit ->
            FakeTts(onInit).also { it.headerOnly = true; it.init(TextToSpeech.SUCCESS) }
        }
        val e = runCatching { sys.synthesizeToFile("你好", File(root, "a.wav")) }.exceptionOrNull()
        assertEquals(S.playback.systemTtsProducedNo, e?.message)
        assertFalse(sys.speaksAloud)
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
