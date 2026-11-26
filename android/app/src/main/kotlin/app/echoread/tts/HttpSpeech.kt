package app.echoread.tts

import app.echoread.core.HttpRecipe
import app.echoread.core.ListRecipe
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.ResponseSpec
import app.echoread.core.Template
import app.echoread.core.TextLang
import app.echoread.core.VoiceProfile
import app.echoread.core.VoiceSpec
import app.echoread.core.i18n.I18n
import app.echoread.core.i18n.S
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 「按模板发请求」的通用执行器：服务目录里 kind=http 的服务商全部走这里，
 * 用户自己导入的服务定义也走这里 —— 接入一家新服务不需要新的 Kotlin 代码。
 *
 * 复用 [SpeechApi] 的超时预算、错误归一与音频校验：模板决定「发什么」，
 * 「失败了怎么报、回来的是不是音频」对所有服务商是同一套口径。
 */
object HttpSpeech {
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    /** 模板里可用的全部变量。[text] 为 null 时不含正文相关的三项（拉列表时用） */
    fun vars(spec: ProviderSpec, p: VoiceProfile, text: String?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m.putAll(Providers.fieldVars(spec, p))
        m["base"] = Providers.baseOf(spec, p)
        m["key"] = p.apiKey.trim()
        m["model"] = p.model.trim().ifEmpty { spec.defaultModel }
        m["voice"] = p.voice.trim()
        m["style"] = if (Providers.supportsStyle(spec, p.model)) p.style.trim() else ""
        m["uiLang"] = if (I18n.english) "en" else "zh"
        if (text != null) {
            m["text"] = text
            m["lang"] = TextLang.of(text)
            m["locale"] = TextLang.locale(text)
        }
        return m
    }

