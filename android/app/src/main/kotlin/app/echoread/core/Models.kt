package app.echoread.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TTS 服务商类型：AI 语音接口 / 系统语音（Android TextToSpeech，离线兜底） */
@Serializable
enum class TtsProvider {
    @SerialName("openai-speech") OPENAI,
    @SerialName("system") SYSTEM
}

/** OpenAI 兼容语音接口配置（OpenRouter / OpenAI / SiliconFlow / FishAudio 等通用） */
@Serializable
data class OpenAISpeechConfig(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val model: String = "hexgrad/kokoro-82m",
    val voice: String = "zf_xiaoxiao",
    /** 部分模型支持的语气指令 */
    val instructions: String = "",
    /** mp3 / opus / pcm */
    val format: String = "mp3"
)

@Serializable
data class TtsSettings(
    val provider: TtsProvider = TtsProvider.OPENAI,
    val openai: OpenAISpeechConfig = OpenAISpeechConfig(),
    /** 每模型记忆的音色选择（切换模型来回不丢音色） */
    val voiceByModel: Map<String, String> = emptyMap(),
    /** 播放倍速（客户端变速，避免重复合成） */
    val rate: Float = 1f,
    /** 单个合成片段的最大字符数 */
    val maxChunkChars: Int = 120,
    /** 最少预取片段数（实际窗口按合成耗时自适应放大，最多 6 段） */
    val prefetch: Int = 3
)

@Serializable
data class ReaderSettings(
    /** dark / light / paper / eye / ink */
    val theme: String = "dark",
    val fontSize: Int = 19,
    val lineHeight: Float = 1.9f,
    /** sans / serif */
    val fontFamily: String = "serif",
    val paraSpacing: Float = 1f,
    /** 手势触觉反馈（翻页越过半页、弹层吸附、返回提交） */
    val haptics: Boolean = true
)

/** 在线拉取到的 TTS 模型条目 */
@Serializable
data class TtsModelInfo(
    val id: String,
    val name: String,
    /** 服务端声明的可用音色（supported_voices），null 表示未提供 */
    val voices: List<String>? = null,
    val description: String? = null,
    /** 每字符美元单价（OpenRouter TTS 按输入字符计价） */
    val promptPrice: Double? = null,
    /** 输出 token 单价（Gemini 等按 token 计费的模型才有） */
    val completionPrice: Double? = null
)
