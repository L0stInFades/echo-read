package app.echoread.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.echoread.core.BookFormat
import app.echoread.core.BookMeta
import app.echoread.core.EpubParser
import app.echoread.core.Hash
import app.echoread.core.ParsedBook
import app.echoread.core.Progress
import app.echoread.core.Sample
import app.echoread.core.TextOps
import app.echoread.core.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 书架仓库：导入归一化只发生在这里（章节限长 + 段落合成规范文本） */
class LibraryRepo(
    private val context: Context,
    private val dao: BookDao,
    private val chapterCache: ChapterCache
) {
    private val _books = MutableStateFlow<List<BookMeta>>(emptyList())
    val books: StateFlow<List<BookMeta>> = _books

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    suspend fun refresh() {
        _books.value = dao.allBooks().map { it.toMeta() }
            .sortedByDescending { it.lastReadAt ?: it.createdAt }
    }

    suspend fun book(id: String): BookMeta? = dao.book(id)?.toMeta()

    suspend fun chapterTitles(id: String): List<String> = dao.chapterTitles(id).map { it.title }

    /** 从内容 URI 导入一本书（TXT / EPUB）；失败抛出带用户可读信息的异常 */
    suspend fun importUri(uri: Uri): BookMeta {
        _importing.value = true
        try {
            val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt"
            val ext = name.substringAfterLast('.', "").lowercase()
            // 体积闸门：解析要把整个文件读进内存，上百 MB 的「.txt」（厂商 bugreport、聊天记录导出、
            // 数据库 dump）会直接 OOM。宁可明确拒绝，也不要让进程崩掉。
            val bytes = fileSize(uri)
            if (bytes > MAX_IMPORT_BYTES) {
                throw IllegalArgumentException("文件过大（${bytes / 1024 / 1024} MB），无法导入；单本上限 ${MAX_IMPORT_BYTES / 1024 / 1024} MB")
            }
            val parsed: ParsedBook = withContext(Dispatchers.IO) {
                when (ext) {
                    "txt", "text" -> {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalArgumentException("无法读取文件")
                        TxtParser.parse(name, bytes)
                    }
                    "epub" -> {
                        val tmp = File.createTempFile("import-", ".epub", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                ?: throw IllegalArgumentException("无法读取文件")
                            EpubParser.parse(tmp, name, ::scaleCover)
                        } finally {
                            tmp.delete()
                        }
                    }
                    else -> throw IllegalArgumentException("仅支持 TXT / EPUB 文件")
                }
            }
            return store(parsed, if (ext == "epub") BookFormat.EPUB else BookFormat.TXT)
        } finally {
            _importing.value = false
        }
    }

    /** 写入内置示例书（已存在则直接返回） */
    suspend fun importSample(): BookMeta {
        _books.value.firstOrNull { it.title == Sample.BOOK_NAME }?.let { return it }
        val parsed = withContext(Dispatchers.Default) { TxtParser.parse("${Sample.BOOK_NAME}.txt", Sample.TEXT.toByteArray()) }
        return store(parsed, BookFormat.TXT)
    }

    private suspend fun store(parsed: ParsedBook, format: BookFormat): BookMeta {
        // 统一守卫：章节限长归一化 + 段落合成为规范纯文本（内存上限由此锁定）
        val normalized = withContext(Dispatchers.Default) { TextOps.boundChapters(parsed.chapters) }
        if (normalized.isEmpty()) throw IllegalArgumentException("未能解析出任何章节")
        val bookId = Hash.nanoid()
        val entity = BookEntity(
            id = bookId,
            title = parsed.title,
            author = parsed.author.ifBlank { "佚名" },
            format = format.label,
            cover = parsed.cover,
            intro = parsed.intro,
            chapterCount = normalized.size,
            totalChars = normalized.sumOf { it.text.length },
            createdAt = System.currentTimeMillis(),
            lastReadAt = null,
            progressChapter = 0,
            progressOffset = 0
        )
        val chapters = normalized.mapIndexed { i, c -> ChapterEntity(bookId, i, c.title, c.text) }
        dao.putBook(entity, chapters)
        refresh()
        return entity.toMeta()
    }

    suspend fun remove(bookId: String) {
        dao.deleteBook(bookId)
        chapterCache.invalidate(bookId)
        refresh()
    }

    suspend fun saveProgress(bookId: String, chapterIndex: Int, offset: Int) {
        val now = System.currentTimeMillis()
        dao.updateProgress(bookId, chapterIndex, offset, now)
        _books.update { list ->
            list.map { if (it.id == bookId) it.copy(progress = Progress(chapterIndex, offset), lastReadAt = now) else it }
                .sortedByDescending { it.lastReadAt ?: it.createdAt }
        }
    }

    /** 文件大小；取不到时返回 0（不阻断导入） */
    private fun fileSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** 单本导入体积上限，与 BookScanner.MAX_BOOK_BYTES 保持一致 */
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    }

    /** 封面图缩放到 ≤360px 宽的 JPEG，避免数据库存大图 */
    private fun scaleCover(bytes: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scaled = if (bmp.width > 360) {
                val h = (bmp.height * 360f / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, 360, h, true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            out.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }
}
