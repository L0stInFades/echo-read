package app.echoread.data

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.echoread.core.BookFormat
import app.echoread.core.BookMeta
import app.echoread.core.Bookmark
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

/**
 * 书签（0.7.0，数据库第 2 版）：书里的一个内容位置，见 [Bookmark]。
 *
 * 同一本书的同一个位置只存一枚（唯一索引），重复添加由插入时的 IGNORE 吃掉。
 * 偏移量这一列叫 `pos`：`offset` 是 SQL 的关键字，写查询时处处要加反引号。
 * 没有声明外键：books 表是用 REPLACE 写入的，REPLACE 在 SQLite 里是「先删再插」，
 * 带级联删除的外键会在重写一本书的元数据时把它的书签一并带走。书被删除时由 [BookDao.deleteBook] 显式清掉。
 */
@Entity(tableName = "bookmarks", indices = [Index(value = ["bookId", "chapter", "pos"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    @ColumnInfo(name = "pos") val offset: Int,
    val excerpt: String,
    val createdAt: Long
) {
    fun toModel(): Bookmark = Bookmark(id, bookId, chapter, offset, excerpt, createdAt)

    companion object {
        fun of(b: Bookmark): BookmarkEntity = BookmarkEntity(b.id, b.bookId, b.chapter, b.offset, b.excerpt, b.createdAt)
    }
}

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
        deleteBookmarksOf(id)
    }

    @Query("SELECT idx, title FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun chapterTitles(bookId: String): List<ChapterTitleRow>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND idx = :index")
    suspend fun chapter(bookId: String, index: Int): ChapterEntity?

    /* ---- 书签 ---- */

    /** 按书里的位置排，同一位置的按创建时间 */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapter, pos, createdAt")
    suspend fun bookmarks(bookId: String): List<BookmarkEntity>

    /** 这个位置已经有书签时什么都不做（返回 -1） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun deleteBookmarks(ids: List<Long>)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksOf(bookId: String)
}

/** 每个版本的表结构导出到 android/app/schemas/（随代码提交），改表时拿它核对迁移 */
@Database(entities = [BookEntity::class, ChapterEntity::class, BookmarkEntity::class], version = 2, exportSchema = true)
abstract class EchoDb : RoomDatabase() {
    abstract fun dao(): BookDao

    companion object {
        /**
         * 1 → 2（0.7.0）：新增书签表，原有的两张表一个字节都不碰。
         *
         * 这两句 SQL 必须与 Room 按 [BookmarkEntity] 生成的建表语句逐字段一致（列名、类型、NOT NULL、
         * 主键、索引名）：迁移跑完后 Room 会拿实际的表结构去对它期望的那份，对不上就抛异常、应用打不开。
         * 改 [BookmarkEntity] 的任何一个字段，都要新开一个版本号、另写一条迁移，不能回头改这里。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, `chapter` INTEGER NOT NULL, `pos` INTEGER NOT NULL, " +
                        "`excerpt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_bookId_chapter_pos` ON `bookmarks` (`bookId`, `chapter`, `pos`)")
            }
        }

        /**
         * 刻意不配任何 fallbackToDestructiveMigration：缺了某条迁移时 Room 直接抛异常、应用打不开，
         * 这正是想要的 —— 宁可启动失败，也不能静默清掉库里用户的书架和阅读进度。
         * （它的布尔参数是 dropAllTables，不是开关：写成 fallbackToDestructiveMigration(false) 照样会清库。）
         */
        fun open(context: Context): EchoDb =
            Room.databaseBuilder(context.applicationContext, EchoDb::class.java, "echo-read.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
