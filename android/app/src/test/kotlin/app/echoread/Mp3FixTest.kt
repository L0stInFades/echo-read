package app.echoread

import app.echoread.tts.Mp3Fix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样本：OpenRouter 上 Kokoro 82M 真实返回的前 80 KB —— 两段拼接、各带 Xing 头（偏移 13 与 68461） */
class Mp3FixTest {
    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/kokoro-concat.mp3")!!.readBytes()

    private fun countTag(b: ByteArray, tag: String): Int {
        var n = 0
        val t = tag.toByteArray()
        outer@ for (i in 0..b.size - t.size) {
            for (j in t.indices) if (b[i + j] != t[j]) continue@outer
            n++
        }
        return n
    }

    @Test
    fun `拼接 MP3 的每个 Xing 帧都被剥掉，其余字节原样保留`() {
        val src = fixture()
        assertTrue(Mp3Fix.looksLikeMp3(src))
        assertEquals(2, countTag(src, "Xing"))
        val out = Mp3Fix.stripXing(src)
        assertEquals(0, countTag(out, "Xing"))
        // 两个 Xing 帧各占一帧长度：结果必然更短，且首帧之后的正常音频帧完整保留
        assertTrue(out.size < src.size)
        assertTrue(src.size - out.size < 2 * 600)
        // 输出仍是合法 MP3 且尾部（可能被截断的半帧）与输入尾部一致
        assertTrue(Mp3Fix.looksLikeMp3(out))
        assertEquals(src.takeLast(64), out.takeLast(64))
    }

    @Test
    fun `没有 Xing 的流零拷贝返回`() {
        val src = fixture()
        val clean = Mp3Fix.stripXing(src)
        assertSame(clean, Mp3Fix.stripXing(clean))
    }

    /** MPEG-1 Layer III、128 kbps、44.1 kHz、立体声的一帧（417 字节）；[tagAt] 处写入 VBR 标签 */
    private fun frame(crc: Boolean, tagAt: Int = -1, tag: String = "Info"): ByteArray {
        val f = ByteArray(417)
        f[0] = 0xFF.toByte()
        f[1] = (if (crc) 0xFA else 0xFB).toByte() // 最低位是 protection_bit：0 = 帧头后跟 2 字节 CRC
        f[2] = 0x90.toByte()
        if (tagAt >= 0) tag.toByteArray().copyInto(f, tagAt)
        return f
    }

    /** 回归（21）：带 CRC 的帧，标签在帧头 4 + CRC 2 + 边信息 32 = 38 处；旧实现只看 36，这种 Info 帧剥不掉 */
    @Test
    fun `带 CRC 的 Info 帧也被剥掉`() {
        val audio = frame(crc = true)
        assertArrayEquals(audio + audio, Mp3Fix.stripXing(frame(crc = true, tagAt = 38) + audio + audio))
        // 不管 CRC、仍把标签写在 36 的帧：ExoPlayer 正是在 36 找标签，照剥不误
        assertArrayEquals(audio + audio, Mp3Fix.stripXing(frame(crc = true, tagAt = 36, tag = "Xing") + audio + audio))
        // 不带 CRC 的帧只认 36：38 处碰巧有这几个字节不算，原样零拷贝返回
        val plain = frame(crc = false, tagAt = 38) + frame(crc = false)
        assertSame(plain, Mp3Fix.stripXing(plain))
    }

    @Test
    fun `非 MP3 字节不被误判`() {
        val wav = "RIFF....WAVEfmt ".toByteArray()
        assertFalse(Mp3Fix.looksLikeMp3(wav))
        assertSame(wav, Mp3Fix.stripXing(wav))
        assertFalse(Mp3Fix.looksLikeMp3(ByteArray(0)))
    }
}
