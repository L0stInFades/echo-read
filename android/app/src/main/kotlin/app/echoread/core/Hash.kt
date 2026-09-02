package app.echoread.core

import java.security.SecureRandom

object Hash {
    /** cyrb53 字符串哈希（音频缓存键、封面配色） */
    fun cyrb53(str: String, seed: Int = 0): String {
        var h1 = 0xdeadbeefL.toInt() xor seed
        var h2 = 0x41c6ce57 xor seed
        for (ch in str) {
            val c = ch.code
            h1 = (h1 xor c) * 2654435761L.toInt()
            h2 = (h2 xor c) * 1597334677
        }
        h1 = (h1 xor (h1 ushr 16)) * 2246822507L.toInt()
        h1 = h1 xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
        h2 = (h2 xor (h2 ushr 16)) * 2246822507L.toInt()
        h2 = h2 xor ((h1 xor (h1 ushr 13)) * 3266489909L.toInt())
        return Integer.toHexString(h2) + Integer.toHexString(h1)
    }

    private const val ALPHABET = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
    private val random = SecureRandom()

    fun nanoid(size: Int = 12): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        val sb = StringBuilder(size)
        for (b in bytes) sb.append(ALPHABET[b.toInt() and 63])
        return sb.toString()
    }
}
