package app.echoread.tts

import app.echoread.core.Hash
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.core.Segmenter
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings
import app.echoread.data.ChapterCache
import app.echoread.data.DerivedChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class EngineSnapshot(
    val state: PlayerState = PlayerState.IDLE,
    val bookId: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val segmentStart: Int = 0,
    val segmentEnd: Int = 0,
    /** 正在调用 TTS 接口合成（尚未开始出声） */
    val synthesizing: Boolean = false,
    /** 自愈进行时的可见提示（退避倒计时/跳段说明） */
    val retryNote: String = "",
    val error: String = "",
    /** 当前片段之后已就绪（可零等待播放）的连续片段数 */
    val buffered: Int = 0
)

/** 指数退避延迟（毫秒）：1s 起倍增、30s 封顶、±20% 抖动；attempt 从 0 计 */
fun backoffDelay(attempt: Int, rand: () -> Double = { Math.random() }): Long {
    val base = min(1000.0 * 2.0.pow(attempt), 30000.0)
    return (base * (1 + (rand() * 2 - 1) * 0.2)).roundToInt().toLong()
}

/**
 * 朗读引擎（对应网页版 tts/engine.ts）：只持有 派生章节引用 + 当前片段下标 两个核心状态。
 * 并发模型：所有状态变更都在主线程；每个可能产生在途异步工作的方法（load/seek/合成中暂停）
 * 都递增 generation 并取消旧循环，循环每次从挂起点恢复先校验代际，过期即退出。
 */
