package app.echoread.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 有上限的读取：只信实际读到的字节数，不信对方声称的大小。
 * 内容提供方可以不报大小（OpenableColumns.SIZE 为 0），zip 条目的声明大小也可以造假；
 * 一律按「至多多读 1 字节」来判定超限，内存与磁盘占用都被 [limit] 锁住。
 */
object BoundedRead {
    /** 拷贝至多 [limit] 字节；流里还有更多就返回 false（已写出的部分由调用方丢弃） */
    fun copy(input: InputStream, out: OutputStream, limit: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            // 每次最多读到「上限 + 1」为止：多出的那 1 字节就是超限的证据，绝不多读
            val want = minOf(buf.size.toLong(), limit + 1 - total).toInt()
            if (want <= 0) return false
            val n = input.read(buf, 0, want)
            if (n < 0) return true
            total += n
            if (total > limit) return false
            out.write(buf, 0, n)
        }
    }

    /** 整个读进内存，至多 [limit] 字节；超限返回 null */
    fun bytes(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (copy(input, out, limit)) out.toByteArray() else null
    }
}
