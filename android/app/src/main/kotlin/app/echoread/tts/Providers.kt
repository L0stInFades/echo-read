package app.echoread.tts

import app.echoread.core.Discovery
import app.echoread.core.GeminiTtsConfig
import app.echoread.core.Hash
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.ProviderCatalog
import app.echoread.core.ProviderSpec
import app.echoread.core.ResponseSpec
import app.echoread.core.Template
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.VoiceSpec
import app.echoread.core.i18n.I18n
import app.echoread.core.net.CallKind
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * 朗读服务的登记处：内置目录（`resources/tts-providers.json`）+ 用户导入的定义，
 * 以及「一个已添加的服务 → 一次可执行的合成」这一步解析。
 *
 * 接入一家新服务商的全部工作量，按可能性从大到小：
 * 1. 它讲 OpenAI 的 /audio/speech：目录里加一段，`kind` 写 `openai`，填地址、模型、音色；
 * 2. 它是别的 REST 形状：`kind` 写 `http`，再写一段 `synth` 模板（地址、头、请求体、音频在响应的哪）；
 * 3. 模板表达不了（要在响应里做判断、要签名、要 WebSocket）：这时才写适配器，并在 [target] 里接上。
 */
object Providers {
    /** 通用的 OpenAI 兼容端点（地址由用户填，可以加很多个） */
    const val CUSTOM = "custom"
    const val OPENROUTER = "openrouter"
    const val GEMINI = "gemini"

