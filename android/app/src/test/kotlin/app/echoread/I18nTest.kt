package app.echoread

import app.echoread.core.ReaderSettings
import app.echoread.core.Sample
import app.echoread.core.TxtParser
import app.echoread.core.i18n.AppLanguage
import app.echoread.core.i18n.EnStrings
import app.echoread.core.i18n.I18n
import app.echoread.core.i18n.S
import app.echoread.core.i18n.Strings
import app.echoread.core.i18n.ZhStrings
import app.echoread.core.net.NetErrors
import app.echoread.tts.SystemTts
import app.echoread.tts.Voices
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 语言内核：语言判定、语言包完整性，以及英文界面下各层的实际输出 */
class I18nTest {
    /** 语言是全局状态：每个用例结束都回到中文，不影响其他测试类（它们按中文断言） */
    @After
    fun backToChinese() = I18n.apply(AppLanguage.ZH)

    private fun english() = I18n.apply(AppLanguage.EN)

    @Test
    fun systemLanguageResolution() {
        assertFalse(I18n.resolve(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE))
        assertFalse(I18n.resolve(AppLanguage.SYSTEM, Locale.TRADITIONAL_CHINESE))
        assertFalse(I18n.resolve(AppLanguage.SYSTEM, Locale.forLanguageTag("zh-Hans-CN")))
        assertTrue(I18n.resolve(AppLanguage.SYSTEM, Locale.US))
        assertTrue("非中文系统一律英文", I18n.resolve(AppLanguage.SYSTEM, Locale.JAPAN))
        assertTrue(I18n.resolve(AppLanguage.EN, Locale.CHINA))
        assertFalse(I18n.resolve(AppLanguage.ZH, Locale.US))
        assertEquals("旧存档没有 language 键：跟随系统", AppLanguage.SYSTEM, ReaderSettings().language)
    }

    @Test
    fun packSwitching() {
        assertEquals("试听", S.common.preview)
        english()
        assertEquals("Preview", S.common.preview)
        assertEquals(Locale.US, I18n.locale)
        I18n.apply(AppLanguage.ZH)
        assertEquals("试听", S.common.preview)
    }

    /** 逐句调用一个语言包的全部文案（带参数的用占位参数调用） */
    private fun allStrings(pack: Strings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (section in Strings::class.java.methods.filter { it.parameterCount == 0 && it.name.startsWith("get") }) {
            val obj = section.invoke(pack)
            for (m in section.returnType.methods) {
                if (m.returnType != String::class.java) continue
                val args = m.parameterTypes.map { if (it == Int::class.javaPrimitiveType) 2 else "x" }.toTypedArray()
                val name = m.name.removePrefix("get").replaceFirstChar { it.lowercase() }
                out["${section.name.removePrefix("get")}.$name"] = m.invoke(obj, *args) as String
            }
        }
        return out
    }

    @Test
    fun packsAreCompleteAndClean() {
        val zh = allStrings(ZhStrings)
        val en = allStrings(EnStrings)
        assertTrue("语言包至少有几百句", zh.size > 500)
        assertEquals("两个语言包的句子一一对应", zh.keys, en.keys)
        assertEquals("不允许空文案", emptyList<String>(), (zh + en.mapKeys { "en:" + it.key }).filterValues { it.isBlank() }.keys.toList())
        // 英文包里出现汉字 = 漏翻（语言名「中文」这种按惯例用本族文字的除外）
        val allowed = setOf("Settings.languageZh")
        val cjk = Regex("[\\u4e00-\\u9fff]")
        assertEquals(emptyList<String>(), en.filter { (k, v) -> k !in allowed && cjk.containsMatchIn(v) }.map { "${it.key} = ${it.value}" })
    }

    @Test
    fun errorsInEnglish() {
        english()
        val auth = NetErrors.fromHttp(401, null, endpoint = "https://openrouter.ai/api/v1/audio/speech")
        assertEquals("API key rejected (401)", auth.headline())
        assertTrue(auth.detail().contains("Suggestion: "))
        val quota = NetErrors.fromHttp(429, """{"error":{"code":"quota_exceeded","message":"daily"}}""", endpoint = "https://generativelanguage.googleapis.com/v1beta/interactions")
        assertTrue(quota.advice().contains("midnight Pacific"))
        assertEquals("no response", NetErrors.fromThrowable(java.net.SocketTimeoutException("t")).badge())
    }

