package app.echoread

import app.echoread.core.PlayerState
import app.echoread.core.Providers
import app.echoread.core.i18n.AppLanguage
import app.echoread.core.i18n.I18n
import app.echoread.core.i18n.S
import app.echoread.data.LibraryRepo
import app.echoread.data.SettingsStore
import app.echoread.data.Vault
import app.echoread.tts.PlayerController
import app.echoread.tts.SleepMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** 播放控制器：进度落库的节流、「播完本章」与分钟定时（引擎、书库、设置都是真的，平台那一层换成替身） */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {
    private val root = Files.createTempDirectory("controller-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
        // SettingsStore 读档时按「跟随系统」定界面语言：别把它留给后面的测试
        I18n.apply(AppLanguage.ZH)
    }

    private val plainVault = object : Vault {
        override fun seal(plain: String) = plain
        override fun open(stored: String) = stored
    }

    private inner class Rig(test: TestScope) {
        val engine = EngineRig(test, root)
        private val context = TestContext()
        /** 新安装默认用系统语音；这些用例走 AI 语音（合成是替身）：加一个 OpenRouter 用上 */
        private val settings = SettingsStore(context, plainVault).apply { setActive(addProfile(Providers.spec(Providers.OPENROUTER)!!).id) }
        /** 控制器交给界面层的提示（生产里由 AppGraph 接到 toast 上） */
        val notices = ArrayList<String>()
        /** 时钟加一个大偏移：生产里 lastSave 的初值 0 离「现在」很远，装载后第一次落库不受节流 */
        val controller = PlayerController(
            engine.engine, LibraryRepo(context, engine.dao, engine.chapters), settings, engine.main,
            onNotice = { notices += it }
        ) { 1_000_000L + test.testScheduler.currentTime }
        val saves get() = engine.dao.progress
    }

    /** 装载后立即记一次；播放中片段推进按 4 秒节流；离开「播放中」（暂停）立即记 */
    @Test
    fun progressSavesAreThrottledWhilePlaying() = runTest {
        val r = Rig(this)
        val e = r.engine.engine
        val seg = r.engine.segments(0)
        r.controller.loadBook("b", 0, 0)
        assertEquals(listOf(0 to 0), r.saves)
        e.play()
        // 每段合成 1 秒、出声后立刻播完：1、2、3 秒时的推进落在节流窗口里，4 秒那次落库
        repeat(4) {
            advanceTimeBy(1000)
            runCurrent()
            r.engine.fake.current.finish()
        }
        assertEquals(4, e.current.segmentIndex)
        assertEquals(listOf(0 to 0, 0 to seg[4].start), r.saves)
        advanceTimeBy(500)
        e.pause()
        assertEquals("暂停立即落库", listOf(0 to 0, 0 to seg[4].start, 0 to seg[4].start), r.saves)
    }

    /** 「播完本章」：进入下一章、真正开始读的那一刻暂停，定时随之关闭 */
    @Test
    fun chapterSleepTimerPausesAtTheNextChapter() = runTest {
        val r = Rig(this)
        val e = r.engine.engine
        r.controller.loadBook("b", 0, r.engine.segments(0).last().start)
        e.play()
        advanceUntilIdle()
        r.controller.setSleepTimer(SleepMode.Chapter)
        r.engine.fake.current.finish()
        advanceUntilIdle()
        assertEquals(1, e.current.chapterIndex)
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertEquals(SleepMode.Off, r.controller.sleepMode.value)
        assertEquals("新的一章一个字都没读出来", 1, r.engine.fake.handles.size)
        assertEquals("暂停的同时提示一次", listOf(S.playback.sleepTimerEnded), r.notices)
    }

    /** 分钟定时：每秒倒数，到点暂停并关掉定时（虚拟时间里走完一整分钟） */
    @Test
    fun minuteSleepTimerCountsDownAndPauses() = runTest {
        val r = Rig(this)
        val e = r.engine.engine
        r.controller.loadBook("b", 0, 0)
        e.play()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(PlayerState.PLAYING, e.current.state)
        r.controller.setSleepTimer(SleepMode.Minutes(1))
        assertEquals(60, r.controller.sleepRemaining.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(59, r.controller.sleepRemaining.value)
        advanceTimeBy(58_000)
        runCurrent()
        assertEquals(1, r.controller.sleepRemaining.value)
        assertEquals(PlayerState.PLAYING, e.current.state)
        assertEquals("没到点不提示", emptyList<String>(), r.notices)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(PlayerState.PAUSED, e.current.state)
        assertEquals(SleepMode.Off, r.controller.sleepMode.value)
        assertEquals(0, r.controller.sleepRemaining.value)
        assertEquals("到点暂停并提示一次", listOf(S.playback.sleepTimerEnded), r.notices)
    }
}
