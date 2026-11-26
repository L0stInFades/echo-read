package app.echoread.data

import app.echoread.core.Bookmark

/**
 * 书签仓库：一本书的书签的查、加、删、放回。每个写操作都返回这本书最新的书签表（按书里的位置排），
 * 调用方拿它直接换掉手里的那份 —— 一本书的书签不过几枚到几十枚，不值得做增量维护。
 * 书被删除时书签跟着清掉，那一步在 [BookDao.deleteBook] 的事务里。
 */
class BookmarkRepo(private val dao: BookDao) {
    suspend fun list(bookId: String): List<Bookmark> = dao.bookmarks(bookId).map { it.toModel() }

    /** 这个位置已经有书签时不会再加一枚（表上有唯一索引） */
    suspend fun add(bookId: String, chapter: Int, offset: Int, excerpt: String): List<Bookmark> {
        dao.insertBookmark(BookmarkEntity(bookId = bookId, chapter = chapter, offset = offset, excerpt = excerpt, createdAt = System.currentTimeMillis()))
        return list(bookId)
    }

    suspend fun remove(bookId: String, marks: List<Bookmark>): List<Bookmark> {
        dao.deleteBookmarks(marks.map { it.id })
        return list(bookId)
    }

    /** 撤销删除：原样放回去（创建时间与摘录不变）。这期间书被删了就不放，免得留下没有书的书签 */
    suspend fun restore(bookId: String, marks: List<Bookmark>): List<Bookmark> {
        if (dao.book(bookId) != null) dao.insertBookmarks(marks.map { BookmarkEntity.of(it) })
        return list(bookId)
    }
}