class TtsEngine(
    private val scope: CoroutineScope,
    private val chapters: ChapterCache,
    private val audioCache: AudioCache,
    private val systemTts: SystemTts,
    private val playback: Playback,
    private val tempDir: File
) {
    @Volatile
    var settings: TtsSettings = TtsSettings()
        private set

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot

    private var state = PlayerState.IDLE
    private var bookId = ""
    private var chapterCount = 0
    private var derived: DerivedChapter? = null
    private var chapterIndex = -1
    private var segmentIndex = 0

    private var handle: PlayHandle? = null
    private var loopJob: Job? = null

    @Volatile
    private var generation = 0
    private var synthesizing = false
    private var errorMsg = ""
    private var retryNote = ""
    /** 连续合成失败的片段数，任一片段成功播出即清零 */
    private var failStreak = 0
    private val pipeline = SynthPipeline(scope, audioCache)
    private var lastEndedAt = 0L

    init {
        tempDir.mkdirs()
        pipeline.onChanged = { ensureLookahead() }
        playback.onInterrupt = { pause() }
        playback.onResumeAfterInterrupt = { if (state == PlayerState.PAUSED) play() }
    }

    val current: EngineSnapshot get() = _snapshot.value

    /** 当前片段已朗读的比例（0..1，按音频进度）；无在播句柄时为 0 */
    fun playbackFraction(): Float = if (handle != null) playback.progressFraction() else 0f

    private fun emit() {
        _snapshot.value = EngineSnapshot(
            state = state,
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = derived?.title ?: "",
            segmentIndex = segmentIndex,
            segmentCount = derived?.segments?.size ?: 0,
            segmentStart = derived?.segments?.getOrNull(segmentIndex)?.start ?: 0,
            segmentEnd = derived?.segments?.getOrNull(segmentIndex)?.end ?: 0,
            synthesizing = synthesizing,
            retryNote = retryNote,
            error = errorMsg,
            buffered = bufferedAhead()
        )
        playback.setActive(state == PlayerState.PLAYING)
    }

    /** 当前片段之后连续已就绪的片段数（最多数 8 段） */
    private fun bufferedAhead(): Int {
        val d = derived ?: return 0
        if (settings.provider == TtsProvider.SYSTEM) return 0
        val cfg = settings.openai
        var n = 0
        var i = segmentIndex + 1
        while (i < d.segments.size && n < 8) {
            if (!pipeline.isReady(cacheKey(slice(d.segments[i]), cfg))) break
            n++
            i++
        }
        return n
    }

    fun updateConfig(s: TtsSettings) {
        settings = s
        handle?.setRate(s.rate)
    }

    /** 片段长度等分段参数变化后，原地按当前偏移重载（保持播放状态） */
    suspend fun reload() {
        if (state == PlayerState.IDLE || state == PlayerState.LOADING || chapterIndex < 0) return
        val offset = derived?.segments?.getOrNull(segmentIndex)?.start ?: 0
        val playing = state == PlayerState.PLAYING
        if (load(bookId, chapterIndex, offset, chapterCount) && playing) play()
    }

    /* ---------- 章节装载 ---------- */

    /** 装载成功返回 true；被更新的装载取代或章节缺失返回 false */
    suspend fun load(bookId: String, chapterIndex: Int, offset: Int, chapterCount: Int): Boolean {
        generation++
        val gen = generation
        stopHandle(keep = coroutineContext[Job])
        state = PlayerState.LOADING
        errorMsg = ""
        this.bookId = bookId
        this.chapterCount = chapterCount
        // 装载期间快照归零，避免新旧章节混杂
        derived = null
        this.chapterIndex = -1
        emit()

        // 调用方协程被取消（界面离开等）也把装载做完：引擎状态必须收敛，不能停在 LOADING
        val d = try {
            withContext(NonCancellable) { chapters.get(bookId, chapterIndex, settings.maxChunkChars) }
        } catch (_: Exception) {
            null
        }
        if (gen != generation) return false
        if (d == null) {
            state = PlayerState.ERROR
            errorMsg = "章节内容缺失"
            emit()
            return false
        }
        derived = d
        this.chapterIndex = chapterIndex
        segmentIndex = if (d.segments.isNotEmpty()) Segmenter.segmentIndexAt(d.segments, offset) else 0
        state = PlayerState.PAUSED
        emit()
        ensureLookahead()
        return true
    }

    /* ---------- 播放控制 ---------- */

    fun play() {
        if (state != PlayerState.PAUSED && state != PlayerState.ERROR) return
        if (derived == null) return
        handle?.let {
            it.resume()
            state = PlayerState.PLAYING
            emit()
            return
        }
        errorMsg = ""
        failStreak = 0
        state = PlayerState.PLAYING
        emit()
        ensureLookahead()
        val gen = generation
        loopJob = scope.launch { loop(gen) }
    }

    fun pause() {
        if (state != PlayerState.PLAYING) return
        val h = handle
        if (h != null) {
            // 正常播放中：句柄级暂停，可被 resume 续上，loop 仍在等 ended
            h.pause()
        } else if (synthesizing) {
            // 合成窗口期：中止在途请求并换代，旧 loop 经代际守卫退出（防止暂停后仍出声）
            generation++
            stopHandle(keep = null)
        }
        state = PlayerState.PAUSED
        emit()
    }

    fun toggle() {
        if (state == PlayerState.PLAYING) pause() else play()
    }

    /** 任意字跳转：将朗读位置定位到章节文本的指定字符偏移 */
    fun seekToOffset(offset: Int) {
        val segments = derived?.segments
        if (segments.isNullOrEmpty()) return
        generation++
        val wasPlaying = state == PlayerState.PLAYING
        stopHandle(keep = null)
        segmentIndex = Segmenter.segmentIndexAt(segments, offset)
        errorMsg = ""
        failStreak = 0
        if (wasPlaying) {
            state = PlayerState.PLAYING
            emit()
            val gen = generation
            loopJob = scope.launch { loop(gen) }
        } else {
            if (state != PlayerState.IDLE) state = PlayerState.PAUSED
            emit()
        }
        ensureLookahead()
    }

    /** 跳章节并从头朗读（或停在开头） */
    suspend fun gotoChapter(chapterIndex: Int, autoplay: Boolean) {
        val wasPlaying = autoplay || state == PlayerState.PLAYING
        if (load(bookId, chapterIndex, 0, chapterCount) && wasPlaying) play()
    }

    val hasNextChapter: Boolean get() = chapterIndex >= 0 && chapterIndex < chapterCount - 1
    val hasPrevChapter: Boolean get() = chapterIndex > 0
    val chapterTotal: Int get() = chapterCount

    /** 释放引擎：停止一切在途工作（应用退出） */
    fun stopAll() {
        generation++
        stopHandle(keep = null)
        state = PlayerState.IDLE
        derived = null
        chapterIndex = -1
        emit()
    }

    /* ---------- 主循环 ---------- */

    /** 停掉在途合成与播放；keep 为当前协程自身的 Job 时不取消它（装载来自循环内部的自动跨章） */
    private fun stopHandle(keep: Job?) {
        synthesizing = false
        retryNote = ""
        val job = loopJob
        if (job != null && job !== keep) {
            job.cancel()
            loopJob = null
        }
        handle?.stop()
        handle = null
    }

    private fun slice(seg: Range): String = derived!!.text.substring(seg.start, seg.end)

    private suspend fun loop(startGen: Int) {
        var gen = startGen
        while (true) {
            if (gen != generation || state != PlayerState.PLAYING) return
            val d = derived ?: return
            val segments = d.segments
            if (segmentIndex >= segments.size) {
                // 本章播完 → 自动下一章（load 内部换代，成功后更新本地 gen 续播）
                if (hasNextChapter) {
                    if (!load(bookId, chapterIndex + 1, 0, chapterCount)) return
                    gen = generation
                    state = PlayerState.PLAYING
                    emit()
                    continue
                }
                state = PlayerState.PAUSED
                emit()
                return
            }

            val seg = segments[segmentIndex]
            emit() // 更新高亮

            val h: PlayHandle
            try {
                synthesizing = true
                emit()
                h = createHandle(slice(seg), gen)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AbortedException) {
                if (gen == generation) synthesizing = false
                return
            } catch (e: Exception) {
                // 僵尸 loop（已换代）不得回写共享合成状态
                if (gen != generation) return
                synthesizing = false
                if (SpeechApi.isFatalSpeechError(e)) {
                    // 配置类错误（无效 Key 等）：跳段无意义，立即停播暴露给用户
                    state = PlayerState.ERROR
                    errorMsg = e.message ?: e.toString()
                    retryNote = ""
                    emit()
                    return
                }
                // 单段重试穷尽：跳过本段续播，连续多段失败才认定环境不可用
                failStreak++
                if (failStreak >= MAX_FAIL_STREAK) {
                    state = PlayerState.ERROR
                    errorMsg = if (settings.provider == TtsProvider.SYSTEM) (e.message ?: "系统语音合成失败")
                    else "连续多段合成失败，请检查网络或 TTS 配置"
                    retryNote = ""
                    emit()
                    return
                }
                retryNote = "本段合成失败，已跳过"
                segmentIndex++
                emit()
                continue
            }
            if (gen == generation) synthesizing = false
            // 合成窗口期被 pause/seek/换章：句柄不落地，直接停掉（防孤儿音频）
            if (gen != generation || state != PlayerState.PLAYING) {
                h.stop()
                return
            }
            handle = h
            failStreak = 0
            emit() // 句柄落地：出声期间不再显示「合成中」
            // 观测：段间等待（上段播完 → 本段出声）与缓冲深度，用于验证流水线效果
            if (lastEndedAt > 0) android.util.Log.d("EchoTts", "seg=$segmentIndex gap=${System.currentTimeMillis() - lastEndedAt}ms buffered=${bufferedAhead()} synthEma=${pipeline.synthMs.toInt()}ms msPerChar=${pipeline.msPerChar.toInt()}")
            ensureLookahead()

            val playStart = System.currentTimeMillis()
            try {
                h.awaitEnded()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 被打断（seek/pause 内部 stop）或播放错误；死句柄必须摘除
                if (handle === h) handle = null
                if (gen != generation) return
                if (state == PlayerState.PLAYING) {
                    state = PlayerState.ERROR
                    errorMsg = "播放中断"
                    emit()
                }
                return
            }
            if (gen != generation) return
            if (handle === h) handle = null
            lastEndedAt = System.currentTimeMillis()
            // 以实际播放时长校准每字时长（换算回 1× 倍速），驱动前瞻窗口自适应
            pipeline.recordPlayback(seg.end - seg.start, ((System.currentTimeMillis() - playStart) * settings.rate).toLong())
            segmentIndex++
            emit()
        }
    }

    private fun cacheKey(text: String, c: OpenAISpeechConfig): String =
        Hash.cyrb53("${settings.provider}|${c.model}|${c.voice}|${c.format}|${c.instructions}|$text")

    /** 合成一个片段并返回可播放句柄（带缓存、指数退避重试；协程取消即中止） */
    private suspend fun createHandle(text: String, gen: Int): PlayHandle {
        val rate = settings.rate
        if (settings.provider == TtsProvider.SYSTEM) {
            val file = File(tempDir, "sys-${System.nanoTime()}.wav")
            try {
                systemTts.synthesizeToFile(text, file)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
            if (gen != generation) {
                file.delete()
                throw AbortedException()
            }
            return playback.play(file, rate, deleteAfter = true)
        }

        // 退避重试窗口可达分钟级，期间改音色/模型不换代：缓存键与全部重试锚定入口配置快照
        val cfg = settings.openai
        val key = cacheKey(text, cfg)
        // 热表 / 磁盘命中：无需 Key 也可离线回放已缓存片段
        val hot = pipeline.readyFile(key) ?: runCatching { audioCache.get(key) }.getOrNull()
        if (gen != generation) throw AbortedException()
        if (hot != null) {
            setRetryNote("", gen)
            return playback.play(hot, rate)
        }
        if (cfg.apiKey.isBlank()) throw SpeechHttpException("请先在朗读设置中填写 API Key", 401)

        var lastErr: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (gen != generation) throw AbortedException()
            try {
                // 流水线去重：若预取已在途则直接等它，绝不重复请求
                val file = pipeline.obtain(key, cfg, text)
                if (gen != generation) throw AbortedException()
                setRetryNote("", gen)
                return playback.play(file, rate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AbortedException) {
                throw e
            } catch (e: Exception) {
                // 配置类错误（401/404 等）重试救不了：立即上抛，保证无 Key 场景快速报错
                if (SpeechApi.isFatalSpeechError(e)) throw e
                lastErr = e
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                val d = backoffDelay(attempt)
                setRetryNote("网络异常，${(d / 1000.0).roundToInt()} 秒后重试（第 ${attempt + 2}/$MAX_ATTEMPTS 次）", gen)
                delay(d)
            }
        }
        throw lastErr ?: IllegalStateException("合成失败")
    }

    /** 更新自愈提示并广播；换代后（暂停/跳转）静默忽略，防僵尸 loop 污染新状态 */
    private fun setRetryNote(note: String, gen: Int) {
        if (gen != generation || retryNote == note) return
        retryNote = note
        emit()
    }

    /**
     * 补满前瞻窗口：从当前片段之后开始，确保「已就绪 + 在途」覆盖足够的可播时长
     * （窗口按合成耗时与播放速率自适应，最少 settings.prefetch 段、最多 MAX_LOOKAHEAD 段），
     * 新请求并发不超过 MAX_PARALLEL；章尾剩余不足时提前派生下一章并预取其开头。
     * 播放中与暂停中都维持窗口（暂停多半会继续），空闲/出错不预取。
     */
    private fun ensureLookahead() {
        val d = derived ?: return
        if (settings.provider == TtsProvider.SYSTEM) return
        if (state != PlayerState.PLAYING && state != PlayerState.PAUSED) return
        val cfg = settings.openai
        if (cfg.apiKey.isBlank()) return
        val want = pipeline.lookaheadCount(
            avgSegChars = settings.maxChunkChars.coerceAtLeast(40),
            min = settings.prefetch.coerceIn(1, MAX_LOOKAHEAD),
            max = MAX_LOOKAHEAD
        )
        val segments = d.segments
        var covered = 0
        var i = segmentIndex + 1
        while (i < segments.size && covered < want) {
            val text = slice(segments[i])
            val key = cacheKey(text, cfg)
            if (!pipeline.isReady(key) && !pipeline.isPending(key)) {
                if (pipeline.inflightCount >= MAX_PARALLEL) return
                pipeline.prefetch(key, cfg, text)
            }
            covered++
            i++
        }
        // 跨章前瞻：本章剩余不足窗口时，预取下一章开头（派生结果本身也进 ChapterCache，切章零等待）
        if (covered < want && hasNextChapter && pipeline.inflightCount < MAX_PARALLEL) {
            val need = want - covered
            val nextIndex = chapterIndex + 1
            val myGen = generation
            scope.launch {
                val nd = runCatching { chapters.get(bookId, nextIndex, settings.maxChunkChars) }.getOrNull() ?: return@launch
                if (myGen != generation) return@launch
                for (j in 0 until min(need, nd.segments.size)) {
                    if (pipeline.inflightCount >= MAX_PARALLEL) break
                    val text = nd.text.substring(nd.segments[j].start, nd.segments[j].end)
                    pipeline.prefetch(cacheKey(text, cfg), cfg, text)
                }
            }
        }
    }

    companion object {
        /** 单个片段的合成尝试总数上限（含首次） */
        const val MAX_ATTEMPTS = 8
        /** 连续多少个片段合成失败（各自穷尽重试）才停播报错 */
        const val MAX_FAIL_STREAK = 3
        /** 前瞻窗口上限（段） */
        const val MAX_LOOKAHEAD = 6
        /** 预取并发上限（遵守服务商限流） */
        const val MAX_PARALLEL = 2
    }
}
