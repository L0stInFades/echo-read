package app.echoread.tts

import app.echoread.core.GeminiTtsConfig
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings

/**
 * 一次远程合成的全部输入（入口处的配置快照）。引擎与流水线只认它，不关心背后是哪家接口 ——
 * 在途去重、磁盘缓存、退避重试、预取窗口这些来之不易的机制，两条接口共用同一份实现。
 */
sealed interface SynthTarget {
    val provider: TtsProvider
    val model: String
    /** 报错归因用的端点 */
    val endpoint: String
    val hasKey: Boolean
    /** 参与缓存键的身份串：同一段文字，这里任何一项变了都必须重新合成 */
    val cacheTag: String

    suspend fun synthesize(text: String): ByteArray

    /** OpenAI 兼容接口（OpenRouter / OpenAI / SiliconFlow / 自建…） */
    data class OpenAI(val cfg: OpenAISpeechConfig) : SynthTarget {
        override val provider get() = TtsProvider.OPENAI
        override val model get() = cfg.model
        override val endpoint get() = cfg.baseUrl
        override val hasKey get() = cfg.apiKey.isNotBlank()
        // 与 0.3.x 的缓存键逐字一致：升级后已经花钱合成过的语音照常命中，不重复请求
        override val cacheTag get() = "${cfg.model}|${cfg.voice}|${cfg.format}|${cfg.instructions}"
        override suspend fun synthesize(text: String): ByteArray = SpeechApi.synthesize(cfg, text)
    }

    /** Gemini 原生接口（Interactions API） */
    data class Gemini(val cfg: GeminiTtsConfig) : SynthTarget {
        override val provider get() = TtsProvider.GEMINI
        override val model get() = GeminiTts.modelOf(cfg)
        override val endpoint get() = GeminiTts.interactionsUrl()
        override val hasKey get() = cfg.apiKey.isNotBlank()
        override val cacheTag get() = "$model|${cfg.voice.trim()}|wav|${cfg.style.trim()}"
        override suspend fun synthesize(text: String): ByteArray = GeminiTts.synthesize(cfg, text)
    }

    companion object {
        /** 当前设置对应的远程合成目标；系统语音为 null */
        fun of(s: TtsSettings): SynthTarget? = when (s.provider) {
            TtsProvider.OPENAI -> OpenAI(s.openai)
            TtsProvider.GEMINI -> Gemini(s.gemini)
            TtsProvider.SYSTEM -> null
        }
    }
}
