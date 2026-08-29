package app.echoread.core

/** 字符偏移区间 [start, end) —— 段落/句子/合成片段/高亮的统一表达（UTF-16 码元偏移，与网页版一致） */
data class Range(val start: Int, val end: Int) {
    val length: Int get() = end - start
    operator fun contains(offset: Int): Boolean = offset >= start && offset < end
}

enum class BookFormat(val label: String) { TXT("txt"), EPUB("epub") }

/** 解析器产物中的单章（入库前，段落尚未合并为规范文本） */
data class ParsedChapter(val title: String, val paragraphs: List<String>)

/** 解析器产物（入库前） */
data class ParsedBook(
    val title: String,
    val author: String,
    val intro: String? = null,
    /** 缩略封面 JPEG 字节；无封面时为 null */
    val cover: ByteArray? = null,
    val chapters: List<ParsedChapter>
)

/** 章节限长归一化后的单章：只有一份规范纯文本（段落以 \n 分隔） */
data class BoundChapter(val title: String, val text: String)

/** 阅读进度：章节索引 + 章节内字符偏移 */
data class Progress(val chapterIndex: Int, val offset: Int)

/** 书架上的书籍元数据（章节内容单独存储） */
data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val cover: ByteArray? = null,
    val intro: String? = null,
    val chapterCount: Int,
    val totalChars: Int,
    val createdAt: Long,
    val lastReadAt: Long? = null,
    val progress: Progress
) {
    override fun equals(other: Any?): Boolean = other is BookMeta && other.id == id &&
        other.title == title && other.author == author && other.chapterCount == chapterCount &&
        other.totalChars == totalChars && other.createdAt == createdAt && other.lastReadAt == lastReadAt &&
        other.progress == progress && (other.cover?.size ?: -1) == (cover?.size ?: -1)

    override fun hashCode(): Int = id.hashCode()
}

/** 章节纯文本行（持久层唯一形态） */
data class ChapterText(val bookId: String, val index: Int, val title: String, val text: String)

enum class PlayerState { IDLE, LOADING, PLAYING, PAUSED, ERROR }
