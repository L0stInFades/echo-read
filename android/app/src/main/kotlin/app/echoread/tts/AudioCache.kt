package app.echoread.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频缓存：按键存文件（LRU：命中即触碰 mtime；超过 300MB / 800 条时淘汰最旧到 80%）。
 * 文件不带真实扩展名，交由 ExoPlayer 按内容嗅探 mp3/ogg/wav。
 */
class AudioCache(private val dir: File) {
    private var putCount = 0

    @Volatile
    private var evicting = false

    init {
        dir.mkdirs()
    }

    private fun fileOf(key: String) = File(dir, "$key.audio")

    suspend fun get(key: String): File? = withContext(Dispatchers.IO) {
        val f = fileOf(key)
        if (f.isFile && f.length() > 0) {
            runCatching { f.setLastModified(System.currentTimeMillis()) }
            f
        } else null
    }

    /** 写入缓存并返回文件；磁盘写入失败时退回临时文件（尽力而为，不拦合成） */
    suspend fun put(key: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val target = fileOf(key)
        try {
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            if (++putCount % 10 == 0) evict()
            target
        } catch (_: Throwable) {
            val fallback = File.createTempFile("seg-", ".audio", dir.parentFile ?: dir)
            fallback.writeBytes(bytes)
            fallback
        }
    }

    data class Stats(val count: Int, val bytes: Long)

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".audio") } ?: emptyArray()
        Stats(files.size, files.sumOf { it.length() })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun evict() {
        if (evicting) return
        evicting = true
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".audio") } ?: return
            var total = files.sumOf { it.length() }
            var count = files.size
            if (count <= MAX_ENTRIES && total <= MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (count <= MAX_ENTRIES * 0.8 && total <= MAX_BYTES * 0.8) break
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    count--
                }
            }
        } finally {
            evicting = false
        }
    }

    companion object {
        private const val MAX_BYTES = 300L * 1024 * 1024
        private const val MAX_ENTRIES = 800
    }
}
