package app.echoread

import app.echoread.core.EpubParser
import app.echoread.core.i18n.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB 解析冒烟：内存构造最小 EPUB */
class EpubTest {
    private fun buildEpub(): File {
        val f = File.createTempFile("test-", ".epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bid">test-001</dc:identifier>
    <dc:title>测试之书</dc:title>
    <dc:creator>作者甲</dc:creator>
    <dc:language>zh</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>""")
            put("OEBPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>
  <nav epub:type="toc"><ol>
    <li><a href="text/ch1.xhtml">第一章 启程</a></li>
    <li><a href="text/ch2.xhtml">第二章 风暴</a></li>
  </ol></nav></body></html>""")
            put("OEBPS/text/ch1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>
  <h1>第一章 启程</h1>
  <p>他推开那扇门。</p>
  <p>门外是漫天的<br/>星光。</p>
  <p>排版
     换行的段落。</p>
  <script>var x = 1;</script>
</body></html>""")
            put("OEBPS/text/ch2.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>
  <h2>第二章 风暴</h2>
  <div><p>风来了。</p><blockquote>「跑！」有人喊。</blockquote></div>
  <table><tr><td>甲</td><td>乙</td></tr></table>
</body></html>""")
        }
        return f
    }

    @Test
    fun parseMinimalEpub() {
        val f = buildEpub()
        try {
            val book = EpubParser.parse(f, "fallback-name")
            assertEquals("测试之书", book.title)
            assertEquals("作者甲", book.author)
            assertEquals(book.chapters.map { it.title }.toString(), 2, book.chapters.size)
            assertEquals("第一章 启程", book.chapters[0].title)
            assertEquals("第二章 风暴", book.chapters[1].title)
            assertEquals(listOf("他推开那扇门。", "门外是漫天的", "星光。", "排版换行的段落。"), book.chapters[0].paragraphs)
            assertTrue(book.chapters[1].paragraphs.contains("风来了。"))
            assertTrue(book.chapters[1].paragraphs.contains("「跑！」有人喊。"))
            assertTrue(book.chapters[1].paragraphs.contains("甲") && book.chapters[1].paragraphs.contains("乙"))
            assertFalse("td 不应连字", book.chapters[1].paragraphs.contains("甲乙"))
            assertFalse("script 内容不应出现", book.chapters.flatMap { it.paragraphs }.joinToString("|").contains("var x"))
        } finally {
            f.delete()
        }
    }

    /** 最小 EPUB：可选书名；每章是一个直接往 zip 流里写的函数，造大条目不必先在内存里拼出来 */
    private fun writeEpub(title: String?, chapters: List<(OutputStream) -> Unit>): File {
        val f = File.createTempFile("test-", ".epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            fun put(name: String, write: (OutputStream) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                write(zip)
                zip.closeEntry()
            }
            put("META-INF/container.xml") { it.write("""<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray()) }
            val items = chapters.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val refs = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
            val meta = title?.let { "<dc:title>$it</dc:title>" } ?: ""
            put("OEBPS/content.opf") {
                it.write("""<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$meta</metadata><manifest>$items</manifest><spine>$refs</spine></package>""".toByteArray())
            }
            chapters.forEachIndexed { i, write -> put("OEBPS/c$i.xhtml", write) }
        }
        return f
    }

    private fun page(text: String): (OutputStream) -> Unit = { it.write("<html><body><p>$text</p></body></html>".toByteArray()) }

    /** 一个 [bytes] 字节的章节文件（正文是一整段注释，解析起来便宜）；压缩后只有几 KB */
    private fun filler(bytes: Long): (OutputStream) -> Unit = { out ->
        val head = "<html><body><!--".toByteArray()
        val tail = "--></body></html>".toByteArray()
        out.write(head)
        var left = bytes - head.size - tail.size
        val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            left -= n
        }
        out.write(tail)
    }

    private fun withEpub(title: String?, chapters: List<(OutputStream) -> Unit>, block: (File) -> Unit) {
        val f = writeEpub(title, chapters)
        try {
            block(f)
        } finally {
            f.delete()
        }
    }

    /** 没有书名就退回文件名；书名恰好叫「未命名」的书照样是它自己的名字 */
    @Test
    fun missingTitleFallsBackToFileName() {
        withEpub(null, listOf(page("正文。"))) { f ->
            assertEquals("我的书", EpubParser.parse(f, "我的书.epub").title)
            assertNull("预览拿不到书名就不给", EpubParser.peekMeta(f))
        }
        withEpub("未命名", listOf(page("正文。"))) { f ->
            assertEquals("未命名", EpubParser.parse(f, "file.epub").title)
            assertEquals("未命名" to "", EpubParser.peekMeta(f))
        }
    }

    /** 解压炸弹：单个条目解出来超过上限就拒收，读到上限 + 1 字节即停，不会整个读进内存 */
    @Test
    fun oversizedEntryIsRefused() {
        withEpub("炸弹", listOf(filler(EpubParser.MAX_ENTRY_BYTES + 1))) { f ->
            assertTrue("压缩后应该很小", f.length() < 1 shl 20)
            val e = assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(f, "bomb.epub") }
            assertEquals(S.importer.epubTooLarge, e.message)
        }
    }

    /** 每个条目都在上限内，但累计解出的字节超过总上限，同样拒收 */
    @Test
    fun totalUnpackedSizeIsCapped() {
        val each = EpubParser.MAX_ENTRY_BYTES - 1024
        val count = (EpubParser.MAX_TOTAL_BYTES / each + 1).toInt()
        withEpub("很多章", List(count) { filler(each) }) { f ->
            val e = assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(f, "big.epub") }
            assertEquals(S.importer.epubTooLarge, e.message)
        }
    }

    @Test
    fun resolvePath() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolvePath("OEBPS/", "text/ch1.xhtml#frag"))
        assertEquals("OEBPS/img/a.png", EpubParser.resolvePath("OEBPS/text/", "../img/a.png"))
        assertEquals("a/b.html", EpubParser.resolvePath("x/", "/a/b.html"))
        assertEquals("OEBPS/名 字.xhtml", EpubParser.resolvePath("OEBPS/", "%E5%90%8D%20%E5%AD%97.xhtml"))
        assertEquals("OEBPS/100%.xhtml", EpubParser.resolvePath("OEBPS/", "100%.xhtml"))
    }
}
