package app.echoread.core

import app.echoread.core.i18n.AppLanguage
import app.echoread.core.i18n.S
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TTS 服务商类型：AI 语音接口 / 系统语音（Android TextToSpeech，离线兜底）/ Gemini 原生接口。
 *
 * 旧版本读到不认识的 "gemini" 也不会崩：SettingsStore 的 Json 开着 coerceInputValues，
 * 未知枚举值回落到属性默认值（OPENAI），降级安装是安全的。
 */
@Serializable
enum class TtsProvider {
    @SerialName("openai-speech") OPENAI,
    @SerialName("system") SYSTEM,
    /** Google Gemini API 原生语音合成（Interactions API，0.4.0 新增） */
    @SerialName("gemini") GEMINI
}

/**
 * Gemini 原生语音合成配置（Gemini API · Interactions API · v1beta）。
 *
 * 与 [OpenAISpeechConfig] 分开存，而不是复用它换个 Base URL：两边的 Key 互不覆盖，
 * 在「AI 语音」与「Gemini」之间来回切换不丢任何一边的配置；字段语义也不同 ——
 * Gemini 3.8 把正文当逐字稿，语气走结构化的 `speech_metadata.style`，不是 OpenAI 的 instructions。
 */
@Serializable
data class GeminiTtsConfig(
    val apiKey: String = "",
    /** gemini-3.8-flash-tts（旗舰，官方列出的有声书首选）/ gemini-3.8-flash-lite-tts（更快更省） */
    val model: String = "gemini-3.8-flash-tts",
    /** 预置音色名（Kore…）、扩展音色库里的音色 ID，或自建音色 voice_… / voicekey_… */
    val voice: String = "Kore",
    /** `speech_metadata.style`：整段的语气、节奏、情绪。官方建议多数请求留空 */
    val style: String = ""
)

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

/**
 * 一个已添加的朗读服务（0.5.0）：目录里的某家服务商 + 用户自己的 Key 与选择。
 *
 * 每个服务各存各的 Key、模型、音色，来回切换互不覆盖 —— 0.4.x 只有「AI 语音」和「Gemini」两个
 * 写死的槽位，想同时留着 OpenRouter 和硅基流动的配置就做不到。
 */
@Serializable
data class VoiceProfile(
    /** 实例 ID：内置服务商就是它的目录 ID（每家一个），自定义端点是 custom-xxxx */
    val id: String,
    /** 对应目录里的哪家（[ProviderSpec.id]）；自定义的 OpenAI 兼容端点是 "custom" */
    val spec: String = id,
    /** 自定义端点的显示名（内置服务商留空，用目录里的品牌名） */
    val name: String = "",
    /** 覆盖目录里的地址（自定义端点必填；内置服务商留空） */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    /** 语气 / 风格（OpenAI 的 instructions、Gemini 的 speech_metadata.style） */
    val style: String = "",
    /** OpenAI 兼容接口的 response_format（mp3 / opus / pcm / wav；留空 = 按目录，再不行 mp3） */
    val format: String = "",
    /** 目录声明的额外字段（区域、App ID…） */
    val fields: Map<String, String> = emptyMap(),
    /** 每个模型记住的音色：切换模型来回不丢 */
    val voiceByModel: Map<String, String> = emptyMap()
)

