package app.echoread

import app.echoread.data.BookDao
import app.echoread.data.BookEntity
import app.echoread.data.BookmarkEntity
import app.echoread.data.ChapterCache
import app.echoread.data.ChapterEntity
import app.echoread.data.ChapterTitleRow
import app.echoread.tts.AudioCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** 两级缓存：音频文件缓存（LRU 淘汰、原子写入）与派生章节缓存（并发去重、删书时的竞态） */
class CacheTest {
    private val dir: File = Files.createTempDirectory("cache-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /* ---------------- AudioCache ---------------- */

    /** 超过 800 条时按最近使用淘汰到八成：最久没碰过的先走，刚被读过的留下 */
    @Test
    fun audioCacheEvictsLeastRecentlyUsed() = runBlocking {
        val cache = AudioCache(dir)
        val longAgo = System.currentTimeMillis() - 10L * 24 * 3600 * 1000
        repeat(800) { i ->
            cache.put("k$i", byteArrayOf(1))
            File(dir, "k$i.audio").setLastModified(longAgo + i * 1000L)
        }
        assertEquals("没超上限不淘汰", 800, cache.stats().count)
        assertNotNull("命中即刷新使用时间", cache.get("k0"))
        repeat(10) { cache.put("n$it", byteArrayOf(2)) }
        assertEquals(640, cache.stats().count)
        assertTrue("刚读过的最老条目留下", File(dir, "k0.audio").exists())
        assertTrue((1..170).none { File(dir, "k$it.audio").exists() })
        assertTrue(File(dir, "k171.audio").exists())
        assertTrue((0 until 10).all { File(dir, "n$it.audio").exists() })
    }

    /** 先写临时文件再改名：写完不留临时文件，覆盖写读到的是完整的新内容，半截文件不会被当成命中 */
    @Test
    fun audioCacheWritesAtomically() = runBlocking {
        val cache = AudioCache(dir)
        val first = ByteArray(1000) { 1 }
        val second = ByteArray(2000) { 2 }
        assertArrayEquals(first, cache.put("seg", first).readBytes())
        assertFalse(File(dir, "seg.tmp").exists())
        cache.put("seg", second)
        assertArrayEquals(second, cache.get("seg")!!.readBytes())
        File(dir, "half.tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertNull("崩溃留下的临时文件不算缓存", cache.get("half"))
        File(dir, "empty.audio").writeBytes(ByteArray(0))
        assertNull("空文件不算命中", cache.get("empty"))
    }

    /* ---------------- ChapterCache ---------------- */

    /** 只实现章节读取：每次读库计数，并卡在 [gate] 上，直到测试放行 */
    private class GatedDao : BookDao {
        val reads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        override suspend fun chapter(bookId: String, index: Int): ChapterEntity {
            reads.incrementAndGet()
            gate.await()
            return ChapterEntity(bookId, index, "第 ${index + 1} 章", "第一句。第二句。\n第二段。")
        }

        override suspend fun allBooks(): List<BookEntity> = emptyList()
        override suspend fun book(id: String): BookEntity? = null
        override suspend fun insertBook(book: BookEntity) {}
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
        override suspend fun updateProgress(id: String, chapterIndex: Int, offset: Int, at: Long) {}
        override suspend fun deleteBookRow(id: String) {}
        override suspend fun deleteChapters(id: String) {}
        override suspend fun chapterTitles(bookId: String): List<ChapterTitleRow> = emptyList()
        override suspend fun bookmarks(bookId: String): List<BookmarkEntity> = emptyList()
        override suspend fun insertBookmark(bookmark: BookmarkEntity): Long = -1
        override suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>) {}
        override suspend fun deleteBookmarks(ids: List<Long>) {}
        override suspend fun deleteBookmarksOf(bookId: String) {}
    }

    /** 同一章的并发未命中共享同一个在途任务：只读一次库，拿到的是同一个实例；之后直接命中 */
    @Test
    fun concurrentMissesShareOneLoad() = runBlocking {
        val dao = GatedDao()
        val cache = ChapterCache(dao)
        val a = async { cache.get("b", 0, 200) }
        val b = async { cache.get("b", 0, 200) }
        yield() // a、b 都已挂在同一个在途任务上（库读还卡在 gate）
        dao.gate.complete(Unit)
        val first = a.await()
        assertNotNull(first)
        assertSame(first, b.await())
        assertSame(first, cache.get("b", 0, 200))
        assertEquals(1, dao.reads.get())
    }

    /** 派生还在途时书被删了：在途的调用方照样拿到结果，但亡书的派生不得写回缓存 */
    @Test
    fun invalidateDuringLoadDoesNotResurrect() = runBlocking {
        val dao = GatedDao()
        val cache = ChapterCache(dao)
        val inFlight = async { cache.get("b", 0, 200) }
        yield()
        cache.invalidate("b")
        dao.gate.complete(Unit)
        assertNotNull(inFlight.await())
        assertNotNull(cache.get("b", 0, 200))
        assertEquals("删书之后必须重新读库", 2, dao.reads.get())
    }
}
