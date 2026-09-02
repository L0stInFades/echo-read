package app.echoread

import app.echoread.core.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    @Test
    fun resolvePath() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolvePath("OEBPS/", "text/ch1.xhtml#frag"))
        assertEquals("OEBPS/img/a.png", EpubParser.resolvePath("OEBPS/text/", "../img/a.png"))
        assertEquals("a/b.html", EpubParser.resolvePath("x/", "/a/b.html"))
        assertEquals("OEBPS/名 字.xhtml", EpubParser.resolvePath("OEBPS/", "%E5%90%8D%20%E5%AD%97.xhtml"))
        assertEquals("OEBPS/100%.xhtml", EpubParser.resolvePath("OEBPS/", "100%.xhtml"))
    }
}
