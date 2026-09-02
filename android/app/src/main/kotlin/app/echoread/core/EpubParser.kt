package app.echoread.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** EPUB 解析（zip + OPF + spine，纯文本抽取）；不依赖任何 Android API，可在 JVM 单测运行 */
object EpubParser {
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "pre", "tr", "td", "th", "table", "br", "hr", "dd", "dt", "figcaption"
    )

    private fun Element.localName(): String = tagName().substringAfterLast(':').lowercase()

    /** 段内空白归一：换行处两侧皆为 CJK 则直接拼接，否则折叠为单个空格 */
    private val NEWLINE_RUN = Regex("[ \\t\\u3000]*\\n[ \\t\\u3000\\n]*")
    private val SPACE_RUN = Regex("[ \\t]{2,}")
    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || b == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun normalizeParagraph(raw: String): String {
        val s = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        val sb = StringBuilder(s.length)
        var last = 0
        for (m in NEWLINE_RUN.findAll(s)) {
            sb.append(s, last, m.range.first)
            val before = sb.lastOrNull()
            val after = s.getOrNull(m.range.last + 1)
            if (before != null && after != null && isCjk(before) && isCjk(after)) {
                /* CJK 之间的排版换行：直接拼接 */
            } else if (before != null && after != null) {
                sb.append(' ')
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
        return SPACE_RUN.replace(sb, " ").trim()
    }

    /** 将 XHTML 正文序列化为段落文本（块级元素换行，<br> 换行） */
    fun extractParagraphs(doc: Document): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        fun flush() {
            val t = normalizeParagraph(buf.toString())
            if (t.isNotEmpty()) out.add(t)
            buf.setLength(0)
        }
        fun walk(node: Node) {
            if (node is TextNode) {
                buf.append(node.wholeText)
                return
            }
            if (node !is Element) return
            val tag = node.localName()
            if (tag == "script" || tag == "style" || tag == "img" || tag == "svg" || tag == "head") return
            if (tag == "br") {
                flush()
                return
            }
            val isBlock = tag in BLOCK_TAGS
            if (isBlock) flush()
            for (child in node.childNodes()) walk(child)
            if (isBlock) flush()
        }
        val body = doc.body()
        if (body != null) walk(body) else walk(doc)
        flush()
        return out
    }

    private fun dirname(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    /** 相对 OPF 所在目录解析 href（处理 ../ 与 ./；/ 开头按包根；坏编码回退原样） */
    fun resolvePath(base: String, href: String): String {
        val raw = href.substringBefore('#')
        val clean = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Throwable) {
            raw
        }
        val joined = (if (clean.startsWith("/")) "" else base) + clean
        val out = ArrayList<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                ".", "" -> continue
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    private class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private class OpfInfo(
        var title: String,
        val author: String,
        val intro: String?,
        val coverPath: String?,
        val base: String,
        val manifest: LinkedHashMap<String, ManifestItem>,
        val spine: List<String>
    )

    private fun parseOpf(xml: String, opfPath: String): OpfInfo {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val metadata = doc.allElements.firstOrNull { it.localName() == "metadata" }
        fun text(name: String): String {
            val els = metadata?.allElements ?: return ""
            for (el in els) if (el.localName() == name) return el.text().trim()
            return ""
        }
        val title = text("title").ifEmpty { "未命名" }
        val author = text("creator")
        val intro = text("description").ifEmpty { null }

        val manifest = LinkedHashMap<String, ManifestItem>()
        doc.allElements.firstOrNull { it.localName() == "manifest" }?.children()?.forEach { item ->
            if (item.localName() != "item") return@forEach
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isEmpty() || href.isEmpty()) return@forEach
            manifest[id] = ManifestItem(href, item.attr("media-type"), item.attr("properties"))
        }

        val spine = ArrayList<String>()
        doc.allElements.firstOrNull { it.localName() == "spine" }?.children()?.forEach { ref ->
            if (ref.localName() != "itemref") return@forEach
            val idref = ref.attr("idref")
            if (idref.isNotEmpty() && ref.attr("linear") != "no" && manifest.containsKey(idref)) spine.add(idref)
        }

        // 封面：EPUB3 properties="cover-image" 或 EPUB2 <meta name="cover" content="id">
        var coverPath: String? = null
        for ((_, v) in manifest) if (v.properties.contains("cover-image")) coverPath = v.href
        if (coverPath == null) {
            val metaCover = metadata?.children()?.firstOrNull { it.localName() == "meta" && it.attr("name") == "cover" }?.attr("content")
            if (!metaCover.isNullOrEmpty() && manifest.containsKey(metaCover)) coverPath = manifest[metaCover]!!.href
        }
        return OpfInfo(title, author, intro, coverPath, dirname(opfPath), manifest, spine)
    }

    private fun ZipFile.readText(path: String): String? {
        val entry = getEntry(path) ?: return null
        return getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun ZipFile.readBytes(path: String): ByteArray? {
        val entry = getEntry(path) ?: return null
        return getInputStream(entry).use { it.readBytes() }
    }

    /** 从 nav / NCX 提取 href → 章节标题 映射（href 相对 nav 文档自身目录解析） */
    private fun extractNavTitles(zip: ZipFile, opf: OpfInfo): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        fun put(navBase: String, href: String, label: String) {
            val full = resolvePath(navBase, href)
            val t = label.trim().replace(Regex("\\s+"), " ")
            if (t.isNotEmpty() && !map.containsKey(full)) map[full] = t
        }
        // EPUB3 nav 文档
        for ((_, v) in opf.manifest) {
            if (!v.properties.contains("nav")) continue
            val navPath = resolvePath(opf.base, v.href)
            val navXml = zip.readText(navPath) ?: continue
            val navBase = dirname(navPath)
            val doc = Jsoup.parse(navXml)
            for (a in doc.select("nav a[href]")) put(navBase, a.attr("href"), a.text())
            if (map.isNotEmpty()) return map
        }
        // EPUB2 NCX
        val ncxItem = opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = resolvePath(opf.base, ncxItem.href)
            val ncxXml = zip.readText(ncxPath)
            if (ncxXml != null) {
                val ncxBase = dirname(ncxPath)
                val doc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
                for (np in doc.allElements) {
                    if (np.localName() != "navpoint") continue
                    val label = np.children().firstOrNull { it.localName() == "navlabel" }
                        ?.children()?.firstOrNull { it.localName() == "text" }?.text() ?: ""
                    val src = np.children().firstOrNull { it.localName() == "content" }?.attr("src")
                    if (!src.isNullOrEmpty()) put(ncxBase, src, label)
                }
            }
        }
        return map
    }

    /**
     * 只读书名/作者（导入列表的预览用）：走 zip 中央目录 + container.xml + OPF 元数据，
     * 不解压正文、不抽章节、不解码封面。任何异常都返回 null —— 预览失败不该影响这本书能否导入。
     */
    fun peekMeta(file: File): Pair<String, String>? = try {
        ZipFile(file).use { zip ->
            val containerXml = zip.readText("META-INF/container.xml")
            val opfPath = containerXml?.let {
                Jsoup.parse(it, "", Parser.xmlParser()).allElements.firstOrNull { e -> e.localName() == "rootfile" }?.attr("full-path")
            }
            val opfXml = if (opfPath.isNullOrEmpty()) null else zip.readText(opfPath)
            if (opfXml == null) null else {
                val opf = parseOpf(opfXml, opfPath!!)
                if (opf.title.isBlank() || opf.title == "未命名") null else opf.title to opf.author
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 解析 EPUB 文件。coverScaler 把原始封面图字节缩放为缩略 JPEG（Android 侧用 BitmapFactory，
     * 单测传 null 或原样返回），解析核心保持纯 JVM。
     */
    fun parse(file: File, fallbackName: String, coverScaler: ((ByteArray) -> ByteArray?)? = null): ParsedBook {
        ZipFile(file).use { zip ->
            val containerXml = zip.readText("META-INF/container.xml") ?: throw IllegalArgumentException("无效的 EPUB：缺少 container.xml")
            val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val opfPath = containerDoc.allElements.firstOrNull { it.localName() == "rootfile" }?.attr("full-path")
            if (opfPath.isNullOrEmpty()) throw IllegalArgumentException("无效的 EPUB：找不到 OPF 路径")
            val opfXml = zip.readText(opfPath) ?: throw IllegalArgumentException("无效的 EPUB：缺少 OPF 文件")
            val opf = parseOpf(opfXml, opfPath)
            if (opf.title == "未命名") opf.title = fallbackName.replace(Regex("\\.epub$", RegexOption.IGNORE_CASE), "")

            val navTitles = extractNavTitles(zip, opf)

            var cover: ByteArray? = null
            if (opf.coverPath != null) {
                val bytes = zip.readBytes(resolvePath(opf.base, opf.coverPath))
                if (bytes != null) cover = if (coverScaler != null) coverScaler(bytes) else bytes
            }

            val chapters = ArrayList<ParsedChapter>()
            for (id in opf.spine) {
                val item = opf.manifest[id] ?: continue
                if (!Regex("xhtml|html", RegexOption.IGNORE_CASE).containsMatchIn(item.mediaType)) continue
                val path = resolvePath(opf.base, item.href)
                val html = zip.readText(path) ?: continue
                val doc = Jsoup.parse(html)
                val paragraphs = extractParagraphs(doc)
                if (paragraphs.isEmpty()) continue

                var title = navTitles[path]
                if (title == null) {
                    val heading = doc.selectFirst("h1,h2,h3,h4")?.text()?.trim()
                    title = if (!heading.isNullOrEmpty() && heading.length <= 60) heading else "第 ${chapters.size + 1} 节"
                }
                val body = if (paragraphs[0] == title) paragraphs.drop(1) else paragraphs
                chapters.add(ParsedChapter(title, if (body.isNotEmpty()) body else paragraphs))
            }
            if (chapters.isEmpty()) throw IllegalArgumentException("EPUB 中没有可用的文本章节")
            return ParsedBook(opf.title, opf.author, opf.intro, cover, chapters)
        }
    }
}
