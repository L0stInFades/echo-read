package app.echoread.tts

import app.echoread.core.i18n.S
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 系统语音这一段没出声。[seen] 是当时在引擎那儿看到的现象（交回多少字节、花了多久），进错误详情 */
class NoAudioException(val seen: String) : IllegalStateException(S.playback.systemTtsProducedNo)

/**
 * 系统语音（Android TextToSpeech）：离线兜底。朗读引擎只认 [play] 这一个入口，各家引擎的脾气都收在这个文件里：
 * - 交得出音频的（绝大多数）：合成到 WAV 文件后走与 AI 语音完全相同的 ExoPlayer 播放管线，锁屏控制、倍速、后台续播一并复用；
 * - 不肯写文件、只肯自己出声的（见 [speaksAloud]）：改走 [speak]，引擎自己读，句柄跟着它的回调走；
 * - 交不出声音的（绑不上、不回调、交回一段静音）：限时之内报出原因，绝不当成读过。
 * 只按看到的行为分，不按厂商或包名分：新机型上遇到的新脾气，先在单测的替身里复现，再落到这三类之一。
 */
class SystemTts(
    private val context: Context,
    /** 交得出音频的引擎，合成出的 WAV 交给它播 */
    private val output: AudioOutput,
    /** 合成出的 WAV 临时放在这儿，播完即删 */
    private val tempDir: File,
    /** 时钟（毫秒）；参数化只为单测换成虚拟时间 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 创建平台的 TextToSpeech；参数化只为单测换成替身 */
    private val create: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = ::TextToSpeech
) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * 这台引擎不交出音频：合成到文件时只写一个空 WAV 头，自己用 AudioTrack 把这段读出声
     * （联想平板自带的云端语音就是这样）。认出来之后改让它直接朗读（[speak]），不再合成到文件。
     */
    @Volatile
    var speaksAloud = false
        private set

    /** 绑着的是哪个引擎（包名 + 版本）。进错误详情：没有这一条，别人手机上的问题无从复现 */
    val engineId: String
        get() {
            val pkg = runCatching { tts?.defaultEngine }.getOrNull()?.takeIf { it.isNotBlank() } ?: return "system-tts"
            val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
            return listOfNotNull(pkg, version).joinToString(" ")
        }

    init {
        tempDir.mkdirs()
    }

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

    /** 引擎就绪、朗读语言按这一段切好，返回手上的实例 */
    private suspend fun prepared(text: String): TextToSpeech {
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
        return engine
    }

    /** 朗读一段，返回跟着这段声音走的句柄；协程取消即停下 */
    suspend fun play(text: String, rate: Float): PlayHandle {
        // 没有一个读得出来的字（「……」「＊＊＊」）：各家引擎对这种输入有的给静音、有的秒回、有的报错，不去问它，直接翻过去
        if (text.none { it.isLetterOrDigit() }) return PlayHandle.Ended
        if (speaksAloud) return speak(text, rate)
        val file = File(tempDir, "sys-${System.nanoTime()}.wav")
        val wrote = try {
            synthesizeToFile(text, file)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        // 引擎没交出音频、自己把这段读完了：这段算播过，下一段起让它直接朗读
        if (!wrote) {
            file.delete()
            return PlayHandle.Ended
        }
        return output.play(file, rate, deleteAfter = true)
    }

    /**
     * 合成一段文本到 WAV 文件（挂起直到写完；协程取消即停止合成）。
     * 返回 false：引擎没写出音频，而是自己把这段读出了声 —— 这段已经听过了，之后改走 [speak]。
     */
    internal suspend fun synthesizeToFile(text: String, file: File): Boolean {
        val engine = prepared(text)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val startedAt = clock()
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
        val bytes = if (file.isFile) file.length() else 0L
        if (bytes >= 64 && audible(file)) return true
        val took = clock() - startedAt
        // 交回了音频却从头到尾是静音：引擎眼下的语音读不了这种文字（一加自带的引擎在系统里选着英文语音时，中文一律
        // 交回 0.16 秒的静音）。不能当成读过，否则整本书会被无声地「读」完、进度跟着跑。
        // 文件里一个采样都没有：秒回的是真没合成出来；耗时够读完一句的，是引擎自己读出了声
        if (bytes >= 64 || took < SPOKE_ALOUD_MIN_MS) throw NoAudioException("file: $bytes B, no sound · ${text.length} chars · $took ms")
        speaksAloud = true
        return false
    }

    /**
     * 让引擎直接朗读这一段（给 [speaksAloud] 的引擎用），返回跟着它回调走的句柄。主线程调用。
     * 不到一秒就说「读完了」的并没有出声（联想的云端语音离线时什么也不读就交差）：按这一段失败处理，跳段逻辑照旧。
     * 倍速交给引擎自己的语速，从下一口起生效。
     */
    internal suspend fun speak(text: String, rate: Float): PlayHandle {
        val h = Spoken(prepared(text), text, rate)
        watchAudible()
        h.start()
        val early = try {
            withTimeoutOrNull(SPOKE_ALOUD_MIN_MS) { h.awaitEnded() }
        } catch (e: CancellationException) {
            h.stop()
            throw e
        }
        if (early != null) throw NoAudioException("aloud: done in under $SPOKE_ALOUD_MIN_MS ms · ${text.length} chars")
        return h
    }

    /** 直接朗读时正在读的那一段 */
    private var speaking: Spoken? = null

    /** 引擎每读一个字要多少毫秒（1× 语速），按读完的每一口校准；0 = 还没量过 */
    @Volatile
    private var msPerChar = 0.0

    // 别的应用的播放器认不出是谁的，但「有媒体在出声」看得到：焦点在我们手上、自己的播放器又闲着，出声的就是引擎
    private val audible = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }) engineAudible()
        }
    }
    private var watching = false

    private fun watchAudible() {
        if (watching) return
        watching = true
        runCatching { context.getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(audible, null) }
    }

    /** 引擎的播放器开口了（直接朗读时）：记下时刻，暂停时据此估算读到了哪儿 */
    internal fun engineAudible() {
        speaking?.heard()
    }

    /**
     * 引擎直接朗读的一段。系统 TTS 停下就接不上：暂停时按引擎开口以来的时长折算读到了哪个字，
     * 续播从那一句（逗号、句号……）的开头重新开口 —— 多听小半句，而不是整段从头再来。
     */
    private inner class Spoken(private val engine: TextToSpeech, private val text: String, private var rate: Float) : PlayHandle {
        private val ended = CompletableDeferred<Unit>()

        /** 正在读的那一口；暂停或停下后为 null，它晚到的回调不再算数 */
        private var utterance: String? = null

        /** 这一口从第几个字读起 */
        private var from = 0

        /** 引擎这一口真正出声的时刻（0 = 还没出声） */
        @Volatile
        private var audibleAt = 0L

        fun start() {
            val id = UUID.randomUUID().toString()
            val said = CompletableDeferred<Unit>()
            utterance = id
            audibleAt = 0L
            speaking = this
            pending[id] = said
            said.invokeOnCompletion { e -> if (e == null) finished() else ended.completeExceptionally(e) }
            engine.setSpeechRate(rate)
            if (engine.speak(text.substring(from), TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                utterance = null
                ended.completeExceptionally(IllegalStateException(S.playback.systemTtsRequestFailed))
            }
        }

        fun heard() {
            if (utterance != null && audibleAt == 0L) audibleAt = clock()
        }

        /** 这一口读完了：顺手按它校准每字时长 */
        private fun finished() {
            val at = audibleAt
            val chars = text.length - from
            if (at > 0 && chars >= 8) ((clock() - at) * rate.toDouble() / chars).takeIf { it in 40.0..800.0 }?.let { msPerChar = it }
            ended.complete(Unit)
        }

        private fun halt() {
            utterance?.let { pending.remove(it) }
            utterance = null
            runCatching { engine.stop() }
        }

        override suspend fun awaitEnded() = ended.await()

        override fun pause() {
            val at = audibleAt
            if (utterance != null && at > 0) {
                // 没量过就按常见语速估：汉字一个字约 230 毫秒、拉丁字母约 75 毫秒（宁慢勿快：估慢了多听半句，估快了会漏）
                val perChar = msPerChar.takeIf { it > 0 } ?: if (localeFor(text) == Locale.US) 75.0 else 230.0
                from = resumeFrom(text, from + ((clock() - at) * rate / perChar).toInt())
            }
            halt()
        }

        override fun resume() {
            if (utterance == null && !ended.isCompleted) start()
        }

        override fun stop() {
            halt()
            ended.completeExceptionally(AbortedException())
        }

        override fun setRate(rate: Float) {
            this.rate = rate
        }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
        currentLocale = null
        speaksAloud = false
        if (watching) runCatching { context.getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(audible) }
        watching = false
    }

    /** 引擎当前设置的语言（null = 未知，下一段必定重新设置） */
    @Volatile
    private var currentLocale: Locale? = null

    companion object {
        /** 等引擎初始化回调的上限：绑定一个本机服务正常一两秒，二十秒还没回音就当它坏了 */
        internal const val INIT_TIMEOUT_MS = 20_000L

        /** 等一段合成完成的上限：实测每段一两秒，这里放得很宽，只防坏掉的引擎永远不回调 */
        internal const val SYNTH_TIMEOUT_MS = 60_000L

        /** 一口话至少要这么久才算真的读出了声（联想的云端语音读一句要好几秒，离线或坏掉的引擎是秒回的） */
        internal const val SPOKE_ALOUD_MIN_MS = 1_000L

        /** 16 位采样的幅度超过它才算有声音（满幅 32767）。实测：一加交回的静音峰值是 4，正常朗读在几千到一万 */
        private const val AUDIBLE = 256

        /**
         * 引擎写出的 WAV 里有没有声音：找到一个听得见的采样就算有，所以正常的一段只读开头几 KB，
         * 从头读到尾的只有真是静音的那种。8 位、浮点这些少见的位深不去解，照旧交给播放器。
         */
        internal fun audible(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { f ->
                val buf = ByteArray(16 * 1024)
                f.readFully(buf, 0, 44) // 框架写的 WAV 头固定 44 字节，位深在第 34 字节
                if (buf[34].toInt() != 16) return@use true
                while (true) {
                    val n = f.read(buf)
                    if (n < 2) break
                    for (i in 0 until n - 1 step 2) {
                        val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                        if (v > AUDIBLE || v < -AUDIBLE) return@use true
                    }
                }
                false
            }
        }.getOrDefault(false)

        private val CLAUSE_ENDS = charArrayOf('，', '。', '！', '？', '；', '：', '、', '…', ',', '.', '!', '?', ';', ':', '\n')

        /** 续播从第几个字开口：估算读到的位置 [at] 往回退到那一句（逗号、句号……）的开头，至少留一个字给引擎读 */
        internal fun resumeFrom(text: String, at: Int): Int {
            val end = minOf(at, text.length - 1)
            return if (end <= 0) 0 else text.lastIndexOfAny(CLAUSE_ENDS, end - 1) + 1
        }

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
