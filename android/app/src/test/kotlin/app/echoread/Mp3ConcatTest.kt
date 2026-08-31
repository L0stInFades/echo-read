package app.echoread

import app.echoread.tts.Mp3Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼接式 MP3 的清洗测试。
 *
 * 背景：OpenRouter 上的 Kokoro 82M 等把整段文本分句合成后，**把多个独立的 MP3 文件首尾拼接**返回。
 * 每个文件各带一个 Xing（VBR 信息）帧，而解码器只认第一个 —— 它声明的帧数只覆盖第一段，
 * ExoPlayer 据此把 dataEndPosition 定在第一段末尾，读到那里就当作播完。
 *
 * 真实世界里每个被拼接的文件前面**还可能带 ID3v2 标签**，这正是原实现漏掉的情况：
 * 帧游标走到第二个文件的 ID3 头时同步丢失，代码 break 并把余下字节原样拷出 ——
 * 于是第二个及之后所有的 Xing 帧全都留了下来。
 */
class Mp3ConcatTest {

    /** MPEG-1 Layer III / 128kbps / 44.1kHz / 立体声：帧长 417 字节 */
    private val FRAME_LEN = 417

    private fun frame(xing: Boolean, fill: Byte = 0x11): ByteArray {
        val f = ByteArray(FRAME_LEN) { fill }
        f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0x00
        if (xing) {
            // MPEG1 立体声的 side info 为 32 字节，标记写在帧内偏移 4+32
            "Xing".forEachIndexed { i, ch -> f[36 + i] = ch.code.toByte() }
        }
        return f
    }

    /** ID3v2.3 标签，内容全零，synchsafe 长度 */
    private fun id3(contentLen: Int): ByteArray {
        val h = ByteArray(10 + contentLen)
        h[0] = 'I'.code.toByte(); h[1] = 'D'.code.toByte(); h[2] = '3'.code.toByte()
        h[3] = 3; h[4] = 0; h[5] = 0
        h[6] = ((contentLen shr 21) and 0x7f).toByte()
        h[7] = ((contentLen shr 14) and 0x7f).toByte()
        h[8] = ((contentLen shr 7) and 0x7f).toByte()
        h[9] = (contentLen and 0x7f).toByte()
        return h
    }

    /** 一个「文件」：ID3 + Xing 帧 + n 个音频帧 */
    private fun oneFile(audioFrames: Int, withId3: Boolean, fill: Byte): ByteArray {
        var out = ByteArray(0)
        if (withId3) out += id3(24)
        out += frame(xing = true)
        repeat(audioFrames) { out += frame(xing = false, fill = fill) }
        return out
    }

    private fun countXing(b: ByteArray): Int {
        var n = 0
        var i = 0
        while (i + 40 <= b.size) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0.toInt().toByte().toInt() + 0) {
                // 简化：直接找 "Xing" 字面量
            }
            if (b[i] == 'X'.code.toByte() && b[i + 1] == 'i'.code.toByte() &&
                b[i + 2] == 'n'.code.toByte() && b[i + 3] == 'g'.code.toByte()
            ) n++
            i++
        }
        return n
    }

    /** 基线：无 ID3 的纯帧拼接，原实现就能处理 */
    @Test
    fun stripsAllXingWhenNoId3Between() {
        val concat = oneFile(3, withId3 = false, fill = 0x11) + oneFile(3, withId3 = false, fill = 0x22)
        assertEquals("构造的样本应含两个 Xing", 2, countXing(concat))
        val out = Mp3Fix.stripXing(concat)
        assertEquals("两个 Xing 都应被剥掉", 0, countXing(out))
        assertEquals("应恰好少两帧", concat.size - 2 * FRAME_LEN, out.size)
    }

    /**
     * 真实形态：每个被拼接的文件自带 ID3v2 标签。
     * 这是原实现失败的用例 —— 走到第二个 ID3 时同步丢失，余下字节原样拷出，第二个 Xing 存活。
     */
    @Test
    fun stripsAllXingAcrossConcatenatedFilesWithId3() {
        val concat = oneFile(3, withId3 = true, fill = 0x11) +
            oneFile(3, withId3 = true, fill = 0x22) +
            oneFile(3, withId3 = true, fill = 0x33)
        assertEquals("构造的样本应含三个 Xing", 3, countXing(concat))
        val out = Mp3Fix.stripXing(concat)
        assertEquals("三个 Xing 都应被剥掉（原实现只剥掉第一个）", 0, countXing(out))
        // 音频帧一帧都不能少
        assertEquals("应恰好少三帧", concat.size - 3 * FRAME_LEN, out.size)
    }

    /** 中间夹一段无法识别的垃圾时，不能丢掉后面的真实音频 */
    @Test
    fun survivesUnparseableGarbageInTheMiddle() {
        val concat = oneFile(2, withId3 = false, fill = 0x11) +
            ByteArray(37) { 0x5A } +
            oneFile(2, withId3 = false, fill = 0x22)
        val out = Mp3Fix.stripXing(concat)
        assertTrue("音频帧不能被吞掉", out.size >= concat.size - 2 * FRAME_LEN - 37)
        assertEquals("两个 Xing 都应被剥掉", 0, countXing(out))
    }

    /** 没有 Xing 时零拷贝返回原数组 */
    @Test
    fun returnsSameArrayWhenNothingToStrip() {
        val plain = frame(xing = false) + frame(xing = false)
        assertTrue("无可剥帧时应原样返回", Mp3Fix.stripXing(plain) === plain)
    }
}
