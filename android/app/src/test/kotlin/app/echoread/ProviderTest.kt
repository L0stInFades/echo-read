package app.echoread

import app.echoread.core.Discovery
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.ProviderSpec
import app.echoread.core.Template
import app.echoread.core.TextLang
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.VoiceSpec
import app.echoread.core.i18n.AppLanguage
import app.echoread.core.i18n.EnStrings
import app.echoread.core.i18n.I18n
import app.echoread.core.i18n.ZhStrings
import app.echoread.core.net.Disposition
import app.echoread.core.net.NetCategory
import app.echoread.core.net.NetException
import app.echoread.data.SettingsStore
import app.echoread.tts.HttpSpeech
import app.echoread.tts.Providers
import app.echoread.tts.SpeechApi
import app.echoread.tts.SynthTarget
import app.echoread.tts.TtsEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * 服务目录（0.5.0）：内置目录的静态检查、模板求值、各家请求的形状、端到端的响应解码，以及旧存档迁移。
 *
 * 这里没有任何一家的真实 Key：请求形状逐条对照 2026-11-26 复核的官方文档写成断言，
 * 响应解码用本机的假服务端到端跑真实的 OkHttp 路径。目录是数据，写错一个字段名这里就会红。
 */
class ProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun backToChinese() = I18n.apply(AppLanguage.ZH)

    private fun spec(id: String): ProviderSpec = Providers.spec(id) ?: error("no provider $id")

    private fun profile(id: String, key: String = "KEY", block: (VoiceProfile) -> VoiceProfile = { it }): VoiceProfile =
        block(Providers.newProfile(spec(id), emptyList()).copy(apiKey = key))

    private fun mp3(n: Int = 64): ByteArray = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(n) { (it % 7).toByte() }

    private fun withServer(block: (MockWebServer, String) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, server.url("/").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }

    private fun body(req: okhttp3.Request): String = okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()

    /* ---------------- 内置目录 ---------------- */

    @Test
    fun builtInCatalogIsValid() {
        val all = Providers.builtIn
        assertTrue("目录至少有十几家", all.size >= 18)
        assertEquals("ID 不能重复", all.size, all.map { it.id }.toSet().size)
        for (s in all) {
            assertEquals("${s.id} 的静态检查", emptyList<String>(), Providers.validate(s))
            assertTrue("${s.id}：region 只有三种", s.region in setOf("global", "cn", "local"))
            // 标签词表与语言内核一一对应：目录里出现一个界面不认识的标签，就等于白写
            val badges = s.badges + s.models.flatMap { it.badges }
            assertEquals("${s.id} 用了不认识的标签", emptyList<String>(), badges.filter { it !in Providers.BADGES })
            if (s.defaultVoice.isNotBlank() && s.id != Providers.OPENROUTER && s.kind != ProviderSpec.KIND_GEMINI) {
                val voices = Providers.staticVoices(s, s.defaultModel)
                assertTrue("${s.id}：默认音色 ${s.defaultVoice} 不在音色表里", voices.isEmpty() || voices.any { it.id == s.defaultVoice })
            }
            if (s.sunset.isNotBlank()) assertTrue("${s.id}：sunset 写成 yyyy-mm-dd", Regex("\\d{4}-\\d{2}-\\d{2}").matches(s.sunset))
        }
        // 三个承重的 ID：迁移、缓存命名空间、自定义端点都认它们
        assertNotNull(Providers.spec(Providers.OPENROUTER))
        assertNotNull(Providers.spec(Providers.GEMINI))
        assertNotNull(Providers.spec(Providers.CUSTOM))
        for (b in Providers.BADGES) {
            assertNotEquals("标签 $b 缺中文文案", b, ZhStrings.providers.badge(b))
            assertNotEquals("标签 $b 缺英文文案", b, EnStrings.providers.badge(b))
        }
    }

    /* ---------------- 标志 ---------------- */

    /**
     * 目录里的每一个标志都要能被 Compose 的路径解析器读出来：画它的就是这个解析器，没有别的库兜底。
     * 写法的约定见 android/tools/lobe2logo.py：每条路径以绝对的 M 开头，弧线的两个标志位各自成词。
     */
    @Test
    fun catalogLogosParse() {
        val withLogo = Providers.builtIn.filter { it.logo.isNotEmpty() }
        assertTrue("目录里应当有十几家带标志", withLogo.size >= 15)
        var chars = 0
        for (s in withLogo) {
            chars += s.logo.length
            val vector = app.echoread.ui.ProviderLogos.build(s.logo)
            assertEquals("${s.id}：24×24 的视口", 24f, vector.viewportWidth, 0f)
            for (part in s.logo.split('|')) {
                val d = part.substringAfter('@')
                val nodes = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes()
                assertTrue("${s.id}：路径是空的", nodes.size >= 3)
                val first = nodes.first()
                assertTrue("${s.id}：路径必须以绝对的 M 开头", first is androidx.compose.ui.graphics.vector.PathNode.MoveTo)
                first as androidx.compose.ui.graphics.vector.PathNode.MoveTo
                assertTrue("${s.id}：起点落在视口里", first.x in 0f..24f && first.y in 0f..24f)
                if ('@' in part) assertTrue("${s.id}：不透明度在 0 到 1 之间", part.substringBefore('@').toFloat() in 0.05f..1f)
            }
            assertTrue("${s.id}：logoScale", s.logoScale in 0.2f..1f)
        }
        // 「省」是这件事的前提：整份目录的标志加起来十几 KB 文本
        assertTrue("标志总量 $chars 个字符", chars < 20_000)
        // 国内版与国际版是同一家，同一个标志
        assertEquals(Providers.spec("minimax")!!.logo, Providers.spec("minimax-cn")!!.logo)
        // 自定义端点没有标志：它代表的是用户自己的服务，显示名字的首字母
        assertEquals("", Providers.spec(Providers.CUSTOM)!!.logo)
    }

    /** 路径写法里用到的几种紧凑形式，解析器必须按 SVG 的语义读对（升级 Compose 时这条先红） */
    @Test
    fun logoPathDialect() {
        fun nodes(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes()
        // 省掉前导 0 的小数、以负号当分隔符、同一命令连写
        val curve = nodes("M1 2c.64 .64 .19 1.74-.72 1.74z")[1] as androidx.compose.ui.graphics.vector.PathNode.RelativeCurveTo
        assertEquals(0.64f, curve.dx1, 1e-4f); assertEquals(0.64f, curve.dy1, 1e-4f)
        assertEquals(0.19f, curve.dx2, 1e-4f); assertEquals(1.74f, curve.dy2, 1e-4f)
        assertEquals(-0.72f, curve.dx3, 1e-4f); assertEquals(1.74f, curve.dy3, 1e-4f)
        // 弧线：两个标志位各自成词
        val arc = nodes("M18.654 3.87a5.087 5.087 0 1 1 0 10.174z")[1] as androidx.compose.ui.graphics.vector.PathNode.RelativeArcTo
        assertTrue(arc.isMoreThanHalf); assertTrue(arc.isPositiveArc)
        assertEquals(0f, arc.arcStartDx, 1e-4f); assertEquals(10.174f, arc.arcStartDy, 1e-4f)
        // M 后面连写的坐标是直线
        val poly = nodes("M5 0h5v24H5V0zM14 0h5v24h-5V0z")
        assertEquals(12, poly.size)
        // 画不出来的数据不抛到界面上：返回 null，头像退回首字母
        assertNull(app.echoread.ui.ProviderLogos.vector(""))
        assertNotNull(app.echoread.ui.ProviderLogos.vector("M0 0h24v24H0z"))
    }

    @Test
    fun importedLogoIsValidated() {
        val base = Providers.spec("openai")!!.copy(id = "mine-1")
        assertEquals(emptyList<String>(), Providers.validate(base))
        assertTrue(Providers.validate(base.copy(logo = "<svg onload=x>")).any { it.startsWith("logo must be") })
        assertTrue(Providers.validate(base.copy(logo = "M0 0h1z".repeat(3000))).any { it.startsWith("logo must be") })
    }

    /* ---------------- 模板 ---------------- */

    @Test
    fun templateExpansion() {
        val vars = mapOf("base" to "https://x.test/v1", "voice" to "Chinese (Mandarin)_Radio Host", "key" to "sk-1", "text" to "他说：\"你好\"\n<下一行>", "style" to "")
        // URL 里除 {base} 外都做百分号编码（空格是 %20，不是 +）
        assertEquals(
            "https://x.test/v1/tts/Chinese%20%28Mandarin%29_Radio%20Host?a=1",
            Template.expand("{base}/tts/{voice}?a=1", vars, Template.Context.URL)
        )
        assertEquals("Bearer sk-1", Template.expand("Bearer {key}", vars, Template.Context.HEADER))
        assertEquals("他说：&quot;你好&quot;\n&lt;下一行&gt;", Template.expand("{text|xml}", vars, Template.Context.RAW))
        assertEquals("zh-CN", Template.expand("{voice|locale}", mapOf("voice" to "zh-CN-YunxiNeural"), Template.Context.RAW))
        assertEquals("en-US", Template.expand("{voice|locale}", mapOf("voice" to "en-US-Harper:MAI-Voice-2.1"), Template.Context.RAW))
        assertEquals("不认识的变量按空串", "a--b", Template.expand("a-{nope}-b", vars, Template.Context.RAW))

        val tpl = json.parseToJsonElement("""{"text":"{text}","ref":"{style?}","n":3,"nested":{"voice":"{voice}","flag":true},"list":["{key}",1]}""")
        val out = Template.expandJson(tpl, vars).jsonObject
        // 正文里的引号与换行由 JSON 序列化负责转义：重新解析回来必须逐字相同
        val round = json.parseToJsonElement(out.toString()).jsonObject
        assertEquals("他说：\"你好\"\n<下一行>", round["text"]!!.jsonPrimitive.content)
        assertFalse("取值为空的可省略键整个不发", out.containsKey("ref"))
        assertEquals(3, round["n"]!!.jsonPrimitive.content.toInt())
        assertEquals("Chinese (Mandarin)_Radio Host", round["nested"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals(setOf("text", "style", "voice", "key"), Template.names(tpl))
    }

    @Test
    fun jsonPathsAndFraming() {
        val root = json.parseToJsonElement("""{"data":{"audio":"abc","n":0},"choices":[{"message":{"audio":{"data":"xyz"}}}],"empty":"","ok":true}""")
        assertEquals("abc", Template.text(root, "data.audio"))
        assertEquals("xyz", Template.text(root, "choices.0.message.audio.data"))
        assertEquals("多个候选路径取第一个非空的", "abc", Template.text(root, "empty|nope.x|data.audio"))
        assertEquals("0", Template.text(root, "data.n"))
        assertNull(Template.text(root, "data.missing"))
        assertTrue(Template.truthy(root, "ok"))
        assertFalse(Template.truthy(root, "data.n"))
        // 三种分帧都切得开：NDJSON、SSE、对象首尾相接；字符串里的花括号不算
        val objs = listOf("""{"code":0,"data":"QQ=="}""", """{"code":0,"data":"}{"}""", """{"code":20000000}""")
        assertEquals(objs, Template.jsonObjects(objs.joinToString("\n")))
        assertEquals(objs, Template.jsonObjects(objs.joinToString("\n\n") { "event: x\ndata: $it" }))
        assertEquals(objs, Template.jsonObjects(objs.joinToString("")))
        assertEquals(emptyList<String>(), Template.jsonObjects("<html>502</html>"))
    }

    @Test
    fun textLanguage() {
        assertEquals("zh", TextLang.of("雨是在傍晚时分落下来的。"))
        assertEquals("en", TextLang.of("It was a dark and stormy night."))
        assertEquals("中文里夹几个英文单词仍是中文", "zh", TextLang.of("这是 Lector 的示例"))
        assertEquals("ja", TextLang.of("吾輩は猫である。名前はまだ無い。"))
        assertEquals("ko", TextLang.of("나는 고양이로소이다"))
        assertEquals("zh-CN", TextLang.locale("第一章"))
        assertEquals("只有数字：跟界面语言走", "zh", TextLang.of("1234"))
        I18n.apply(AppLanguage.EN)
        assertEquals("en", TextLang.of("1234"))
    }

    /* ---------------- 各家请求的形状（对照官方文档） ---------------- */

    @Test
    fun elevenLabsRequest() {
        val s = spec("elevenlabs")
        val p = profile("elevenlabs") { it.copy(model = "eleven_flash_v2_5", voice = "JBFqnCBsd6RMkjVDRZzb") }
        val req = HttpSpeech.buildRequest(s.synth!!, HttpSpeech.vars(s, p, "Hello"))
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb?output_format=mp3_44100_128", req.url.toString())
        assertEquals("KEY", req.header("xi-api-key"))
        assertNull(req.header("Authorization"))
        val b = json.parseToJsonElement(body(req)).jsonObject
        assertEquals("Hello", b["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_flash_v2_5", b["model_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun azureRequestIsEscapedSsml() {
        val s = spec("azure")
        val p = profile("azure") { it.copy(voice = "zh-CN-YunxiNeural", fields = mapOf("region" to "japaneast")) }
        val req = HttpSpeech.buildRequest(s.synth!!, HttpSpeech.vars(s, p, "A & B <tag> 'q'"))
        assertEquals("区域在主机名里", "https://japaneast.tts.speech.microsoft.com/cognitiveservices/v1", req.url.toString())
        assertEquals("KEY", req.header("Ocp-Apim-Subscription-Key"))
        assertEquals("audio-24khz-96kbitrate-mono-mp3", req.header("X-Microsoft-OutputFormat"))
        assertNotNull("Azure 要求带 User-Agent", req.header("User-Agent"))
        assertTrue(req.body!!.contentType().toString().startsWith("application/ssml+xml"))
        val ssml = body(req)
        assertEquals(
            "<speak version='1.0' xml:lang='zh-CN'><voice name='zh-CN-YunxiNeural'>A &amp; B &lt;tag&gt; &apos;q&apos;</voice></speak>",
            ssml
        )
        // 没填区域时用目录里的默认值
        assertEquals("https://eastasia.tts.speech.microsoft.com", Providers.baseOf(s, profile("azure")))
        // 世纪互联版：整个地址由用户覆盖
        assertEquals("https://chinaeast2.tts.speech.azure.cn", Providers.baseOf(s, p.copy(baseUrl = "https://chinaeast2.tts.speech.azure.cn/")))
    }

    @Test
    fun miniMaxRequest() {
        for (id in listOf("minimax", "minimax-cn")) {
            val s = spec(id)
            val p = profile(id) { it.copy(voice = "Chinese (Mandarin)_Radio_Host") }
            val req = HttpSpeech.buildRequest(s.synth!!, HttpSpeech.vars(s, p, "你好"))
            assertTrue(req.url.toString().endsWith("/v1/t2a_v2"))
            assertEquals("Bearer KEY", req.header("Authorization"))
            val b = json.parseToJsonElement(body(req)).jsonObject
            assertEquals("speech-2.8-hd", b["model"]!!.jsonPrimitive.content)
            assertEquals("hex", b["output_format"]!!.jsonPrimitive.content)
            assertEquals("false", b["stream"]!!.jsonPrimitive.content)
            // 音色 ID 里的空格与括号原样发，不做任何编码
            assertEquals("Chinese (Mandarin)_Radio_Host", b["voice_setting"]!!.jsonObject["voice_id"]!!.jsonPrimitive.content)
            assertEquals("mp3", b["audio_setting"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        }
        assertEquals("https://api.minimax.io", spec("minimax").baseUrl)
        assertEquals("https://api.minimax.cn", spec("minimax-cn").baseUrl)
    }

    @Test
    fun fishRequestOmitsEmptyVoice() {
        val s = spec("fish")
        val noVoice = HttpSpeech.buildRequest(s.synth!!, HttpSpeech.vars(s, profile("fish"), "hi"))
        assertEquals("https://api.fish.audio/v1/tts", noVoice.url.toString())
        assertEquals("模型走请求头", "s2.1-pro", noVoice.header("model"))
        assertFalse("没选音色就不发 reference_id", json.parseToJsonElement(body(noVoice)).jsonObject.containsKey("reference_id"))
        val withVoice = HttpSpeech.buildRequest(s.synth!!, HttpSpeech.vars(s, profile("fish") { it.copy(voice = "abc123") }, "hi"))
        assertEquals("abc123", json.parseToJsonElement(body(withVoice)).jsonObject["reference_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun volcengineDashscopeXaiMistralCartesiaDeepgram() {
        val volc = spec("volcengine")
        val v = HttpSpeech.buildRequest(volc.synth!!, HttpSpeech.vars(volc, profile("volcengine"), "你好"))
        assertEquals("https://openspeech.bytedance.com/api/v3/tts/unidirectional", v.url.toString())
        assertEquals("KEY", v.header("X-Api-Key"))
        assertEquals("资源 ID 就是模型", "seed-tts-2.0", v.header("X-Api-Resource-Id"))
        val vb = json.parseToJsonElement(body(v)).jsonObject["req_params"]!!.jsonObject
        assertEquals("zh_female_vv_uranus_bigtts", vb["speaker"]!!.jsonPrimitive.content)
        assertEquals("mp3", vb["audio_params"]!!.jsonObject["format"]!!.jsonPrimitive.content)

        val ds = spec("dashscope")
        val d = HttpSpeech.buildRequest(ds.synth!!, HttpSpeech.vars(ds, profile("dashscope"), "你好"))
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", d.url.toString())
        val db = json.parseToJsonElement(body(d)).jsonObject
        assertEquals("qwen3-tts-flash", db["model"]!!.jsonPrimitive.content)
        assertEquals("Cherry", db["input"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals(600, Providers.maxChars(ds, "qwen3-tts-flash"))

        val xai = spec("xai")
        val x = HttpSpeech.buildRequest(xai.synth!!, HttpSpeech.vars(xai, profile("xai"), "hi"))
        assertEquals("https://api.x.ai/v1/tts", x.url.toString())
        val xb = json.parseToJsonElement(body(x)).jsonObject
        assertEquals("eve", xb["voice_id"]!!.jsonPrimitive.content)
        assertEquals("language 是必填项", "auto", xb["language"]!!.jsonPrimitive.content)
        assertFalse("这个接口没有 model 字段", xb.containsKey("model"))

        val mi = spec("mistral")
        val m = HttpSpeech.buildRequest(mi.synth!!, HttpSpeech.vars(mi, profile("mistral"), "hi"))
        assertEquals("https://api.mistral.ai/v1/audio/speech", m.url.toString())
        val mb = json.parseToJsonElement(body(m)).jsonObject
        // 路径和 OpenAI 一样，字段却不同：音色叫 voice_id，多发一个不认识的字段就是 422
        assertEquals(setOf("model", "input", "voice_id", "response_format"), mb.keys)

        val ca = spec("cartesia")
        val c = HttpSpeech.buildRequest(ca.synth!!, HttpSpeech.vars(ca, profile("cartesia"), "雨是在傍晚落下来的"))
        assertEquals("https://api.cartesia.ai/tts/bytes", c.url.toString())
        assertEquals("2026-08-14", c.header("Cartesia-Version"))
        val cb = json.parseToJsonElement(body(c)).jsonObject
        assertEquals("语言按这段正文判断", "zh", cb["language"]!!.jsonPrimitive.content)
        assertEquals("sonic-3.6", cb["model_id"]!!.jsonPrimitive.content)
        assertTrue(cb["voice"] is JsonObject)

        val dg = spec("deepgram")
        val g = HttpSpeech.buildRequest(dg.synth!!, HttpSpeech.vars(dg, profile("deepgram") { it.copy(voice = "flux-hannah-en") }, "hi"))
        assertEquals("https://api.deepgram.com/v2/speak?model=flux-hannah-en&encoding=mp3", g.url.toString())
        assertEquals("Token KEY", g.header("Authorization"))
        assertEquals(setOf("text"), json.parseToJsonElement(body(g)).jsonObject.keys)
    }

    @Test
    fun openAiFamilyQuirks() {
        // 阶跃：语气参数叫 instruction，只有支持的模型才发
        val step = spec("stepfun")
        val sp = profile("stepfun") { it.copy(style = "温柔") }
        val body = SpeechApi.buildSpeechBody(Providers.openAiConfig(step, sp), "你好", Providers.openAiOptions(step))
        assertEquals("温柔", body["instruction"]!!.jsonPrimitive.content)
        assertNull(body["instructions"])
        val mini = Providers.openAiConfig(step, sp.copy(model = "step-tts-mini"))
        assertEquals("不支持语气的模型不发这个字段", "", mini.instructions)
        // 智谱只回 wav / pcm：response_format 必须显式写 wav
        val zhipu = Providers.openAiConfig(spec("zhipu"), profile("zhipu"))
        assertEquals("wav", SpeechApi.buildSpeechBody(zhipu, "你好")["response_format"]!!.jsonPrimitive.content)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", zhipu.baseUrl)
        // 硅基流动：音色是「模型:名字」
        val sf = Providers.openAiConfig(spec("siliconflow"), profile("siliconflow"))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", sf.voice)
        // OpenAI：tts-1 不吃 instructions
        assertTrue(Providers.supportsStyle(spec("openai"), "gpt-4o-mini-tts"))
        assertFalse(Providers.supportsStyle(spec("openai"), "tts-1"))
        assertTrue(Providers.supportsStyle(spec("openrouter"), "google/gemini-3.8-flash-tts"))
        assertFalse(Providers.supportsStyle(spec("openrouter"), "hexgrad/kokoro-82m"))
    }

    /* ---------------- 端到端：真实的 OkHttp 请求打到本机假服务 ---------------- */

    @Test
    fun bytesResponseEndToEnd() = withServer { server, base ->
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(mp3())))
        val s = spec("elevenlabs")
        val p = profile("elevenlabs") { it.copy(baseUrl = base, voice = "v 1") }
        val out = runBlocking { SynthTarget.Http(s, p).synthesize("hi") }
        assertTrue(SpeechApi.looksLikeAudio(out, "audio/mpeg"))
        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/v%201?output_format=mp3_44100_128", req.path)
        assertEquals("KEY", req.getHeader("xi-api-key"))
    }

    @Test
    fun hexJsonAndBusinessErrors() = withServer { server, base ->
        val s = spec("minimax")
        val p = profile("minimax") { it.copy(baseUrl = base) }
        val hex = mp3().joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setBody("""{"data":{"audio":"$hex","status":2},"base_resp":{"status_code":0,"status_msg":"success"}}"""))
        assertTrue(runBlocking { HttpSpeech.synthesize(s, p, "你好") }.contentEquals(mp3()))
        // MiniMax 的「Key 无效」是 HTTP 200 + status_code 1004：必须认成鉴权失败并停播，不能逐段跳过
        server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1004,"status_msg":"login fail: Please carry the API secret key"}}"""))
        val auth = try {
            runBlocking { HttpSpeech.synthesize(s, p, "你好") }
            fail("应当抛出"); throw IllegalStateException()
        } catch (e: NetException) {
            e.error
        }
        assertEquals(NetCategory.AUTH, auth.category)
        assertEquals("状态码只可能是服务端真实回的", 200, auth.status)
        assertEquals("1004", auth.providerCode)
        assertTrue(auth.providerMessage!!.contains("login fail"))
        assertEquals(Disposition.STOP_SESSION, auth.disposition())
        // 限流（1002）值得重试；余额不足（1008）停播
        server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1002,"status_msg":"rate limit"}}"""))
        assertEquals(NetCategory.RATE_LIMIT, runCatching { runBlocking { HttpSpeech.synthesize(s, p, "x") } }.exceptionOrNull().let { (it as NetException).error.category })
        server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}"""))
        assertEquals(NetCategory.QUOTA, runCatching { runBlocking { HttpSpeech.synthesize(s, p, "x") } }.exceptionOrNull().let { (it as NetException).error.category })
        // 200 却不是 JSON（网关页）：无法解析，而不是「音频为空」
        server.enqueue(MockResponse().setBody("<html>gateway</html>"))
        assertEquals(NetCategory.PARSE, runCatching { runBlocking { HttpSpeech.synthesize(s, p, "x") } }.exceptionOrNull().let { (it as NetException).error.category })
        // 没填 Key：本地前置校验，不发请求
        val before = server.requestCount
        val missing = runCatching { runBlocking { HttpSpeech.synthesize(s, p.copy(apiKey = " "), "x") } }.exceptionOrNull() as NetException
        assertEquals(NetCategory.CONFIG_MISSING_KEY, missing.error.category)
        assertNull(missing.error.status)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun chunkedBase64Lines() = withServer { server, base ->
        val s = spec("volcengine")
        val p = profile("volcengine") { it.copy(baseUrl = base) }
        val audio = mp3(90)
        val a = Base64.getEncoder().encodeToString(audio.copyOfRange(0, 40))
        val b = Base64.getEncoder().encodeToString(audio.copyOfRange(40, audio.size))
        // 分片之间没有任何分隔符也要拼得起来；最后一片只有结束码
        server.enqueue(MockResponse().setBody("""{"code":0,"message":"","data":"$a"}{"code":0,"data":"$b"}{"code":20000000,"message":"ok"}"""))
        assertTrue(runBlocking { HttpSpeech.synthesize(s, p, "你好") }.contentEquals(audio))
        // 流中途报错：按服务端的码与原话上报
        server.enqueue(MockResponse().setBody("""{"code":0,"data":"$a"}""" + "\n" + """{"code":55000000,"message":"server busy"}"""))
        val err = (runCatching { runBlocking { HttpSpeech.synthesize(s, p, "你好") } }.exceptionOrNull() as NetException).error
        assertEquals("55000000", err.providerCode)
        assertEquals("server busy", err.providerMessage)
        // 开流之前就被拒：真实的 HTTP 400，服务端的 message 要留下来
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"header":{"reqid":"r","code":45000010,"message":"load grant: requested grant not found"}}"""))
        val pre = (runCatching { runBlocking { HttpSpeech.synthesize(s, p, "你好") } }.exceptionOrNull() as NetException).error
        assertEquals(NetCategory.AUTH, pre.category)
        assertTrue(pre.providerMessage!!.contains("grant not found"))
    }

    @Test
    fun urlAndBase64Json() = withServer { server, base ->
        // 通义：先回一个音频链接，再去把它取回来；取音频的那次请求不带鉴权头
        val ds = spec("dashscope")
        server.enqueue(MockResponse().setBody("""{"output":{"audio":{"url":"$base/files/a.wav","data":""},"finish_reason":"stop"}}"""))
        val wav = SpeechApi.pcmToWav(ByteArray(480) { (it % 5).toByte() }, 24000, 1)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(wav)))
        val out = runBlocking { HttpSpeech.synthesize(ds, profile("dashscope") { it.copy(baseUrl = base) }, "你好") }
        assertTrue(out.contentEquals(wav))
        assertEquals("Bearer KEY", server.takeRequest().getHeader("Authorization"))
        val fetch = server.takeRequest()
        assertEquals("/files/a.wav", fetch.path)
        assertNull("Key 只发给目录里声明的那个接口", fetch.getHeader("Authorization"))

        // Mistral：JSON 里的 base64
        val mi = spec("mistral")
        server.enqueue(MockResponse().setBody("""{"audio_data":"${Base64.getEncoder().encodeToString(mp3())}"}"""))
        assertTrue(runBlocking { HttpSpeech.synthesize(mi, profile("mistral") { it.copy(baseUrl = base) }, "hi") }.contentEquals(mp3()))
        // 字段在，却不是合法的 base64
        server.enqueue(MockResponse().setBody("""{"audio_data":"!!!not base64!!!"}"""))
        assertEquals(NetCategory.PARSE, (runCatching { runBlocking { HttpSpeech.synthesize(mi, profile("mistral") { it.copy(baseUrl = base) }, "hi") } }.exceptionOrNull() as NetException).error.category)
        // 200 + JSON 里没有音频字段
        server.enqueue(MockResponse().setBody("""{"detail":"nothing here"}"""))
        assertEquals(NetCategory.EMPTY_AUDIO, (runCatching { runBlocking { HttpSpeech.synthesize(mi, profile("mistral") { it.copy(baseUrl = base) }, "hi") } }.exceptionOrNull() as NetException).error.category)
    }

    @Test
    fun keylessLocalServer() = withServer { server, base ->
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(mp3())))
        val s = spec("kokoro-local")
        val p = Providers.newProfile(s, emptyList()).copy(baseUrl = "$base/v1")
        assertTrue("自建服务不要 Key 也算就绪", Providers.ready(s, p))
        assertFalse("没填地址不算就绪", Providers.ready(s, p.copy(baseUrl = "")))
        val t = Providers.target(p)
        assertTrue(t.hasKey)
        assertTrue(runBlocking { t.synthesize("hi") }.isNotEmpty())
        val req = server.takeRequest()
        assertEquals("/v1/audio/speech", req.path)
        assertNull("没有 Key 就不发 Authorization，而不是发一个空的 Bearer", req.getHeader("Authorization"))
        assertEquals("kokoro", json.parseToJsonElement(req.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }

    /* ---------------- 列表：音色 / 模型 ---------------- */

    @Test
    fun listMapping() {
        val eleven = spec("elevenlabs").listVoices[0]
        val (voices, next) = HttpSpeech.parseList(
            json.parseToJsonElement(
                """{"voices":[
                {"voice_id":"a1","name":"Rachel","category":"premade","labels":{"language":"en","gender":"female","accent":"american","use_case":"narration"}},
                {"voice_id":"b2","name":"我的声音","category":"cloned","labels":{"language":"zh","gender":"male"}},
                {"name":"没有 id 的条目"}
                ],"has_more":true,"next_page_token":"tok2"}"""
            ),
            eleven
        )
        assertEquals(listOf("a1", "b2"), voices.map { it.id })
        assertEquals(VoiceSpec("a1", "Rachel", "en", "f", listOf("american", "narration"), mine = false), voices[0])
        assertTrue("克隆的音色算「我的」", voices[1].mine)
        assertEquals("m", voices[1].gender)
        assertEquals("tok2", next)

        // MiniMax：三张表在同一个响应里，后两张是用户自己的
        val mm = HttpSpeech.parseList(
            json.parseToJsonElement("""{"system_voice":[{"voice_id":"audiobook_male_1","voice_name":"有声书男声","description":["沉稳"]}],"voice_cloning":[{"voice_id":"clone_1"}],"voice_generation":[]}"""),
            spec("minimax").listVoices[0]
        ).first
        assertEquals(listOf("audiobook_male_1" to false, "clone_1" to true), mm.map { it.id to it.mine })
        assertEquals(listOf("沉稳"), mm[0].tags)

        // 阶跃：数组里直接是字符串
        val st = HttpSpeech.parseList(json.parseToJsonElement("""{"voices":["cixingnansheng","wenrounvsheng"],"voices-details":{}}"""), spec("stepfun").listVoices[0]).first
        assertEquals(listOf("cixingnansheng", "wenrounvsheng"), st.map { it.id })

        // Azure：响应体本身就是数组
        val az = HttpSpeech.parseList(
            json.parseToJsonElement("""[{"ShortName":"zh-CN-YunxiNeural","DisplayName":"Yunxi","LocalName":"云希","Gender":"Male","Locale":"zh-CN"}]"""),
            spec("azure").listVoices[0]
        ).first
        assertEquals(VoiceSpec("zh-CN-YunxiNeural", "云希", "zh-CN", "m"), az[0])

        // ElevenLabs 的模型表：只留能做语音合成的
        val models = HttpSpeech.parseList(
            json.parseToJsonElement("""[{"model_id":"eleven_v4","name":"Eleven v4","can_do_text_to_speech":true},{"model_id":"scribe_v2","name":"Scribe","can_do_text_to_speech":false}]"""),
            spec("elevenlabs").listModels!!
        ).first
        assertEquals(listOf("eleven_v4"), models.map { it.id })
    }

    @Test
    fun syncFollowsPaginationAndValidatesKey() = withServer { server, base ->
        val p = profile("elevenlabs") { it.copy(baseUrl = base) }
        server.enqueue(MockResponse().setBody("""[{"model_id":"eleven_v4","name":"Eleven v4","can_do_text_to_speech":true}]"""))
        server.enqueue(MockResponse().setBody("""{"voices":[{"voice_id":"a","name":"A","category":"premade"}],"has_more":true,"next_page_token":"p2"}"""))
        server.enqueue(MockResponse().setBody("""{"voices":[{"voice_id":"b","name":"B","category":"cloned"}],"has_more":false}"""))
        val r = runBlocking { Providers.sync(p) }
        assertTrue(r.verified)
        assertEquals(listOf("eleven_v4"), r.models.map { it.id })
        assertEquals(listOf("a", "b"), r.voices.map { it.id })
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/v2/voices?page_size=100", server.takeRequest().path)
        assertEquals("/v2/voices?page_size=100&next_page_token=p2", server.takeRequest().path)
        // Key 被拒：同步本身就是连接检查
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""))
        val err = (runCatching { runBlocking { Providers.sync(p) } }.exceptionOrNull() as NetException).error
        assertEquals(NetCategory.AUTH, err.category)
        assertEquals("Invalid API key", err.providerMessage)
        // MiniMax：列表接口对无效 Key 回的是 200 + 1004（实测），不能因此亮绿灯
        server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1004,"status_msg":"login fail: Please carry the API secret key in the 'Authorization' field of the request header"}}"""))
        val mm = (runCatching { runBlocking { Providers.sync(profile("minimax") { it.copy(baseUrl = base) }) } }.exceptionOrNull() as NetException).error
        assertEquals(NetCategory.AUTH, mm.category)
        assertEquals(200, mm.status)
        // Fish Audio：公共音色库不要鉴权（200），「我的音色」那张被拒（401）—— 整体算 Key 无效
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Invalid Token"}"""))
        server.enqueue(MockResponse().setBody("""{"total":1,"items":[{"_id":"abc","title":"Narrator","languages":["en"]}]}"""))
        val fish = (runCatching { runBlocking { Providers.sync(profile("fish") { it.copy(baseUrl = base) }) } }.exceptionOrNull() as NetException).error
        assertEquals(NetCategory.AUTH, fish.category)
        // xAI：无效 Key 是 400 + 一句明说的话（实测），同样认成鉴权失败并停播
        val xai = app.echoread.core.net.NetErrors.fromHttp(400, """{"code":"Client specified an invalid argument","error":"Incorrect API key provided. You can obtain an API key from https://console.x.ai."}""")
        assertEquals(NetCategory.AUTH, xai.category)
        assertEquals(Disposition.STOP_SESSION, xai.disposition())
        assertEquals("普通的 400 仍然只跳过这一段", NetCategory.BAD_REQUEST, app.echoread.core.net.NetErrors.fromHttp(400, """{"error":"voice_id is not valid"}""").category)
        // 目录里没有任何列表接口的服务商：不发请求，如实说「没验证过」
        val before = server.requestCount
        assertFalse(runBlocking { Providers.sync(profile("zhipu")) }.verified)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun voiceCatalogMergesAndHidesStaleOwnVoices() {
        val s = spec("minimax")
        val p = profile("minimax")
        val own = VoiceSpec("clone_1", "我的复刻", mine = true)
        val extra = VoiceSpec("English_Graceful_Lady", "Graceful Lady")
        val d = Discovery(voices = listOf(own, extra), fp = Providers.fingerprint(s, p))
        val cat = Providers.voiceCatalog(s, p, d)
        assertEquals("我的音色排最前", "clone_1", cat[0].id)
        assertEquals("mine", cat[0].lang)
        assertTrue("目录里的静态音色还在", cat.any { it.id == "Chinese (Mandarin)_Radio_Host" })
        assertEquals("没写语种的按 ID 前缀猜", "en", cat.first { it.id == "English_Graceful_Lady" }.lang)
        assertEquals("zh", cat.first { it.id == "Chinese (Mandarin)_Radio_Host" }.lang)
        // 换了 Key：上一个账户的私有音色不摆出来，公共音色照旧
        val other = Providers.voiceCatalog(s, p.copy(apiKey = "ANOTHER"), d)
        assertFalse(other.any { it.id == "clone_1" })
        assertTrue(other.any { it.id == "English_Graceful_Lady" })
        // Gemini：没拉过列表也有 30 个精选音色
        assertEquals(30, Providers.voiceCatalog(spec("gemini"), profile("gemini"), null).size)
        // OpenRouter：沿用按模型的音色目录
        assertEquals(54, Providers.voiceCatalog(spec("openrouter"), profile("openrouter"), null).size)
    }

    /* ---------------- 旧存档迁移、默认值、上限 ---------------- */

    @Test
    fun legacySettingsBecomeProfiles() {
        // 0.4.x：OpenRouter + Gemini 两个槽位都配过，当前用的是 Gemini
        val old = TtsSettings(
            provider = TtsProvider.GEMINI,
            openai = OpenAISpeechConfig(apiKey = "sk-or-1", model = "fish-audio/s2.1-pro-free:free", voice = "", instructions = "calm"),
            gemini = app.echoread.core.GeminiTtsConfig(apiKey = "AIzaX", voice = "Puck"),
            voiceByModel = mapOf("hexgrad/kokoro-82m" to "zf_xiaoyi"),
            rate = 1.5f
        )
        val s = SettingsStore.sanitizeTts(old)
        assertEquals(listOf("openrouter", "gemini"), s.profiles.map { it.id })
        assertEquals("gemini", s.active)
        val or = s.profiles[0]
        assertEquals(listOf("sk-or-1", "fish-audio/s2.1-pro-free:free", "", "calm"), listOf(or.apiKey, or.model, or.voice, or.style))
        assertEquals("每模型的音色记忆跟着走", "zf_xiaoyi", or.voiceByModel["hexgrad/kokoro-82m"])
        assertEquals(listOf("AIzaX", "Puck"), listOf(s.profiles[1].apiKey, s.profiles[1].voice))
        assertEquals(1.5f, s.rate)
        assertEquals("再读一遍不会重复迁移", s, SettingsStore.sanitizeTts(s))

        // 自定义地址：不认识的主机变成一个「自定义端点」，地址与格式原样保留
        val custom = SettingsStore.sanitizeTts(TtsSettings(openai = OpenAISpeechConfig(baseUrl = "http://192.168.1.5:8880/v1", apiKey = "", model = "kokoro", voice = "af_heart", format = "opus")))
        val c = custom.profiles[0]
        assertEquals(listOf("custom-legacy", Providers.CUSTOM, "192.168.1.5", "http://192.168.1.5:8880/v1", "opus"), listOf(c.id, c.spec, c.name, c.baseUrl, c.format))
        assertEquals("custom-legacy", custom.active)
        assertEquals("缓存命名空间与 0.3.x 相同", "OPENAI", SynthTarget.of(custom)!!.cacheNs)
        // 认得出的主机直接对上目录里的那一家
        val sf = SettingsStore.sanitizeTts(TtsSettings(openai = OpenAISpeechConfig(baseUrl = "https://api.siliconflow.cn/v1/", apiKey = "sk", model = "FunAudioLLM/CosyVoice2-0.5B", voice = "FunAudioLLM/CosyVoice2-0.5B:alex")))
        assertEquals("siliconflow", sf.profiles[0].id)
        assertEquals("", sf.profiles[0].baseUrl)

        // 系统语音
        val sys = SettingsStore.sanitizeTts(TtsSettings(provider = TtsProvider.SYSTEM))
        assertEquals(TtsSettings.SYSTEM, sys.active)
        assertTrue(sys.usesSystemVoice)
        assertEquals("旧字段随时回写：降级安装还是系统语音", TtsProvider.SYSTEM, sys.provider)

        // 回写：切到另一家 OpenAI 兼容服务后，旧版本读到的 openai 配置就是它
        val switched = SettingsStore.sanitizeTts(s.copy(active = "openrouter", profiles = s.profiles.map { if (it.id == "openrouter") it.copy(apiKey = "sk-or-2") else it }))
        assertEquals(TtsProvider.OPENAI, switched.provider)
        assertEquals("sk-or-2", switched.openai.apiKey)
        assertEquals("https://openrouter.ai/api/v1", switched.openai.baseUrl)
        assertEquals("AIzaX", switched.gemini.apiKey)
    }

    @Test
    fun freshInstallDefaultsFollowLanguage() {
        val zh = SettingsStore.sanitizeTts(TtsSettings())
        assertEquals("openrouter", zh.active)
        assertEquals("zf_xiaoxiao", zh.profiles[0].voice)
        assertEquals("OpenRouter 上已下架的模型换成现役默认", "hexgrad/kokoro-82m", SettingsStore.sanitizeTts(TtsSettings(openai = OpenAISpeechConfig(apiKey = "k", model = "openai/tts-1"))).profiles[0].model)
        I18n.apply(AppLanguage.EN)
        val en = SettingsStore.sanitizeTts(TtsSettings())
        assertEquals("英文界面的新安装不该默认一个中文音色", "af_heart", en.profiles[0].voice)
        assertEquals("en-US-JennyNeural", Providers.newProfile(spec("azure"), emptyList()).voice)
        I18n.apply(AppLanguage.ZH)
        assertEquals("zh-CN-XiaoxiaoNeural", Providers.newProfile(spec("azure"), emptyList()).voice)
        assertEquals("eastasia", Providers.newProfile(spec("azure"), emptyList()).fields["region"])
    }

    @Test
    fun sanitizeGuardsProfiles() {
        // 当前服务不存在了：退到第一个；一个都没有就是系统语音
        val base = SettingsStore.sanitizeTts(TtsSettings())
        assertEquals("openrouter", SettingsStore.sanitizeTts(base.copy(active = "gone")).active)
        assertEquals(TtsSettings.SYSTEM, SettingsStore.sanitizeTts(base.copy(profiles = listOf(VoiceProfile(id = "system")), active = "x")).active)
        // 导入的定义不能顶替内置服务商的 ID：否则一段粘贴来的 JSON 就能把已存的 Key 引到别的主机上
        val evil = spec("openrouter").copy(baseUrl = "https://evil.example/v1")
        val guarded = SettingsStore.sanitizeTts(base.copy(customSpecs = listOf(evil)))
        assertTrue(guarded.customSpecs.isEmpty())
        assertEquals("https://openrouter.ai/api/v1", Providers.baseOf(Providers.specOf(guarded.profiles[0], guarded), guarded.profiles[0]))
        // 内置服务商每家一个实例；自定义端点可以加很多个
        val two = Providers.newProfile(spec("custom"), listOf(Providers.newProfile(spec("custom"), emptyList())))
        assertTrue(two.id.startsWith("custom-"))
        assertEquals("elevenlabs", Providers.newProfile(spec("elevenlabs"), base.profiles).id)
    }

    @Test
    fun chunkLimitAndNamespaces() {
        fun settingsFor(id: String, chunk: Int): TtsSettings {
            val p = profile(id)
            return SettingsStore.sanitizeTts(TtsSettings(maxChunkChars = chunk, profiles = listOf(p), active = p.id))
        }
        assertEquals("没有上限的服务用用户设的值", 400, Providers.chunkChars(settingsFor("openrouter", 400)))
        assertEquals(400, Providers.chunkChars(settingsFor("dashscope", 400)))
        val small = spec("elevenlabs").copy(id = "tiny", maxChars = 200)
        val tiny = Providers.newProfile(small, emptyList()).copy(apiKey = "k")
        assertEquals("超过服务上限的压下来", 200, Providers.chunkChars(TtsSettings(maxChunkChars = 400, customSpecs = listOf(small), profiles = listOf(tiny), active = tiny.id)))
        assertEquals("模型自己的上限更小时取模型的", 600, Providers.maxChars(spec("dashscope"), "qwen3-tts-flash"))
        assertEquals(120, Providers.chunkChars(TtsSettings(active = TtsSettings.SYSTEM)))
        // 缓存命名空间：OpenRouter / 自定义端点沿用旧值，其余各用各的，同名模型在两家之间不串音
        assertEquals("OPENAI", Providers.cacheNs(spec("openrouter")))
        assertEquals("OPENAI", Providers.cacheNs(spec("custom")))
        assertEquals("GEMINI", Providers.cacheNs(spec("gemini")))
        assertEquals("siliconflow", Providers.cacheNs(spec("siliconflow")))
        val a = Providers.target(profile("together"))
        val b = Providers.target(profile("kokoro-local") { it.copy(baseUrl = "http://x/v1", model = a.model, voice = "zf_xiaoxiao") })
        assertNotEquals(TtsEngine.cacheKeyOf("你好", a), TtsEngine.cacheKeyOf("你好", b))
        // 按模板发请求的服务：换音色 / 模型要重新合成，换 Key 不用
        val e1 = Providers.target(profile("elevenlabs"))
        assertNotEquals(TtsEngine.cacheKeyOf("hi", e1), TtsEngine.cacheKeyOf("hi", Providers.target(profile("elevenlabs") { it.copy(voice = "other") })))
        assertEquals(TtsEngine.cacheKeyOf("hi", e1), TtsEngine.cacheKeyOf("hi", Providers.target(profile("elevenlabs", key = "KEY2"))))
        assertTrue(Providers.retired(spec("openai"), "2027-01-07"))
        assertFalse(Providers.retired(spec("openai"), "2026-11-26"))
    }

    /** 落盘保护：盘上只有密文，旧字段里不留 Key；换了设备解不开时 Key 清空、其余设置都在 */
    @Test
    fun keysAreSealedAtRest() {
        val vault = object : app.echoread.data.Vault {
            override fun seal(plain: String) = if (plain.isEmpty()) plain else "enc1:" + plain.reversed()
            override fun open(stored: String) = if (stored.startsWith("enc1:")) stored.removePrefix("enc1:").reversed() else stored
        }
        val withSecret = spec("azure").copy(id = "sec", fields = listOf(app.echoread.core.FieldSpec(id = "token", secret = true), app.echoread.core.FieldSpec(id = "region", default = "eastus")))
        val p = Providers.newProfile(withSecret, emptyList()).copy(apiKey = "sk-live-123", fields = mapOf("token" to "t0p", "region" to "eastus"))
        val mem = SettingsStore.sanitizeTts(
            TtsSettings(openai = OpenAISpeechConfig(apiKey = "sk-or-abc")).let { SettingsStore.sanitizeTts(it) }
                .let { it.copy(customSpecs = listOf(withSecret), profiles = it.profiles + p) }
        )
        val disk = app.echoread.data.Sealing.sealed(mem, vault)
        val text = Json { encodeDefaults = true }.encodeToString(TtsSettings.serializer(), disk)
        assertFalse("盘上不得出现任何一把明文 Key", text.contains("sk-or-abc") || text.contains("sk-live-123") || text.contains("t0p"))
        assertEquals("旧字段里不再留 Key", "", disk.openai.apiKey)
        assertEquals("不是秘密的字段保持可读", "eastus", disk.profiles.first { it.id == p.id }.fields["region"])
        // 读回来与内存里的一致（旧字段的 Key 由回写补上）
        assertEquals(mem, SettingsStore.sanitizeTts(app.echoread.data.Sealing.opened(disk, vault)))
        // 0.4.x 的明文存档：原样读进来，等迁移
        val legacy = TtsSettings(openai = OpenAISpeechConfig(apiKey = "sk-plain"))
        assertEquals("sk-plain", SettingsStore.sanitizeTts(app.echoread.data.Sealing.opened(legacy, vault)).profiles[0].apiKey)
        // 恢复到另一台设备：密文解不开 → Key 清空，模型与音色都在
        val lost = object : app.echoread.data.Vault {
            override fun seal(plain: String) = plain
            override fun open(stored: String) = if (stored.startsWith("enc1:")) "" else stored
        }
        val restored = SettingsStore.sanitizeTts(app.echoread.data.Sealing.opened(disk, lost))
        assertEquals("", restored.profiles[0].apiKey)
        assertEquals(mem.profiles[0].model, restored.profiles[0].model)
        assertEquals(mem.profiles.map { it.id }, restored.profiles.map { it.id })
    }

    /* ---------------- 用户导入的定义 ---------------- */

    @Test
    fun importedDefinitions() {
        val ok = """{"id":"my-tts","name":"My TTS","kind":"http","baseUrl":"https://tts.example.com",
            "voices":[{"id":"narrator","lang":"en"}],"defaultVoice":"narrator",
            "synth":{"url":"{base}/speak","headers":{"Authorization":"Bearer {key}"},"json":{"text":"{text}","voice":"{voice}"},
                     "response":{"type":"json","audio":"audio.b64"}},
            "somethingNew":"以后加的字段旧版本直接忽略"}"""
        val spec = Providers.parseSpec(ok).getOrThrow()
        assertEquals("my-tts", spec.id)
        assertEquals("narrator", Providers.newProfile(spec, emptyList()).voice)
        // 整份目录也能粘：取第一条
        assertEquals("my-tts", Providers.parseSpec("""{"schema":1,"providers":[$ok]}""").getOrThrow().id)

        fun problems(text: String): String = Providers.parseSpec(text).exceptionOrNull()?.message.orEmpty()
        assertTrue(problems(ok.replace("{voice}", "{voise}")).contains("unknown variable voise"))
        assertTrue(problems(ok.replace("\"kind\":\"http\"", "\"kind\":\"grpc\"")).contains("unknown kind"))
        assertTrue(problems("""{"id":"x1","name":"X","kind":"http","baseUrl":"https://a.b"}""").contains("needs a synth recipe"))
        assertTrue(problems(ok.replace("{base}/speak", "ftp://a/speak")).contains("url must start with"))
        assertTrue(problems(ok.replace("\"text\":\"{text}\",", "")).contains("{text} is never sent"))
        assertTrue(problems(ok.replace("\"audio\":\"audio.b64\"", "\"audio\":\"\"")).contains("audio path is empty"))
        assertTrue(problems(ok.replace("\"id\":\"my-tts\"", "\"id\":\"system\"")).contains("id must match"))
        assertTrue(Providers.parseSpec("not json at all").isFailure)
        assertTrue("更高的目录格式版本直接拒收", runCatching { Providers.parseCatalog("""{"schema":2,"providers":[]}""") }.isFailure)
    }

    /** 地址与 Key 的输入规整：中文输入法敲出来的全角字符、大写的协议名、夹带的空白 */
    @Test
    fun addressesAreNormalized() {
        assertEquals("http://127.0.0.1:8899", Providers.cleanAddress("HTTP：／／127.0.0.1：8899"))
        assertEquals("https://api.example.com/v1", Providers.cleanAddress("  Https://api.example。com/v1 \n"))
        assertEquals("中文域名原样保留", "https://例子.cn/v1", Providers.cleanAddress("https://例子.cn/v1"))
        assertEquals("sk-abc123", Providers.cleanSecret(" ｓｋ－ａｂｃ１２３ "))
        assertTrue(Providers.validBase("http://192.168.1.5:8880/v1"))
        assertFalse(Providers.validBase("192.168.1.5:8880"))
        assertFalse(Providers.validBase("HTTP：／／127.0.0.1：8899"))

        val local = spec("kokoro-local")
        val typed = Providers.newProfile(local, emptyList()).copy(baseUrl = "192.168.1.5:8880/v1")
        assertFalse("没有协议头的地址发不出请求，不算就绪", Providers.ready(local, typed))

        // 旧存档里没规整过的地址，读出来照样能用
        val mm = spec("minimax")
        val legacy = Providers.newProfile(mm, emptyList()).copy(apiKey = "K", baseUrl = "HTTP：／／127.0.0.1：8899")
        assertEquals("http://127.0.0.1:8899", Providers.baseOf(mm, legacy))
        assertTrue(Providers.ready(mm, legacy))
        assertEquals("http://127.0.0.1:8899/v1/t2a_v2", HttpSpeech.buildRequest(mm.synth!!, HttpSpeech.vars(mm, legacy, "hi")).url.toString())
    }

    /** 地址写错是配置问题：停下来让人去改，而不是当成网络抖动重试 */
    @Test
    fun malformedAddressIsAConfigProblem() {
        val e = app.echoread.core.net.NetErrors.fromThrowable(IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for 192.16..."))
        assertEquals(NetCategory.CONFIG_MISSING_BASE_URL, e.category)
        assertEquals(Disposition.STOP_SESSION, e.disposition())
        assertNotEquals(NetCategory.CONFIG_MISSING_BASE_URL, app.echoread.core.net.NetErrors.fromThrowable(IllegalArgumentException("something else")).category)
    }

    /** 系统配了代理时，本机地址仍然直连；别的地址照常交给系统代理 */
    @Test
    fun loopbackBypassesTheSystemProxy() {
        for (h in listOf("127.0.0.1", "localhost", "LOCALHOST", "127.8.0.1", "[::1]", "tts.localhost")) assertTrue(h, SpeechApi.isLoopback(h))
        for (h in listOf("192.168.1.5", "10.0.2.2", "127.0.0.1.example.com", "localhost.example.com", "1127.0.0.1")) assertFalse(h, SpeechApi.isLoopback(h))

        val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("192.168.31.104", 7890))
        val before = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?) = listOf(proxy)
            override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        })
        try {
            val selector = SpeechApi.client.proxySelector
            assertEquals(listOf(java.net.Proxy.NO_PROXY), selector.select(java.net.URI("http://127.0.0.1:8880/v1/audio/speech")))
            assertEquals(listOf(java.net.Proxy.NO_PROXY), selector.select(java.net.URI("http://localhost:8880/v1")))
            assertEquals(listOf(proxy), selector.select(java.net.URI("https://api.openai.com/v1/audio/speech")))
            assertEquals("局域网地址不替用户做决定", listOf(proxy), selector.select(java.net.URI("http://192.168.1.5:8880/v1")))
        } finally {
            java.net.ProxySelector.setDefault(before)
        }
    }

    /** 连接状态只对算出它的那把「端点 + Key」作数：Key 改错又改回来，不能还挂着那条旧的「被拒绝」 */
    @Test
    fun statusIsBoundToTheKeyItWasCheckedWith() {
        val now = 1_000_000_000L
        val good = "fp-good"
        val bad = "fp-bad"
        val d = Discovery(models = emptyList(), voices = emptyList(), at = now - 60_000, fp = good, model = "m")
        val ok = app.echoread.tts.SyncStatus.Ok(verified = true)
        val failed = app.echoread.tts.SyncStatus.Failed(app.echoread.core.net.NetErrors.config(missingKey = true, endpoint = "", kind = app.echoread.core.net.CallKind.MODEL_LIST))
        fun needs(cur: app.echoread.tts.SyncStatus?, checked: String?, fp: String, model: String = "m", scoped: Boolean = false, disc: Discovery? = d, at: Long = now) =
            app.echoread.tts.ServiceSync.needsSync(cur, checked, fp, disc, at, scoped, model)

        assertTrue("本次启动还没查过", needs(null, null, good))
        assertFalse("同一把 Key、刚查过、是好的：不重复发请求", needs(ok, good, good))
        assertTrue("Key 换了", needs(ok, good, bad))
        assertTrue("Key 改错（被拒绝）又改回来：Discovery 里的指纹对得上，但这条红色状态是拿错的那把算的", needs(failed, bad, good))
        assertTrue("失败不留到下一次", needs(failed, bad, bad))
        assertFalse("正在查的就是这一把", needs(app.echoread.tts.SyncStatus.Syncing, good, good))
        assertTrue("在途的是上一把 Key 的检查", needs(app.echoread.tts.SyncStatus.Syncing, bad, good))
        assertTrue("缓存超过 6 小时", needs(ok, good, good, at = now + app.echoread.tts.ServiceSync.MAX_AGE_MS))
        assertTrue("音色表按模型分而模型换了", needs(ok, good, good, model = "m2", scoped = true))
        assertFalse(needs(ok, good, good, model = "m2", scoped = false))
    }

    /** Inworld 的音色列表不在 /v1 下面，但仍然跟着服务地址走：换了地址（中转）就不该再把 Key 发回官方主机 */
    @Test
    fun inworldVoiceListFollowsTheAddress() {
        val s = spec("inworld")
        val r = s.listVoices[0]
        fun url(p: VoiceProfile) = HttpSpeech.buildRequest(r.method, r.url, r.headers, r.json, null, "", HttpSpeech.vars(s, p, null)).url.toString()
        assertEquals("https://api.inworld.ai/tts/v1/voices", url(profile("inworld")))
        assertEquals("http://10.0.0.2:9000/relay/tts/v1/voices", url(profile("inworld") { it.copy(baseUrl = "http://10.0.0.2:9000/relay/v1") }))
    }

    /** 英文包只用 ASCII 标点：中文字体里弯引号、省略号、破折号是全角的，夹在英文句子中间会撑出一大段空白 */
    @Test
    fun englishPackUsesAsciiPunctuation() {
        val typographic = Regex("[“”‘’…—–]")
        val bad = ArrayList<String>()
        for (section in app.echoread.core.i18n.Strings::class.java.methods.filter { it.parameterCount == 0 && it.name.startsWith("get") }) {
            val obj = section.invoke(EnStrings)
            for (m in section.returnType.methods) {
                if (m.returnType != String::class.java) continue
                val args = m.parameterTypes.map { I18nTest.placeholderArg(it) }.toTypedArray()
                val text = m.invoke(obj, *args) as String
                if (typographic.containsMatchIn(text)) bad += "${section.name}.${m.name} = $text"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }
}
