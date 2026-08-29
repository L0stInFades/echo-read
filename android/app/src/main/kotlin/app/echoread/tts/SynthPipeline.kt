package app.echoread.tts

import app.echoread.core.OpenAISpeechConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * 合成流水线（主线程调用）：
 * - 在途去重：`key → Deferred<File>` 单一登记表，播放器与预取共用，同一片段绝不重复请求；
 * - 内存热表：前瞻窗口内的文件同步命中，段间切换不跳 IO 线程；磁盘 LRU 由 AudioCache 负责；
 * - 自适应度量：合成耗时 EMA 与每字播放时长 EMA，供引擎计算需要提前多少段才不会断流。
 */
class SynthPipeline(
    private val scope: CoroutineScope,
    private val audioCache: AudioCache
) {
    private val inflight = HashMap<String, Deferred<File>>()
    private val ready = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?): Boolean = size > 64
    }

    /** 合成一段的平均耗时（毫秒，EMA） */
    var synthMs: Double = 5000.0
        private set

    /** 1× 倍速下每字播放时长（毫秒，EMA；中文约 180ms/字） */
    var msPerChar: Double = 180.0
        private set

    /** 任一在途任务完成（成功或失败）后回调，引擎借此补满前瞻窗口 */
    var onChanged: (() -> Unit)? = null

    val inflightCount: Int get() = inflight.size

    fun readyFile(key: String): File? {
        val f = ready[key] ?: return null
        if (f.isFile) return f
        ready.remove(key)
        return null
    }

    private fun liveInflight(key: String): Deferred<File>? {
        val d = inflight[key] ?: return null
        // 失败的 Deferred 处于 cancelled 态：视为不存在，允许重新发起
        return if (d.isCompleted && d.isCancelled) null else d
    }

    fun isPending(key: String): Boolean = liveInflight(key) != null
    fun isReady(key: String): Boolean = readyFile(key) != null

    /** 取得片段音频：热表 → 在途 → 磁盘 → 新请求（单次尝试，失败抛出，由调用方决定重试） */
    suspend fun obtain(key: String, cfg: OpenAISpeechConfig, text: String): File {
        readyFile(key)?.let { return it }
        liveInflight(key)?.let { return it.await() }
        audioCache.get(key)?.let {
            ready[key] = it
            return it
        }
        return start(key, cfg, text).await()
    }

    /** 预取：已就绪/在途则跳过；返回是否新发起了请求 */
    fun prefetch(key: String, cfg: OpenAISpeechConfig, text: String): Boolean {
        if (readyFile(key) != null || liveInflight(key) != null) return false
        start(key, cfg, text)
        return true
    }

    private fun start(key: String, cfg: OpenAISpeechConfig, text: String): Deferred<File> {
        val t0 = System.currentTimeMillis()
        val d = scope.async(Dispatchers.IO) {
            audioCache.get(key)?.let { return@async it }
            val bytes = SpeechApi.synthesize(cfg, text)
            if (bytes.size < 10) throw SpeechHttpException("返回的音频为空", 502)
            audioCache.put(key, bytes)
        }
        inflight[key] = d
        d.invokeOnCompletion { cause ->
            // 回到主线程更新登记表（与 obtain/prefetch 同一线程，无需锁）
            scope.launch {
                if (inflight[key] === d) inflight.remove(key)
                if (cause == null) {
                    runCatching { ready[key] = d.getCompleted() }
                    val cost = (System.currentTimeMillis() - t0).toDouble()
                    if (cost > 200) synthMs = ema(synthMs, cost, 0.3)
                }
                onChanged?.invoke()
            }
        }
        return d
    }

    /** 记录一段实际播放：字数与 1× 倍速下的音频时长 */
    fun recordPlayback(chars: Int, audioMs: Long) {
        if (chars <= 0 || audioMs <= 0) return
        val v = audioMs.toDouble() / chars
        if (v in 40.0..800.0) msPerChar = ema(msPerChar, v, 0.25)
    }

    /**
     * 前瞻段数：保证已缓冲可播时长 ≥ max(2.5 × 合成耗时, floorMs)，
     * 在 [min, max] 内自适应——网络慢就多囤几段，快就少花钱。
     */
    fun lookaheadCount(avgSegChars: Int, min: Int, max: Int, floorMs: Double = 15_000.0): Int {
        val segMs = max(avgSegChars, 20) * msPerChar
        val need = max(synthMs * 2.5, floorMs)
        return ceil(need / segMs).toInt().coerceIn(min, max)
    }

    private fun ema(old: Double, sample: Double, alpha: Double) = old * (1 - alpha) + sample * alpha
}