    /** 按模板组装请求（纯函数，便于测试）。取值为空的鉴权头不发：没有 Key 的自建服务不该收到一个空的 Bearer */
    fun buildRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        jsonBody: JsonElement?,
        rawBody: String?,
        contentType: String,
        vars: Map<String, String>
    ): Request {
        val target = Template.expand(url, vars, Template.Context.URL)
        val b = Request.Builder().url(target)
        for ((k, v) in headers) {
            val value = Template.expand(v, vars, Template.Context.HEADER).trim()
            val needsKey = "key" in Template.names(v)
            if (value.isEmpty() || (needsKey && vars["key"].isNullOrEmpty())) continue
            b.header(k, value)
        }
        val body = when {
            jsonBody != null -> Template.expandJson(jsonBody, vars).toString().toRequestBody(JSON_MEDIA)
            rawBody != null -> Template.expand(rawBody, vars, Template.Context.RAW)
                .toRequestBody(contentType.ifBlank { "text/plain" }.toMediaType())
            else -> null
        }
        return when (method.uppercase()) {
            "GET" -> b.get().build()
            else -> b.method(method.uppercase(), body ?: ByteArray(0).toRequestBody(null)).build()
        }
    }

    fun buildRequest(recipe: HttpRecipe, vars: Map<String, String>): Request =
        buildRequest(recipe.method, recipe.url, recipe.headers, recipe.json, recipe.body, recipe.contentType, vars)

    private fun config(missingKey: Boolean, endpoint: String, kind: CallKind) =
        NetException(NetErrors.config(missingKey = missingKey, endpoint = endpoint, kind = kind))

    /** 合成一段文本。失败一律抛 [NetException]，状态码只可能是服务端真实回的 */
    suspend fun synthesize(spec: ProviderSpec, p: VoiceProfile, text: String): ByteArray = withContext(Dispatchers.IO) {
        val recipe = spec.synth ?: throw IllegalStateException("${spec.id}: no synth recipe")
        val v = vars(spec, p, text)
        val base = v["base"].orEmpty()
        if (base.isEmpty() && "base" in Template.names(recipe.url)) throw config(false, "", CallKind.SYNTHESIS)
        val endpoint = Template.expand(recipe.url.substringBefore('?'), v, Template.Context.URL)
        if (v["key"].isNullOrEmpty() && !spec.keyOptional) throw config(true, endpoint, CallKind.SYNTHESIS)
        val model = v["model"]?.takeIf { it.isNotEmpty() }
        val t0 = System.currentTimeMillis()
        val req = buildRequest(recipe, v)
        SpeechApi.call(req, CallKind.SYNTHESIS, model).use { res ->
            SpeechApi.ensureOk(res, CallKind.SYNTHESIS, model, t0)
            val ctype = res.header("Content-Type") ?: ""
            val r = recipe.response
            val pcmRate = r.pcmRate.takeIf { it > 0 } ?: spec.pcmRate.takeIf { it > 0 } ?: 24000
            when (r.type) {
                ResponseSpec.TYPE_JSON -> {
                    val raw = res.body?.string() ?: ""
                    // 必须是对象或数组：一张没有空白的网关页会被解析器当成一个不带引号的字符串「解析成功」
                    val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
                        ?: throw unparseable(res.code, raw, endpoint, model, t0)
                    businessError(r, root, res.code, endpoint, model, t0)?.let { throw NetException(it) }
                    val value = Template.text(root, r.audio)
                        ?: throw noAudio(res.code, endpoint, model, t0, raw)
                    val bytes = decode(value, r.encoding, endpoint, model, t0)
                    SpeechApi.playable(bytes.first, bytes.second, res.code, endpoint, model, t0, pcmRate, r.pcmChannels, rawPcm = r.pcmRate > 0)
                }
                ResponseSpec.TYPE_LINES -> {
                    val raw = res.body?.string() ?: ""
                    val out = ByteArrayOutputStream()
                    var seen = 0
                    for (chunk in Template.jsonObjects(raw)) {
                        val root = runCatching { json.parseToJsonElement(chunk) }.getOrNull() ?: continue
                        seen++
                        businessError(r, root, res.code, endpoint, model, t0)?.let { throw NetException(it) }
                        val value = Template.text(root, r.audio) ?: continue
                        // 流式分片不支持「先给一个链接」：每片必须自带音频
                        if (r.encoding == "url") continue
                        out.write(decodeInline(value, r.encoding) ?: continue)
                    }
                    if (seen == 0) throw unparseable(res.code, raw, endpoint, model, t0)
                    if (out.size() == 0) throw noAudio(res.code, endpoint, model, t0, raw)
                    SpeechApi.playable(out.toByteArray(), "", res.code, endpoint, model, t0, pcmRate, r.pcmChannels, rawPcm = r.pcmRate > 0)
                }
                else -> {
                    val bytes = res.body?.bytes() ?: ByteArray(0)
                    SpeechApi.playable(bytes, ctype, res.code, endpoint, model, t0, pcmRate, r.pcmChannels, rawPcm = r.pcmRate > 0)
                }
            }
        }
    }

    /** base64 / hex 就地解码；url 则再发一次 GET 把音频取回来。返回（字节, Content-Type） */
    private suspend fun decode(value: String, encoding: String, endpoint: String, model: String?, t0: Long): Pair<ByteArray, String> {
        if (encoding != "url") {
            val bytes = decodeInline(value, encoding)
                ?: throw NetException(
                    NetError(
                        category = NetCategory.PARSE, status = 200, endpoint = NetErrors.cleanEndpoint(endpoint), method = "POST",
                        kind = CallKind.SYNTHESIS, model = model, providerMessage = S.providers.undecodableAudio(encoding),
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                )
            return bytes to ""
        }
        // 音频链接多半是对象存储的签名地址：不带我们的鉴权头，也不该带 —— Key 只发给目录里声明的那个主机
        val url = value.toHttpUrlOrNull()
            ?: throw NetException(
                NetError(
                    category = NetCategory.PARSE, status = 200, endpoint = NetErrors.cleanEndpoint(endpoint), method = "POST",
                    kind = CallKind.SYNTHESIS, model = model, providerMessage = S.providers.undecodableAudio("url"),
                    elapsedMs = System.currentTimeMillis() - t0
                )
            )
        return SpeechApi.call(Request.Builder().url(url).get().build(), CallKind.SYNTHESIS, model).use { res ->
            SpeechApi.ensureOk(res, CallKind.SYNTHESIS, model, t0)
            (res.body?.bytes() ?: ByteArray(0)) to (res.header("Content-Type") ?: "")
        }
    }

    internal fun decodeInline(value: String, encoding: String): ByteArray? = when (encoding) {
        "hex" -> hexToBytes(value)
        else -> runCatching { Base64.getDecoder().decode(value) }
            .recoverCatching { Base64.getUrlDecoder().decode(value) }
            .recoverCatching { Base64.getMimeDecoder().decode(value) }
            .getOrNull()
    }

    internal fun hexToBytes(hex: String): ByteArray? {
        val s = hex.trim()
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * HTTP 200 里的业务错误（MiniMax 把「Key 无效」放在 base_resp.status_code=1004 里，状态码照样是 200）。
     * 种类决定引擎怎么处置：Key / 余额问题停播，限流重试，其余跳过这一段。
     */
    internal fun businessError(r: ResponseSpec, root: JsonElement, status: Int, endpoint: String, model: String?, t0: Long): NetError? {
        if (r.errorCode.isBlank()) return null
        val code = Template.text(root, r.errorCode) ?: return null
        if (code in r.okCodes) return null
        val message = Template.text(root, r.errorMessage.ifBlank { "message" })
        val kind = r.errorKinds[code] ?: guessKind(message.orEmpty())
        return NetError(
            category = when (kind) {
                "auth" -> NetCategory.AUTH
                "quota" -> NetCategory.QUOTA
                "rate" -> NetCategory.RATE_LIMIT
                else -> NetCategory.BAD_REQUEST
            },
            status = status,
            providerMessage = message,
            providerCode = code,
            endpoint = NetErrors.cleanEndpoint(endpoint),
            method = "POST",
            kind = CallKind.SYNTHESIS,
            model = model,
            bodySnippet = NetErrors.redact(root.toString()),
            elapsedMs = System.currentTimeMillis() - t0
        )
    }

    private fun guessKind(message: String): String {
        val m = message.lowercase()
        return when {
            listOf("api key", "apikey", "unauthor", "invalid key", "token", "credential", "鉴权", "认证", "密钥").any { it in m } -> "auth"
            listOf("balance", "insufficient", "quota", "credit", "余额", "欠费", "额度").any { it in m } -> "quota"
            listOf("rate", "too many", "频率", "限流", "并发").any { it in m } -> "rate"
            else -> "bad"
        }
    }

    private fun unparseable(status: Int, raw: String, endpoint: String, model: String?, t0: Long) = NetException(
        NetErrors.fromHttp(
            status = status, bodyRaw = raw.take(512), endpoint = endpoint, method = "POST",
            kind = CallKind.SYNTHESIS, model = model, elapsedMs = System.currentTimeMillis() - t0
        ).copy(category = NetCategory.PARSE)
    )

    private fun noAudio(status: Int, endpoint: String, model: String?, t0: Long, raw: String) = NetException(
        NetError(
            category = NetCategory.EMPTY_AUDIO, status = status, endpoint = NetErrors.cleanEndpoint(endpoint), method = "POST",
            kind = CallKind.SYNTHESIS, model = model, providerMessage = S.providers.noAudioField,
            bodySnippet = NetErrors.redact(raw), elapsedMs = System.currentTimeMillis() - t0
        )
    )

    /* ---------------- 列表：音色 / 模型 ---------------- */

    private fun normGender(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "f", "female", "feminine", "woman" -> "f"
        "m", "male", "masculine", "man" -> "m"
        else -> ""
    }

    /** 把一个 JSON 条目映射成统一的条目（纯函数，便于测试）。条目是字符串时它就是 ID */
    fun mapItem(item: JsonElement, r: ListRecipe, mine: Boolean): VoiceSpec? {
        if (item is JsonPrimitive) {
            val id = item.content.trim()
            return if (item.isString && id.isNotEmpty()) VoiceSpec(id = id, mine = mine) else null
        }
        if (item !is JsonObject) return null
        if (r.where.isNotBlank() && !Template.truthy(item, r.where)) return null
        val id = Template.text(item, r.id) ?: return null
        val tags = r.tags.split(',').mapNotNull { path -> path.trim().takeIf { it.isNotEmpty() }?.let { Template.text(item, it) } }
            .map { if (it.length > 28) it.take(28) + "…" else it }.distinct()
        return VoiceSpec(
            id = id,
            name = r.name.takeIf { it.isNotBlank() }?.let { Template.text(item, it) }.orEmpty(),
            lang = r.lang.takeIf { it.isNotBlank() }?.let { Template.text(item, it) }.orEmpty(),
            gender = normGender(r.gender.takeIf { it.isNotBlank() }?.let { Template.text(item, it) }),
            tags = tags,
            mine = mine || (r.mine.isNotBlank() && Template.text(item, r.mine) in r.mineValues)
        )
    }

    /** 解析一页列表（纯函数）：返回条目与下一页令牌 */
    fun parseList(root: JsonElement?, r: ListRecipe): Pair<List<VoiceSpec>, String?> {
        val out = ArrayList<VoiceSpec>()
        fun collect(paths: String, mine: Boolean) {
            for (path in paths.split('|')) {
                val arr = Template.at(root, path.trim()) as? JsonArray ?: continue
                arr.forEach { item -> mapItem(item, r, mine)?.let { out.add(it) } }
            }
        }
        // items 与 mineItems 都没写：响应体本身就是数组
        if (r.items.isBlank() && r.mineItems.isBlank()) collect("", false)
        if (r.items.isNotBlank()) collect(r.items, false)
        if (r.mineItems.isNotBlank()) collect(r.mineItems, true)
        val next = r.next.takeIf { it.isNotBlank() }?.let { Template.text(root, it) }
        return out to next
    }

    /** 拉一张列表（跟随翻页，最多 [ListRecipe.maxPages] 页）。也是这家服务的「连接检查」 */
    suspend fun list(r: ListRecipe, spec: ProviderSpec, p: VoiceProfile): List<VoiceSpec> = withContext(Dispatchers.IO) {
        val v = vars(spec, p, null)
        // 展示用的端点也过一遍 URL 规整（{base}/../x 这类相对写法显示成最终地址）
        val endpoint = Template.expand(r.url.substringBefore('?'), v, Template.Context.URL).let { it.toHttpUrlOrNull()?.toString() ?: it }
        if (v["base"].isNullOrEmpty() && "base" in Template.names(r.url)) throw config(false, "", CallKind.MODEL_LIST)
        val needsKey = r.headers.values.any { "key" in Template.names(it) }
        if (needsKey && v["key"].isNullOrEmpty() && !spec.keyOptional) throw config(true, endpoint, CallKind.MODEL_LIST)
        val out = LinkedHashMap<String, VoiceSpec>()
        var token: String? = null
        var pages = 0
        do {
            val t0 = System.currentTimeMillis()
            // 模板只在 buildRequest 里展开一次，翻页令牌加在展开好的地址上。再展开一遍的话，
            // 不编码的取值（{base}、{x|raw}）里碰巧像占位符的文字会被当成变量再替换一次
            val page = buildRequest(r.method, r.url, r.headers, r.json, null, "", v)
            val req = if (token != null && r.nextParam.isNotBlank()) {
                page.newBuilder().url(page.url.newBuilder().setQueryParameter(r.nextParam, token).build()).build()
            } else {
                page
            }
            SpeechApi.call(req, CallKind.MODEL_LIST).use { res ->
                SpeechApi.ensureOk(res, CallKind.MODEL_LIST, startedAt = t0)
                val raw = res.body?.string() ?: ""
                val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
                    ?: throw NetException(
                        NetErrors.fromHttp(
                            status = res.code, bodyRaw = raw.take(512), endpoint = endpoint, method = r.method,
                            kind = CallKind.MODEL_LIST, elapsedMs = System.currentTimeMillis() - t0
                        ).copy(category = NetCategory.PARSE)
                    )
                // 列表接口同样可能把「Key 无效」装在 200 里：不查的话，一把错的 Key 会换来一个绿色的「已连接」
                businessError(
                    ResponseSpec(errorCode = r.errorCode, okCodes = r.okCodes, errorMessage = r.errorMessage, errorKinds = r.errorKinds),
                    root, res.code, endpoint, null, t0
                )?.let { throw NetException(it.copy(method = r.method, kind = CallKind.MODEL_LIST)) }
                val (items, next) = parseList(root, r)
                items.forEach { out.putIfAbsent(it.id, it) }
                token = next
            }
            pages++
        } while (!token.isNullOrEmpty() && r.nextParam.isNotBlank() && pages < r.maxPages.coerceIn(1, 10))
        out.values.toList()
    }
}
