package app.echoread.tts

import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 合成流水线（主线程调用）：
 * - 在途去重：`key → Deferred<File>` 单一登记表，播放器与预取共用，同一片段绝不重复请求；
 * - 内存热表：前瞻窗口内的文件同步命中，段间切换不跳 IO 线程；磁盘 LRU 由 AudioCache 负责；
 * - 自适应度量：合成耗时 EMA 与每字播放时长 EMA，供引擎计算需要提前多少段才不会断流；
 * - **失败冷却**：失败的 key 进入指数退避冷却期，期间预取跳过它（见 [isCoolingDown]）。
 *
 * 冷却是 0.2.0 修掉的一个隐形重灾区：失败的 `async` 处于 cancelled 态，[liveInflight] 视其为
 * 「从未请求」，而 `invokeOnCompletion` 无论成败都会触发 [onChanged] → 引擎补窗 → 立刻重发。
 * 于是一把无效 Key 会产生约 10 次/秒的 401 风暴，永不停止，界面上却一点痕迹都没有，
 * 而且**暂停状态下也在跑**（load() 之后引擎就停在 PAUSED）。
 */
class SynthPipeline(
    private val scope: CoroutineScope,
    private val audioCache: AudioCache
) {
    private val inflight = HashMap<String, Deferred<File>>()
    private val ready = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?): Boolean = size > 64
    }

    /** key → 冷却截止时刻（毫秒）。仅约束 [prefetch]，[obtain] 永不受它阻挡 */
    private val cooldownUntil = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 128
    }
    private val consecutiveFailures = HashMap<String, Int>()

    /** 合成一段的平均耗时（毫秒，EMA） */
    var synthMs: Double = 5000.0
        private set

    /** 1× 倍速下每字播放时长（毫秒，EMA；中文约 180ms/字） */
    var msPerChar: Double = 180.0
        private set

    /** 任一在途任务完成（成功或失败）后回调，引擎借此补满前瞻窗口 */
    var onChanged: (() -> Unit)? = null

    /**
     * 预取失败回调。引擎据此判断「配置坏了」并立刻停播报错 ——
     * 这是应用能最早察觉无效 Key 的时刻（打开书的瞬间，无需用户按播放）。
     */
    var onFailure: ((key: String, err: NetError) -> Unit)? = null

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

    /** 该 key 是否处于失败冷却期（只影响投机性预取） */
    fun isCoolingDown(key: String, now: Long = System.currentTimeMillis()): Boolean =
        (cooldownUntil[key] ?: 0L) > now

    /** 成功播放/命中后清掉冷却记录，避免一次抖动长期压制某一段 */
    fun clearCooldown(key: String) {
        cooldownUntil.remove(key)
        consecutiveFailures.remove(key)
    }

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

    /** 预取：已就绪/在途/冷却中则跳过；返回是否新发起了请求 */
    fun prefetch(key: String, cfg: OpenAISpeechConfig, text: String): Boolean {
        if (readyFile(key) != null || liveInflight(key) != null || isCoolingDown(key)) return false
        start(key, cfg, text)
        return true
    }

    private fun start(key: String, cfg: OpenAISpeechConfig, text: String): Deferred<File> {
        val t0 = System.currentTimeMillis()
        val d = scope.async(Dispatchers.IO) {
            audioCache.get(key)?.let { return@async it }
            val bytes = SpeechApi.synthesize(cfg, text)
            audioCache.put(key, bytes)
        }
        inflight[key] = d
        d.invokeOnCompletion { cause ->
            // 回到主线程更新登记表（与 obtain/prefetch 同一线程，无需锁）
            scope.launch {
                if (inflight[key] === d) inflight.remove(key)
                if (cause == null) {
                    runCatching { ready[key] = d.getCompleted() }
                    clearCooldown(key)
                    val cost = (System.currentTimeMillis() - t0).toDouble()
                    if (cost > 200) synthMs = ema(synthMs, cost, 0.3)
                } else if (cause !is CancellationException) {
                    val n = (consecutiveFailures[key] ?: 0) + 1
                    consecutiveFailures[key] = n
                    // 1s → 2s → 4s … 60s 封顶。没有这条曲线，失败就是无退避的死循环
                    cooldownUntil[key] = System.currentTimeMillis() + min(1000.0 * 2.0.pow(n - 1), 60_000.0).toLong()
                    onFailure?.invoke(
                        key,
                        NetErrors.fromThrowable(cause, endpoint = cfg.baseUrl, model = cfg.model, attempt = n)
                    )
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
