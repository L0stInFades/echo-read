package app.echoread.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 朗读服务目录（0.5.0）—— 「内置原生支持的服务商」与「用户自己加的端点」共用的**同一份数据格式**。
 *
 * 设计目标是好维护：接入一家新的服务商，绝大多数情况下只是往 `assets/tts-providers.json`
 * 里加一段数据（地址、鉴权头、请求体模板、响应里音频在哪），不写一行 Kotlin。
 * 只有线上协议本身没法用模板表达时（Gemini 的 Interactions API 要在 steps[] 里挑音频块）才写适配器，
 * 目前这样的协议只有两种：[KIND_OPENAI]（/audio/speech 这一族）和 [KIND_GEMINI]。
 *
 * 目录里**只放与语言无关的数据**（ID、地址、模型名、音色 ID、语种、标签）。界面上的说明文字
 * 由标签（[badges]）经语言内核渲染，所以目录不需要翻译，漏翻仍然是编译错误。
 *
 * 用户导入的服务（设置里「粘贴服务配置」）就是一段 [ProviderSpec] JSON，与内置条目逐字段相同。
 */
@Serializable
data class ProviderCatalog(
    /** 格式版本。解析器只认自己看得懂的版本，更高的直接拒收而不是猜 */
    val schema: Int = 1,
    /** 目录修订号（yyyymmdd），只用于显示与排查 */
    val revision: Int = 0,
    val providers: List<ProviderSpec> = emptyList()
)

@Serializable
data class ProviderSpec(
    val id: String,
    /** 品牌名，不翻译 */
    val name: String,
    /** 线上协议：openai（POST {base}/audio/speech）/ gemini（Interactions API）/ http（按 [synth] 模板发请求） */
    val kind: String = KIND_OPENAI,
    /** 留空 = 必须由用户填（自建服务、自定义端点）；可以含 `{f.xxx}`（Azure 的区域在主机名里） */
    val baseUrl: String = "",
    /** 地址输入框的占位 */
    val baseHint: String = "",
    /** 去哪里创建 Key */
    val keyUrl: String = "",
    val docsUrl: String = "",
    /** Key 输入框的占位（sk-…） */
    val keyHint: String = "",
    /** global / cn / local —— 目录里分组与「大陆直连」标记用 */
    val region: String = "global",
    /** 标签词表见语言内核的 Providers.badge：free_tier / cloning / flagship / fast … 不认识的标签不显示 */
    val badges: List<String> = emptyList(),
    /** 不需要 Key（自建的本地服务） */
    val keyOptional: Boolean = false,
    /** 接受一句整体的语气/风格描述（OpenAI 的 instructions、Gemini 的 style） */
    val style: Boolean = false,
    /** openai 协议：语气这个参数叫什么（OpenAI 是 instructions，阶跃是 instruction） */
    val styleParam: String = "instructions",
    /** list = 从列表里选；free = 接受任意音色 ID（复刻音色、音色库 ID）；none = 没有音色这个概念 */
    val voiceMode: String = VOICE_LIST,
    /** 音色可以留空，由服务端取默认 */
    val voiceOptional: Boolean = false,
    /** 单次请求的字符上限（0 = 不限制）。引擎会把「单片段字数」压到这个值以内 */
    val maxChars: Int = 0,
    /** openai 协议：response_format 的取值（留空 = mp3） */
    val format: String = "",
    /** 回来的是无头 PCM 时的采样率（留空按 Content-Type，再不行按 24 kHz） */
    val pcmRate: Int = 0,
    /** 除 Key 之外还要用户填的东西（Azure 的区域、火山的 App ID） */
    val fields: List<FieldSpec> = emptyList(),
    val models: List<ModelSpec> = emptyList(),
    /** 全服务商通用的音色；模型自带音色表时以模型的为准 */
    val voices: List<VoiceSpec> = emptyList(),
    val defaultModel: String = "",
    /** 默认音色：中文界面 / 英文界面各一个（英文留空则同中文） */
    val defaultVoice: String = "",
    val defaultVoiceEn: String = "",
    /** kind=http：一次合成怎么发、音频怎么取 */
    val synth: HttpRecipe? = null,
    /** 在线拉音色表（可以有好几张：「我的音色」一张、公共音色库一张）与模型表，任何 kind 都可以配 */
    val listVoices: List<ListRecipe> = emptyList(),
    val listModels: ListRecipe? = null,
    /** 这个接口整体停用的日期（yyyy-mm-dd）。到期前界面提示，到期后不再出现在「添加服务」里 */
    val sunset: String = "",
    /** 头像底色（#RRGGBB），留空按 id 取色 */
    val color: String = "",
    /**
     * 标志：不是图片，是一段 SVG 路径数据，画在 24×24 的视口里、按奇偶规则填充，颜色按 [color] 的深浅取白或黑。
     * 多条路径用 `|` 分隔，路径前可以带「不透明度@」（`.5@M…`）表示这一层半透明。
     * 留空 = 头像显示名字的首字母。
     *
     * 内置目录里的标志是 LobeHub Icons 的单色版，由 `android/tools/lobe2logo.py` 转成这种写法。
     */
    val logo: String = "",
    /** 标志占头像边长的比例（LobeHub 给每个标志定的 AVATAR_ICON_MULTIPLE） */
    val logoScale: Float = 0.75f
) {
    companion object {
        const val KIND_OPENAI = "openai"
        const val KIND_GEMINI = "gemini"
        const val KIND_HTTP = "http"
        const val VOICE_LIST = "list"
        const val VOICE_FREE = "free"
        const val VOICE_NONE = "none"

        /** [logo] 里允许出现的字符：路径命令、数字、分隔符，以及分层用的 `|` 和 `@` */
        const val LOGO_CHARS = "MmLlHhVvCcSsQqTtAaZz0123456789.,-+eE |@"

        /** [logo] 的长度上限。内置目录里最长的一个是 3.6 KB */
        const val LOGO_MAX_CHARS = 12_000
    }
}

