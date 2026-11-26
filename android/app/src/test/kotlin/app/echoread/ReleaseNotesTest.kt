package app.echoread

import app.echoread.core.ReleaseNote
import app.echoread.core.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新说明的拆分：一行一条、去掉给老客户端垫的实验版提示、认出行首的小标题 */
class ReleaseNotesTest {
    @Test
    fun linesBecomeNotesAndTheLegacyWarningGoes() {
        val zh = "【实验版】改动较大，可能有未发现的问题。不想升级可以点更新卡片上的「稍后再说 / ✕」。\n" +
            "这一版没有新功能，是一轮修补与加固。\n\n" +
            "  导入面板：授权或移除文件夹后列表立刻刷新。  \n" +
            "修复：「上一章」图标画错位置；书架按界面语言排序。"
        assertEquals(
            listOf(
                ReleaseNote(null, "这一版没有新功能，是一轮修补与加固。"),
                ReleaseNote("导入面板", "授权或移除文件夹后列表立刻刷新。"),
                ReleaseNote("修复", "「上一章」图标画错位置；书架按界面语言排序。")
            ),
            ReleaseNotes.parse(zh)
        )
        val en = "[Experimental] Bigger changes; there may be undiscovered issues.\nFixed: the Previous chapter icon was drawn out of place."
        assertEquals(listOf(ReleaseNote("Fixed", "the Previous chapter icon was drawn out of place.")), ReleaseNotes.parse(en))
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse(" \n\n ").isEmpty())
    }

    @Test
    fun theWarningIsOnlyDroppedAsTheFirstLine() {
        assertEquals(
            listOf(ReleaseNote(null, "第一条。"), ReleaseNote(null, "【实验版】在中间的不是那一行。")),
            ReleaseNotes.parse("第一条。\n【实验版】在中间的不是那一行。")
        )
    }

    @Test
    fun titlesAreShortPhrasesBeforeAColon() {
        fun title(line: String) = ReleaseNotes.parse(line).single().title
        assertEquals("Liquid glass, rebuilt", title("Liquid glass, rebuilt: the floating button groups are clear lenses."))
        assertEquals("In-app update", title("In-app update: a package is downloaded only with a checksum."))
        assertEquals("新增「主题」", title("新增「主题」：跟随系统 / 浅色 / 深色。"))
        // 冒号前是一整句话：不是标题
        assertEquals(null, title("Speech services now show their logos: OpenRouter, Gemini and more."))
        assertEquals(null, title("Paging back moves the bar back. Import panel: refreshes."))
        assertEquals(null, title("系统语音在 Android 11 及以上能用了。导入：更快。"))
        // 网址里的冒号后面没有空格；冒号后面什么都没有；冒号在开头
        assertEquals(null, title("See https://example.com for details."))
        assertEquals(null, title("只有标题："))
        assertEquals(null, title("：没有标题"))
    }
}