    @Test
    fun voiceCatalogInEnglish() {
        english()
        assertEquals("Firm", Voices.describeVoice("google/gemini-3.8-flash-tts", "Kore").note)
        assertEquals("Xiaoxiao", Voices.describeVoice("hexgrad/kokoro-82m", "zf_xiaoxiao").label)
        assertEquals("英文界面默认英文音色", "af_heart", Voices.defaultVoiceFor("hexgrad/kokoro-82m"))
        assertEquals("en", Voices.groupVoices(Voices.catalogVoices("hexgrad/kokoro-82m"))[0].lang)
        assertTrue(Voices.modelHints("google/gemini-3.8-flash-tts")!!.langs!!.contains("130 languages"))
        assertTrue("「支持中文」与界面语言无关", Voices.modelHints("google/gemini-3.8-flash-tts")!!.zh)
        assertTrue(Voices.RECOMMENDED_MODELS.any { it.id.startsWith("elevenlabs/") })
        I18n.apply(AppLanguage.ZH)
        assertEquals("坚定", Voices.describeVoice("google/gemini-3.8-flash-tts", "Kore").note)
        assertEquals("zf_xiaoxiao", Voices.defaultVoiceFor("hexgrad/kokoro-82m"))
        assertTrue(Voices.modelHints("google/gemini-3.8-flash-tts")!!.zh)
        assertTrue(Voices.RECOMMENDED_MODELS.any { it.id.startsWith("qwen/") })
    }

    @Test
    fun bookLengthPerLanguage() {
        assertEquals("1.2 万字", S.shelf.bookLength(12_345))
        assertEquals("999 字", S.shelf.bookLength(999))
        english()
        assertEquals("12.3k chars", S.shelf.bookLength(12_345))
        assertEquals("1.2M chars", S.shelf.bookLength(1_234_567))
        assertEquals("9,999 chars", S.shelf.bookLength(9_999))
        assertEquals("Found 1 book", S.importer.foundBooks(1))
        assertEquals("Found 3 books", S.importer.foundBooks(3))
    }

    /** 英文 TXT：罗马数字、英文数词、序章尾声都要能分章（0.3.x 只认 Chapter 12） */
    @Test
    fun englishChapterHeadings() {
        val roman = TxtParser.splitChapters("Title page\n\nCHAPTER I\nIt was a dark night.\n\nCHAPTER II\nMorning came.\n\nCHAPTER III.\nThe end.")
        assertEquals(listOf("CHAPTER I", "CHAPTER II", "CHAPTER III."), roman.drop(1).map { it.title })
        val words = TxtParser.splitChapters("Prologue\nBefore.\n\nChapter One: The Start\nA.\n\nChapter Twenty-One\nB.\n\nEpilogue\nAfter.")
        assertEquals(listOf("Prologue", "Chapter One: The Start", "Chapter Twenty-One", "Epilogue"), words.map { it.title })
        // 正文里提到 chapter 不算标题
        val prose = TxtParser.splitChapters("Chapter 1\nShe read the chapter illustrations again.\n\nChapter 2\nThe next chapter began.")
        assertEquals(2, prose.size)
    }

    @Test
    fun englishSampleBook() {
        english()
        val book = TxtParser.parse("${Sample.bookName}.txt", Sample.text.toByteArray())
        assertEquals("The Night Bookshop (Sample)", book.title)
        assertEquals(
            listOf("Opening", "Chapter One: A Visitor on a Rainy Night", "Chapter Two: The Book That Breathed", "Chapter Three: The First Listener", "Epilogue: The Light Stays On"),
            book.chapters.map { it.title }
        )
        I18n.apply(AppLanguage.ZH)
        assertEquals("深夜书屋（示例）", Sample.bookName)
    }

    /** 系统语音按正文的文字选语言，不再一律强制中文 */
    @Test
    fun systemTtsLocalePerText() {
        assertEquals(Locale.US, SystemTts.localeFor("It was a dark and stormy night."))
        assertEquals(Locale.SIMPLIFIED_CHINESE, SystemTts.localeFor("雨是在傍晚时分落下来的。"))
        assertEquals("中文里夹几个英文单词仍是中文", Locale.SIMPLIFIED_CHINESE, SystemTts.localeFor("这是 Lector 的示例文字"))
        assertEquals(Locale.SIMPLIFIED_CHINESE, SystemTts.localeFor("1234"))
    }

    @Test
    fun readerSettingsLanguageRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }
        val s = ReaderSettings(language = AppLanguage.EN)
        val text = json.encodeToString(ReaderSettings.serializer(), s)
        assertTrue(text.contains("\"language\":\"en\""))
        assertEquals(s, json.decodeFromString(ReaderSettings.serializer(), text))
        assertEquals(AppLanguage.SYSTEM, json.decodeFromString(ReaderSettings.serializer(), """{"theme":"dark","language":"klingon"}""").language)
    }
}
