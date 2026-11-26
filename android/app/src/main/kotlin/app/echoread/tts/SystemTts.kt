package app.echoread.tts

import app.echoread.core.i18n.S
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统语音（Android TextToSpeech）：离线兜底。合成到 WAV 文件后走与 AI 语音完全相同的
 * ExoPlayer 播放管线，锁屏控制、倍速、后台续播一并复用。
 */
class SystemTts(
    private val context: Context,
    /** 创建平台的 TextToSpeech；参数化只为单测换成替身 */
    private val create: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = ::TextToSpeech
) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(Unit) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.completeExceptionally(IllegalStateException(S.playback.systemTtsError)) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.completeExceptionally(IllegalStateException(S.playback.systemTtsErrorCode(errorCode))) }
        }
    }

    /** 引擎就绪了吗。初始化失败、或迟迟没有回音（坏掉的 ROM 引擎）都不记死：丢掉这个实例，下一段从头再试 */
    private suspend fun ensureReady(): Boolean {
        val d = ready ?: CompletableDeferred<Boolean>().also {
            // 先登记再切线程：并发进来的调用等同一个初始化，不会建出两个引擎。
            // 建引擎不可取消：登记了的就绪信号若没人驱动，后来的调用只能干等到超时
            ready = it
            withContext(Dispatchers.Main + NonCancellable) { startEngine(it) }
        }
        // 已就绪是常态，不必每段都挂一个超时
        val ok = if (d.isCompleted) d.await() else withTimeoutOrNull(INIT_TIMEOUT_MS) { d.await() } ?: false
        if (!ok && ready === d) {
            ready = null
            runCatching { tts?.shutdown() }
            tts = null
        }
        return ok
    }

    /**
     * 创建引擎（主线程）。有的 ROM 绑不上引擎时在构造函数里就同步回调，那一刻构造还没返回、
     * 手上还没有实例：先记下状态，等构造返回再收尾，配置用的始终是手上这个实例。
     */
    private fun startEngine(d: CompletableDeferred<Boolean>) {
        var engine: TextToSpeech? = null
        var early: Int? = null
        val t = create(context.applicationContext, TextToSpeech.OnInitListener { status ->
            val e = engine
            if (e == null) early = status else finishInit(d, e, status)
        })
        engine = t
        tts = t
        early?.let { finishInit(d, t, it) }
    }

    private fun finishInit(d: CompletableDeferred<Boolean>, t: TextToSpeech, status: Int) {
        // 超时作废之后才姗姗来迟的回调：那个实例已经关掉了，不再碰它
        val ok = status == TextToSpeech.SUCCESS && ready === d
        if (ok) {
            t.setOnUtteranceProgressListener(listener)
            val zh = t.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
            if (zh >= TextToSpeech.LANG_AVAILABLE) {
                t.language = Locale.SIMPLIFIED_CHINESE
                currentLocale = Locale.SIMPLIFIED_CHINESE
            }
            t.setSpeechRate(1f)
        }
        d.complete(ok)
    }

    /** 合成一段文本到 WAV 文件（挂起直到写完；协程取消即停止合成） */
    suspend fun synthesizeToFile(text: String, file: File) {
        if (!ensureReady()) throw IllegalStateException(S.playback.systemTtsMissingEngine)
        val engine = tts ?: throw IllegalStateException(S.playback.systemTtsUnavailable)
        val loc = localeFor(text)
        if (loc != currentLocale) {
            // 引擎没有这门语言就不切，保持原样（比报错停播强）
            val ok = withContext(Dispatchers.Main) {
                engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE && engine.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE
            }
            if (ok) currentLocale = loc
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val r = withContext(Dispatchers.Main) { engine.synthesizeToFile(text, Bundle(), file, id) }
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw IllegalStateException(S.playback.systemTtsRequestFailed)
        }
        val finished = try {
            // 坏掉的 ROM 引擎可能永远不回调：给它一个上限，超时按这一段失败处理，而不是让播放一直挂着
            withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } != null
        } catch (e: CancellationException) {
            pending.remove(id)
            runCatching { engine.stop() }
            throw e
        }
        if (!finished) {
            pending.remove(id)
            runCatching { engine.stop() }
            throw IllegalStateException(S.playback.systemTtsTimedOut)
        }
        if (!file.isFile || file.length() < 64) throw IllegalStateException(S.playback.systemTtsProducedNo)
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
        currentLocale = null
    }

    /** 引擎当前设置的语言（null = 未知，下一段必定重新设置） */
    @Volatile
    private var currentLocale: Locale? = null

    companion object {
        /** 等引擎初始化回调的上限：绑定一个本机服务正常一两秒，二十秒还没回音就当它坏了 */
        internal const val INIT_TIMEOUT_MS = 20_000L

        /** 等一段合成完成的上限：实测每段一两秒，这里放得很宽，只防坏掉的引擎永远不回调 */
        internal const val SYNTH_TIMEOUT_MS = 60_000L

        /**
         * 按这一段正文的文字挑朗读语言：汉字为主用简体中文，拉丁字母为主（英文书）用英语。
         * 0.3.x 只要设备有中文语音就一律强制中文，英文书会被中文语音硬读。
         * 汉字一个字就是一个词，拉丁字母约五个才是一个词，所以按 1 : 3 比较，中文里夹几个英文单词仍判为中文。
         */
        fun localeFor(text: String): Locale {
            var han = 0
            var latin = 0
            for (ch in text) {
                if (ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf') han++
                else if (ch in 'A'..'Z' || ch in 'a'..'z') latin++
            }
            return if (latin > 0 && han * 3 < latin) Locale.US else Locale.SIMPLIFIED_CHINESE
        }
    }
}
