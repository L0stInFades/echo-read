package app.echoread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分层只许向下依赖：core ← data ← tts ← ui。根包（AppGraph、MainActivity）负责把各层接起来，不受限。
 * 直接读 src/main 下的源码：import 与行内写全名的引用（`app.echoread.tts.X.call()`）一样算越层。
 */
class LayerTest {
    /** 真实源码：越层的每一处都列成「文件:行: 内容」 */
    @Test
    fun layersOnlyDependDownwards() {
        val root = File("src/main/kotlin/app/echoread")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }
        // 四层都得扫到：路径或包名写错时，这条测试不能空转着通过；多出一个顶层包，就得先想清楚它属于哪一层
        assertEquals(setOf("core", "data", "tts", "ui"), sources.values.mapNotNull { layerOf(it) }.toSet())
        val found = sources.flatMap { (path, text) -> violations(path, text) }
        assertTrue("越层引用：\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** 规则本身得真抓得到：import、行内全名、androidx 都算；向下依赖、整行注释、只是前缀相同的包不算 */
    @Test
    fun theCheckSeesImportsAndInlineReferences() {
        fun hits(text: String) = violations("X.kt", text).size
        assertEquals(1, hits("package app.echoread.data\nimport app.echoread.tts.Providers"))
        assertEquals(1, hits("package app.echoread.tts\nfun f() = app.echoread.ui.Toaster.show(\"x\")"))
        assertEquals(1, hits("package app.echoread.core.i18n\nimport androidx.compose.runtime.Composable"))
        assertEquals(1, hits("package app.echoread.core\nval t = android.os.SystemClock.uptimeMillis()"))
        assertEquals(0, hits("package app.echoread.ui\nimport app.echoread.tts.Providers"))
        assertEquals(0, hits("package app.echoread.tts\n/** 见 [app.echoread.ui.Toaster] */\n// app.echoread.ui.Toaster"))
        assertEquals(0, hits("package app.echoread.tts\nimport app.echoread.uikit.Widget"))
        assertEquals(0, hits("package app.echoread\nimport app.echoread.ui.EchoApp"))
    }

    private companion object {
        /** 每一层不许碰的包。core 是纯 JVM：android 与 androidx 也不许 */
        val FORBIDDEN = mapOf(
            "core" to listOf("android", "androidx", "app.echoread.data", "app.echoread.tts", "app.echoread.ui"),
            "data" to listOf("app.echoread.tts", "app.echoread.ui"),
            "tts" to listOf("app.echoread.ui")
        )

        val PACKAGE = Regex("""^package app\.echoread\.(\w+)""", RegexOption.MULTILINE)

        /** 文件属于哪一层：按 package 声明（引用解析认的是它，不是目录）；根包返回 null */
        fun layerOf(text: String): String? = PACKAGE.find(text)?.groupValues?.get(1)

        /**
         * 一个文件里越层的行。逐行按文本查，宁严勿漏，只跳过整行注释（KDoc、// 注释）：文档里提到上层不构成依赖。
         * 包名两头都要落在边界上：`app.echoread.uikit` 不是 ui，`foo.android.x` 也不是 android。
         */
        fun violations(path: String, text: String): List<String> {
            val banned = layerOf(text)?.let { FORBIDDEN[it] } ?: return emptyList()
            val ref = Regex("""(?<![\w.])(""" + banned.joinToString("|") { Regex.escape(it) } + """)\.\w""")
            return text.lines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                val comment = code.startsWith("//") || code.startsWith("/*") || code.startsWith("*")
                if (!comment && ref.containsMatchIn(line)) "$path:${i + 1}: $code" else null
            }
        }
    }
}
