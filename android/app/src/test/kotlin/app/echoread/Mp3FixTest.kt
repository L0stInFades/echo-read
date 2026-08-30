package app.echoread

import app.echoread.tts.Mp3Fix
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

    @Test
    fun `非 MP3 字节不被误判`() {
        val wav = "RIFF....WAVEfmt ".toByteArray()
        assertFalse(Mp3Fix.looksLikeMp3(wav))
        assertSame(wav, Mp3Fix.stripXing(wav))
        assertFalse(Mp3Fix.looksLikeMp3(ByteArray(0)))
    }
}