    /** 目录格式版本：只认自己看得懂的 */
    const val SCHEMA = 1

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 内置目录。放在 Java resources 而不是 assets：纯 JVM 的单元测试与真机走同一条加载路径 */
    val catalog: ProviderCatalog by lazy {
        val text = Providers::class.java.classLoader?.getResourceAsStream("tts-providers.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text == null) ProviderCatalog() else parseCatalog(text)
    }

    fun parseCatalog(text: String): ProviderCatalog {
        val c = json.decodeFromString(ProviderCatalog.serializer(), text)
        require(c.schema == SCHEMA) { "unsupported catalog schema ${c.schema}" }
        return c
    }

    /** 兜底定义：目录里找不到（用户删掉了导入的定义、目录被改名）时按普通的 OpenAI 兼容端点处理，不崩 */
    private val FALLBACK = ProviderSpec(
        id = CUSTOM, name = "OpenAI-compatible", kind = ProviderSpec.KIND_OPENAI,
        keyOptional = true, style = true, voiceMode = ProviderSpec.VOICE_FREE, voiceOptional = true
    )

    val builtIn: List<ProviderSpec> get() = catalog.providers

    fun spec(id: String, s: TtsSettings? = null): ProviderSpec? =
        s?.customSpecs?.firstOrNull { it.id == id } ?: builtIn.firstOrNull { it.id == id }

    fun specOf(p: VoiceProfile, s: TtsSettings? = null): ProviderSpec = spec(p.spec, s) ?: FALLBACK

    /* ---------------- 用户导入的定义：校验 ---------------- */

    private val KNOWN_VARS = setOf("base", "key", "model", "voice", "style", "text", "lang", "locale", "uiLang")
    private val ID_RE = Regex("^[a-z0-9][a-z0-9_-]{1,39}$")

    /** 解析并校验一段用户粘贴的服务定义。返回定义，或一组问题描述（技术性的英文短句，原样显示） */
    fun parseSpec(text: String): Result<ProviderSpec> = runCatching {
        val trimmed = text.trim()
        val spec = try {
            json.decodeFromString(ProviderSpec.serializer(), trimmed)
        } catch (e: Exception) {
            // 也接受整份目录：取第一条
            runCatching { parseCatalog(trimmed).providers.first() }.getOrElse { throw IllegalArgumentException(e.message?.lineSequence()?.firstOrNull() ?: "not valid JSON") }
        }
        val problems = validate(spec)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        spec
    }

    /** 目录条目的静态检查：单元测试对内置目录逐条跑，导入时对用户的定义跑 */
    fun validate(spec: ProviderSpec): List<String> {
        val out = ArrayList<String>()
        if (!ID_RE.matches(spec.id) || spec.id == TtsSettings.SYSTEM) out += "id must match [a-z0-9_-]{2,40}"
        if (spec.name.isBlank()) out += "name is empty"
        if (spec.kind !in setOf(ProviderSpec.KIND_OPENAI, ProviderSpec.KIND_GEMINI, ProviderSpec.KIND_HTTP)) out += "unknown kind ${spec.kind}"
        if (spec.voiceMode !in setOf(ProviderSpec.VOICE_LIST, ProviderSpec.VOICE_FREE, ProviderSpec.VOICE_NONE)) out += "unknown voiceMode ${spec.voiceMode}"
        val fieldVars = spec.fields.map { "f.${it.id}" }.toSet()
        fun checkVars(where: String, names: Set<String>) {
            val unknown = names - KNOWN_VARS - fieldVars
            if (unknown.isNotEmpty()) out += "$where: unknown variable ${unknown.joinToString()}"
        }
        fun checkUrl(where: String, url: String) {
            if (!(url.startsWith("{base}") || url.startsWith("https://") || url.startsWith("http://"))) out += "$where: url must start with {base} or http(s)://"
            checkVars(where, Template.names(url))
        }
        if (spec.baseUrl.isNotBlank()) {
            if (!(spec.baseUrl.startsWith("https://") || spec.baseUrl.startsWith("http://"))) out += "baseUrl must be http(s)"
            checkVars("baseUrl", Template.names(spec.baseUrl))
        }
        if (spec.kind == ProviderSpec.KIND_HTTP) {
            val r = spec.synth
            if (r == null) out += "kind=http needs a synth recipe"
            else {
                checkUrl("synth", r.url)
                r.headers.values.forEach { checkVars("synth.headers", Template.names(it)) }
                checkVars("synth.json", Template.names(r.json))
                r.body?.let { checkVars("synth.body", Template.names(it)) }
                if (r.json != null && r.body != null) out += "synth: json and body are mutually exclusive"
                val names = Template.names(r.url) + Template.names(r.json) + (r.body?.let { Template.names(it) } ?: emptySet())
                if ("text" !in names) out += "synth: {text} is never sent"
                val resp = r.response
                if (resp.type !in setOf(ResponseSpec.TYPE_BYTES, ResponseSpec.TYPE_JSON, ResponseSpec.TYPE_LINES)) out += "synth.response: unknown type ${resp.type}"
                if (resp.encoding !in setOf("base64", "hex", "url")) out += "synth.response: unknown encoding ${resp.encoding}"
                if (resp.type != ResponseSpec.TYPE_BYTES && resp.audio.isBlank()) out += "synth.response: audio path is empty"
            }
        }
        (spec.listVoices + listOfNotNull(spec.listModels)).forEachIndexed { i, r ->
            checkUrl("list[$i]", r.url)
            r.headers.values.forEach { checkVars("list[$i].headers", Template.names(it)) }
            checkVars("list[$i].json", Template.names(r.json))
        }
        if (spec.models.isNotEmpty() && spec.defaultModel.isNotBlank() && spec.models.none { it.id == spec.defaultModel }) out += "defaultModel is not in models"
        return out
    }

    /* ---------------- 解析一个已添加的服务 ---------------- */

    /**
     * 把输入里的全角字符换回半角。中文输入法下很容易把地址敲成「HTTP：／／192.168.1.5：8880」
     * （真机上用 Gboard 拼音实测如此），而 Key、地址、区域这些东西从来只有 ASCII。
     */
    fun halfWidth(s: String): String {
        if (s.none { it.code >= 0x3000 }) return s
        val out = StringBuilder(s.length)
        for (ch in s) out.append(
            when (ch.code) {
                in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                0x3000 -> ' '
                0x3002 -> '.'
                else -> ch
            }
        )
        return out.toString()
    }

    /** 规整一个服务地址：全角转半角、去掉空白、协议名小写（HTTP:// → http://） */
    fun cleanAddress(raw: String): String {
        val s = halfWidth(raw).filterNot { it.isWhitespace() }
        val m = Regex("^(https?)(://)", RegexOption.IGNORE_CASE).find(s) ?: return s
        return m.groupValues[1].lowercase() + "://" + s.substring(m.range.last + 1)
    }

    /** 规整 Key / 额外字段：全角转半角、去掉首尾空白 */
    fun cleanSecret(raw: String): String = halfWidth(raw).trim()

    /** 是一个能发请求的 http(s) 地址 */
    fun validBase(base: String): Boolean = base.toHttpUrlOrNull() != null

    fun fieldVars(spec: ProviderSpec, p: VoiceProfile): Map<String, String> =
        spec.fields.associate { f -> "f.${f.id}" to (p.fields[f.id]?.trim()?.takeIf { it.isNotEmpty() } ?: f.default) }

    /** 实际使用的地址：用户填的覆盖目录里的；`{f.region}` 这类字段就地展开；去掉末尾的斜杠 */
    fun baseOf(spec: ProviderSpec, p: VoiceProfile): String {
        // 存档里若还留着没规整过的地址（旧版本存的、别处同步来的），这里再规整一次
        val raw = cleanAddress(p.baseUrl).ifEmpty { spec.baseUrl }
        return Template.expand(raw, fieldVars(spec, p), Template.Context.RAW).trim().trimEnd('/')
    }

    fun modelOf(spec: ProviderSpec, p: VoiceProfile): String = p.model.trim().ifEmpty { spec.defaultModel }

    /** 这个模型吃不吃「语气」。OpenRouter 上按模型分（Gemini TTS 与 Fish Audio 才用它），其余看目录 */
    fun supportsStyle(spec: ProviderSpec, model: String): Boolean {
        if (spec.id == OPENROUTER) return Voices.modelHints(model)?.instructions == true
        val m = spec.models.firstOrNull { it.id == model.trim() }
        return m?.style ?: spec.style
    }

    /** 目录里这个模型的静态音色表（模型专属的优先，否则用服务商通用的） */
    fun staticVoices(spec: ProviderSpec, model: String): List<VoiceSpec> =
        spec.models.firstOrNull { it.id == model.trim() }?.voices ?: spec.voices

    /** 单次请求的字符上限（0 = 不限） */
    fun maxChars(spec: ProviderSpec, model: String): Int {
        val m = spec.models.firstOrNull { it.id == model.trim() }?.maxChars ?: 0
        return listOf(m, spec.maxChars).filter { it > 0 }.minOrNull() ?: 0
    }

    /**
     * 引擎实际用的片段长度：用户设的「单片段字数」，再压到当前服务的上限以内。
     * 阅读器排版与引擎分段都必须取这同一个值（章节派生结果按它缓存）。
     */
    fun chunkChars(s: TtsSettings): Int {
        val p = s.activeProfile ?: return s.maxChunkChars
        if (s.usesSystemVoice) return s.maxChunkChars
        val spec = specOf(p, s)
        val cap = maxChars(spec, modelOf(spec, p))
        return if (cap > 0) minOf(s.maxChunkChars, cap).coerceAtLeast(40) else s.maxChunkChars
    }

    /** 缺 Key 就没法用（自建服务除外） */
    fun needsKey(spec: ProviderSpec, p: VoiceProfile): Boolean = !spec.keyOptional && p.apiKey.isBlank()

    /** 配置够不够发出第一次请求（不保证 Key 有效） */
    fun ready(spec: ProviderSpec, p: VoiceProfile): Boolean = !needsKey(spec, p) && validBase(baseOf(spec, p))

    fun openAiConfig(spec: ProviderSpec, p: VoiceProfile): OpenAISpeechConfig {
        val model = modelOf(spec, p)
        return OpenAISpeechConfig(
            baseUrl = baseOf(spec, p),
            apiKey = p.apiKey.trim(),
            model = model,
            voice = p.voice,
            // OpenRouter 沿用 0.4.x 的做法（非空就发，不认它的模型自己会忽略），缓存键因此与旧版逐字一致；
            // 其余服务商只在目录声明支持时才发：有的接口遇到不认识的字段直接 422
            instructions = if (spec.id == OPENROUTER || supportsStyle(spec, model)) p.style else "",
            format = p.format.ifBlank { spec.format.ifBlank { "mp3" } }
        )
    }

    fun openAiOptions(spec: ProviderSpec): SpeechApi.Options =
        SpeechApi.Options(keyOptional = spec.keyOptional, styleParam = spec.styleParam, pcmRate = spec.pcmRate)

    fun geminiConfig(spec: ProviderSpec, p: VoiceProfile): GeminiTtsConfig =
        GeminiTtsConfig(apiKey = p.apiKey.trim(), model = modelOf(spec, p), voice = p.voice, style = p.style)

    /**
     * 缓存命名空间。OpenRouter 与自定义端点沿用 0.3.x 的 "OPENAI"、Gemini 沿用 "GEMINI"：
     * 升级后已经花钱合成过的语音照常命中。其余服务商各用自己的 ID，同名模型在两家之间不串音。
     */
    fun cacheNs(spec: ProviderSpec): String = when {
        spec.id == OPENROUTER || spec.id == CUSTOM -> "OPENAI"
        spec.kind == ProviderSpec.KIND_GEMINI -> "GEMINI"
        else -> spec.id
    }

    fun target(p: VoiceProfile, s: TtsSettings? = null): SynthTarget {
        val spec = specOf(p, s)
        return when (spec.kind) {
            ProviderSpec.KIND_GEMINI -> SynthTarget.Gemini(geminiConfig(spec, p), baseOf(spec, p).ifEmpty { GeminiTts.BASE_URL })
            ProviderSpec.KIND_HTTP -> SynthTarget.Http(spec, p)
            else -> SynthTarget.OpenAI(openAiConfig(spec, p), openAiOptions(spec), cacheNs(spec))
        }
    }

    /** 当前设置对应的远程合成目标；系统语音为 null */
    fun target(s: TtsSettings): SynthTarget? {
        if (s.usesSystemVoice) return null
        val p = s.activeProfile
        if (p != null) return target(p, s)
        // 还没迁移过的旧存档（只在单元测试里直接构造 TtsSettings 时出现；真机读档必经 sanitize）
        return when (s.provider) {
            app.echoread.core.TtsProvider.GEMINI -> SynthTarget.Gemini(s.gemini)
            app.echoread.core.TtsProvider.SYSTEM -> null
            else -> SynthTarget.OpenAI(s.openai)
        }
    }

    /* ---------------- 给界面用的目录视图 ---------------- */

    /** 界面认识的标签（其余的不显示）。文案在语言内核的 Providers.badge 里 */
    val BADGES = setOf("free", "free_tier", "free_models", "cloning", "flagship", "fast", "cheap", "expressive", "long_form", "multilingual", "aggregator", "local")

    /** 这家服务已经过了停用日期 */
    fun retired(spec: ProviderSpec, today: String): Boolean = spec.sunset.isNotBlank() && today > spec.sunset

    /** 可选的模型：在线拉到的优先，否则用目录里写的 */
    fun modelOptions(spec: ProviderSpec, d: Discovery?): List<TtsModelInfo> =
        d?.models?.takeIf { it.isNotEmpty() } ?: spec.models.map { TtsModelInfo(id = it.id, name = it.name.ifBlank { it.id }) }

    /**
     * 当前模型可选的音色：我的音色 → 目录里的 → 在线拉到的（去重）。
     * 列表缓存与当前「端点 + Key」对不上时不摆出「我的音色」—— 那是上一个账户的私有音色。
     */
    fun voiceCatalog(spec: ProviderSpec, p: VoiceProfile, d: Discovery?): List<VoiceInfo> {
        val model = modelOf(spec, p)
        val sameAccount = d != null && (d.fp.isEmpty() || d.fp == fingerprint(spec, p))
        val modelScoped = spec.listVoices.any { "model" in Template.names(it.url) }
        val discovered = d?.voices.orEmpty()
            .filter { sameAccount || !it.mine }
            .takeIf { !modelScoped || d?.model == model }.orEmpty()
        val server = d?.models?.firstOrNull { it.id == model }?.voices
        return when {
            spec.kind == ProviderSpec.KIND_GEMINI -> Voices.geminiCatalog(discovered.map { Voices.geminiEntryOf(it) })
            spec.id == OPENROUTER -> Voices.catalogVoices(model, server)
            else -> {
                val out = LinkedHashMap<String, VoiceInfo>()
                discovered.filter { it.mine }.forEach { out[it.id] = Voices.fromSpec(it) }
                staticVoices(spec, model).forEach { out.putIfAbsent(it.id, Voices.fromSpec(it)) }
                // 自定义端点背后也可能是个聚合网关：/models 里带了 supported_voices 就用上
                if (!server.isNullOrEmpty()) Voices.catalogVoices(model, server).forEach { out.putIfAbsent(it.id, it) }
                discovered.forEach { out.putIfAbsent(it.id, Voices.fromSpec(it)) }
                out.values.toList()
            }
        }
    }

    /** 音色可以留空吗（留空 = 服务端默认） */
    fun voiceOptional(spec: ProviderSpec, model: String): Boolean =
        if (spec.id == OPENROUTER) Voices.modelHints(model)?.voiceOptional == true else spec.voiceOptional

    /* ---------------- 新建 / 默认值 ---------------- */

    private fun hostOf(url: String): String = runCatching { URI(url.trim()).host?.lowercase() ?: "" }.getOrDefault("")

    /** 按地址认出是目录里的哪一家（迁移旧存档、处理外部传入的配置时用） */
    fun specForBase(baseUrl: String): ProviderSpec? {
        val host = hostOf(baseUrl)
        if (host.isEmpty()) return null
        return builtIn.firstOrNull { it.kind == ProviderSpec.KIND_OPENAI && it.baseUrl.isNotBlank() && hostOf(it.baseUrl) == host }
    }

    fun defaultVoice(spec: ProviderSpec, model: String, discovered: List<VoiceSpec> = emptyList()): String {
        if (spec.id == OPENROUTER) return Voices.defaultVoiceFor(model, null)
        val all = staticVoices(spec, model) + discovered
        val preferred = if (I18n.english && spec.defaultVoiceEn.isNotBlank()) spec.defaultVoiceEn else spec.defaultVoice
        if (preferred.isNotBlank() && (all.isEmpty() || all.any { it.id == preferred })) return preferred
        if (all.isEmpty()) return ""
        if (spec.voiceOptional && spec.voiceMode == ProviderSpec.VOICE_FREE) return ""
        val home = if (I18n.english) "en" else "zh"
        val best = all.firstOrNull { Voices.langGroupOf(it.lang) == home } ?: all.firstOrNull { it.lang == "multi" }
        return (best ?: all[0]).id
    }

    /** 为目录里的一家新建一个服务。内置服务商每家一个实例；自定义端点可以加很多个 */
    fun newProfile(spec: ProviderSpec, existing: List<VoiceProfile>): VoiceProfile {
        val multi = spec.id == CUSTOM || spec.baseUrl.isBlank()
        val id = if (!multi && existing.none { it.id == spec.id }) spec.id else {
            var candidate: String
            do { candidate = "${spec.id}-${Hash.nanoid().take(5).lowercase()}" } while (existing.any { it.id == candidate })
            candidate
        }
        return VoiceProfile(
            id = id,
            spec = spec.id,
            model = spec.defaultModel,
            voice = defaultVoice(spec, spec.defaultModel),
            fields = spec.fields.filter { it.default.isNotBlank() }.associate { it.id to it.default }
        )
    }

    /* ---------------- 同步：模型表、音色表、顺带验证 Key ---------------- */

    data class SyncResult(
        val models: List<TtsModelInfo>,
        val voices: List<VoiceSpec>,
        /** 至少有一次带鉴权的请求成功了：Key 是通的 */
        val verified: Boolean,
        val credits: SpeechApi.Credits? = null,
        val creditsError: NetError? = null,
        /** 带过滤的请求被拒、不带的却成功：鉴权可疑（见 [SpeechApi.fetchTtsModels]） */
        val suspiciousAuth: NetError? = null
    )

    /** 「端点 + Key」指纹：变了就得重新同步（自建音色属于 Key 所在的账户，换了 Key 不能把上一家的私有音色摆出来） */
    fun fingerprint(spec: ProviderSpec, p: VoiceProfile): String = Hash.cyrb53("${spec.id}|${baseOf(spec, p)}|${p.apiKey.trim()}")

    /**
     * 拉取这家服务的模型表与音色表。任何一步的鉴权失败都上抛（这就是「连接检查」）；
     * 目录里没有声明任何列表接口的服务商返回 `verified = false` —— 那就只能靠试听来验证。
     */
    suspend fun sync(p: VoiceProfile, s: TtsSettings? = null): SyncResult {
        val spec = specOf(p, s)
        val model = modelOf(spec, p)
        var models: List<TtsModelInfo> = emptyList()
        var voices: List<VoiceSpec> = emptyList()
        var verified = false
        var credits: SpeechApi.Credits? = null
        var creditsError: NetError? = null
        var suspicious: NetError? = null

        when (spec.kind) {
            ProviderSpec.KIND_GEMINI -> {
                val cfg = geminiConfig(spec, p)
                voices = GeminiTts.listVoices(cfg, baseOf(spec, p).ifEmpty { GeminiTts.BASE_URL }).map { v ->
                    VoiceSpec(
                        id = v.id, name = v.displayName, lang = v.languageCode.orEmpty(),
                        gender = when (v.gender) { "female" -> "f"; "male" -> "m"; else -> "" },
                        tags = listOfNotNull(v.accent, v.context ?: v.persona).distinct(),
                        mine = Voices.isGeminiCustom(v)
                    )
                }
                verified = true
            }
            ProviderSpec.KIND_OPENAI -> {
                val cfg = openAiConfig(spec, p)
                // 目录里没写模型表的（OpenRouter、自定义端点）去问服务端；写了的以目录为准，不多发一次请求
                if (spec.models.isEmpty() && spec.listModels == null) {
                    try {
                        val r = SpeechApi.fetchTtsModels(cfg)
                        models = r.models
                        suspicious = r.suspiciousAuth
                        verified = r.suspiciousAuth == null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val err = NetErrors.fromThrowable(e, endpoint = cfg.baseUrl, method = "GET", kind = CallKind.MODEL_LIST)
                        // 自建 / 小众服务常常没有 /models：这不等于连不上，只是没有列表可选
                        val noList = spec.id != OPENROUTER && (err.category == NetCategory.NOT_FOUND || err.status == 405 || err.category == NetCategory.PARSE)
                        if (!noList) throw e
                    }
                }
                if (SpeechApi.isOpenRouterBase(cfg.baseUrl)) {
                    val c = SpeechApi.fetchCredits(cfg)
                    credits = c.getOrNull()
                    creditsError = c.exceptionOrNull()?.let { NetErrors.fromThrowable(it, endpoint = cfg.baseUrl, kind = CallKind.CREDITS) }
                }
            }
        }

        spec.listModels?.let { r ->
            models = HttpSpeech.list(r, spec, p).map { TtsModelInfo(id = it.id, name = it.name.ifBlank { it.id }) }
            verified = true
        }
        if (spec.listVoices.isNotEmpty()) {
            val all = LinkedHashMap<String, VoiceSpec>()
            var firstError: Throwable? = null
            var anyOk = false
            for (r in spec.listVoices) {
                try {
                    HttpSpeech.list(r, spec, p.copy(model = model)).forEach { all.putIfAbsent(it.id, it) }
                    anyOk = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (firstError == null) firstError = e
                }
            }
            // 公共音色库常常不要鉴权（Fish Audio）：别的表拉下来了不代表 Key 是对的。
            // 只要有一张表是因为 Key / 额度被拒的，这次检查就算失败
            firstError?.let { e ->
                val cat = (e as? app.echoread.core.net.NetException)?.error?.category
                if (!anyOk || cat == NetCategory.AUTH || cat == NetCategory.QUOTA) throw e
            }
            voices = all.values.toList()
            verified = true
        }
        return SyncResult(models, voices, verified, credits, creditsError, suspicious)
    }

    /** 试听：合成一句短话，成功时把音频一并返回（调用方负责播放） */
    suspend fun test(p: VoiceProfile, s: TtsSettings? = null): SpeechApi.TestResult {
        val t = target(p, s)
        return try {
            val text = if (t is SynthTarget.Gemini) GeminiTts.PREVIEW_TEXT else app.echoread.core.i18n.S.providers.previewText
            SpeechApi.TestResult(true, app.echoread.core.i18n.S.common.connectionOk, t.synthesize(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err = NetErrors.fromThrowable(e, endpoint = t.endpoint, kind = CallKind.SYNTHESIS, model = t.model)
                .let { if (it.model == null) it.copy(model = t.model) else it }
            SpeechApi.TestResult(false, err.headline(), error = err)
        }
    }
}
