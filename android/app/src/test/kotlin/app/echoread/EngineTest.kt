package app.echoread

import app.echoread.core.PlayerState
import app.echoread.core.net.NetErrors
import app.echoread.core.net.NetException
import app.echoread.tts.EngineFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 朗读引擎的状态机：代际守卫、暂停 / 续播 / 跳转的竞态、重试与跳段、预取撞上配置错误、短暂失焦后的续播。
 * 播放层与合成是替身，引擎连同真实的流水线与磁盘缓存跑在虚拟时间上（见 [EngineRig]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {
    private val root = Files.createTempDirectory("engine-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun http(status: Int, body: String? = null, retryAfter: String? = null) =
        NetException(NetErrors.fromHttp(status, body, retryAfterHeader = retryAfter, endpoint = "https://x.test/v1/audio/speech"))

    /* ---------------- 代际守卫与竞态 ---------------- */

    /** 合成窗口期暂停：旧循环经代际守卫退出，合成回来也不出声；再按播放直接用上那次在途请求的结果，不重复花钱 */
    @Test
    fun pauseDuringSynthesisLeavesNoOrphanAudio() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        runCurrent()
        assertTrue("第一段正在合成", e.current.synthesizing)
        e.pause()
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertFalse(e.current.synthesizing)
        advanceUntilIdle()
        assertTrue("暂停之后合成回来也不得出声", rig.fake.handles.isEmpty())
        e.play()
        runCurrent()
        assertEquals(1, rig.fake.handles.size)
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals("暂停前发出的那次请求被续用", 1, rig.synth.count(segTag(0, 0)))
    }

    /** 播放中跳转：旧句柄当场停掉，旧循环被换代挡住、不得再推进段号；从目标段接着读 */
    @Test
    fun seekWhilePlayingRestartsAtTheTarget() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        val first = rig.fake.current
        e.seekToOffset(rig.segments(0)[3].start + 5)
        assertTrue("旧句柄当场停掉", first.stopped)
        assertEquals(3, e.current.segmentIndex)
        assertEquals(PlayerState.PLAYING, e.current.state)
        advanceUntilIdle()
        assertEquals(2, rig.fake.handles.size)
        assertEquals("旧循环没有推进段号", 3, e.current.segmentIndex)
        assertEquals(1, rig.synth.count(segTag(0, 3)))
        rig.fake.current.finish()
        assertEquals("下一段早已预取好，接着就读", 4, e.current.segmentIndex)
        assertEquals(3, rig.fake.handles.size)
    }

    /** 播放中暂停再继续：同一个句柄续上，不重新合成；暂停期间交还焦点 */
    @Test
    fun pauseAndResumeKeepTheSameHandle() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        val h = rig.fake.current
        assertTrue(rig.fake.active)
        e.pause()
        assertTrue(h.paused)
        assertFalse("暂停即交还焦点", rig.fake.active)
        e.play()
        assertFalse(h.paused)
        assertTrue(rig.fake.active)
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals(1, rig.fake.handles.size)
        assertEquals(1, rig.synth.count(segTag(0, 0)))
    }

    /** 章尾自动进入下一章并接着读（连播），下一章的开头在章尾就预取好了 */
    @Test
    fun chapterEndAdvancesAndKeepsReading() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load(offset = rig.segments(0).last().start)
        e.play()
        advanceUntilIdle()
        assertEquals(5, e.current.segmentIndex)
        rig.fake.current.finish()
        advanceUntilIdle()
        assertEquals(1, e.current.chapterIndex)
        assertEquals(0, e.current.segmentIndex)
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals(2, rig.fake.handles.size)
        assertEquals(1, rig.synth.count(segTag(1, 0)))
    }

    /* ---------------- 重试与跳段 ---------------- */

    /** 可重试的失败（503 + Retry-After）：按服务商要求的时长退避，状态行带着第几次尝试；之后成功就照常出声 */
    @Test
    fun retryHonoursRetryAfterThenPlays() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        var failures = 2
        rig.synth.respond = { text ->
            delay(1000)
            if (text.startsWith(segTag(0, 0)) && failures-- > 0) throw http(503, retryAfter = "2")
            FakeSynth.AUDIO
        }
        rig.load()
        e.play()
        advanceTimeBy(1500)
        val retry = e.current.retry
        assertNotNull("第一次失败后进入退避", retry)
        assertEquals(2, retry!!.attempt)
        assertEquals("服务商要求等 2 秒就等 2 秒", 2000L, retry.nextInMs)
        assertEquals(503, retry.error.status)
        assertTrue(e.current.synthesizing)
        advanceUntilIdle()
        assertEquals(1, rig.fake.handles.size)
        assertNull(e.current.retry)
        assertNull(e.current.failure)
        assertEquals(3, rig.synth.count(segTag(0, 0)))
        assertEquals(PlayerState.PLAYING, e.current.state)
    }

    /**
     * 单段被拒（400）：不重试、跳过它继续读，跳段记录活过下一段的成功（静默丢内容是正确性问题）；
     * 连续两段都失败才认定环境不可用，停下报错。
     */
    @Test
    fun skippedSegmentsSurviveSuccessAndAStreakStops() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        val rejected = listOf(segTag(0, 0), segTag(0, 2), segTag(0, 3))
        rig.synth.respond = { text ->
            delay(1000)
            if (rejected.any { text.startsWith(it) }) throw http(400, """{"error":{"message":"voice not supported"}}""")
            FakeSynth.AUDIO
        }
        rig.load()
        e.play()
        advanceUntilIdle()
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals(1, e.current.segmentIndex)
        val skipped = e.current.failure as EngineFailure.SkippedSegments
        assertEquals(1, skipped.count)
        assertEquals(0, skipped.firstSegmentIndex)
        assertEquals("被拒的段不重试", 1, rig.synth.count(segTag(0, 0)))
        rig.fake.current.finish()
        advanceUntilIdle()
        assertEquals(PlayerState.ERROR, e.current.state)
        val stop = e.current.failure as EngineFailure.Session
        assertEquals(2, stop.segmentsFailed)
        assertEquals(400, stop.error.status)
        assertFalse(rig.fake.active)
    }

    /* ---------------- 预取撞上配置类错误（2.3） ---------------- */

    private fun keyRevokedAtSegmentOne(rig: EngineRig) {
        rig.synth.respond = { text ->
            if (text.startsWith(segTag(0, 1))) {
                delay(3000)
                throw http(401, """{"error":{"message":"Invalid API key"}}""")
            }
            delay(1000)
            FakeSynth.AUDIO
        }
    }

    /**
     * 回归（2.3）：正在播一段已到手的音频时，预取撞上 401。旧实现当场切到 ERROR、交还焦点，声音却还在继续，
     * 暂停键也失灵（只在 PLAYING 下生效）。现在：这段照常播完，期间仍是 PLAYING、焦点在手，播完再停下报错。
     */
    @Test
    fun fatalPrefetchErrorLetsTheSoundingSegmentFinish() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        keyRevokedAtSegmentOne(rig)
        rig.load()
        e.play()
        advanceUntilIdle()
        val h = rig.fake.current
        assertEquals("与真实的声音一致：还在出声就是 PLAYING", PlayerState.PLAYING, e.current.state)
        assertTrue("焦点没有被提前交还", rig.fake.active)
        assertFalse(h.stopped)
        assertNull(e.current.failure)
        h.finish()
        assertEquals(PlayerState.ERROR, e.current.state)
        assertEquals(401, (e.current.failure as EngineFailure.Session).error.status)
        assertFalse(rig.fake.active)
        assertEquals("播完这段就停，不再起下一段", 1, rig.fake.handles.size)
        assertEquals("再按播放从下一段开始", 1, e.current.segmentIndex)
    }

    /** 同上，但这段播完之前用户按了暂停：声音停了，失败立刻落地 */
    @Test
    fun pausingThatSegmentReportsTheFailureAtOnce() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        keyRevokedAtSegmentOne(rig)
        rig.load()
        e.play()
        advanceUntilIdle()
        val h = rig.fake.current
        e.pause()
        assertTrue(h.paused)
        assertEquals(PlayerState.ERROR, e.current.state)
        assertTrue(e.current.failure is EngineFailure.Session)
        assertFalse(rig.fake.active)
    }

    /** 刚打开书、还没按播放：预取撞上 401 立刻报错 —— 这是最早能发现无效 Key 的时刻 */
    @Test
    fun fatalPrefetchErrorWhilePausedReportsAtOnce() = runTest {
        val rig = EngineRig(this, root)
        rig.synth.respond = { delay(1000); throw http(401) }
        rig.load()
        advanceUntilIdle()
        assertEquals(PlayerState.ERROR, rig.engine.current.state)
        assertTrue(rig.engine.current.failure is EngineFailure.Session)
        assertTrue(rig.fake.handles.isEmpty())
    }

    /* ---------------- 短暂失焦（2.1） ---------------- */

    /**
     * 回归（2.1）：短暂失焦（来电、导航播报）暂停朗读但不交还焦点，焦点回来自动续播，续的是同一个句柄。
     * 旧实现暂停时顺手交还了焦点、清掉了续播标记，AUDIOFOCUS_GAIN 永远到不了。
     */
    @Test
    fun transientFocusLossResumesOnGain() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        val h = rig.fake.current
        rig.fake.onInterrupt!!(true)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertTrue(h.paused)
        assertTrue("焦点登记留着，焦点回来才收得到通知", rig.fake.active)
        rig.fake.onResumeAfterInterrupt!!()
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertFalse(h.paused)
        assertEquals(1, rig.fake.handles.size)
    }

    /** 永久失焦 / 拔耳机：暂停并交还焦点，之后来的 GAIN 不续播；短暂失焦期间用户（或睡眠定时）按了暂停，同样作罢 */
    @Test
    fun onlyATransientPauseAutoResumes() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        rig.fake.onInterrupt!!(false)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertFalse(rig.fake.active)
        rig.fake.onResumeAfterInterrupt!!()
        assertEquals(PlayerState.PAUSED, e.current.state)

        e.play()
        rig.fake.onInterrupt!!(true)
        assertTrue(rig.fake.active)
        e.pause()
        assertFalse("用户按了暂停：不再等焦点，焦点随之交还", rig.fake.active)
        rig.fake.onResumeAfterInterrupt!!()
        assertEquals(PlayerState.PAUSED, e.current.state)
    }

    /** 引擎本来就停着（比如设置页正在试听）时的短暂失焦：焦点回来也不能把书读起来 */
    @Test
    fun focusGainNeverStartsAPausedBook() = runTest {
        val rig = EngineRig(this, root)
        rig.load()
        rig.fake.setActive(true) // 试听占着焦点
        rig.fake.onInterrupt!!(true)
        rig.fake.onResumeAfterInterrupt!!()
        assertEquals(PlayerState.PAUSED, rig.engine.current.state)
        advanceUntilIdle()
        assertTrue(rig.fake.handles.isEmpty())
    }

    /** 合成窗口期的短暂失焦：在途请求作废、不出声；焦点回来从这一段重新开始（合成结果被续用） */
    @Test
    fun transientLossDuringSynthesisResumesOnGain() = runTest {
        val rig = EngineRig(this, root)
        val e = rig.engine
        rig.load()
        e.play()
        runCurrent()
        assertTrue(e.current.synthesizing)
        rig.fake.onInterrupt!!(true)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertTrue(rig.fake.active)
        advanceUntilIdle()
        assertTrue(rig.fake.handles.isEmpty())
        rig.fake.onResumeAfterInterrupt!!()
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals(1, rig.fake.handles.size)
        assertEquals(1, rig.synth.count(segTag(0, 0)))
    }
}
