package app.echoread.tts

import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.TtsModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 携带 HTTP 状态码的接口错误（网络层 IOException 无状态码，不属此类） */
class SpeechHttpException(message: String, val status: Int) : IOException(message)

/**
 * OpenAI 兼容语音接口（对应网页版 tts/providers/openai-speech.ts）。
 * 兼容 OpenRouter、OpenAI 官方、SiliconFlow、FishAudio 等所有实现该格式的服务。
 *
 * OpenRouter 的 /audio/speech 与 OpenAI 官方有三处差异：
 * ① response_format 仅支持 mp3 / pcm；
 * ② instructions 不是顶层参数，须经 provider.options.openai 透传；
 * ③ Gemini TTS 仅回 pcm 裸流（Content-Type 携带 rate/channels），需客户端封 WAV。
 */
object SpeechApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
        "X-OpenRouter-Title" to "EchoRead",
        "X-OpenRouter-Categories" to "audio-gen"
    )

    fun buildHeaders(cfg: OpenAISpeechConfig): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Authorization"] = "Bearer ${cfg.apiKey.trim()}"
        if (isOpenRouterBase(cfg.baseUrl)) h.putAll(APP_ATTRIBUTION)
        return h
    }

    /** 配置类致命错误判定：4xx（408/429 除外）重试无意义；429/408/5xx/网络层错误可恢复 */
    fun isFatalSpeechError(e: Throwable?): Boolean {
        val status = (e as? SpeechHttpException)?.status ?: return false
        return status in 400..499 && status != 408 && status != 429
    }

    /** 组装 /audio/speech 请求体（纯函数，便于测试） */
    fun buildSpeechBody(cfg: OpenAISpeechConfig, text: String): JsonObject {
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
            if (instructions.isNotEmpty()) {
                if (or) {
                    putJsonObject("provider") { putJsonObject("options") { putJsonObject("openai") { put("instructions", instructions) } } }
                } else {
                    put("instructions", instructions)
                }
            }
        }
    }

    data class PcmParams(val rate: Int, val channels: Int)

    /** 从 Content-Type（如 audio/pcm;rate=24000;channels=1）解析采样参数 */
    fun parsePcmParams(contentType: String): PcmParams {
        val rate = Regex("(?:^|[;\\s])rate=(\\d+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.toIntOrNull()
        val channels = Regex("(?:^|[;\\s])channels=(\\d+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.toIntOrNull()
        return PcmParams(rate?.takeIf { it > 0 } ?: 24000, channels?.takeIf { it > 0 } ?: 1)
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

    /** 统一的错误映射：状态码/JSON 错误体 → 用户可读信息，异常携带 status 供重试策略分类 */
    private fun ensureOk(res: Response, action: String) {
        if (res.isSuccessful) return
        val raw = try { res.body?.string()?.take(500) ?: "" } catch (_: Throwable) { "" }
        var msg = ""
        try {
            msg = json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
        } catch (_: Throwable) {
            /* 非 JSON 错误体 */
        }
        val status = res.code
        val text = when {
            status == 401 -> "API Key 无效或已过期（401）"
            status == 402 -> "账户余额不足（402）"
            status == 404 -> "接口或模型不存在（404），请检查 Base URL 与模型名"
            status == 429 -> "请求过于频繁（429），稍后重试"
            status >= 500 -> "上游服务商暂时故障（$status），稍后重试" + if (msg.isNotEmpty()) "：$msg" else ""
            else -> "${action}失败（$status）：${msg.ifEmpty { raw.ifEmpty { res.message } }}"
        }
        throw SpeechHttpException(text, status)
    }

    /** 合成一段文本，返回可直接播放的音频字节（mp3/ogg/wav；PCM 裸流已封 WAV） */
    suspend fun synthesize(cfg: OpenAISpeechConfig, text: String): ByteArray = withContext(Dispatchers.IO) {
        val base = trimBase(cfg.baseUrl)
        if (base.isEmpty()) throw SpeechHttpException("请先填写 Base URL", 400)
        val body = buildSpeechBody(cfg, text).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/audio/speech").post(body).apply {
            buildHeaders(cfg).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).await()
        res.use {
            ensureOk(it, "合成")
            val ctype = it.header("Content-Type") ?: ""
            val bytes = it.body?.bytes() ?: ByteArray(0)
            if (Regex("audio/(x-)?pcm|audio/l16", RegexOption.IGNORE_CASE).containsMatchIn(ctype)) {
                val (rate, channels) = parsePcmParams(ctype)
                pcmToWav(bytes, rate, channels)
            } else {
                bytes
            }
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

    /** 拉取服务商的在线模型列表并筛出 TTS 模型 */
    suspend fun fetchTtsModels(cfg: OpenAISpeechConfig): List<TtsModelInfo> = withContext(Dispatchers.IO) {
        val base = trimBase(cfg.baseUrl)
        if (base.isEmpty()) throw SpeechHttpException("请先填写 Base URL", 400)
        val headers = buildHeaders(cfg)
        fun get(url: String) = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        var res = client.newCall(get("$base/models?output_modalities=speech")).await()
        if (!res.isSuccessful) {
            res.close()
            res = client.newCall(get("$base/models")).await()
        }
        res.use {
            ensureOk(it, "获取模型列表")
            val text = it.body?.string() ?: ""
            pickTtsModels(runCatching { json.parseToJsonElement(text) }.getOrNull())
        }
    }

    data class TestResult(val ok: Boolean, val message: String)

    /** 校验配置是否可用（用极短文本试合成） */
    suspend fun testConfig(cfg: OpenAISpeechConfig): TestResult = try {
        val bytes = synthesize(cfg, "你好")
        if (bytes.size < 10) TestResult(false, "返回的音频为空") else TestResult(true, "连接成功")
    } catch (e: Exception) {
        TestResult(false, e.message ?: e.toString())
    }
}
