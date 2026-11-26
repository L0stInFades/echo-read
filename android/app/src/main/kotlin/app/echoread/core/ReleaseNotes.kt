package app.echoread.core

/** 更新说明里的一条改动。[title] 是行首冒号前的短语（「液态玻璃重做」「Fixed」），没有就是 null */
data class ReleaseNote(val title: String?, val body: String)

/**
 * 更新清单里的说明是多行纯文本，一行一条改动（发布脚本就是这么写进去的）。
 * 这里把它拆成条目，认出行首的小标题，界面据此排成列表并加粗标题。
 */
object ReleaseNotes {
    /**
     * 发布脚本给只认 notes 的老客户端在最前面垫的一行实验版提示。
     * 现在的界面另有「实验版」标记与说明，这一行再显示就重复了。
     */
    private val legacyWarning = listOf("【实验版】", "[Experimental]")

    /** 小标题最宽多少（中日韩字符算 2，其余算 1）：再长就是一句话，不是标题 */
    private const val MAX_TITLE_WIDTH = 28

    fun parse(text: String): List<ReleaseNote> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterIndexed { i, line -> i > 0 || legacyWarning.none { line.startsWith(it) } }
        .map(::note)
        .toList()

    private fun note(line: String): ReleaseNote {
        val colon = titleEnd(line) ?: return ReleaseNote(null, line)
        val body = line.substring(colon + 1).trim()
        return if (body.isEmpty()) ReleaseNote(null, line) else ReleaseNote(line.substring(0, colon).trim(), body)
    }

    /** 标题后的冒号在哪：全角「：」，或后面跟空格的半角「:」。冒号前出现句末标点、或者太宽，就不算标题 */
    private fun titleEnd(line: String): Int? {
        var width = 0
        for (i in line.indices) {
            val ch = line[i]
            val next = line.getOrNull(i + 1)
            when {
                ch == '：' || (ch == ':' && next == ' ') -> return i.takeIf { it > 0 }
                ch in "。！？；;!?" || (ch == '.' && next == ' ') -> return null
            }
            width += if (ch.code >= 0x2E80) 2 else 1
            if (width > MAX_TITLE_WIDTH) return null
        }
        return null
    }
}