@Serializable
data class FieldSpec(
    /** 模板里用 `{f.<id>}` 引用 */
    val id: String,
    val default: String = "",
    val hint: String = "",
    /** 按密码框显示 */
    val secret: Boolean = false,
    val required: Boolean = true
)

@Serializable
data class ModelSpec(
    val id: String,
    val name: String = "",
    /** 支持的语言数（0 = 不显示） */
    val langs: Int = 0,
    /** 能读中文 */
    val zh: Boolean = false,
    val badges: List<String> = emptyList(),
    val maxChars: Int = 0,
    val sunset: String = "",
    /** 模型专属音色表；null = 用服务商通用的 */
    val voices: List<VoiceSpec>? = null,
    /** 覆盖服务商级的 style 开关 */
    val style: Boolean? = null
)

@Serializable
data class VoiceSpec(
    val id: String,
    val name: String = "",
    /** BCP-47 或两位语言码；multi = 多语言 */
    val lang: String = "",
    /** f / m */
    val gender: String = "",
    /** 原样显示的短标签（口音、风格），来自服务商自己的数据 */
    val tags: List<String> = emptyList(),
    /** 属于用户自己的音色（复刻 / 设计），列表里单独成组 */
    val mine: Boolean = false
)

/**
 * 一次 HTTP 调用的模板。字符串里的 `{名字}` 在发请求时替换：
 *
 * | 占位符 | 值 |
 * |---|---|
 * | `{base}` `{key}` `{model}` `{voice}` `{style}` | 服务配置 |
 * | `{text}` | 这一段正文 |
 * | `{lang}` / `{locale}` | 按这段正文的文字判断：zh / en / ja / ko，zh-CN / en-US … |
 * | `{f.xxx}` | [ProviderSpec.fields] 里用户填的值 |
 * | `{uiLang}` | 界面语言：zh / en（拉公共音色库时按它筛） |
 *
 * 过滤器写在竖线后：`{text|xml}`（XML 转义，给 SSML 用）、`{voice|locale}`（zh-CN-YunxiNeural → zh-CN）、
 * `{x|url}`（百分号编码；URL 模板里除 `{base}` 外默认都会编码）。
 * JSON 模板里整个值是 `{名字?}` 且取值为空时，这个键整个省略。
 */
@Serializable
data class HttpRecipe(
    val method: String = "POST",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** JSON 请求体模板（只替换字符串值，结构与数字原样保留） */
    val json: JsonElement? = null,
    /** 原始请求体模板（SSML 等非 JSON 的情形），与 [json] 二选一 */
    val body: String? = null,
    val contentType: String = "",
    val response: ResponseSpec = ResponseSpec()
)

@Serializable
data class ResponseSpec(
    /** bytes = 响应体就是音频；json = 音频在 JSON 的某个字段里；lines = 逐行 JSON（NDJSON / SSE），各行的音频块拼起来 */
    val type: String = TYPE_BYTES,
    /** json / lines：音频字段的路径，如 `data.audio`、`output.audio.url` */
    val audio: String = "",
    /** base64 / hex / url（再发一次 GET 把音频取回来） */
    val encoding: String = "base64",
    /** 音频是无头 PCM（16-bit 小端）时的采样率与声道数：给了就封 WAV 头 */
    val pcmRate: Int = 0,
    val pcmChannels: Int = 1,
    /** HTTP 200 里的业务状态码路径（MiniMax 的 base_resp.status_code）与表示成功的取值 */
    val errorCode: String = "",
    val okCodes: List<String> = emptyList(),
    val errorMessage: String = "",
    /** 业务状态码 → 错误种类（auth / quota / rate / bad），决定是停播、重试还是跳过这一段；没列出的按文案猜 */
    val errorKinds: Map<String, String> = emptyMap()
) {
    companion object {
        const val TYPE_BYTES = "bytes"
        const val TYPE_JSON = "json"
        const val TYPE_LINES = "lines"
    }
}

/** 拉一张列表（音色 / 模型）并映射成统一的条目。路径语法同 [ResponseSpec.audio]；多个候选路径用 `|` 分隔，取第一个非空的 */
@Serializable
data class ListRecipe(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** POST 时的 JSON 请求体模板 */
    val json: JsonElement? = null,
    /** 数组在哪（留空 = 响应体本身就是数组）。多个数组用 `|` 分隔，全部并进来；条目可以是对象，也可以直接是字符串（就当作 ID） */
    val items: String = "",
    /** 同 [items]，但这些数组里的都算「我的音色」 */
    val mineItems: String = "",
    val id: String = "id",
    val name: String = "",
    val lang: String = "",
    val gender: String = "",
    /** 逗号分隔的若干路径，各取一个短标签 */
    val tags: String = "",
    /** 只保留这个路径为 true（或非空）的条目 */
    val where: String = "",
    /** 标记「我的音色」：这个路径的值落在 [mineValues] 里（ElevenLabs 的 category = cloned / generated） */
    val mine: String = "",
    val mineValues: List<String> = emptyList(),
    /** 翻页：下一页令牌的路径 + 带回去的查询参数名 */
    val next: String = "",
    val nextParam: String = "",
    val maxPages: Int = 3,
    /** HTTP 200 里的业务状态码（同 [ResponseSpec.errorCode]）：MiniMax 的列表接口对无效 Key 也回 200 */
    val errorCode: String = "",
    val okCodes: List<String> = emptyList(),
    val errorMessage: String = "",
    val errorKinds: Map<String, String> = emptyMap()
)
