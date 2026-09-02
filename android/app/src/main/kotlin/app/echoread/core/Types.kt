package app.echoread.core

/** 字符偏移区间 [start, end) —— 段落/句子/合成片段/高亮的统一表达（UTF-16 码元偏移） */
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

/**
 * 书级阅读进度（0..1）。**阅读器与书架共用这一个函数** —— 这是 0.2.0 修掉的一处硬伤：
 * 0.1.x 里两块界面用三套互不相干的算法，同一本书同一时刻能同时显示 100% 和 0%。
 *
 * 定义：`(章号 + 章内比例) / 总章数`。刻意用 `chapterCount` 而不是 `chapterCount - 1` 作分母，
 * 因为「刚翻到最后一章」是 11/12 = 91.7%，不是 100%；旧书架的 `chapterIndex/(chapterCount-1)`
 * 会在**到达**最后一章的瞬间就报 100%，还把「已读 100%」写在一本刚开始读的最后一章上。
 *
 * 它有意**不**依赖任何合成参数。旧阅读器用 `segmentIndex / segmentCount`，
 * 于是拖动「单片段字数」滑块能让静止不动的进度条在 40%～50% 之间来回走 —— 一个计费/延迟
 * 旋钮不该是「我读了多少」的输入。
 *
 * @param chapterLen 当前章字符数。阅读器传精确值；书架没有分章长度，传 totalChars/chapterCount 的均值即可
 */
fun bookFraction(chapterIndex: Int, offsetInChapter: Int, chapterLen: Int, chapterCount: Int): Float {
    if (chapterCount <= 0 || chapterIndex < 0) return 0f
    val within = (offsetInChapter.toFloat() / chapterLen.coerceAtLeast(1)).coerceIn(0f, 1f)
    return ((chapterIndex + within) / chapterCount).coerceIn(0f, 1f)
}

/** 书架用：只有元数据时的书级进度。章长取全书均值 */
fun BookMeta.readFraction(): Float =
    bookFraction(progress.chapterIndex, progress.offset, if (chapterCount > 0) totalChars / chapterCount else totalChars, chapterCount)

/** 是否已经开始读（用于区分「未开始」与「已读 0%」） */
fun BookMeta.started(): Boolean = progress.chapterIndex > 0 || progress.offset > 0
