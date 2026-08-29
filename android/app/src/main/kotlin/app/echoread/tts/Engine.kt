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
import java.util.Collections
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
    val error: String = ""
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
    private val prefetching: MutableSet<String> = Collections.synchronizedSet(HashSet())

    init {
        tempDir.mkdirs()
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
            error = errorMsg
        )
        playback.setActive(state == PlayerState.PLAYING)
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
            prefetchFrom(segmentIndex + 1)

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
        val cached = runCatching { audioCache.get(key) }.getOrNull()
        if (gen != generation) throw AbortedException()
        if (cached != null) {
            setRetryNote("", gen)
            return playback.play(cached, rate)
        }
        if (cfg.apiKey.isBlank()) throw SpeechHttpException("请先在朗读设置中填写 API Key", 401)

        var lastErr: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (gen != generation) throw AbortedException()
            try {
                val bytes = SpeechApi.synthesize(cfg, text)
                if (gen != generation) throw AbortedException()
                if (bytes.size < 10) throw SpeechHttpException("返回的音频为空", 502)
                val file = audioCache.put(key, bytes)
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

    /** 后台预取后续 N 个片段到缓存（换代后静默丢弃结果） */
    private fun prefetchFrom(index: Int) {
        val d = derived ?: return
        if (settings.provider == TtsProvider.SYSTEM) return
        val gen = generation
        val cfg = settings.openai
        if (cfg.apiKey.isBlank()) return
        val segments = d.segments
        val n = settings.prefetch
        for (i in index until min(index + n, segments.size)) {
            val text = slice(segments[i])
            val key = cacheKey(text, cfg)
            if (!prefetching.add(key)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    if (audioCache.get(key) != null) return@launch
                    val bytes = SpeechApi.synthesize(cfg, text)
                    if (gen != generation || bytes.size < 10) return@launch
                    audioCache.put(key, bytes)
                } catch (_: Throwable) {
                    /* 预取失败静默 */
                } finally {
                    prefetching.remove(key)
                }
            }
        }
    }

    companion object {
        /** 单个片段的合成尝试总数上限（含首次） */
        const val MAX_ATTEMPTS = 8
        /** 连续多少个片段合成失败（各自穷尽重试）才停播报错 */
        const val MAX_FAIL_STREAK = 3
    }
}
