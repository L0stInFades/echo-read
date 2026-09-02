package app.echoread

import app.echoread.core.Hash
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.net.Disposition
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetErrors
import app.echoread.core.net.StatusClass
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import app.echoread.tts.SpeechApi
import app.echoread.tts.Voices
import app.echoread.tts.backoffDelay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun pickTtsModelsModalityAndHeuristic() {
        val models = SpeechApi.pickTtsModels(
            json.parseToJsonElement(
                """{"data":[
                {"id":"openai/gpt-4o-mini-tts","name":"OpenAI: GPT-4o mini TTS","architecture":{"input_modalities":["text"],"output_modalities":["audio"]}},
                {"id":"fish-audio/s2-pro","name":"FishAudio: S2 Pro","architecture":{"input_modalities":["text"],"output_modalities":["speech"]},"supported_voices":null},
                {"id":"google/gemini-3.1-flash-tts-preview","architecture":{"input_modalities":["text"],"output_modalities":["speech"]},"supported_voices":["Zephyr","Puck",42]},
                {"id":"openai/gpt-4o","name":"OpenAI: GPT-4o","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
                {"id":"some/audio-chat","architecture":{"input_modalities":["audio"],"output_modalities":["audio"]}},
                {"id":"custom/my-tts","architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"name":"无 id 垃圾条目","architecture":{"input_modalities":["text"],"output_modalities":["speech"]}}
            ]}"""
            )
        )
        assertEquals(listOf("custom/my-tts", "fish-audio/s2-pro", "google/gemini-3.1-flash-tts-preview", "openai/gpt-4o-mini-tts"), models.map { it.id })
        assertEquals("OpenAI: GPT-4o mini TTS", models[3].name)
        assertEquals("google/gemini-3.1-flash-tts-preview", models[2].name)
        assertEquals(listOf("Zephyr", "Puck"), models[2].voices)
        assertNull(models[1].voices)
    }

    @Test
    fun pickTtsModelsHeuristicOnly() {
        val models = SpeechApi.pickTtsModels(json.parseToJsonElement("""{"data":[{"id":"tts-1"},{"id":"gpt-4o"},{"id":"fish-speech-1.5"},{"id":"whisper-1"}]}"""))
        assertEquals(listOf("fish-speech-1.5", "tts-1"), models.map { it.id })
        assertEquals(emptyList<Any>(), SpeechApi.pickTtsModels(null))
        assertEquals(emptyList<Any>(), SpeechApi.pickTtsModels(json.parseToJsonElement("""{"data":"junk"}""")))
    }

    @Test
    fun pickTtsModelsPricing() {
        val models = SpeechApi.pickTtsModels(
            json.parseToJsonElement(
                """{"data":[
                {"id":"hexgrad/kokoro-82m","name":"Kokoro","architecture":{"input_modalities":["text"],"output_modalities":["speech"]},"description":" 开源轻量 TTS ","pricing":{"prompt":"0.00000062","completion":"0"}},
                {"id":"google/gemini-3.1-flash-tts-preview","name":"Gemini TTS","architecture":{"input_modalities":["text"],"output_modalities":["speech"]},"pricing":{"prompt":"0.000001","completion":"0.00002"}}
            ]}"""
            )
        )
        assertEquals("开源轻量 TTS", models[1].description)
        assertEquals(0.00000062, models[1].promptPrice!!, 1e-12)
        assertNull(models[1].completionPrice)
        assertEquals(0.00002, models[0].completionPrice!!, 1e-12)
    }

    @Test
    fun describeVoices() {
        assertEquals("fish-audio/s2.1-pro-free", Voices.canonicalModelId("fish-audio/s2.1-pro-free:free"))
        val xiaoxiao = Voices.describeVoice("hexgrad/kokoro-82m", "zf_xiaoxiao")
        assertEquals(listOf("晓晓", "zh", "f"), listOf(xiaoxiao.label, xiaoxiao.lang, xiaoxiao.gender))
        val emma = Voices.describeVoice("hexgrad/kokoro-82m", "bf_emma")
        assertEquals(listOf("Emma", "en", "f", "英音"), listOf(emma.label, emma.lang, emma.gender, emma.note))
        val thalia = Voices.describeVoice("deepgram/aura-2", "aura-2-thalia-en")
        assertEquals(listOf("Thalia", "en", "f"), listOf(thalia.label, thalia.lang, thalia.gender))
        val jane = Voices.describeVoice("mistralai/voxtral-mini-tts-2603", "gb_jane_confident")
        assertEquals(listOf("Jane·自信", "en", "f", "英音"), listOf(jane.label, jane.lang, jane.gender, jane.note))
        val harper = Voices.describeVoice("microsoft/mai-voice-2", "en-US-Harper:MAI-Voice-2")
        assertEquals(listOf("Harper", "en", "f"), listOf(harper.label, harper.lang, harper.gender))
        val kore = Voices.describeVoice("google/gemini-3.1-flash-tts-preview", "Kore")
        assertEquals(listOf("multi", "f", "坚定"), listOf(kore.lang, kore.gender, kore.note))
        val raw = Voices.describeVoice("unknown/model", "nova")
        assertEquals("nova", raw.label)
        assertNull(raw.lang)
    }

    @Test
    fun catalogAndGroups() {
        assertEquals(54, Voices.catalogVoices("hexgrad/kokoro-82m").size)
        assertEquals(90, Voices.catalogVoices("deepgram/aura-2").size)
        assertEquals(30, Voices.catalogVoices("mistralai/voxtral-mini-tts-2603").size)
        assertEquals(30, Voices.catalogVoices("google/gemini-3.1-flash-tts-preview").size)
        assertEquals(0, Voices.catalogVoices("fish-audio/s2.1-pro-free:free").size)
        assertEquals(0, Voices.catalogVoices("minimax/speech-2.8-turbo").size)
        assertEquals(listOf("zm_yunxi"), Voices.catalogVoices("hexgrad/kokoro-82m", listOf("zm_yunxi")).map { it.id })
        val groups = Voices.groupVoices(Voices.catalogVoices("hexgrad/kokoro-82m"))
        assertEquals("zh", groups[0].lang)
        assertEquals(8, groups[0].voices.size)
        assertTrue(groups.all { it.voices.isNotEmpty() })
    }

    @Test
    fun defaultVoices() {
        assertEquals("zf_xiaoxiao", Voices.defaultVoiceFor("hexgrad/kokoro-82m"))
        assertEquals("nova", Voices.defaultVoiceFor("openai/tts-1-hd", listOf("nova", "shimmer")))
        assertEquals("", Voices.defaultVoiceFor("fish-audio/s2.1-pro-free:free"))
        assertEquals("audiobook_female_1", Voices.defaultVoiceFor("minimax/speech-2.8-turbo"))
        assertEquals("Kore", Voices.defaultVoiceFor("google/gemini-3.1-flash-tts-preview"))
        assertTrue(Voices.modelHints("fish-audio/s9-future")?.voiceOptional == true)
    }

    @Test
    fun speechBodyDialects() {
        val or = OpenAISpeechConfig(baseUrl = "https://openrouter.ai/api/v1", apiKey = "k", model = "hexgrad/kokoro-82m", voice = "zf_xiaoxiao", instructions = "温柔一点", format = "opus")
        val body = SpeechApi.buildSpeechBody(or, "你好")
        assertEquals("mp3", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("温柔一点", body["provider"]!!.jsonObject["options"]!!.jsonObject["openai"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
        assertNull(body["instructions"])
        val gemini = SpeechApi.buildSpeechBody(or.copy(model = "google/gemini-3.1-flash-tts-preview", voice = "Kore"), "hi")
        assertEquals("pcm", gemini["response_format"]!!.jsonPrimitive.content)
        val fish = SpeechApi.buildSpeechBody(or.copy(model = "fish-audio/s1", voice = " "), "hi")
        assertFalse(fish.containsKey("voice"))
        val oa = SpeechApi.buildSpeechBody(or.copy(baseUrl = "https://api.openai.com/v1"), "hi")
        assertEquals("opus", oa["response_format"]!!.jsonPrimitive.content)
        assertEquals("温柔一点", oa["instructions"]!!.jsonPrimitive.content)
        assertNull(oa["provider"])
    }

    @Test
    fun pcmToWav() {
        assertEquals(SpeechApi.PcmParams(24000, 1), SpeechApi.parsePcmParams("audio/pcm;rate=24000;channels=1"))
        assertEquals(SpeechApi.PcmParams(24000, 1), SpeechApi.parsePcmParams("audio/pcm"))
        assertEquals(SpeechApi.PcmParams(24000, 1), SpeechApi.parsePcmParams("audio/pcm;bitrate=64000"))
        val wav = SpeechApi.pcmToWav(byteArrayOf(1, 2, 3, 4), 24000, 1)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, bb.getInt(24))
        assertEquals(1.toShort(), bb.getShort(22))
        assertEquals(4, bb.getInt(40))
    }

    @Test
    fun headers() {
        val cfg = OpenAISpeechConfig(baseUrl = "https://openrouter.ai/api/v1", apiKey = "sk-or-test", model = "hexgrad/kokoro-82m", voice = "zf_xiaoxiao")
        val or = SpeechApi.buildHeaders(cfg)
        assertEquals("Bearer sk-or-test", or["Authorization"])
        assertEquals("Lector", or["X-OpenRouter-Title"])
        assertTrue(or["HTTP-Referer"]!!.startsWith("https://"))
        val oa = SpeechApi.buildHeaders(cfg.copy(baseUrl = "https://api.openai.com/v1"))
        assertEquals("Bearer sk-or-test", oa["Authorization"])
        assertNull(oa["X-OpenRouter-Title"])
        assertFalse(SpeechApi.isOpenRouterBase("https://evil.com/openrouter.ai/api/v1"))
        assertTrue(SpeechApi.isOpenRouterBase("https://api.openrouter.ai/v1"))
    }

    @Test
    fun backoff() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map { backoffDelay(it) { 0.5 } })
        assertEquals(800L, backoffDelay(0) { 0.0 })
        assertEquals(1200L, backoffDelay(0) { 1.0 })
        assertEquals(24000L, backoffDelay(5) { 0.0 })
        assertEquals(36000L, backoffDelay(5) { 1.0 })
    }

    /**
     * 处置真值表，取代旧的布尔 isFatalSpeechError。
     * 关键差异：400 类不再是「整本书致命」（一段被审核拦下不该停掉整本），
     * 而 UnknownHostException 这类传输层异常不再被判成「可无限重试」。
     */
    @Test
    fun dispositionTruthTable() {
        fun http(s: Int) = NetErrors.fromHttp(s, null, endpoint = "https://x.test/v1/audio/speech")
        // 配置坏了：重试无意义，停掉会话
        for (s in listOf(401, 402, 404)) assertEquals("$s 应停会话", Disposition.STOP_SESSION, http(s).disposition())
        // 这一段不被接受：跳过它，继续读整本书
        for (s in listOf(400, 413, 422, 451)) assertEquals("$s 应跳段", Disposition.SKIP_SEGMENT, http(s).disposition())
        // 对方的问题 / 限流：值得退避重试
        for (s in listOf(429, 408, 500, 502, 503)) assertEquals("$s 应重试", Disposition.RETRY, http(s).disposition())
        // 501 Not Implemented 永远不会自愈
        assertEquals(Disposition.SKIP_SEGMENT, http(501).disposition())
        // 没网就别重试；有网的连不上值得重试
        val dns = NetErrors.fromThrowable(UnknownHostException("no host"))
        assertEquals(Disposition.STOP_SESSION, dns.disposition(online = false))
        assertEquals(Disposition.RETRY, dns.disposition(online = true))
    }

    /** 异常分类顺序：子类必须排在父类前，否则 SocketTimeout/SSL 三兄弟会被父类吞掉 */
    @Test
    fun throwableClassification() {
        assertEquals(NetCategory.TIMEOUT, NetErrors.fromThrowable(SocketTimeoutException("t")).category)
        // okhttp 的 callTimeout 抛的是 InterruptedIOException（SocketTimeoutException 的父类）
        assertEquals(NetCategory.TIMEOUT, NetErrors.fromThrowable(InterruptedIOException("call timeout")).category)
        assertEquals(NetCategory.CONNECTIVITY, NetErrors.fromThrowable(UnknownHostException("h")).category)
        assertEquals(NetCategory.CONNECTIVITY, NetErrors.fromThrowable(ConnectException("refused")).category)
        assertEquals(NetCategory.TLS, NetErrors.fromThrowable(SSLHandshakeException("bad cert")).category)
        assertEquals(NetCategory.TLS, NetErrors.fromThrowable(SSLPeerUnverifiedException("nope")).category)
        assertEquals(NetCategory.CONNECTIVITY, NetErrors.fromThrowable(IOException("broken")).category)
    }

    /** 4xx / 5xx / 无响应 必须是三种可见地不同的形态 —— 这是用户提的原始诉求 */
    @Test
    fun statusClassIsAlwaysVisible() {
        assertEquals(StatusClass.CLIENT, NetErrors.fromHttp(429, null).statusClass)
        assertEquals(StatusClass.SERVER, NetErrors.fromHttp(503, null).statusClass)
        assertEquals(StatusClass.NONE, NetErrors.fromThrowable(SocketTimeoutException("t")).statusClass)
        // 标题里永远找得到状态码或「无响应」
        assertTrue(NetErrors.fromHttp(503, null).headline().contains("503"))
        assertTrue(NetErrors.fromHttp(401, null).headline().contains("401"))
        assertTrue(NetErrors.fromThrowable(SocketTimeoutException("t")).headline().contains("无响应"))
        // 本地前置校验绝不伪造状态码
        assertEquals(null, NetErrors.config(missingKey = true).status)
        assertEquals(Disposition.STOP_SESSION, NetErrors.config(missingKey = true).disposition())
    }

    /** 服务商原文必须留下来，尤其是 401/402/404/429 —— 旧实现恰好在这四个上把它丢了 */
    @Test
    fun providerMessageSurvives() {
        val e = NetErrors.fromHttp(401, """{"error":{"message":"Key disabled by org policy","code":"key_disabled"}}""")
        assertEquals("Key disabled by org policy", e.providerMessage)
        assertEquals("key_disabled", e.providerCode)
        assertTrue(e.detail().contains("Key disabled by org policy"))
        // 另外四种真实世界里的错误体形状
        assertEquals("boom", NetErrors.parseProviderError("""{"message":"boom"}""").first)
        assertEquals("boom", NetErrors.parseProviderError("""{"detail":"boom"}""").first)
        assertEquals("boom", NetErrors.parseProviderError("""{"error":"boom"}""").first)
        // HTML 网关页：抓 <title>
        assertEquals("502 Bad Gateway", NetErrors.parseProviderError("<html><title>502 Bad Gateway</title></html>").first)
    }

    /** Key 绝不能出现在可复制的诊断信息里 */
    @Test
    fun redactionStripsSecrets() {
        val r = NetErrors.redact("""{"api_key":"sk-or-v1-abcdefghijklmnop","Authorization":"Bearer sk-abcdefghijklmnop"}""")!!
        assertFalse("完整 key 不得出现", r.contains("abcdefghijklmnop"))
        assertEquals(null, NetErrors.redact("  "))
        // 端点上的 query 也要去掉（可能带 key）
        assertEquals("https://x.test/v1/models", NetErrors.cleanEndpoint("https://x.test/v1/models?key=secret"))
    }

    @Test
    fun retryAfterIsHonoured() {
        assertEquals(12L, NetErrors.parseRetryAfter("12"))
        assertEquals(null, NetErrors.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(null, NetErrors.parseRetryAfter(null))
        assertEquals(30L, NetErrors.fromHttp(429, null, retryAfterHeader = "30").retryAfterSec)
    }


    /**
     * 200 + 非音频（门户认证页 / 代理拦截）必须当场报错，不能落盘。
     * 实测教训：旧实现把 HTML 当音频缓存下来，用户看到的是三十秒后 Media3 的
     * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED，而且缓存命中后连请求都不再发，永不自愈。
     */
    @Test
    fun rejectsNonAudioPayload() {
        val html = "<html><head><title>Login</title></head></html>".toByteArray()
        assertFalse(SpeechApi.looksLikeAudio(html, "text/html"))
        assertFalse(SpeechApi.looksLikeAudio(html, "audio/mpeg"))          // 声称是音频也不认
        assertFalse(SpeechApi.looksLikeAudio("""{"error":{"message":"x"}}""".toByteArray(), "application/json"))
        // 真音频要放行
        assertTrue(SpeechApi.looksLikeAudio(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(32), "audio/mpeg"))
        assertTrue(SpeechApi.looksLikeAudio("ID3".toByteArray() + ByteArray(32), "audio/mpeg"))
        assertTrue(SpeechApi.looksLikeAudio("RIFF0000WAVE".toByteArray() + ByteArray(32), "audio/wav"))
        assertTrue(SpeechApi.looksLikeAudio("OggS".toByteArray() + ByteArray(32), "audio/ogg"))
        // PCM 裸流没有魔数，靠 Content-Type 放行
        assertTrue(SpeechApi.looksLikeAudio(ByteArray(64) { 7 }, "audio/pcm;rate=24000"))
        assertFalse(SpeechApi.looksLikeAudio(ByteArray(4), "audio/mpeg"))  // 太短
    }

    @Test
    fun cyrb53MatchesWeb() {
        // 与网页版 cyrb53('hello') 输出逐位一致
        assertEquals("bfb06f3a63cd7226", Hash.cyrb53("hello"))
        assertEquals("501c2ba782c97901", Hash.cyrb53("a"))
        assertEquals("f511e1d83a006a07", Hash.cyrb53("深夜书屋（示例）"))
        assertTrue(Hash.cyrb53("a") != Hash.cyrb53("b"))
        assertEquals(12, Hash.nanoid().length)
    }
}
