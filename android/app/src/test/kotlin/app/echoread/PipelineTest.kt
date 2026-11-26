package app.echoread

import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.net.NetErrors
import app.echoread.core.net.NetException
import app.echoread.tts.AudioCache
import app.echoread.tts.SynthPipeline
import app.echoread.tts.SynthTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 合成流水线：在途去重、失败的指数冷却、自适应前瞻（全部跑在虚拟时间上） */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {
    private val root = Files.createTempDirectory("pipeline-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val target = SynthTarget.OpenAI(OpenAISpeechConfig(apiKey = "k"))

    private class Rig(test: TestScope, root: File) {
        val main = test.appScope()
        val io = StandardTestDispatcher(test.testScheduler)
        val cache = AudioCache(root, io)
        val synth = FakeSynth()
        val clock = { test.testScheduler.currentTime }
        fun pipeline() = SynthPipeline(main, cache, io, clock) { t, text -> synth.invoke(t, text) }
    }

    /** 同一段只发一次请求：预取在途时的取用、并发的两次取用都等同一个 Deferred；之后热表、磁盘直接命中 */
    @Test
    fun inFlightRequestsAreShared() = runTest {
        val r = Rig(this, root)
        val p = r.pipeline()
        assertTrue(p.prefetch("k1", target, "a"))
        assertFalse("在途的不重复发", p.prefetch("k1", target, "a"))
        assertTrue(p.isPending("k1"))
        val first = async { p.obtain("k1", target, "a") }
        val second = async { p.obtain("k1", target, "a") }
        advanceUntilIdle()
        assertEquals(1, r.synth.requests.size)
        assertEquals(first.await(), second.await())
        assertTrue(p.isReady("k1"))
        assertEquals(0, p.inflightCount)
        assertEquals(first.await(), p.obtain("k1", target, "a"))
        // 换一条流水线（相当于重启）：磁盘缓存命中，仍然不发请求
        assertEquals(first.await(), r.pipeline().obtain("k1", target, "a"))
        assertEquals(1, r.synth.requests.size)
    }

    /**
     * 失败冷却（0.2.0 修掉的 401 风暴）：失败的段 1s → 2s → 4s 内不再预取，上报带着连续失败的次数；
     * 取用（播放器真的要这一段）不受冷却阻挡，成功一次就清掉记录。
     */
    @Test
    fun failuresCoolDownExponentiallyForPrefetchOnly() = runTest {
        val r = Rig(this, root)
        val p = r.pipeline()
        val attempts = ArrayList<Int>()
        p.onFailure = { _, err -> attempts += err.attempt }
        r.synth.respond = { delay(1000); throw NetException(NetErrors.fromHttp(503, null)) }
        p.prefetch("k", target, "a")
        advanceUntilIdle()
        assertTrue(p.isCoolingDown("k"))
        assertFalse("冷却中：预取跳过", p.prefetch("k", target, "a"))
        advanceTimeBy(1000)
        assertFalse("1 秒后冷却结束", p.isCoolingDown("k"))
        assertTrue(p.prefetch("k", target, "a"))
        advanceUntilIdle()
        assertTrue(p.isCoolingDown("k", now = currentTime + 1999))
        assertFalse(p.isCoolingDown("k", now = currentTime + 2000))
        advanceTimeBy(2000)
        assertTrue(p.prefetch("k", target, "a"))
        advanceUntilIdle()
        assertTrue(p.isCoolingDown("k", now = currentTime + 3999))
        assertFalse(p.isCoolingDown("k", now = currentTime + 4000))
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(3, r.synth.requests.size)

        r.synth.respond = { delay(1000); FakeSynth.AUDIO }
        val got = async { p.obtain("k", target, "a") }
        advanceUntilIdle()
        assertTrue("取用不受冷却阻挡", got.await().isFile)
        assertFalse("成功即清掉冷却", p.isCoolingDown("k"))
    }

    /** 自适应前瞻：合成耗时与每字时长的 EMA 决定要提前囤几段；网络一慢窗口就张开，离谱的样本不进均值 */
    @Test
    fun lookaheadAdaptsToSynthesisTimeAndReadingSpeed() = runTest {
        val r = Rig(this, root)
        val p = r.pipeline()
        assertEquals("默认 5 秒合成：囤 15 秒的音频，下限说了算", 3, p.lookaheadCount(avgSegChars = 120, min = 3, max = 6))
        r.synth.respond = { delay(20_000); FakeSynth.AUDIO }
        for (i in 0 until 3) {
            p.prefetch("s$i", target, "t$i")
            advanceUntilIdle()
        }
        assertEquals("5000 → 9500 → 12650 → 14855（α = 0.3）", 14_855.0, p.synthMs, 1e-6)
        assertEquals("要囤 37.1 秒，每段 7.2 秒", 6, p.lookaheadCount(avgSegChars = 40, min = 1, max = 6))
        assertEquals("每段 21.6 秒", 2, p.lookaheadCount(avgSegChars = 120, min = 1, max = 6))
        p.recordPlayback(chars = 100, audioMs = 20_000)
        assertEquals("180 → 185 ms/字（α = 0.25）", 185.0, p.msPerChar, 1e-9)
        p.recordPlayback(chars = 100, audioMs = 1_000)
        p.recordPlayback(chars = 0, audioMs = 1_000)
        assertEquals("10 ms/字这种离谱样本丢掉", 185.0, p.msPerChar, 1e-9)
        // 秒回（≤ 200 ms，多半是命中了缓存）不算合成耗时
        r.synth.respond = { FakeSynth.AUDIO }
        p.prefetch("fast", target, "f")
        advanceUntilIdle()
        assertEquals(14_855.0, p.synthMs, 1e-6)
    }
}
