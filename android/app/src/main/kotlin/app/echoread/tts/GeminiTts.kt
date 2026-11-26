package app.echoread.tts

import app.echoread.core.i18n.S
import app.echoread.core.GeminiTtsConfig
import app.echoread.core.GeminiVoiceEntry
import app.echoread.core.net.CallKind
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import app.echoread.core.net.NetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Gemini 原生语音合成 —— 照 Google 官方文档实现，直连、不经任何转发：
 *
 *     POST https://generativelanguage.googleapis.com/v1beta/interactions
 *     x-goog-api-key: <Gemini API Key>
 *
 * 文档：https://ai.google.dev/gemini-api/docs/speech-generation （2026-11 复核）。
 * 官方现在把 Interactions API 列为所有新项目的推荐接口（generateContent 降为 legacy），
 * Gemini 3.8 TTS 的语音能力目前只在 v1beta 提供。
 *
 * 与 OpenAI 兼容接口的差别都写在官方文档里，每一条对应这里的一处实现：
 * ① **正文是逐字稿**。写进正文的舞台说明（「温柔地读：」）会被原样念出来；整段的语气、节奏、情绪
 *    只能放进 `speech_metadata` 注解的 `style` —— 所以语气与正文分两个字段传，绝不拼接；
 * ② 单人音色写在 `generation_config.speech_config` 的**数组**里：`[{"voice": "Kore"}]`
 *    （对象形态 `{"speakers": [...]}` 是双人对话用的）。预置音色名、扩展音色库 ID、
 *    自建音色 `voice_…` / `voicekey_…` 都填这同一个字段；
 * ③ 非流式请求默认返回**完整 WAV**（RIFF 头，24 kHz / 单声道 / 16-bit），解码即可播，
 *    不必再像 3.1 那样给无头 PCM 封头（官方迁移指南第 5 条）。手填旧模型时回来的若是
 *    `audio/l16` 裸流，仍按块里的采样率封 WAV；
 * ④ 音频在 `steps[]` 中 `type=model_output` 的步骤里、`content[]` 中 `type=audio` 的块，base64 编码；
 *    取最后一块 —— 与官方 REST 示例的 jq 写法、SDK 的 `output_audio` 便捷属性同义；
 * ⑤ `store: false`：Interactions API 默认在服务端保存每次交互（免费层 1 天、付费层 55 天），
 *    听书用不到服务端会话状态，书的正文没有理由留在服务器上。
 */
object GeminiTts {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val MODEL_FLASH = "gemini-3.8-flash-tts"
    const val MODEL_FLASH_LITE = "gemini-3.8-flash-lite-tts"

    /** 试听文本：比单个「你好」长一点，才听得出音色和语气 */
    val PREVIEW_TEXT: String get() = S.gemini.previewText

    /** 扩展音色库最多翻几页（每页上限 1000 条；目录约几百条，一页就够，这里只防服务端分页异常） */
    private const val MAX_VOICE_PAGES = 5

    /** 本地缓存里每条音色描述的长度上限：目录几百条，全文落盘会把 SharedPreferences 撑大 */
    private const val MAX_DESCRIPTION = 160

    /**
     * 严格模式（不开 isLenient）：Google 的响应永远是标准 JSON；宽松模式会把一张没有空白的网关页
     * 当成一个不带引号的字符串「解析成功」，于是本该报「无法解析」的响应被误报成「音频为空」。
     */
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    fun interactionsUrl(base: String = BASE_URL): String = "${base.trimEnd('/')}/interactions"
    fun voicesUrl(base: String = BASE_URL): String = "${base.trimEnd('/')}/voices"

    /**
     * 规整模型 ID：容忍从别处粘来的 `models/gemini-…`（Google 资源名）与 `google/gemini-…`（OpenRouter ID），
     * Interactions API 的 `model` 字段只认裸 ID。留空回落到旗舰档。
     */
    fun modelOf(cfg: GeminiTtsConfig): String =
        cfg.model.trim().removePrefix("models/").removePrefix("google/").substringBefore(':').ifEmpty { MODEL_FLASH }

