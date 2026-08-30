package app.echoread.tts

/**
 * MP3 流清洗：剥掉所有 Xing / Info（VBR 信息）帧。
 *
 * 背景：部分供应商（OpenRouter 上的 Kokoro 82M 等）把文本分句合成后直接把多个独立 MP3 文件首尾拼接返回。
 * 每一段都自带 Xing 头，而解码器只认第一个：它声明的帧数 / 字节数只覆盖第一段，
 * ExoPlayer 据此把 `dataEndPosition` 定在第一段末尾，读到那里就报 EOF —— 表现为「每段只读开头一句就跳下一段」。
 * 去掉这些帧后解码器退回逐帧解析，拼接流按真实长度完整播放。
 *
 * 纯函数、无分配热点：逐帧走一遍头部（不解码），同步丢失时把余下字节原样保留。
 */
object Mp3Fix {
    private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SAMPLE_RATE = intArrayOf(44100, 48000, 32000)

    fun looksLikeMp3(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return true
        return frameLength(b, 0) > 0
    }

    /** 返回去掉 Xing/Info 帧后的字节；没有可剥的帧时返回原数组（零拷贝） */
    fun stripXing(b: ByteArray): ByteArray {
        var i = id3Size(b)
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, i)
        var stripped = 0
        while (i + 4 <= b.size) {
            val len = frameLength(b, i)
            if (len <= 0 || i + len > b.size) break
            if (isXing(b, i)) stripped++ else out.write(b, i, len)
            i += len
        }
        if (stripped == 0) return b
        if (i < b.size) out.write(b, i, b.size - i)
        return out.toByteArray()
    }

    private fun id3Size(b: ByteArray): Int {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
        val size = (b[6].toInt() and 0x7f shl 21) or (b[7].toInt() and 0x7f shl 14) or (b[8].toInt() and 0x7f shl 7) or (b[9].toInt() and 0x7f)
        val footer = if (b[5].toInt() and 0x10 != 0) 10 else 0
        return (10 + size + footer).coerceAtMost(b.size)
    }

    /** 帧总长（字节），非法帧头返回 0 */
    private fun frameLength(b: ByteArray, i: Int): Int {
        if (i + 4 > b.size) return 0
        val h = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
        if ((h ushr 21) and 0x7ff != 0x7ff) return 0
        val ver = (h ushr 19) and 3       // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layer = (h ushr 17) and 3     // 1 = Layer III
        val bri = (h ushr 12) and 15
        val sri = (h ushr 10) and 3
        val pad = (h ushr 9) and 1
        if (ver == 1 || layer != 1 || bri == 0 || bri == 15 || sri == 3) return 0
        val sr = SAMPLE_RATE[sri] / when (ver) { 3 -> 1; 2 -> 2; else -> 4 }
        val br = (if (ver == 3) BITRATE_V1 else BITRATE_V2)[bri] * 1000
        val samples = if (ver == 3) 1152 else 576
        return samples / 8 * br / sr + pad
    }

    private fun isXing(b: ByteArray, i: Int): Boolean {
        val h1 = b[i + 1].toInt() and 0xff
        val ver = (h1 ushr 3) and 3
        val mono = ((b[i + 3].toInt() and 0xff) ushr 6) == 3
        val side = if (ver == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val p = i + 4 + side
        if (p + 4 > b.size) return false
        val t0 = b[p].toInt(); val t1 = b[p + 1].toInt(); val t2 = b[p + 2].toInt(); val t3 = b[p + 3].toInt()
        val xing = t0 == 'X'.code && t1 == 'i'.code && t2 == 'n'.code && t3 == 'g'.code
        val info = t0 == 'I'.code && t1 == 'n'.code && t2 == 'f'.code && t3 == 'o'.code
        return xing || info
    }
}
