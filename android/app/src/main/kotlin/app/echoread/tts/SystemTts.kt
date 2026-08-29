package app.echoread.tts

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
            utteranceId?.let { pending.remove(it)?.completeExceptionally(IllegalStateException("系统语音合成出错")) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.completeExceptionally(IllegalStateException("系统语音合成出错（$errorCode）")) }
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
                        if (zh >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.SIMPLIFIED_CHINESE
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
        if (!ensureReady()) throw IllegalStateException("系统语音不可用，请在系统设置中安装语音引擎")
        val engine = tts ?: throw IllegalStateException("系统语音不可用")
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val r = withContext(Dispatchers.Main) { engine.synthesizeToFile(text, Bundle(), file, id) }
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw IllegalStateException("系统语音合成请求失败")
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            pending.remove(id)
            runCatching { engine.stop() }
            throw e
        }
        if (!file.isFile || file.length() < 64) throw IllegalStateException("系统语音未生成音频")
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
    }
}
