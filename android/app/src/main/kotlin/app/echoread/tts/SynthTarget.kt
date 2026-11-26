package app.echoread.tts

import app.echoread.core.GeminiTtsConfig
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile

/**
 * 一次远程合成的全部输入（入口处的配置快照）。引擎与流水线只认它，不关心背后是哪家接口 ——
 * 在途去重、磁盘缓存、退避重试、预取窗口这些来之不易的机制，所有服务商共用同一份实现。
 *
 * 三种形态对应三种线上协议：/audio/speech 一族、Gemini 的 Interactions API、以及按目录模板发的 HTTP 请求。
 * 「哪家服务商」不在这里区分 —— 那是目录数据，由 [Providers.target] 解析成这三种之一。
 */
sealed interface SynthTarget {
    /** 缓存命名空间：同一段文字在两家之间来回切换各自命中、互不串音 */
    val cacheNs: String
    val model: String
    /** 报错归因用的端点 */
    val endpoint: String
    /** 可以发请求了（有 Key，或这家本来就不要 Key） */
    val hasKey: Boolean
    /** 参与缓存键的身份串：同一段文字，这里任何一项变了都必须重新合成 */
    val cacheTag: String

    suspend fun synthesize(text: String): ByteArray

    /** OpenAI 兼容接口（OpenRouter / OpenAI / 硅基流动 / 自建…） */
    data class OpenAI(
        val cfg: OpenAISpeechConfig,
        val opts: SpeechApi.Options = SpeechApi.Options(),
        override val cacheNs: String = "OPENAI"
    ) : SynthTarget {
        override val model get() = cfg.model
        override val endpoint get() = cfg.baseUrl
        override val hasKey get() = cfg.apiKey.isNotBlank() || opts.keyOptional
        // 与 0.3.x 的缓存键逐字一致：升级后已经花钱合成过的语音照常命中，不重复请求
        override val cacheTag get() = "${cfg.model}|${cfg.voice}|${cfg.format}|${cfg.instructions}"
        override suspend fun synthesize(text: String): ByteArray = SpeechApi.synthesize(cfg, text, opts)
    }

    /** Gemini 原生接口（Interactions API） */
    data class Gemini(val cfg: GeminiTtsConfig, val base: String = GeminiTts.BASE_URL) : SynthTarget {
        override val cacheNs get() = "GEMINI"
        override val model get() = GeminiTts.modelOf(cfg)
        override val endpoint get() = GeminiTts.interactionsUrl(base)
        override val hasKey get() = cfg.apiKey.isNotBlank()
        override val cacheTag get() = "$model|${cfg.voice.trim()}|wav|${cfg.style.trim()}"
        override suspend fun synthesize(text: String): ByteArray = GeminiTts.synthesize(cfg, text, base)
    }

    /** 按目录里的模板发请求（ElevenLabs、MiniMax、Azure、火山…，以及用户导入的定义） */
    data class Http(val spec: ProviderSpec, val profile: VoiceProfile) : SynthTarget {
        override val cacheNs get() = spec.id
        override val model get() = Providers.modelOf(spec, profile)
        override val endpoint get() = Providers.baseOf(spec, profile)
        override val hasKey get() = profile.apiKey.isNotBlank() || spec.keyOptional
        override val cacheTag: String
            get() = "$model|${profile.voice.trim()}|${if (Providers.supportsStyle(spec, model)) profile.style.trim() else ""}"
        override suspend fun synthesize(text: String): ByteArray = HttpSpeech.synthesize(spec, profile, text)
    }

    companion object {
        /** 当前设置对应的远程合成目标；系统语音为 null */
        fun of(s: TtsSettings): SynthTarget? = Providers.target(s)
    }
}
