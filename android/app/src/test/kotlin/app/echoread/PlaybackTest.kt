package app.echoread

import android.media.AudioManager
import app.echoread.core.PlayerState
import app.echoread.tts.AudioOutput
import app.echoread.tts.PlayHandle
import app.echoread.tts.Playback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 播放层的音频焦点：短暂失焦与永久失焦分开上报，短暂失焦期间不撤焦点登记，焦点回来自动续播。
 * AudioManager 与「拔耳机」广播换成替身（[FakeAudio]），焦点处理本身与它和引擎的联动原样跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTest {
    private val root = Files.createTempDirectory("playback-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** 真的 Playback 管焦点，只把出声这一步换掉（ExoPlayer 在 JVM 上起不来） */
    private class SpyOutput(real: Playback) : AudioOutput by real {
        val handles = ArrayList<FakeHandle>()
        override fun play(file: File, rate: Float, deleteAfter: Boolean): PlayHandle = FakeHandle(file).also { handles += it }
    }

    @Test
    fun focusChangesAreReportedByKind() {
        val audio = FakeAudio()
        val p = Playback(TestContext(), audio)
        val events = ArrayList<String>()
        p.onInterrupt = { transient -> events += if (transient) "transient" else "lost" }
        p.onResumeAfterInterrupt = { events += "gain" }
        p.setActive(true)
        p.setActive(true)
        assertEquals("重复激活只申请一次", 1, audio.requests)
        assertTrue(audio.noisyRegistered)
        audio.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        audio.focus(AudioManager.AUDIOFOCUS_GAIN)
        audio.unplug()
        audio.focus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf("transient", "gain", "lost", "lost"), events)
        assertEquals("撤不撤焦点由引擎的 setActive 决定，Playback 自己不撤", 0, audio.abandons)
        // 永久失焦之后焦点已经不在手上：停用时不必交还，再激活要重新申请
        p.setActive(false)
        assertEquals(0, audio.abandons)
        assertFalse(audio.noisyRegistered)
        p.setActive(true)
        assertEquals(2, audio.requests)
        p.setActive(false)
        assertEquals(1, audio.abandons)
    }

    /**
     * 回归（2.1）：真的焦点处理 + 真的引擎。来电（短暂失焦）→ 暂停但焦点登记不撤 → 挂断（GAIN）→ 自动续播，
     * 期间不重新申请焦点。旧实现暂停时就交还了焦点，焦点回来的通知根本到不了。
     */
    @Test
    fun transientLossKeepsFocusAndResumes() = runTest {
        val audio = FakeAudio()
        val out = SpyOutput(Playback(TestContext(), audio))
        val rig = EngineRig(this, root, out)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        val h = out.handles.single()
        assertEquals(1, audio.requests)
        audio.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertTrue(h.paused)
        assertEquals("不交还焦点", 0, audio.abandons)
        assertTrue("拔耳机照样在监听", audio.noisyRegistered)
        audio.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertFalse(h.paused)
        assertEquals("焦点一直登记着，不用重新申请", 1, audio.requests)
    }

    /** 来电期间拔了耳机：挂断后不能从外放自己读起来；永久失焦同理 */
    @Test
    fun unplugOrPermanentLossCancelsTheAutoResume() = runTest {
        val audio = FakeAudio()
        val out = SpyOutput(Playback(TestContext(), audio))
        val rig = EngineRig(this, root, out)
        val e = rig.engine
        rig.load()
        e.play()
        advanceUntilIdle()
        audio.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.unplug()
        assertEquals(1, audio.abandons)
        assertFalse(audio.noisyRegistered)
        audio.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlayerState.PAUSED, e.current.state)

        e.play()
        assertEquals("重新激活：重新申请焦点", 2, audio.requests)
        audio.focus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertEquals("焦点已被拿走，不再交还", 1, audio.abandons)
        assertFalse(audio.noisyRegistered)
        audio.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlayerState.PAUSED, e.current.state)
    }
}