    fun buildHeaders(cfg: GeminiTtsConfig): Map<String, String> = mapOf("x-goog-api-key" to cfg.apiKey.trim())

    /**
     * 组装 `interactions.create` 请求体（纯函数，便于测试）。字段与官方「Single-speaker TTS」REST 示例逐一对应：
     * ```
     * {"model": "gemini-3.8-flash-tts",
     *  "input": [{"type": "user_input",
     *             "content": [{"type": "text", "text": "…",
     *                          "annotations": [{"type": "speech_metadata", "style": "…"}]}]}],
     *  "response_format": {"type": "audio"},
     *  "generation_config": {"speech_config": [{"voice": "Kore"}]},
     *  "store": false}
     * ```
     * style 为空时整个 annotations 省略（官方：先用空 style 试，多数请求不需要任何 style）；
     * voice 为空时省略 generation_config，交给服务端默认音色。
     * response_format 不写 mime_type：非流式默认就是 WAV，手填 3.1 等旧模型时也不会因为格式不支持而 400。
     */
    fun buildInteractionBody(cfg: GeminiTtsConfig, text: String): JsonObject = buildJsonObject {
        put("model", modelOf(cfg))
        putJsonArray("input") {
            addJsonObject {
                put("type", "user_input")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                        val style = cfg.style.trim()
                        if (style.isNotEmpty()) {
                            putJsonArray("annotations") {
                                addJsonObject {
                                    put("type", "speech_metadata")
                                    put("style", style)
                                }
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("response_format") { put("type", "audio") }
        val voice = cfg.voice.trim()
        if (voice.isNotEmpty()) {
            putJsonObject("generation_config") {
                putJsonArray("speech_config") { addJsonObject { put("voice", voice) } }
            }
        }
        put("store", false)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 响应里的一个音频块（data 仍是 base64） */
    data class AudioBlock(val data: String, val mimeType: String?, val sampleRate: Int?, val channels: Int?)

    /**
     * 取出最后一个音频块：`steps[] | select(.type=="model_output") | .content[] | select(.type=="audio")`，取 last。
     * steps 里找不到时再看顶层 `outputs[]`（Interactions API 早期测试版的形态），不认识的步骤一律跳过。
     */
    fun pickAudio(root: JsonElement?): AudioBlock? {
        val o = root as? JsonObject ?: return null
        var last: JsonObject? = null
        (o["steps"] as? JsonArray)?.forEach { step ->
            val s = step as? JsonObject ?: return@forEach
            if (s.str("type") != "model_output") return@forEach
            (s["content"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.takeIf { it.isAudio() }?.let { last = it } }
        }
        if (last == null) {
            (o["outputs"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.takeIf { it.isAudio() }?.let { last = it } }
        }
        val a = last ?: return null
        return AudioBlock(a.str("data")!!, a.str("mime_type"), a.int("sample_rate"), a.int("channels"))
    }

    private fun JsonObject.isAudio(): Boolean = str("type") == "audio" && !str("data").isNullOrEmpty()

    /** 没拿到音频时，把服务端说了什么拼成一句话，进错误详情 */
    fun describeNoAudio(root: JsonElement?): Pair<String, String?> {
        val o = root as? JsonObject ?: return S.gemini.notJsonObject to null
        val status = o.str("status")
        val err = o["error"] as? JsonObject
        val kinds = (o["steps"] as? JsonArray)?.flatMap { step ->
            ((step as? JsonObject)?.get("content") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("type") } ?: emptyList()
        }?.distinct().orEmpty()
        val msg = buildString {
            append(S.gemini.noAudioReturned)
            if (status != null) append(S.gemini.statusSuffix(status))
            if (kinds.isNotEmpty()) append(S.gemini.contentBlocks).append(kinds.joinToString())
            err?.str("message")?.let { append(S.gemini.providerMessageSuffix(it)) }
        }
        return msg to err?.str("code")
    }

    private fun decodeBase64(s: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(s) }
            .recoverCatching { Base64.getUrlDecoder().decode(s) }
            .recoverCatching { Base64.getMimeDecoder().decode(s) }
            .getOrNull()

    private fun ascii(b: ByteArray, off: Int, s: String): Boolean =
        b.size >= off + s.length && s.indices.all { b[off + it].toInt().toChar() == s[it] }

    private fun isWav(b: ByteArray) = ascii(b, 0, "RIFF") && ascii(b, 8, "WAVE")

    /** 带容器头、播放器能直接嗅探的格式（MP3 / Ogg / FLAC / MP4） */
    private fun hasContainer(b: ByteArray): Boolean = b.size >= 12 && (
        ascii(b, 0, "ID3") || (b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) ||
            ascii(b, 0, "OggS") || ascii(b, 0, "fLaC") || ascii(b, 4, "ftyp")
        )

    /**
     * 解码并规整成可直接播放的字节：WAV 原样返回；`audio/l16` 裸流（16-bit 小端，官方写明）封 WAV 头；
     * 其余带容器的格式原样返回。μ-law / A-law 是电话格式，我们从不请求，回来了就当作无法播放（返回 null）。
     * 没标 mime_type 又没有容器头的，只可能是 Gemini 的默认裸流 L16。
     */
    fun toPlayable(block: AudioBlock): ByteArray? {
        val bytes = decodeBase64(block.data)?.takeIf { it.size >= 10 } ?: return null
        val mime = block.mimeType?.trim()?.lowercase().orEmpty()
        fun wrap(): ByteArray {
            val p = SpeechApi.parsePcmParams(mime)
            return SpeechApi.pcmToWav(bytes, block.sampleRate?.takeIf { it > 0 } ?: p.rate, block.channels?.takeIf { it > 0 } ?: p.channels)
        }
        return when {
            isWav(bytes) -> bytes
            mime.startsWith("audio/l16") || mime.startsWith("audio/pcm") -> wrap()
            mime.startsWith("audio/mulaw") || mime.startsWith("audio/alaw") -> null
            hasContainer(bytes) -> bytes
            mime.isEmpty() -> wrap()
            else -> null
        }
    }

    /** 合成一段文本，返回可直接播放的 WAV 字节。失败一律抛 [NetException]，状态码只可能是服务端真实回的 */
    suspend fun synthesize(cfg: GeminiTtsConfig, text: String, base: String = BASE_URL): ByteArray = withContext(Dispatchers.IO) {
        val url = interactionsUrl(base)
        val model = modelOf(cfg)
        if (cfg.apiKey.isBlank()) throw NetException(NetErrors.config(missingKey = true, endpoint = url, kind = CallKind.SYNTHESIS))
        val t0 = System.currentTimeMillis()
        val req = Request.Builder()
            .url(url)
            .post(buildInteractionBody(cfg, text).toString().toRequestBody(JSON_MEDIA))
            .apply { buildHeaders(cfg).forEach { (k, v) -> header(k, v) } }
            .build()
        SpeechApi.call(req, CallKind.SYNTHESIS, model).use { res ->
            SpeechApi.ensureOk(res, CallKind.SYNTHESIS, model, t0)
            val raw = res.body?.string() ?: ""
            // Interaction 资源永远是 JSON 对象；不是对象（网关页、半截响应）就是无法解析
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                ?: throw NetException(
                    NetErrors.fromHttp(
                        status = res.code, bodyRaw = raw.take(512), endpoint = url, method = "POST",
                        kind = CallKind.SYNTHESIS, model = model, elapsedMs = System.currentTimeMillis() - t0
                    ).copy(category = NetCategory.PARSE)
                )
            // HTTP 200 却没有音频（被安全策略拦下、模型只回了文字…）：按真实状态码报「音频为空」，
            // 引擎会重试一次再跳过这一段，不会因为一段被拦就停掉整本书
            val block = pickAudio(root) ?: run {
                val (msg, code) = describeNoAudio(root)
                throw NetException(
                    NetError(
                        category = NetCategory.EMPTY_AUDIO, status = res.code,
                        endpoint = NetErrors.cleanEndpoint(url), method = "POST", kind = CallKind.SYNTHESIS, model = model,
                        providerMessage = msg, providerCode = code, elapsedMs = System.currentTimeMillis() - t0
                    )
                )
            }
            toPlayable(block) ?: throw NetException(
                NetError(
                    category = NetCategory.PARSE, status = res.code,
                    endpoint = NetErrors.cleanEndpoint(url), method = "POST", kind = CallKind.SYNTHESIS, model = model,
                    providerMessage = S.gemini.unplayableMime(block.mimeType ?: S.gemini.mimeNone),
                    elapsedMs = System.currentTimeMillis() - t0
                )
            )
        }
    }

    /** 枚举值规整：兼容 `prebuilt` 与 proto 风格的 `VOICE_TYPE_PREBUILT` 两种写法 */
    private fun enumOf(raw: String?, prefix: String): String? =
        raw?.trim()?.lowercase()?.removePrefix(prefix)?.takeIf { it.isNotEmpty() && it != "unspecified" }

    /**
     * 解析 `GET /v1beta/voices` 的一页（纯函数，便于测试）：返回音色与 next_page_token。
     * 没有 id 的条目跳过 —— `store=false` 复刻出的只有一次性 key，不属于可浏览的目录。
     */
    fun parseVoices(root: JsonElement?): Pair<List<GeminiVoiceEntry>, String?> {
        val o = root as? JsonObject ?: return emptyList<GeminiVoiceEntry>() to null
        val voices = (o["voices"] as? JsonArray)?.mapNotNull { v ->
            val vo = v as? JsonObject ?: return@mapNotNull null
            val id = vo.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GeminiVoiceEntry(
                id = id,
                displayName = vo.str("display_name")?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                type = enumOf(vo.str("type"), "voice_type_"),
                languageCode = vo.str("language_code")?.trim()?.takeIf { it.isNotEmpty() },
                accent = vo.str("accent")?.trim()?.takeIf { it.isNotEmpty() },
                gender = vo.str("gender")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                pitch = enumOf(vo.str("pitch"), "pitch_"),
                persona = vo.str("persona")?.trim()?.takeIf { it.isNotEmpty() },
                context = vo.str("context")?.trim()?.takeIf { it.isNotEmpty() },
                description = vo.str("description")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (it.length > MAX_DESCRIPTION) it.take(MAX_DESCRIPTION) + "…" else it
                }
            )
        } ?: emptyList()
        return voices to o.str("next_page_token")?.takeIf { it.isNotEmpty() }
    }

    /**
     * 拉取音色目录（自建音色在前，随后是预置目录 —— 含 30 个精选音色与扩展音色库）。
     * 也是 Gemini 引擎的「连接检查」：Key 无效时这里就会拿到 400 API_KEY_INVALID。
     */
    suspend fun listVoices(cfg: GeminiTtsConfig, base: String = BASE_URL): List<GeminiVoiceEntry> = withContext(Dispatchers.IO) {
        val endpoint = voicesUrl(base)
        if (cfg.apiKey.isBlank()) throw NetException(NetErrors.config(missingKey = true, endpoint = endpoint, kind = CallKind.MODEL_LIST))
        val out = LinkedHashMap<String, GeminiVoiceEntry>()
        var token: String? = null
        var pages = 0
        do {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("page_size", "1000")
                .apply { token?.let { addQueryParameter("page_token", it) } }
                .build()
            val req = Request.Builder().url(url).get().apply { buildHeaders(cfg).forEach { (k, v) -> header(k, v) } }.build()
            val t0 = System.currentTimeMillis()
            SpeechApi.call(req, CallKind.MODEL_LIST).use { res ->
                SpeechApi.ensureOk(res, CallKind.MODEL_LIST, startedAt = t0)
                val raw = res.body?.string() ?: ""
                val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                    ?: throw NetException(
                        NetErrors.fromHttp(
                            status = res.code, bodyRaw = raw.take(4096), endpoint = endpoint, method = "GET",
                            kind = CallKind.MODEL_LIST, elapsedMs = System.currentTimeMillis() - t0
                        ).copy(category = NetCategory.PARSE)
                    )
                val (voices, next) = parseVoices(root)
                voices.forEach { out.putIfAbsent(it.id, it) }
                token = next
            }
            pages++
        } while (!token.isNullOrEmpty() && pages < MAX_VOICE_PAGES)
        out.values.toList()
    }
}
