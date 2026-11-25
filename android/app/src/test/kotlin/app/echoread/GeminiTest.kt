package app.echoread

import app.echoread.core.GeminiTtsConfig
import app.echoread.core.GeminiVoiceEntry
import app.echoread.core.Hash
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings
import app.echoread.core.net.Disposition
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetErrors
import app.echoread.core.net.NetException
import app.echoread.data.SettingsStore
import app.echoread.tts.GeminiTts
import app.echoread.tts.SpeechApi
import app.echoread.tts.SynthTarget
import app.echoread.tts.TtsEngine
import app.echoread.tts.Voices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Gemini 原生接口：请求体逐字段对照官方「Single-speaker TTS」REST 示例，
 * 响应 / 错误样本取自官方 API 参考与 2026-11-25 对线上端点的实测。
 */
class GeminiTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 与 SettingsStore 相同的 Json 配置（迁移/降级行为必须用同一套参数验证） */
    private val storeJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun wav(samples: Int = 240) = SpeechApi.pcmToWav(ByteArray(samples * 2) { (it % 11).toByte() }, 24000, 1)

    /** 官方 API 参考里 Interaction 资源的形状（steps → model_output → content[type=audio]） */
    private fun interaction(vararg audio: Pair<String, String>, status: String = "completed") = """
        {"id":"v1_test","object":"interaction","model":"gemini-3.8-flash-tts","status":"$status",
         "created":"2026-11-25T12:00:00Z","updated":"2026-11-25T12:00:01Z",
         "steps":[{"type":"model_output","content":[${audio.joinToString(",") { (data, mime) -> """{"type":"audio","data":"$data","mime_type":"$mime"}""" }}]}],
         "usage":{"total_input_tokens":6,"total_output_tokens":50,"total_tokens":56}}
    """.trimIndent()

    /** 2026-11-25 实测：带一个无效 Key 调 POST /v1beta/interactions 的原样响应（HTTP 400，外面包着一层数组） */
    private val liveInvalidKeyBody = """
        [{
          "error": {
            "code": 400,
            "message": "API key not valid. Please pass a valid API key.",
            "status": "INVALID_ARGUMENT",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                "reason": "API_KEY_INVALID",
                "domain": "googleapis.com",
                "metadata": {
                  "service": "generativelanguage.googleapis.com"
                }
              },
              {
                "@type": "type.googleapis.com/google.rpc.LocalizedMessage",
                "locale": "en-US",
                "message": "API key not valid. Please pass a valid API key."
              }
            ]
          }
        }
        ]
    """.trimIndent()

    private val endpoint = GeminiTts.interactionsUrl()

    /* ---------------- 请求体 ---------------- */

    @Test
    fun interactionBodyMatchesOfficialSchema() {
        val cfg = GeminiTtsConfig(apiKey = "k", model = "gemini-3.8-flash-tts", voice = "Kore", style = "cheerful and friendly")
        val body = GeminiTts.buildInteractionBody(cfg, "Have a wonderful day!")
        assertEquals("gemini-3.8-flash-tts", body["model"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user_input", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        val content = input[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Have a wonderful day!", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val ann = content[0].jsonObject["annotations"]!!.jsonArray[0].jsonObject
        assertEquals("speech_metadata", ann["type"]!!.jsonPrimitive.content)
        assertEquals("cheerful and friendly", ann["style"]!!.jsonPrimitive.content)
        // 单人：speech_config 是数组；非流式默认 WAV，所以 response_format 只写 type
        assertEquals("""{"type":"audio"}""", body["response_format"].toString())
        val speech = body["generation_config"]!!.jsonObject["speech_config"]
        assertTrue(speech is JsonArray)
        assertEquals("Kore", speech!!.jsonArray[0].jsonObject["voice"]!!.jsonPrimitive.content)
        // 书的正文不留在服务端
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        // 语气（舞台说明）绝不拼进正文 —— 3.8 会把它原样念出来
        assertFalse(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("cheerful"))
    }

    @Test
    fun interactionBodyOmitsEmptyParts() {
        val body = GeminiTts.buildInteractionBody(GeminiTtsConfig(apiKey = "k", voice = "  ", style = " "), "你好")
        val part = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertNull("style 为空不发 annotations（官方：先用空 style）", part["annotations"])
        assertNull("voice 为空交给服务端默认", body["generation_config"])
        // 自建音色 ID 原样透传
        val custom = GeminiTts.buildInteractionBody(GeminiTtsConfig(apiKey = "k", voice = "voice_abc123"), "x")
        assertEquals("voice_abc123", custom["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0].jsonObject["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun modelIdIsNormalized() {
        assertEquals(GeminiTts.MODEL_FLASH, GeminiTts.modelOf(GeminiTtsConfig(model = "")))
        assertEquals(GeminiTts.MODEL_FLASH, GeminiTts.modelOf(GeminiTtsConfig(model = "models/gemini-3.8-flash-tts")))
        assertEquals(GeminiTts.MODEL_FLASH_LITE, GeminiTts.modelOf(GeminiTtsConfig(model = " google/gemini-3.8-flash-lite-tts ")))
        assertEquals("gemini-3.1-flash-tts-preview", GeminiTts.modelOf(GeminiTtsConfig(model = "gemini-3.1-flash-tts-preview")))
        assertEquals(mapOf("x-goog-api-key" to "AIzaTest"), GeminiTts.buildHeaders(GeminiTtsConfig(apiKey = " AIzaTest ")))
    }

    /* ---------------- 响应解析 ---------------- */

    @Test
    fun unaryWavIsReturnedAsIs() {
        val w = wav()
        val root = json.parseToJsonElement(interaction(b64(w) to "audio/wav"))
        val block = GeminiTts.pickAudio(root)!!
        assertEquals("audio/wav", block.mimeType)
        assertArrayEquals("3.8 非流式默认就是完整 WAV，不能再封一层头", w, GeminiTts.toPlayable(block))
    }

    @Test
    fun l16IsWrappedIntoWav() {
        val pcm = ByteArray(960) { (it % 13).toByte() }
        val root = json.parseToJsonElement(
            """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"audio","data":"${b64(pcm)}","mime_type":"audio/l16","sample_rate":16000,"channels":1}]}]}"""
        )
        val out = GeminiTts.toPlayable(GeminiTts.pickAudio(root)!!)!!
        assertEquals(44 + pcm.size, out.size)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals(16000, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        // 没标采样率的 L16 按官方默认 24 kHz
        val bare = GeminiTts.toPlayable(GeminiTts.AudioBlock(b64(pcm), "audio/l16", null, null))!!
        assertEquals(24000, ByteBuffer.wrap(bare).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        // 什么都没标、也没有容器头：只可能是默认的 L16 裸流
        assertEquals("RIFF", String(GeminiTts.toPlayable(GeminiTts.AudioBlock(b64(pcm), null, null, null))!!, 0, 4, Charsets.US_ASCII))
        // 电话格式我们从不请求，回来了就是无法播放
        assertNull(GeminiTts.toPlayable(GeminiTts.AudioBlock(b64(pcm), "audio/mulaw", 8000, 1)))
    }

    @Test
    fun picksLastAudioBlockOnly() {
        val a = wav(100)
        val b = wav(200)
        val root = json.parseToJsonElement(
            """{"steps":[
                {"type":"thought","content":[{"type":"audio","data":"${b64(a)}"}]},
                {"type":"model_output","content":[{"type":"text","text":"hi"},{"type":"audio","data":"${b64(a)}","mime_type":"audio/wav"}]},
                {"type":"model_output","content":[{"type":"audio","data":"${b64(b)}","mime_type":"audio/wav"}]}
            ]}"""
        )
        assertArrayEquals(b, GeminiTts.toPlayable(GeminiTts.pickAudio(root)!!))
        // 早期测试版的顶层 outputs[] 形态也认
        val legacy = json.parseToJsonElement("""{"outputs":[{"type":"audio","data":"${b64(a)}","mime_type":"audio/wav"}]}""")
        assertArrayEquals(a, GeminiTts.toPlayable(GeminiTts.pickAudio(legacy)!!))
    }

    @Test
    fun noAudioIsDescribed() {
        val root = json.parseToJsonElement(
            """{"status":"failed","steps":[{"type":"model_output","content":[{"type":"text","text":"sorry"}]}],"error":{"code":"safety","message":"blocked"}}"""
        )
        assertNull(GeminiTts.pickAudio(root))
        val (msg, code) = GeminiTts.describeNoAudio(root)
        assertTrue(msg, msg.contains("status=failed") && msg.contains("text") && msg.contains("blocked"))
        assertEquals("safety", code)
        assertNull(GeminiTts.pickAudio(json.parseToJsonElement("[]")))
    }

    /* ---------------- 音色目录 ---------------- */

    @Test
    fun voicesPageParsing() {
        val (voices, next) = GeminiTts.parseVoices(
            json.parseToJsonElement(
                """{"voices":[
                    {"id":"voice_abc123","type":"prompted","display_name":"我的旁白","model":"gemini-3.8-flash-tts","prompted":{"input":"沉稳的中年男声"}},
                    {"id":"Kore","type":"prebuilt","display_name":"Kore"},
                    {"id":"lib-cmn-1","type":"VOICE_TYPE_PREBUILT","display_name":"Mei","language_code":"cmn-CN","accent":"Mandarin","gender":"FEMALE","pitch":"PITCH_MEDIUM","context":"Audiobook","description":"Warm narrator"},
                    {"type":"replicated","key":"voicekey_xyz","display_name":"stateless"}
                ],"next_page_token":"p2"}"""
            )
        )
        assertEquals("p2", next)
        assertEquals(listOf("voice_abc123", "Kore", "lib-cmn-1"), voices.map { it.id })
        val mei = voices[2]
        assertEquals("prebuilt", mei.type)
        assertEquals("medium", mei.pitch)
        assertEquals("female", mei.gender)
        assertNull(GeminiTts.parseVoices(json.parseToJsonElement("""{"voices":[]}""")).second)
    }

    @Test
    fun geminiCatalogOrderingAndGroups() {
        val remote = listOf(
            GeminiVoiceEntry("Kore", "Kore", type = "prebuilt"),
            GeminiVoiceEntry("lib-cmn-1", "Mei", type = "prebuilt", languageCode = "cmn-CN", accent = "Mandarin", gender = "female", context = "Audiobook"),
            GeminiVoiceEntry("lib-yue-1", "Wing", type = "prebuilt", languageCode = "yue-HK", gender = "male"),
            GeminiVoiceEntry("lib-en-1", "Ava", type = "prebuilt", languageCode = "en-US"),
            GeminiVoiceEntry("voice_abc123", "我的旁白", type = "prompted")
        )
        val catalog = Voices.geminiCatalog(remote)
        assertEquals("自建音色排最前", "voice_abc123", catalog[0].id)
        assertEquals("mine", catalog[0].lang)
        assertEquals(30 + 4, catalog.size) // 30 个精选 + 3 个扩展 + 1 个自建，Kore 不重复
        val mei = catalog.first { it.id == "lib-cmn-1" }
        assertEquals(listOf("zh", "f", "Mandarin·Audiobook"), listOf(mei.lang, mei.gender, mei.note))
        assertEquals("粤语也归中文", "zh", catalog.first { it.id == "lib-yue-1" }.lang)
        val kore = catalog.first { it.id == "Kore" }
        assertEquals(listOf("multi", "坚定"), listOf(kore.lang, kore.note))
        val groups = Voices.groupVoices(catalog).map { it.lang }
        assertEquals(listOf("mine", "zh", "multi", "en"), groups)
        // 不联网时也有 30 个精选音色可选
        assertEquals(30, Voices.geminiCatalog(emptyList()).size)
    }

    @Test
    fun langGroups() {
        assertEquals("zh", Voices.langGroupOf("cmn-Hans-CN"))
        assertEquals("zh", Voices.langGroupOf("zh_TW"))
        assertEquals("en", Voices.langGroupOf("en-GB"))
        assertEquals("ko", Voices.langGroupOf("ko-KR"))
        assertEquals("other", Voices.langGroupOf("sw-KE"))
        assertEquals("other", Voices.langGroupOf(null))
        assertEquals("other", Voices.langGroupOf("multi"))
    }

    /* ---------------- 错误分类（Google 的错误形状） ---------------- */

    @Test
    fun liveInvalidKeyIsAuthNotSkip() {
        val e = NetErrors.fromHttp(400, liveInvalidKeyBody, endpoint = endpoint)
        assertEquals("400 + API_KEY_INVALID 必须是鉴权失败", NetCategory.AUTH, e.category)
        assertEquals(Disposition.STOP_SESSION, e.disposition())
        assertEquals("API key not valid. Please pass a valid API key.", e.providerMessage)
        assertEquals("API_KEY_INVALID", e.providerCode)
        assertTrue(e.headline().contains("400"))
        // 普通的 400（不是 Key 的问题）仍然只跳过这一段
        assertEquals(Disposition.SKIP_SEGMENT, NetErrors.fromHttp(400, """{"error":{"code":"invalid_request","message":"bad voice"}}""", endpoint = endpoint).disposition())
    }

    @Test
    fun permissionDeniedAndPrecondition() {
        val denied = NetErrors.fromHttp(
            403, """{"error":{"code":403,"message":"Permission denied on the service.","status":"PERMISSION_DENIED"}}""", endpoint = endpoint
        )
        assertEquals(NetCategory.AUTH, denied.category)
        val region = NetErrors.fromHttp(
            400, """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}""", endpoint = endpoint
        )
        assertEquals(NetCategory.PRECONDITION, region.category)
        assertEquals("地区不支持：跳段只会把整本书跳空", Disposition.STOP_SESSION, region.disposition())
        assertEquals(NetCategory.PRECONDITION, NetErrors.fromHttp(400, """{"error":{"code":"failed_precondition","message":"billing disabled"}}""").category)
        assertTrue(region.advice().contains("地区"))
    }

    @Test
    fun quotaVersusRateLimit() {
        // Interactions API 的错误码（官方 API errors 页）
        val daily = NetErrors.fromHttp(429, """{"error":{"code":"quota_exceeded","message":"You have exceeded your daily quota."}}""", endpoint = endpoint)
        assertEquals(NetCategory.QUOTA, daily.category)
        assertEquals(Disposition.STOP_SESSION, daily.disposition())
        assertTrue("Google 的配额文案要说清楚何时重置", daily.advice().contains("太平洋时间"))
        val rpm = NetErrors.fromHttp(429, """{"error":{"code":"rate_limit_exceeded","message":"slow down"}}""", endpoint = endpoint)
        assertEquals(NetCategory.RATE_LIMIT, rpm.category)
        assertEquals(Disposition.RETRY, rpm.disposition())
        // Google RPC 形状：按天配额 vs 按分钟配额，等待时长只写在响应体的 RetryInfo 里
        fun rpc(quotaId: String) = """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_requests","quotaId":"$quotaId"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"41s"}]}}"""
        assertEquals(NetCategory.QUOTA, NetErrors.fromHttp(429, rpc("GenerateRequestsPerDayPerProjectPerModel-FreeTier"), endpoint = endpoint).category)
        val perMinute = NetErrors.fromHttp(429, rpc("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"), endpoint = endpoint)
        assertEquals(NetCategory.RATE_LIMIT, perMinute.category)
        assertEquals(41L, perMinute.retryAfterSec)
        // Retry-After 头优先于响应体
        assertEquals(5L, NetErrors.fromHttp(429, rpc("PerMinute"), retryAfterHeader = "5").retryAfterSec)
        // OpenAI 的「没余额」也是 429，同样重试不好
        assertEquals(NetCategory.QUOTA, NetErrors.fromHttp(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""").category)
        // 非 Google 端点的配额文案不提太平洋时间
        assertFalse(NetErrors.fromHttp(402, null, endpoint = "https://openrouter.ai/api/v1/audio/speech").advice().contains("太平洋"))
    }

    @Test
    fun googleErrorBodyIsReadPast512Bytes() {
        // reason / quotaId 常常落在 512 字节之后：分类必须看到它们
        val pad = "x".repeat(700)
        val body = """{"error":{"code":429,"message":"$pad","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        assertEquals(NetCategory.QUOTA, NetErrors.fromHttp(429, body).category)
        assertTrue("展示用的片段仍然截短", NetErrors.fromHttp(429, body).bodySnippet!!.length <= 520)
    }

    /* ---------------- 设置迁移与缓存键 ---------------- */

    @Test
    fun settingsMigrationAndDowngrade() {
        val old = """{"provider":"openai-speech","openai":{"baseUrl":"https://openrouter.ai/api/v1","apiKey":"k","model":"hexgrad/kokoro-82m","voice":"zf_xiaoxiao"},"rate":1.25}"""
        val s = storeJson.decodeFromString(TtsSettings.serializer(), old)
        assertEquals(TtsProvider.OPENAI, s.provider)
        assertEquals("0.3.x 的存档没有 gemini 键：取默认值", GeminiTtsConfig(), s.gemini)
        val g = s.copy(provider = TtsProvider.GEMINI, gemini = GeminiTtsConfig(apiKey = "AIzaX", voice = "Puck", style = "calm"))
        val encoded = storeJson.encodeToString(TtsSettings.serializer(), g)
        assertTrue(encoded.contains("\"provider\":\"gemini\""))
        assertEquals(g, storeJson.decodeFromString(TtsSettings.serializer(), encoded))
        // 读档守卫不得把 Gemini 打回 OpenRouter
        assertEquals(TtsProvider.GEMINI, SettingsStore.sanitizeTts(g).provider)
        assertEquals(GeminiTts.MODEL_FLASH, SettingsStore.sanitizeTts(g.copy(gemini = g.gemini.copy(model = " "))).gemini.model)
        // 降级安装：旧版本读到不认识的引擎名，回落默认值而不是崩溃
        assertEquals(TtsProvider.OPENAI, storeJson.decodeFromString(TtsSettings.serializer(), """{"provider":"some-future-engine"}""").provider)
    }

    @Test
    fun openAiCacheKeysUnchangedFromV03() {
        val c = OpenAISpeechConfig(model = "hexgrad/kokoro-82m", voice = "zf_xiaoxiao", format = "mp3", instructions = "温柔")
        // 0.3.x 的写法：Hash.cyrb53("v2|${settings.provider}|${c.model}|${c.voice}|${c.format}|${c.instructions}|$text")
        val legacy = Hash.cyrb53("v2|OPENAI|hexgrad/kokoro-82m|zf_xiaoxiao|mp3|温柔|你好")
        assertEquals("升级后已缓存的语音必须照常命中", legacy, TtsEngine.cacheKeyOf("你好", SynthTarget.OpenAI(c)))
        // 0.5.0：同一份配置迁移成「服务」之后，解析出来的目标仍然落在同一个缓存键上
        val migrated = SettingsStore.sanitizeTts(TtsSettings(openai = c.copy(apiKey = "k")))
        assertEquals("迁移到服务目录后缓存键不变", legacy, TtsEngine.cacheKeyOf("你好", SynthTarget.of(migrated)!!))
        val g = SynthTarget.Gemini(GeminiTtsConfig(apiKey = "k"))
        assertNotEquals(TtsEngine.cacheKeyOf("你好", g), TtsEngine.cacheKeyOf("你好", SynthTarget.Gemini(GeminiTtsConfig(apiKey = "k", style = "calm"))))
        assertNotEquals(TtsEngine.cacheKeyOf("你好", g), TtsEngine.cacheKeyOf("你好", SynthTarget.Gemini(GeminiTtsConfig(apiKey = "k", model = GeminiTts.MODEL_FLASH_LITE))))
        assertEquals("换 Key 不该让缓存失效", TtsEngine.cacheKeyOf("你好", g), TtsEngine.cacheKeyOf("你好", SynthTarget.Gemini(GeminiTtsConfig(apiKey = "other"))))
        val gem = SettingsStore.sanitizeTts(TtsSettings(provider = TtsProvider.GEMINI, gemini = GeminiTtsConfig(apiKey = "k")))
        assertEquals("迁移后的 Gemini 服务与 0.4.x 同一个缓存键", TtsEngine.cacheKeyOf("你好", g), TtsEngine.cacheKeyOf("你好", SynthTarget.of(gem)!!))
        assertEquals("GEMINI", SynthTarget.of(TtsSettings(provider = TtsProvider.GEMINI))!!.cacheNs)
        assertNull(SynthTarget.of(TtsSettings(provider = TtsProvider.SYSTEM)))
        assertNull(SynthTarget.of(SettingsStore.sanitizeTts(TtsSettings(provider = TtsProvider.SYSTEM))))
    }

    /* ---------------- 端到端：真实的 OkHttp 请求打到本机假服务 ---------------- */

    private fun withServer(block: (MockWebServer, String) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, server.url("/v1beta").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun synthesizeEndToEnd() = withServer { server, base ->
        val w = wav(480)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(interaction(b64(w) to "audio/wav")))
        val bytes = runBlocking {
            GeminiTts.synthesize(GeminiTtsConfig(apiKey = "test-key", model = GeminiTts.MODEL_FLASH_LITE, voice = "Puck", style = "calm and relaxed"), "你好，世界", base)
        }
        assertArrayEquals(w, bytes)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/interactions", req.path)
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertNull("Gemini 不用 Bearer", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val sent = json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals(GeminiTts.MODEL_FLASH_LITE, sent["model"]!!.jsonPrimitive.content)
        assertEquals("你好，世界", sent["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun synthesizeErrorsEndToEnd() = withServer { server, base ->
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(liveInvalidKeyBody))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"status":"completed","steps":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html><title>Portal</title></html>"))
        val cfg = GeminiTtsConfig(apiKey = "bogus")
        fun failure(): NetException = try {
            runBlocking { GeminiTts.synthesize(cfg, "hi", base) }
            fail("应当失败")
            throw IllegalStateException()
        } catch (e: NetException) {
            e
        }
        val auth = failure().error
        assertEquals(NetCategory.AUTH, auth.category)
        assertEquals(400, auth.status)
        assertEquals(Disposition.STOP_SESSION, auth.disposition())
        val empty = failure().error
        assertEquals("200 但没有音频：真实状态码，类别是「音频为空」", NetCategory.EMPTY_AUDIO, empty.category)
        assertEquals(200, empty.status)
        val portal = failure().error
        assertEquals(NetCategory.PARSE, portal.category)
        // 没填 Key：本地就拦下，不发请求、不伪造状态码
        val missing = try {
            runBlocking { GeminiTts.synthesize(GeminiTtsConfig(apiKey = " "), "hi", base) }
            null
        } catch (e: NetException) {
            e.error
        }
        assertNotNull(missing)
        assertEquals(NetCategory.CONFIG_MISSING_KEY, missing!!.category)
        assertNull(missing.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun listVoicesFollowsPagination() = withServer { server, base ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"voices":[{"id":"voice_mine","type":"replicated","display_name":"我的声音"},{"id":"Kore","type":"prebuilt","display_name":"Kore"}],"next_page_token":"p2"}"""
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"voices":[{"id":"lib-cmn-1","type":"prebuilt","display_name":"Mei","language_code":"cmn-CN"},{"id":"Kore","type":"prebuilt"}]}"""
            )
        )
        val voices = runBlocking { GeminiTts.listVoices(GeminiTtsConfig(apiKey = "k"), base) }
        assertEquals(listOf("voice_mine", "Kore", "lib-cmn-1"), voices.map { it.id })
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/v1beta/voices?page_size=1000", first.path)
        assertEquals("k", first.getHeader("x-goog-api-key"))
        assertEquals("/v1beta/voices?page_size=1000&page_token=p2", server.takeRequest().path)
    }

    @Test
    fun synthTargetDrivesTheRightApi() = withServer { server, _ ->
        // OpenAI 兼容目标走 /audio/speech + Bearer，与 Gemini 互不串线
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(wav(100))))
        val base = server.url("/v1").toString().trimEnd('/')
        val t = SynthTarget.OpenAI(OpenAISpeechConfig(baseUrl = base, apiKey = "sk-test", model = "x/y", voice = "v"))
        assertTrue(runBlocking { t.synthesize("hi") }.isNotEmpty())
        val req = server.takeRequest()
        assertEquals("/v1/audio/speech", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertNull(req.getHeader("x-goog-api-key"))
    }
}
