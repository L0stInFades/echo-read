package app.echoread

import app.echoread.data.BookScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections

/** 导入链路：待导入队列不丢文件，本机扫描的截断标记按次返回 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportTest {

    /** 上一批还在导入时又投来同样的一批：照样逐个处理，按投递顺序（StateFlow 会把相等的一批吞掉） */
    @Test
    fun queueKeepsEveryItemInOrder() = runTest {
        val handled = ArrayList<String>()
        val firstImport = CompletableDeferred<Unit>()
        val queue = SerialQueue<String>(backgroundScope) { item ->
            if (handled.isEmpty()) firstImport.await()
            handled += item
        }
        queue.offer(listOf("a.txt", "b.epub"))
        runCurrent()
        queue.offer(listOf("a.txt", "b.epub"))
        queue.offer(listOf("c.txt"))
        firstImport.complete(Unit)
        runCurrent()
        assertEquals(listOf("a.txt", "b.epub", "a.txt", "b.epub", "c.txt"), handled)
    }

    /** 多个线程同时投递：一个都不丢 */
    @Test
    fun queueLosesNothingUnderConcurrentProducers() = runBlocking {
        val total = 4 * 500
        val seen = Collections.synchronizedSet(HashSet<Int>())
        val allSeen = CompletableDeferred<Unit>()
        val queue = SerialQueue<Int>(this) { if (seen.add(it) && seen.size == total) allSeen.complete(Unit) }
        List(4) { t -> launch(Dispatchers.Default) { repeat(500) { queue.offer(listOf(t * 500 + it)) } } }.joinAll()
        allSeen.await()
        assertEquals(total, seen.size)
        coroutineContext.cancelChildren() // 停掉队列的消费循环
    }

    /** 截断与否由每次遍历自己返回：一深一浅两棵树交错遍历，结果互不串 */
    @Test
    fun scanTruncationIsPerWalk() = runBlocking {
        val root = Files.createTempDirectory("scan-test").toFile()
        try {
            val shallow = File(root, "shallow").apply { mkdirs() }
            listOf("a.txt", "b.epub", "c.pdf").forEach { File(shallow, it).writeText("x") }
            val deep = File(root, "deep").apply { mkdirs() }
            File(deep, "top.txt").writeText("x")
            // 比深度上限（12）还深两层：最底下那本扫不到，这次遍历算「截断」
            val bottom = (0 until 14).fold(deep) { d, i -> File(d, "d$i") }.apply { mkdirs() }
            File(bottom, "lost.txt").writeText("x")

            val deepHits = ArrayList<String>()
            val shallowHits = ArrayList<String>()
            val deepWalk = async(Dispatchers.IO) { BookScanner.walk(deep, {}, { deepHits += it.name }) }
            val shallowCut = BookScanner.walk(shallow, {}, { shallowHits += it.name })
            assertTrue(deepWalk.await())
            assertFalse(shallowCut)
            assertEquals(listOf("top.txt"), deepHits)
            assertEquals(setOf("a.txt", "b.epub"), shallowHits.toSet())
        } finally {
            root.deleteRecursively()
        }
    }
}
