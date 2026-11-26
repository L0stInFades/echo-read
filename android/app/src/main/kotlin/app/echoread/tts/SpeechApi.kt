package app.echoread.tts

import app.echoread.core.i18n.S
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.TtsModelInfo
import app.echoread.core.Voices
import app.echoread.core.net.CallKind
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import app.echoread.core.net.NetException
import app.echoread.data.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容语音接口。
 * 兼容 OpenRouter、OpenAI 官方、SiliconFlow、FishAudio 等所有实现该格式的服务。
 * （Gemini 原生接口不走这里，见 [GeminiTts]。）
 *
 * OpenRouter 的 /audio/speech 与 OpenAI 官方的差异，以 OpenRouter 官方 TTS 文档为准（2026-11 复核）：
 * ① response_format 仅支持 mp3 / pcm；Gemini TTS 与 HeyGen Voice **只回 pcm，请求 mp3 直接 400**
 *    （0.3.x 只给 Gemini 3.1 标了 pcm，后来上架的 Gemini 3.8 / 2.5 与 HeyGen 全部 400）；
 * ② instructions 现在是顶层参数：Gemini 3.8 会被转成 `speech_metadata.style`，Fish Audio 转成句首的
 *    方括号指示，其余模型忽略。0.3.x 经 provider.options.openai 透传的写法只对已下架的 OpenAI 模型生效；
 * ③ pcm 是裸流（Content-Type 携带 rate/channels），需客户端封 WAV。
 *
 * 错误约定（0.2.0 重写）：本文件抛出的**每一个**失败都是 [NetException]，其中的
 * [NetError.status] 只可能是服务端真实回的码。以前这里会为「没填 Base URL」伪造 400、
 * 为「音频为空」伪造 502，导致「4xx 还是 5xx」这个判断本身不可信。
 */
object SpeechApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 是否 OpenRouter 端点（决定请求体方言）：按主机名判定 */
    fun isOpenRouterBase(baseUrl: String): Boolean = try {
        val host = URI(baseUrl.trim()).host?.lowercase() ?: ""
        host == "openrouter.ai" || host.endsWith(".openrouter.ai")
    } catch (_: Throwable) {
        false
    }

    /** OpenRouter 应用归因头，仅对 openrouter.ai 附加 */
    private val APP_ATTRIBUTION = mapOf(
        "HTTP-Referer" to "https://github.com/L0stInFades/echo-read",
        "X-OpenRouter-Title" to "Lector",
        "X-OpenRouter-Categories" to "audio-gen"
    )

    /**
     * 同是 /audio/speech，各家仍有出入；这些出入由服务目录（[app.echoread.core.ProviderSpec]）声明，汇总成这一份选项：
     * 自建服务不要 Key、阶跃的语气参数叫 instruction、有的只回无头 PCM 且采样率不写在响应头里。
     */
    data class Options(
        val keyOptional: Boolean = false,
        val styleParam: String = "instructions",
        val pcmRate: Int = 0
    )

    fun buildHeaders(cfg: OpenAISpeechConfig): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        // 自建服务常常不设 Key：这时不发 Authorization，而不是发一个空的 Bearer（有的网关会因此直接 401）
        if (cfg.apiKey.isNotBlank()) h["Authorization"] = "Bearer ${cfg.apiKey.trim()}"
        if (isOpenRouterBase(cfg.baseUrl)) h.putAll(APP_ATTRIBUTION)
        return h
    }

    /** 组装 /audio/speech 请求体（纯函数，便于测试） */
    fun buildSpeechBody(cfg: OpenAISpeechConfig, text: String, opts: Options = Options()): JsonObject {
        val or = isOpenRouterBase(cfg.baseUrl)
        val pcmOnly = or && Voices.modelHints(cfg.model)?.pcmOnly == true
        val format = when {
            pcmOnly -> "pcm"
            or && cfg.format != "pcm" -> "mp3"
            else -> cfg.format
        }
        val voice = cfg.voice.trim()
        val instructions = cfg.instructions.trim()
        return buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            if (voice.isNotEmpty()) put("voice", voice)
            put("response_format", format)
            // OpenAI 官方与 OpenRouter 都是顶层 instructions；OpenRouter 对不支持它的模型直接忽略
            if (instructions.isNotEmpty()) put(opts.styleParam.ifBlank { "instructions" }, instructions)
        }
    }

    data class PcmParams(val rate: Int, val channels: Int)

    /**
     * 从 Content-Type（如 audio/pcm;rate=24000;channels=1）解析采样参数。
     * 头里没写采样率时用 [defaultRate]：不同模型的裸流采样率并不相同（HeyGen 是 44.1 kHz），
     * 一律按 24 kHz 封头会让 44.1 kHz 的声音慢放、变调。
     */
    fun parsePcmParams(contentType: String, defaultRate: Int = 24000): PcmParams {
        val rate = Regex("(?:^|[;\\s])rate=(\\d+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.toIntOrNull()
        val channels = Regex("(?:^|[;\\s])channels=(\\d+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.toIntOrNull()
        return PcmParams(rate?.takeIf { it > 0 } ?: defaultRate, channels?.takeIf { it > 0 } ?: 1)
    }

    /** 16-bit LE PCM 裸流封 WAV 头 */
    fun pcmToWav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val blockAlign = channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(rate)
        header.putInt(rate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private fun trimBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * 错误体最多偷看多少字节。512 → 4096：Google 的错误体把 reason（API_KEY_INVALID）、
     * 配额种类（QuotaFailure）、等待时长（RetryInfo）放在长长的 message 之后，512 字节经常截在它们前面。
     * 仍然只是偷看（peekBody），不消费 body；展示用的片段由 [NetErrors.redact] 另行截到 512。
     */
    private const val ERROR_PEEK_BYTES = 4096L

    /**
     * 响应体看起来像音频吗？
     *
     * 这道校验是实测补上的：门户认证页 / 企业代理会用 **HTTP 200 + text/html** 应答，
     * 旧实现原样当成音频写进磁盘缓存，于是 ① 用户看到的是三十秒后 Media3 抛的
     * `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`，与网络问题毫无关联；
     * ② 这段「音频」被永久缓存，之后连请求都不再发，错误再也无法自愈。
     */
    fun looksLikeAudio(bytes: ByteArray, contentType: String): Boolean {
        val ct = contentType.lowercase()
        if (ct.startsWith("text/") || ct.contains("html") || ct.contains("json") || ct.contains("xml")) return false
        if (bytes.size < 12) return false
        fun ascii(off: Int, s: String) = bytes.size > off + s.length &&
            (0 until s.length).all { bytes[off + it].toInt().toChar() == s[it] }
        return when {
            ascii(0, "ID3") -> true                                   // MP3 带 ID3v2
            bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> true  // MP3 帧同步
            ascii(0, "RIFF") && ascii(8, "WAVE") -> true              // WAV
            ascii(0, "OggS") -> true                                  // Ogg / Opus
            ascii(0, "fLaC") -> true                                  // FLAC
            ascii(4, "ftyp") -> true                                  // M4A / AAC in MP4
            ascii(0, "ADIF") -> true
            (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xF6) == 0xF0 -> true // ADTS AAC
            // HTML / JSON 的开头。这一条必须排在 Content-Type 兜底之前 ——
            // 拦截我们的网关往往照抄上游的 `Content-Type: audio/mpeg`，只有字节不会撒谎。
            startsLikeMarkup(bytes) -> false
            // 未知容器但服务端明确声明 audio/*：放行（PCM 裸流走的就是这条）
            ct.startsWith("audio/") || ct.startsWith("application/octet-stream") -> true
            // 认不出容器、也没声明是音频：不收。收下就会被永久缓存，这一段再也不会重新请求
            else -> false
        }
    }

    /** 跳过 UTF-8 BOM 与 ASCII 空白之后，第一个字节是 `<`、`{` 或 `[`（网关页常以 BOM 或 "\n<!DOCTYPE" 开头） */
    private fun startsLikeMarkup(b: ByteArray): Boolean {
        var i = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        while (i < b.size && b[i].toInt().toChar() in " \t\n\r\u000C") i++
        if (i >= b.size) return false
        val c = b[i].toInt().toChar()
        return c == '<' || c == '{' || c == '['
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    /**
     * 执行一次请求，把任何失败都归一成 [NetException]。
     * 传输层异常按类型分类（超时 / DNS / TLS / 断链），HTTP 错误按真实状态码分类，
     * 两者都保留服务商原文，不再压成一句我们自己编的话。
     * （internal：[GeminiTts] 复用同一套超时预算与错误归一，两条接口的报错口径一致。）
     */
    internal suspend fun call(
        req: Request,
        kind: CallKind,
        model: String? = null
    ): Response {
        val t0 = System.currentTimeMillis()
        return try {
            HttpClients.clientFor(kind).newCall(req).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw NetException(
                NetErrors.fromThrowable(
                    e,
                    endpoint = req.url.toString(),
                    method = req.method,
                    kind = kind,
                    model = model,
                    elapsedMs = System.currentTimeMillis() - t0
                )
            )
        }
    }

    /**
     * 非 2xx → [NetException]。
     * 用 `peekBody` 而非 `body.string().take(500)`：后者会把整个错误体（可能是几百 KB 的
     * 网关 HTML）先读进内存再截断。峰值只读 [ERROR_PEEK_BYTES] 字节，且不消费 body。
     */
    internal fun ensureOk(res: Response, kind: CallKind, model: String? = null, startedAt: Long = 0L) {
        if (res.isSuccessful) return
        val raw = try { res.peekBody(ERROR_PEEK_BYTES).string() } catch (_: Throwable) { null }
        throw NetException(
            NetErrors.fromHttp(
                status = res.code,
                bodyRaw = raw,
                retryAfterHeader = res.header("Retry-After"),
                endpoint = res.request.url.toString(),
                method = res.request.method,
                kind = kind,
                model = model,
                elapsedMs = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0
            )
        )
    }

    /**
     * 把一次成功响应里的字节规整成可以直接落盘播放的音频；不是音频就抛 [NetException]。
     * 所有协议（/audio/speech、按模板发的 HTTP 请求、JSON 里解出来的 base64 / hex）最后都过这一关，
     * 所以「200 + 一张网页」「200 + 空音频」在任何一家身上都是同一种报错，也都不会被当成音频缓存下来。
     *
     * @param pcmRate 无头 PCM 的采样率；[rawPcm] 为真时即使 Content-Type 没声明也按 PCM 处理（JSON 里解出来的裸流）
     */
    internal fun playable(
        bytes: ByteArray,
        ctype: String,
        status: Int,
        url: String,
        model: String?,
        t0: Long,
        pcmRate: Int = 24000,
        pcmChannels: Int = 1,
        rawPcm: Boolean = false
    ): ByteArray {
        // HTTP 200 但没有音频：这是真实的 200，绝不伪造成 502 —— 伪造会让它变成「可重试的
        // 上游故障」，于是一个恒定返回空音频的模型/音色组合会把 8 次退避全烧完才报错
        if (bytes.size < 10) {
            throw NetException(
                NetError(
                    category = NetCategory.EMPTY_AUDIO,
                    status = status,
                    endpoint = NetErrors.cleanEndpoint(url),
                    method = "POST",
                    kind = CallKind.SYNTHESIS,
                    model = model,
                    providerMessage = S.playback.emptyAudioBody(ctype, bytes.size),
                    elapsedMs = System.currentTimeMillis() - t0
                )
            )
        }
        val declaredPcm = Regex("audio/(x-)?pcm|audio/l16", RegexOption.IGNORE_CASE).containsMatchIn(ctype)
        return if (declaredPcm || (rawPcm && !hasContainer(bytes))) {
            val p = parsePcmParams(ctype, pcmRate)
            pcmToWav(bytes, p.rate, if (declaredPcm) p.channels else pcmChannels)
        } else if (!looksLikeAudio(bytes, ctype)) {
            // 200 但内容不是音频：按真实状态码报 PARSE，绝不落盘 —— 落盘会让这段永久损坏
            throw NetException(
                NetErrors.fromHttp(
                    status = status,
                    bodyRaw = String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.ISO_8859_1),
                    endpoint = url,
                    method = "POST",
                    kind = CallKind.SYNTHESIS,
                    model = model,
                    elapsedMs = System.currentTimeMillis() - t0
                ).copy(
                    category = NetCategory.PARSE,
                    providerMessage = S.playback.notAudioBody(ctype, bytes.size)
                )
            )
        } else if (Mp3Fix.looksLikeMp3(bytes)) {
            // 拼接式 MP3（Kokoro 等）：剥掉分段自带的 Xing 头，否则解码器只播第一段
            Mp3Fix.stripXing(bytes)
        } else {
            bytes
        }
    }

    /** 带容器头、播放器能直接认出来的格式（与 [looksLikeAudio] 里的魔数一致） */
    private fun hasContainer(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun ascii(off: Int, s: String) = (0 until s.length).all { b[off + it].toInt().toChar() == s[it] }
        return ascii(0, "ID3") || (b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) ||
            (ascii(0, "RIFF") && ascii(8, "WAVE")) || ascii(0, "OggS") || ascii(0, "fLaC") || ascii(4, "ftyp")
    }

    /** 合成一段文本，返回可直接播放的音频字节（mp3/ogg/wav；PCM 裸流已封 WAV） */
    suspend fun synthesize(cfg: OpenAISpeechConfig, text: String, opts: Options = Options()): ByteArray = withContext(Dispatchers.IO) {
        val base = trimBase(cfg.baseUrl)
        if (base.isEmpty()) throw NetException(NetErrors.config(missingKey = false, kind = CallKind.SYNTHESIS))
        if (cfg.apiKey.isBlank() && !opts.keyOptional) {
            throw NetException(NetErrors.config(missingKey = true, endpoint = "$base/audio/speech", kind = CallKind.SYNTHESIS))
        }
        val t0 = System.currentTimeMillis()
        val body = buildSpeechBody(cfg, text, opts).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/audio/speech").post(body).apply {
            buildHeaders(cfg).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = call(req, CallKind.SYNTHESIS, cfg.model)
        res.use {
            ensureOk(it, CallKind.SYNTHESIS, cfg.model, t0)
            val ctype = it.header("Content-Type") ?: ""
            val bytes = it.body?.bytes() ?: ByteArray(0)
            val rate = opts.pcmRate.takeIf { r -> r > 0 } ?: Voices.modelHints(cfg.model)?.pcmRate ?: 24000
            playable(bytes, ctype, it.code, req.url.toString(), cfg.model, t0, pcmRate = rate)
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    /**
     * 从 /models 响应中筛出语音合成候选模型（纯函数，便于测试）。
     * 取两种证据的并集：① architecture 模态元数据为「文本进、语音出」；② id 含 tts/speech。
     */
    fun pickTtsModels(root: JsonElement?): List<TtsModelInfo> {
        val data = root.obj()?.get("data").arr() ?: return emptyList()
        val byModality = data.any { it.obj()?.get("architecture").obj()?.get("output_modalities") is JsonArray }
        val out = ArrayList<TtsModelInfo>()
        for (m in data) {
            val o = m.obj() ?: continue
            val id = o["id"].str()?.takeIf { it.isNotEmpty() } ?: continue
            val arch = o["architecture"].obj()
            val outs = arch?.get("output_modalities").arr()?.mapNotNull { it.str() } ?: emptyList()
            val ins = arch?.get("input_modalities").arr()?.mapNotNull { it.str() } ?: emptyList()
            val hit = (byModality && (outs.contains("speech") || outs.contains("audio")) && ins.contains("text")) ||
                Regex("tts|speech", RegexOption.IGNORE_CASE).containsMatchIn(id)
            if (!hit) continue
            val name = o["name"].str()?.takeIf { it.isNotEmpty() } ?: id
            val voices = o["supported_voices"].arr()?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }
            val description = o["description"].str()?.trim()?.takeIf { it.isNotEmpty() }
            val pricing = o["pricing"].obj()
            val prompt = pricing?.get("prompt").num()?.takeIf { it.isFinite() && it > 0 }
            val completion = pricing?.get("completion").num()?.takeIf { it.isFinite() && it > 0 }
            out.add(TtsModelInfo(id, name, voices, description, prompt, completion))
        }
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
    }

    /** 模型列表拉取结果：区分「解析成功但一个语音模型都没有」与「压根没解析成功」 */
    data class ModelsResult(
        val models: List<TtsModelInfo>,
        /** 响应体解析成功（哪怕结果为空）。false = 返回的根本不是 JSON */
        val parsed: Boolean,
        /** 第一次带 output_modalities 过滤的请求失败了，但兜底请求成功 —— 鉴权可疑 */
        val suspiciousAuth: NetError? = null
    )

    /**
     * 拉取服务商的在线模型列表并筛出 TTS 模型。
     *
     * 两处实测教训：
     * ① 首次请求失败后会退回不带过滤的 `/models`，旧实现直接把首次的状态码丢掉，于是
     *    「POST 需要鉴权、GET /models 匿名可读」的网关会在无效 Key 下亮绿灯；现在把它带出来。
     * ② HTTP 200 + 非 JSON（门户认证页 / 企业代理）旧实现会解析失败→空列表→界面显示
     *    绿点「已连接 · 0 个语音模型」，并把缓存的模型列表覆盖掉。现在它是一个明确的错误。
     */
    suspend fun fetchTtsModels(cfg: OpenAISpeechConfig): ModelsResult = withContext(Dispatchers.IO) {
        val base = trimBase(cfg.baseUrl)
        if (base.isEmpty()) throw NetException(NetErrors.config(missingKey = false, kind = CallKind.MODEL_LIST))
        val headers = buildHeaders(cfg)
        fun get(url: String) = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val t0 = System.currentTimeMillis()
        var firstFailure: NetError? = null
        var res = call(get("$base/models?output_modalities=speech"), CallKind.MODEL_LIST)
        if (!res.isSuccessful) {
            firstFailure = NetErrors.fromHttp(
                status = res.code,
                bodyRaw = try { res.peekBody(ERROR_PEEK_BYTES).string() } catch (_: Throwable) { null },
                retryAfterHeader = res.header("Retry-After"),
                endpoint = res.request.url.toString(),
                method = "GET",
                kind = CallKind.MODEL_LIST
            )
            res.close()
            res = call(get("$base/models"), CallKind.MODEL_LIST)
        }
        res.use {
            ensureOk(it, CallKind.MODEL_LIST, startedAt = t0)
            val text = it.body?.string() ?: ""
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (root == null) {
                throw NetException(
                    NetErrors.fromHttp(
                        status = it.code,
                        bodyRaw = text,
                        endpoint = it.request.url.toString(),
                        method = "GET",
                        kind = CallKind.MODEL_LIST,
                        elapsedMs = System.currentTimeMillis() - t0
                    ).copy(category = NetCategory.PARSE)
                )
            }
            ModelsResult(
                models = pickTtsModels(root),
                parsed = true,
                suspiciousAuth = firstFailure?.takeIf { f -> f.category == NetCategory.AUTH }
            )
        }
    }

    data class Credits(val total: Double, val used: Double) {
        val remaining: Double get() = total - used
    }

    /**
     * OpenRouter 账户余额（GET /credits）。
     * `success(null)` = 不是 OpenRouter 端点（不适用）；`failure` = 真失败，界面必须显示出来。
     * 旧实现一律吞成 null，于是一把被吊销的 Key 看起来和「非 OpenRouter 端点」一模一样。
     */
    suspend fun fetchCredits(cfg: OpenAISpeechConfig): Result<Credits?> = withContext(Dispatchers.IO) {
        if (!isOpenRouterBase(cfg.baseUrl) || cfg.apiKey.isBlank()) return@withContext Result.success(null)
        try {
            val req = Request.Builder().url("${trimBase(cfg.baseUrl)}/credits").get()
                .apply { buildHeaders(cfg).forEach { (k, v) -> header(k, v) } }.build()
            call(req, CallKind.CREDITS).use { res ->
                ensureOk(res, CallKind.CREDITS)
                val body = res.body?.string() ?: ""
                val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull()
                    ?: return@withContext Result.failure(
                        NetException(
                            NetErrors.fromHttp(res.code, body, endpoint = req.url.toString(), method = "GET", kind = CallKind.CREDITS)
                                .copy(category = NetCategory.PARSE)
                        )
                    )
                val total = data["total_credits"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return@withContext Result.success(null)
                val used = data["total_usage"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                Result.success(Credits(total, used))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    data class TestResult(val ok: Boolean, val message: String, val audio: ByteArray? = null, val error: NetError? = null)
}
