package app.echoread.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 服务目录里那些模板的求值器（纯函数，不碰网络，可直接单测）。
 *
 * 刻意做得很小：只有「按名字替换」和三四个过滤器，没有表达式、没有脚本。
 * 阅读类应用里常见的做法是把 JavaScript 引擎塞进来让用户写取音频的脚本 —— 那意味着
 * 一段从网上抄来的配置可以在应用里执行任意代码。这里的模板只能决定「往哪发、发什么、音频在哪」。
 */
object Template {
    /** `{name}`、`{name|filter}`、`{name?}`（JSON 里取值为空则省略这个键） */
    private val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z0-9_.]*)(?:\\|([a-z]+))?(\\?)?\\}")

    enum class Context { URL, HEADER, JSON, RAW }

    /** 模板里出现的全部变量名（校验目录用：写错的变量名在加载时就该报出来） */
    fun names(template: String): Set<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    fun names(template: JsonElement?): Set<String> = when (template) {
        is JsonObject -> template.values.flatMap { names(it) }.toSet()
        is JsonArray -> template.flatMap { names(it) }.toSet()
        is JsonPrimitive -> if (template.isString) names(template.content) else emptySet()
        else -> emptySet()
    }

    fun expand(template: String, vars: Map<String, String>, context: Context): String =
        PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            filter(vars[name].orEmpty(), m.groupValues[2], name, context)
        }

    private fun filter(value: String, filter: String, name: String, context: Context): String = when (filter) {
        "xml" -> xmlEscape(value)
        "url" -> urlEncode(value)
        "locale" -> localeOfVoice(value)
        "raw" -> value
        // URL 里默认做百分号编码：音色 ID 里可以有空格、括号、冒号（MiniMax、Azure 都有）。
        // {base} 本身就是一段 URL，不能再编码一遍
        else -> if (context == Context.URL && name != "base") urlEncode(value) else value
    }

    /** 整个字符串就是一个可省略的占位符 `{name?}` 时返回变量名 */
    private fun optionalName(s: String): String? {
        val m = PLACEHOLDER.matchEntire(s) ?: return null
        return if (m.groupValues[3] == "?") m.groupValues[1] else null
    }

    /** JSON 模板：只替换字符串值；结构、数字、布尔原样保留。字符串的 JSON 转义由序列化负责，正文里有引号也不会破坏请求体 */
    fun expandJson(template: JsonElement, vars: Map<String, String>): JsonElement = when (template) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in template) {
                if (v is JsonPrimitive && v.isString) {
                    val opt = optionalName(v.content)
                    if (opt != null && vars[opt].isNullOrBlank()) continue
                }
                put(k, expandJson(v, vars))
            }
        }
        is JsonArray -> JsonArray(template.map { expandJson(it, vars) })
        is JsonPrimitive -> if (template.isString) JsonPrimitive(expand(template.content, vars, Context.JSON)) else template
        else -> template
    }

    /** 点分路径取值：`data.audio`、`choices.0.message.audio.data`。空路径返回根 */
    fun at(root: JsonElement?, path: String): JsonElement? {
        var cur: JsonElement? = root
        if (path.isBlank()) return cur
        for (seg in path.split('.')) {
            cur = when (val c = cur) {
                is JsonObject -> c[seg]
                is JsonArray -> seg.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            }
            if (cur == null || cur is JsonNull) return null
        }
        return cur
    }

    /** 取字符串：`a.b|c.d` 依次尝试，返回第一个非空的。数字与布尔按字面转成字符串 */
    fun text(root: JsonElement?, paths: String): String? {
        for (p in paths.split('|')) {
            val v = at(root, p.trim()) as? JsonPrimitive ?: continue
            val s = v.content.trim()
            if (s.isNotEmpty()) return s
        }
        return null
    }

    /** 路径上的值算不算「真」：true、非零数字、非空字符串、非空数组 / 对象 */
    fun truthy(root: JsonElement?, path: String): Boolean = when (val v = at(root, path)) {
        null, is JsonNull -> false
        is JsonPrimitive -> v.content.isNotEmpty() && v.content != "false" && v.content != "0"
        is JsonArray -> v.isNotEmpty()
        is JsonObject -> v.isNotEmpty()
    }

    fun xmlEscape(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** RFC 3986 的百分号编码（空格是 %20 而不是 +：路径段里的 + 就是加号本身） */
    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** `zh-CN-YunxiNeural` / `en-US-Harper:MAI-Voice-2.1` → `zh-CN`；认不出来就按英语 */
    fun localeOfVoice(voice: String): String =
        Regex("^([a-z]{2,3}-[A-Za-z]{2,4})(?:-|$)").find(voice.trim())?.groupValues?.get(1) ?: "en-US"

    /**
     * 从一串文字里切出所有顶层 JSON 对象，不管它们之间是换行、SSE 的 `data:` 前缀，还是什么都没有。
     * 流式语音接口的分帧五花八门（NDJSON、SSE、对象直接首尾相接），按括号配对来切对这三种都成立。
     */
    fun jsonObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }
}

/** 按文字判断一段正文是什么语言：给需要显式语言参数的接口用（Cartesia 的 language、Azure 的 xml:lang） */
object TextLang {
    /** ISO 639-1：zh / ja / ko / en。假名一出现就是日语（日文里汉字占多数，不能只数汉字） */
    fun of(text: String): String {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        for (ch in text) {
            when (ch) {
                in '\u4e00'..'\u9fff', in '\u3400'..'\u4dbf' -> han++
                in '\u3040'..'\u30ff' -> kana++
                in '\uac00'..'\ud7af' -> hangul++
                in 'A'..'Z', in 'a'..'z' -> latin++
            }
        }
        return when {
            kana > 0 && kana * 8 >= han -> "ja"
            hangul > 0 && hangul >= han -> "ko"
            han > 0 && han * 4 >= latin -> "zh"
            // 只有数字和标点：没有可判断的文字，跟着界面语言走
            han == 0 && latin == 0 -> if (app.echoread.core.i18n.I18n.english) "en" else "zh"
            else -> "en"
        }
    }

    fun locale(text: String): String = when (of(text)) {
        "zh" -> "zh-CN"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        else -> "en-US"
    }
}
