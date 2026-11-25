package app.echoread.tts

import app.echoread.core.i18n.S
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统语音（Android TextToSpeech）：离线兜底。合成到 WAV 文件后走与 AI 语音完全相同的
 * ExoPlayer 播放管线，锁屏控制、倍速、后台续播一并复用。
 */
class SystemTts(private val context: Context) {
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

    private suspend fun ensureReady(): Boolean {
        ready?.let { return it.await() }
        val d = CompletableDeferred<Boolean>()
        ready = d
        withContext(Dispatchers.Main) {
            tts = TextToSpeech(context.applicationContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    tts?.let { t ->
                        t.setOnUtteranceProgressListener(listener)
                        val zh = t.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
                        if (zh >= TextToSpeech.LANG_AVAILABLE) {
                            t.language = Locale.SIMPLIFIED_CHINESE
                            currentLocale = Locale.SIMPLIFIED_CHINESE
                        }
                        t.setSpeechRate(1f)
                    }
                }
                d.complete(ok)
            }
        }
        return d.await()
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
        try {
            done.await()
        } catch (e: CancellationException) {
            pending.remove(id)
            runCatching { engine.stop() }
            throw e
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
