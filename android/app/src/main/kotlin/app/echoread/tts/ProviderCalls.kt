package app.echoread.tts

import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.VoiceSpec
import app.echoread.core.Voices
import app.echoread.core.net.CallKind
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * 服务目录（core 的 Providers）里要用到网络库与合成接口的那几步：「地址能不能发请求」的判断、
 * 「一个已添加的服务 → 一次可执行的合成」的解析、模型表与音色表的同步、试听。
 * 写成 Providers 的扩展：目录本身留在 core，设置层（data）用它就不必反向依赖 tts，调用处照旧写 Providers.xxx(...)。
 */

/* ---------------- 解析一个已添加的服务 ---------------- */

/** 是一个能发请求的 http(s) 地址 */
fun Providers.validBase(base: String): Boolean = base.toHttpUrlOrNull() != null

/** 配置够不够发出第一次请求（不保证 Key 有效） */
fun Providers.ready(spec: ProviderSpec, p: VoiceProfile): Boolean = !needsKey(spec, p) && validBase(baseOf(spec, p))

fun Providers.openAiOptions(spec: ProviderSpec): SpeechApi.Options =
    SpeechApi.Options(keyOptional = spec.keyOptional, styleParam = spec.styleParam, pcmRate = spec.pcmRate)

fun Providers.target(p: VoiceProfile, s: TtsSettings? = null): SynthTarget {
    val spec = specOf(p, s)
    return when (spec.kind) {
        ProviderSpec.KIND_GEMINI -> SynthTarget.Gemini(geminiConfig(spec, p), baseOf(spec, p).ifEmpty { GeminiTts.BASE_URL })
        ProviderSpec.KIND_HTTP -> SynthTarget.Http(spec, p)
        else -> {
            val cfg = openAiConfig(spec, p)
            SynthTarget.OpenAI(cfg, openAiOptions(spec), cacheNs(spec, cfg.baseUrl))
        }
    }
}

/** 当前设置对应的远程合成目标；系统语音为 null */
fun Providers.target(s: TtsSettings): SynthTarget? {
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

/**
 * 拉取这家服务的模型表与音色表。任何一步的鉴权失败都上抛（这就是「连接检查」）；
 * 目录里没有声明任何列表接口的服务商返回 `verified = false` —— 那就只能靠试听来验证。
 */
suspend fun Providers.sync(p: VoiceProfile, s: TtsSettings? = null): SyncResult {
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
suspend fun Providers.test(p: VoiceProfile, s: TtsSettings? = null): SpeechApi.TestResult {
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