@Serializable
data class TtsSettings(
    /**
     * 0.4.x 及更早的三个字段（引擎 / OpenAI 兼容配置 / Gemini 配置）。0.5.0 起真正生效的是
     * [active] 与 [profiles]；这三个字段仍然随时回写，降级安装旧版本时配置还在。
     */
    val provider: TtsProvider = TtsProvider.OPENAI,
    val openai: OpenAISpeechConfig = OpenAISpeechConfig(),
    /** Gemini 原生接口配置（0.4.0 新增；旧存档缺该键时取默认值） */
    val gemini: GeminiTtsConfig = GeminiTtsConfig(),
    /** 0.4.x 的「每模型记忆音色」；0.5.0 起迁进各自的 [VoiceProfile.voiceByModel] */
    val voiceByModel: Map<String, String> = emptyMap(),
    /** 播放倍速（客户端变速，避免重复合成） */
    val rate: Float = 1f,
    /** 单个合成片段的最大字符数 */
    val maxChunkChars: Int = 120,
    /** 最少预取片段数（实际窗口按合成耗时自适应放大，最多 6 段） */
    val prefetch: Int = 3,
    /** 正在使用的服务：某个 [VoiceProfile.id]，或 "system"（系统语音）。空串 = 旧存档，读档时由旧字段迁移 */
    val active: String = "",
    /** 已添加的服务 */
    val profiles: List<VoiceProfile> = emptyList(),
    /** 用户自己导入的服务定义（与内置目录同一格式） */
    val customSpecs: List<ProviderSpec> = emptyList()
) {
    /** 当前服务；系统语音或尚未迁移时为 null */
    val activeProfile: VoiceProfile? get() = profiles.firstOrNull { it.id == active }

    val usesSystemVoice: Boolean get() = active == SYSTEM || (active.isEmpty() && provider == TtsProvider.SYSTEM)

    companion object {
        const val SYSTEM = "system"
    }
}

/** 翻页手势轴向：左右滑 / 上下滑 / 关闭滑动翻页 */
@Serializable
enum class PageAxis {
    @SerialName("horizontal") HORIZONTAL,
    @SerialName("vertical") VERTICAL,
    @SerialName("off") OFF
}

/**
 * 翻页手势配置。默认值与 0.1.x 的固定行为逐项等价（左右滑 + 左右各 20% 点击翻页 + 中间点读），
 * 因此存量用户升级后手感不变；改动只发生在用户主动去设置里调过之后。
 */
@Serializable
data class GestureSettings(
    /** 滑动翻页轴向 */
    val axis: PageAxis = PageAxis.HORIZONTAL,
    /** 是否启用「点击边缘翻页」热区 */
    val tapTurn: Boolean = true,
    /** 点击热区轴向：HORIZONTAL = 左右两侧，VERTICAL = 上下两端（OFF 等同关闭点击翻页） */
    val tapAxis: PageAxis = PageAxis.HORIZONTAL,
    /** 「上一页」热区占页面的比例（从起始边算起，0 表示无） */
    val prevZone: Float = 0.2f,
    /** 「下一页」热区占页面的比例（从末尾边算起，0 表示无） */
    val nextZone: Float = 0.2f,
    /** 交换上一页/下一页热区（左手习惯，或上下模式想要「点上=下一页」） */
    val invertZones: Boolean = false,
    /** 热区之外轻点正文 = 从该字开始朗读（关掉则整页都不触发朗读） */
    val tapToRead: Boolean = true,
    /** 滑动识别阈值倍数（× 系统 touchSlop）：调大可避免与「点读」误触 */
    val slopScale: Float = 1f
) {
    /** 热区是否真的生效（关闭、或两侧都为 0 时等同关闭） */
    val zonesActive: Boolean get() = tapTurn && tapAxis != PageAxis.OFF && (prevZone > 0.001f || nextZone > 0.001f)
}

/**
 * M3 配色风格。这九种就是 Google material-color-utilities 里的全部变体，
 * 原生安卓从壁纸取色时给出的几个候选，本质上就是同一个种子色跑不同变体的结果。
 *
 * 差别在于「种子色的色相/彩度如何铺开成五条色板」：
 * - [TONAL_SPOT] 安卓默认。只保留种子色相，彩度收敛，最克制
 * - [VIBRANT] 主色高彩度，次/三色相邻色相，鲜明
 * - [EXPRESSIVE] 三色相偏移最大，色彩关系最活泼
 * - [NEUTRAL] 几乎去色，接近灰阶
 * - [MONOCHROME] 纯灰阶
 * - [FIDELITY] / [CONTENT] 尽量忠实还原种子色本身（用于跟随封面/图片取色）
 * - [RAINBOW] / [FRUIT_SALAD] 三色相大幅分离，最跳
 */
@Serializable
enum class ColorStyle {
    @SerialName("tonal_spot") TONAL_SPOT,
    @SerialName("vibrant") VIBRANT,
    @SerialName("expressive") EXPRESSIVE,
    @SerialName("neutral") NEUTRAL,
    @SerialName("monochrome") MONOCHROME,
    @SerialName("fidelity") FIDELITY,
    @SerialName("content") CONTENT,
    @SerialName("rainbow") RAINBOW,
    @SerialName("fruit_salad") FRUIT_SALAD;

