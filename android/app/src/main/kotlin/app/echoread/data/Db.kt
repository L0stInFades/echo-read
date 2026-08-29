package app.echoread.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import app.echoread.core.BookFormat
import app.echoread.core.BookMeta
import app.echoread.core.Progress

/** 书籍元数据（封面为缩略 JPEG 字节，章节正文分表存储，大书列表不卡顿） */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val cover: ByteArray?,
    val intro: String?,
    val chapterCount: Int,
    val totalChars: Int,
    val createdAt: Long,
    val lastReadAt: Long?,
    val progressChapter: Int,
    val progressOffset: Int
) {
    fun toMeta(): BookMeta = BookMeta(
        id = id,
        title = title,
        author = author,
        format = if (format == "epub") BookFormat.EPUB else BookFormat.TXT,
        cover = cover,
        intro = intro,
        chapterCount = chapterCount,
        totalChars = totalChars,
        createdAt = createdAt,
        lastReadAt = lastReadAt,
        progress = Progress(progressChapter, progressOffset)
    )
}

/** 单章内容：只存一份规范纯文本（段落以 \n 分隔） */
@Entity(tableName = "chapters", primaryKeys = ["bookId", "idx"], indices = [Index("bookId")])
data class ChapterEntity(
    val bookId: String,
    val idx: Int,
    val title: String,
    val text: String
)

data class ChapterTitleRow(val idx: Int, val title: String)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    /** 整书入库在单事务内完成：元数据与全部章节要么都在、要么都不在 */
    @Transaction
    suspend fun putBook(book: BookEntity, chapters: List<ChapterEntity>) {
        insertBook(book)
        chapters.chunked(200).forEach { insertChapters(it) }
    }

    @Query("UPDATE books SET progressChapter = :chapterIndex, progressOffset = :offset, lastReadAt = :at WHERE id = :id")
    suspend fun updateProgress(id: String, chapterIndex: Int, offset: Int, at: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookRow(id: String)

    @Query("DELETE FROM chapters WHERE bookId = :id")
    suspend fun deleteChapters(id: String)

    @Transaction
    suspend fun deleteBook(id: String) {
        deleteBookRow(id)
        deleteChapters(id)
    }

    @Query("SELECT idx, title FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun chapterTitles(bookId: String): List<ChapterTitleRow>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND idx = :index")
    suspend fun chapter(bookId: String, index: Int): ChapterEntity?
}

@Database(entities = [BookEntity::class, ChapterEntity::class], version = 1, exportSchema = false)
abstract class EchoDb : RoomDatabase() {
    abstract fun dao(): BookDao

    companion object {
        fun open(context: Context): EchoDb =
            Room.databaseBuilder(context.applicationContext, EchoDb::class.java, "echo-read.db")
                .fallbackToDestructiveMigration(false)
                .build()
    }
}
