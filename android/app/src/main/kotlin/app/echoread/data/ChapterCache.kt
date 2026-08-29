package app.echoread.data

import app.echoread.core.Range
import app.echoread.core.Segmenter
import app.echoread.core.TextOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 派生章节：规范文本 + 段落区间 + 合成片段区间，引擎与视图共享同一实例 */
class DerivedChapter(
    val title: String,
    val text: String,
    val paras: List<Range>,
    val segments: List<Range>
)

/**
 * 派生章节缓存 —— 全书任一时刻只有极少数章节驻留内存（LRU 4 章），
 * 同一章的文本/段落区间/合成片段只有一份实例；并发未命中共享同一在途任务。
 */
class ChapterCache(private val dao: BookDao) {
    private val cache = object : LinkedHashMap<String, DerivedChapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DerivedChapter>?): Boolean = size > LRU_MAX
    }
    private val pending = HashMap<String, Deferred<DerivedChapter?>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun keyOf(bookId: String, index: Int, maxChunk: Int) = "$bookId:$index:$maxChunk"

    private suspend fun derive(bookId: String, index: Int, maxChunk: Int): DerivedChapter? {
        val row = dao.chapter(bookId, index) ?: return null
        return DerivedChapter(row.title, row.text, TextOps.paraRanges(row.text), Segmenter.segmentChapter(row.text, maxChunk))
    }

    suspend fun get(bookId: String, index: Int, maxChunk: Int): DerivedChapter? {
        val key = keyOf(bookId, index, maxChunk)
        val task: Deferred<DerivedChapter?> = mutex.withLock {
            cache[key]?.let { return it }
            pending[key] ?: scope.async { derive(bookId, index, maxChunk) }.also { pending[key] = it }
        }
        val result = try {
            task.await()
        } catch (e: Throwable) {
            mutex.withLock { if (pending[key] === task) pending.remove(key) }
            throw e
        }
        mutex.withLock {
            // 登记仍在才转正：invalidate（删书）已清理时不得把亡书派生写回缓存
            if (pending[key] === task) {
                pending.remove(key)
                if (result != null) cache[key] = result
            }
        }
        return result
    }

    suspend fun invalidate(bookId: String) {
        mutex.withLock {
            cache.keys.removeAll { it.startsWith("$bookId:") }
            pending.keys.removeAll { it.startsWith("$bookId:") }
        }
    }

    companion object {
        private const val LRU_MAX = 4
    }
}