    companion object {
        /**
         * 提供给用户选择的风格。刻意不含 [FIDELITY] 与 [CONTENT]：
         * 这两个变体的设计意图是「尽量忠实还原种子色」，用于从封面/壁纸图片取色的场景；
         * 在固定品牌种子色下它们生成的主色完全相同（实测均为 #3758B8），
         * 摆两个看起来一样的选项只是噪音。引擎仍支持它们，留给将来「跟随书籍封面取色」。
         */
        val PICKABLE = listOf(TONAL_SPOT, VIBRANT, EXPRESSIVE, NEUTRAL, RAINBOW, FRUIT_SALAD, MONOCHROME)
    }

    val label: String
        get() = when (this) {
            TONAL_SPOT -> S.common.standard
            VIBRANT -> S.settings.vibrant
            EXPRESSIVE -> S.settings.expressive
            NEUTRAL -> S.settings.neutral
            MONOCHROME -> S.settings.mono
            FIDELITY -> S.settings.fidelity
            CONTENT -> S.settings.content
            RAINBOW -> S.settings.rainbow
            FRUIT_SALAD -> S.settings.fruitSalad
        }
}

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
    val haptics: Boolean = true,
    /** 翻页手势（0.2.0 新增；旧存档缺该键时取默认值 = 旧行为） */
    val gestures: GestureSettings = GestureSettings(),
    /** Material You 动态取色（跟随壁纸，Android 12+）。默认关闭以保留品牌色 */
    val dynamicColor: Boolean = false,
    /** 固定配色的种子色（ARGB）。关闭动态取色时，整套配色由它 × [colorStyle] 现算 */
    val seedColor: Int = DEFAULT_SEED,
    /** 配色风格，对应 material-color-utilities 的变体 */
    val colorStyle: ColorStyle = ColorStyle.TONAL_SPOT,
    /** 对比度，对应系统「对比度」设置：0 = 标准，0.5 = 中，1 = 高 */
    val contrast: Float = 0f,
    /** 界面语言（0.4.0 新增；旧存档缺该键时跟随系统） */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** 液态玻璃材质（0.5.0）：面板与悬浮件半透明并模糊其后的内容。旧存档缺该键时开启 */
    val glass: Boolean = true
) {
    companion object {
        /** 品牌种子色。0.1.x 起沿用，HCT 色相 273.21 */
        const val DEFAULT_SEED: Int = 0xFF7C9BFF.toInt()
    }
}

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

/**
 * 一个服务在线拉到的东西（模型表、音色表）的本地缓存：离线也能选，打开设置不必每次都等网络。
 */
@Serializable
data class Discovery(
    val models: List<TtsModelInfo> = emptyList(),
    val voices: List<VoiceSpec> = emptyList(),
    /** 同步时刻（毫秒） */
    val at: Long = 0,
    /** 同步时的「端点 + Key」指纹：对不上就得重拉 */
    val fp: String = "",
    /** 音色表是给哪个模型拉的（有的服务商音色表按模型分） */
    val model: String = ""
)

/**
 * Gemini 音色目录条目（`GET /v1beta/voices` 的 Voice 资源，只留界面用得到的字段）。
 * 列表里先是本项目自建的音色（新的在前），后面是 Google 的预置目录。
 */
@Serializable
data class GeminiVoiceEntry(
    /** 合成时填进 speech_config 的值：预置音色是名字（Kore），自建音色是 voice_… */
    val id: String,
    val displayName: String = "",
    /** prebuilt / prompted（音色设计、音色改造）/ replicated（音色复刻） */
    val type: String? = null,
    /** BCP-47，如 en-US */
    val languageCode: String? = null,
    val accent: String? = null,
    /** female / male / neutral */
    val gender: String? = null,
    /** low / medium / high */
    val pitch: String? = null,
    val persona: String? = null,
    /** 适用场景，如 Audiobook / Conversational / News */
    val context: String? = null,
    val description: String? = null
)
