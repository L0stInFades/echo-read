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
            TONAL_SPOT -> "标准"
            VIBRANT -> "鲜明"
            EXPRESSIVE -> "活泼"
            NEUTRAL -> "淡雅"
            MONOCHROME -> "单色"
            FIDELITY -> "忠实"
            CONTENT -> "内容"
            RAINBOW -> "彩虹"
            FRUIT_SALAD -> "缤纷"
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
    val contrast: Float = 0f
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
