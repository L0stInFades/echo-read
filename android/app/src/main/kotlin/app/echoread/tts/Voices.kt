package app.echoread.tts

import app.echoread.core.i18n.S
import app.echoread.core.GeminiVoiceEntry
import app.echoread.core.i18n.I18n
import app.echoread.core.i18n.PerLanguage
import app.echoread.core.TtsModelInfo

/**
 * 音色目录：OpenRouter TTS 模型的精细音色体系。
 * ① 结构化音色 ID —— 解析为 语言/性别/风格（Kokoro、Deepgram、Voxtral、MAI…）
 * ② 具名音色表 —— 逐个标注（Gemini、Grok、Orpheus…）
 * ③ 开放音色 ID —— 服务端不枚举（Fish Audio 参考 ID、MiniMax 系统音色），给精选建议
 *
 * 带文案的表（音色备注、模型说明、推荐列表）按界面语言各建一份（见 [Tables]），
 * 音色 ID、性别这些与语言无关的数据只存一份。
 */
data class VoiceInfo(
    val id: String,
    val label: String,
    val lang: String? = null,
    /** "f" / "m" */
    val gender: String? = null,
    val note: String? = null
)

data class VoiceGroup(val lang: String, val label: String, val voices: List<VoiceInfo>)

data class FreeVoiceHint(val placeholder: String, val hint: String, val suggestions: List<VoiceInfo> = emptyList())

data class ModelHints(
    val label: String? = null,
    val langs: String? = null,
    val note: String? = null,
    val freeVoice: FreeVoiceHint? = null,
    val voiceOptional: Boolean = false,
    /** OpenRouter 上只回 pcm（请求 mp3 会 400）：Gemini TTS 与 HeyGen Voice */
    val pcmOnly: Boolean = false,
    /** 裸流 pcm 的采样率，仅在 Content-Type 没写 rate 时兜底（HeyGen 是 44.1 kHz） */
    val pcmRate: Int? = null,
    val cloning: Boolean = false,
    /** 接受顶层 instructions（OpenRouter 文档：Gemini TTS 与 Fish Audio，其余模型忽略它） */
    val instructions: Boolean = false,
    val preferred: String? = null,
    /** 支持中文朗读（与界面语言无关：「中文」标签、「中文」筛选都看它，不再去文案里找「中」字） */
    val zh: Boolean = false
)

data class RecommendedModel(val id: String, val label: String, val tag: String)

object Voices {
    /** 分组键 → 名称；顺序即「其他语言」之外的已知语种 */
    private val LANG_KEYS = listOf("mine", "zh", "multi", "en", "ja", "ko", "es", "fr", "de", "it", "nl", "pt", "ru", "hi", "other")

    /** 分组顺序：自建音色总在最前；中文界面中文优先，英文界面英语优先 */
    private val ORDER_ZH = listOf("mine", "zh", "multi", "en", "ja", "ko", "es", "fr", "de", "it", "nl", "pt", "ru", "hi", "other")
    private val ORDER_EN = listOf("mine", "en", "multi", "zh", "ja", "ko", "es", "fr", "de", "it", "nl", "pt", "ru", "hi", "other")

    /** 界面语言对应的「母语」分组：选默认音色时优先它 */
    private val homeLang: String get() = if (I18n.english) "en" else "zh"

    fun langLabel(lang: String): String = when (lang) {
        "mine" -> S.catalog.myVoices
        "zh" -> S.common.chinese
        "multi" -> S.catalog.multilingual
        "en" -> S.catalog.english
        "ja" -> S.catalog.japanese
        "ko" -> S.catalog.korean
        "es" -> S.catalog.spanish
        "fr" -> S.catalog.french
        "de" -> S.catalog.german
        "it" -> S.catalog.italian
        "nl" -> S.catalog.dutch
        "pt" -> S.catalog.portuguese
        "ru" -> S.catalog.russian
        "hi" -> S.catalog.hindi
        "other" -> S.common.other
        else -> lang
    }

    /** 汉语的各种 BCP-47 主标签：普通话 cmn、粤语 yue、吴语 wuu、闽南语 nan、客家话 hak… 都归「中文」 */
    private val CHINESE_TAGS = setOf("zh", "cmn", "yue", "wuu", "nan", "hak", "gan", "hsn", "cjy", "cpx", "lzh")

    /** BCP-47 语言标签（如 en-US、cmn-CN）→ 分组键；不在常用语言表里的归「其他」 */
    fun langGroupOf(bcp47: String?): String {
        val primary = bcp47?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
        return when {
            primary.isEmpty() -> "other"
            primary in CHINESE_TAGS -> "zh"
            primary in LANG_KEYS && primary !in setOf("mine", "multi", "other") -> primary
            else -> "other"
        }
    }

    /** 去掉 OpenRouter 模型 id 的变体后缀（:free / :nitro 等） */
    fun canonicalModelId(id: String): String {
        val i = id.indexOf(':')
        return if (i > 0) id.substring(0, i) else id
    }

    private data class LangNote(val lang: String, val note: String? = null)
    private data class GN(val gender: String, val note: String)

    /* ---------------- 与语言无关的数据 ---------------- */

    private val VOXTRAL_GENDER = mapOf("paul" to "m", "oliver" to "m", "jane" to "f", "marie" to "f")
    private val MAI_GENDER = mapOf("Harper" to "f", "Valeria" to "f", "Soleil" to "f", "Klaus" to "m")

    private val DEEPGRAM_GENDER: Map<String, String> = run {
        val f = "thalia agathe agustina amalthea andromeda antonia asteria athena aurelia aurora beatrix callista carina celeste " +
            "cinzia cora cordelia cornelia daphne delia demetra diana elara electra estrella gloria harmonia helena hera hestia iris " +
            "izanami juno kara lara leda livia luna maia melia minerva olivia ophelia pandora phoebe rhea selena selene silvia theia " +
            "uzume vesta viktoria"
        val m = "alvaro apollo arcas atlas cesare dionisio draco elio fabian flavio hector hermes hyperion javier julius jupiter lars " +
            "luciano mars neptune nestor odysseus orion orpheus pluto roman sander saturn sirio valerio zeus"
        val map = HashMap<String, String>()
        f.split(' ').forEach { map[it] = "f" }
        m.split(' ').forEach { map[it] = "m" }
        map
    }

    private val GEMINI_IDS = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus",
        "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
        "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )
    private val KOKORO_IDS = listOf(
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george",
        "bm_lewis", "ef_dora", "em_alex", "em_santa", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo", "pf_dora",
        "pm_alex", "pm_santa"
    )
    private val VOXTRAL_IDS = listOf(
        "en_paul_neutral", "en_paul_cheerful", "en_paul_happy", "en_paul_excited", "en_paul_confident", "en_paul_sad",
        "en_paul_frustrated", "en_paul_angry", "gb_oliver_neutral", "gb_oliver_cheerful", "gb_oliver_excited",
        "gb_oliver_curious", "gb_oliver_confident", "gb_oliver_sad", "gb_oliver_angry", "gb_jane_neutral",
        "gb_jane_confident", "gb_jane_curious", "gb_jane_sad", "gb_jane_frustrated", "gb_jane_confused",
        "gb_jane_sarcasm", "gb_jane_shameful", "gb_jane_jealousy", "fr_marie_neutral", "fr_marie_happy",
        "fr_marie_excited", "fr_marie_curious", "fr_marie_sad", "fr_marie_angry"
    )
    private val HEYGEN_IDS = listOf("nadine", "tabitha", "bram", "gareth", "noelle", "meredith", "sabrina", "lucian", "weston", "vincent")
    private val QWEN3_IDS = listOf("vivian", "serena", "uncle_fu", "dylan", "eric", "ryan", "aiden", "ono_anna", "sohee")
    private val ZONOS_IDS = listOf("american_female", "american_male", "british_female", "british_male", "random")
    private val ORPHEUS_IDS = listOf("tara", "leah", "jess", "leo", "dan", "mia", "zac")
    private val SESAME_IDS = listOf("conversational_a", "conversational_b", "read_speech_a", "read_speech_b", "read_speech_c", "read_speech_d", "none")
    private val MAI_IDS = listOf("en-US-Harper:MAI-Voice-2", "es-MX-Valeria:MAI-Voice-2", "fr-FR-Soleil:MAI-Voice-2", "de-DE-Klaus:MAI-Voice-2")
    private val DEEPGRAM_IDS = listOf(
        "aura-2-thalia-en", "aura-2-amalthea-en", "aura-2-andromeda-en", "aura-2-apollo-en", "aura-2-arcas-en",
        "aura-2-aries-en", "aura-2-asteria-en", "aura-2-athena-en", "aura-2-atlas-en", "aura-2-aurora-en",
        "aura-2-callista-en", "aura-2-cora-en", "aura-2-cordelia-en", "aura-2-delia-en", "aura-2-draco-en",
        "aura-2-electra-en", "aura-2-harmonia-en", "aura-2-helena-en", "aura-2-hera-en", "aura-2-hermes-en",
        "aura-2-hyperion-en", "aura-2-iris-en", "aura-2-janus-en", "aura-2-juno-en", "aura-2-jupiter-en",
        "aura-2-luna-en", "aura-2-mars-en", "aura-2-minerva-en", "aura-2-neptune-en", "aura-2-odysseus-en",
        "aura-2-ophelia-en", "aura-2-orion-en", "aura-2-orpheus-en", "aura-2-pandora-en", "aura-2-phoebe-en",
        "aura-2-pluto-en", "aura-2-saturn-en", "aura-2-selene-en", "aura-2-theia-en", "aura-2-vesta-en",
        "aura-2-zeus-en", "aura-2-agathe-fr", "aura-2-hector-fr", "aura-2-agustina-es", "aura-2-alvaro-es",
        "aura-2-antonia-es", "aura-2-aquila-es", "aura-2-carina-es", "aura-2-celeste-es", "aura-2-diana-es",
        "aura-2-estrella-es", "aura-2-gloria-es", "aura-2-javier-es", "aura-2-luciano-es", "aura-2-nestor-es",
        "aura-2-olivia-es", "aura-2-selena-es", "aura-2-silvia-es", "aura-2-sirio-es", "aura-2-valerio-es",
        "aura-2-ama-ja", "aura-2-ebisu-ja", "aura-2-fujin-ja", "aura-2-izanami-ja", "aura-2-uzume-ja",
        "aura-2-aurelia-de", "aura-2-elara-de", "aura-2-fabian-de", "aura-2-julius-de", "aura-2-kara-de",
        "aura-2-lara-de", "aura-2-viktoria-de", "aura-2-cesare-it", "aura-2-cinzia-it", "aura-2-demetra-it",
        "aura-2-dionisio-it", "aura-2-elio-it", "aura-2-flavio-it", "aura-2-livia-it", "aura-2-maia-it",
        "aura-2-melia-it", "aura-2-beatrix-nl", "aura-2-cornelia-nl", "aura-2-daphne-nl", "aura-2-hestia-nl",
        "aura-2-lars-nl", "aura-2-leda-nl", "aura-2-rhea-nl", "aura-2-roman-nl", "aura-2-sander-nl"
    )

    /** 无在线列表时的兜底音色表（与 2026-11 线上 supported_voices 一致；大目录不内置，以在线列表为准） */
    private val STATIC_VOICES: Map<String, List<String>> = mapOf(
        "hexgrad/kokoro-82m" to KOKORO_IDS,
        "qwen/qwen-audio-3.0-tts-flash" to listOf("loongjohn", "longanhuan_v3.6"),
        "qwen/qwen-audio-3.0-tts-plus" to listOf("longanlingxin", "longanlufeng"),
        "google/gemini-3.1-flash-tts-preview" to GEMINI_IDS,
        "google/gemini-3.8-flash-tts" to GEMINI_IDS,
        "google/gemini-3.8-flash-lite-tts" to GEMINI_IDS,
        "google/gemini-2.5-flash-tts" to GEMINI_IDS,
        "google/gemini-2.5-pro-tts" to GEMINI_IDS,
        "qwen/qwen3-tts-1.7b" to QWEN3_IDS,
        "heygen/heygen-voice-1" to HEYGEN_IDS,
        "heygen/heygen-voice-1-turbo" to HEYGEN_IDS,
        "x-ai/grok-voice-tts-1.0" to listOf("eve", "ara", "rex", "sal", "leo"),
        "zyphra/zonos-v0.1-transformer" to ZONOS_IDS,
        "zyphra/zonos-v0.1-hybrid" to ZONOS_IDS,
        "canopylabs/orpheus-3b-0.1-ft" to ORPHEUS_IDS,
        "sesame/csm-1b" to SESAME_IDS,
        "mistralai/voxtral-mini-tts-2603" to VOXTRAL_IDS,
        "microsoft/mai-voice-2" to MAI_IDS,
        "microsoft/mai-voice-2-flash" to MAI_IDS,
        "deepgram/aura-2" to DEEPGRAM_IDS
    )

    /* ---------------- 带文案的表：按语言各建一份 ---------------- */

    /**
     * 所有带界面文案的表。构建时拿到的是明确的语言参数 [en]，不读全局语言状态，
     * 所以中英两份缓存各自确定（见 [PerLanguage]）。中文版与 0.3.x 逐字一致。
     */
    private class Tables(en: Boolean) {
        /** 这一份表对应的语言包：构建时不读全局语言，中英两份缓存各自确定 */
        private val s = I18n.pack(en)


        val kokoroLang = mapOf(
            "a" to LangNote("en", s.catalog.us), "b" to LangNote("en", s.catalog.uk), "e" to LangNote("es"), "f" to LangNote("fr"),
            "h" to LangNote("hi"), "i" to LangNote("it"), "j" to LangNote("ja"), "p" to LangNote("pt", s.catalog.brazil), "z" to LangNote("zh")
        )

        /** Kokoro 中文音色的名字：中文界面显示汉字，英文界面显示拼音 */
        val kokoroZhNames: Map<String, String> = mapOf(
            "xiaobei" to s.catalog.xiaobei, "xiaoni" to s.catalog.xiaoni, "xiaoxiao" to s.catalog.xiaoxiao, "xiaoyi" to s.catalog.xiaoyi,
            "yunjian" to s.catalog.yunjian, "yunxi" to s.catalog.yunxi, "yunxia" to s.catalog.yunxia, "yunyang" to s.catalog.yunyang
        )

        /** Gemini 30 个预置音色：性别 + 官方描述词（Bright / Upbeat …，中文为意译） */
        val gemini = linkedMapOf(
            "Zephyr" to GN("f", s.catalog.bright), "Puck" to GN("m", s.catalog.upbeat), "Charon" to GN("m", s.catalog.informative),
            "Kore" to GN("f", s.catalog.firm), "Fenrir" to GN("m", s.catalog.excitable), "Leda" to GN("f", s.catalog.youthful),
            "Orus" to GN("m", s.catalog.firm), "Aoede" to GN("f", s.catalog.breezy), "Callirrhoe" to GN("f", s.catalog.easyGoing),
            "Autonoe" to GN("f", s.catalog.bright), "Enceladus" to GN("m", s.catalog.breathy), "Iapetus" to GN("m", s.catalog.clear),
            "Umbriel" to GN("m", s.catalog.easyGoing), "Algieba" to GN("m", s.catalog.smooth), "Despina" to GN("f", s.catalog.smooth),
            "Erinome" to GN("f", s.catalog.clear), "Algenib" to GN("m", s.catalog.gravelly), "Rasalgethi" to GN("m", s.catalog.informative),
            "Laomedeia" to GN("f", s.catalog.upbeatBright), "Achernar" to GN("f", s.catalog.soft), "Alnilam" to GN("m", s.catalog.firmSolid),
            "Schedar" to GN("m", s.catalog.even), "Gacrux" to GN("f", s.catalog.mature), "Pulcherrima" to GN("f", s.catalog.forward),
            "Achird" to GN("m", s.catalog.friendly), "Zubenelgenubi" to GN("m", s.catalog.casual), "Vindemiatrix" to GN("f", s.catalog.gentle),
            "Sadachbia" to GN("m", s.catalog.livelyActive), "Sadaltager" to GN("m", s.catalog.knowledgeable), "Sulafat" to GN("f", s.common.warm)
        )

        val grok = linkedMapOf(
            "eve" to VoiceInfo("eve", "Eve", gender = "f", note = s.catalog.britishWarm),
            "ara" to VoiceInfo("ara", "Ara", gender = "f", note = s.catalog.brightLively),
            "rex" to VoiceInfo("rex", "Rex", gender = "m", note = s.catalog.calm),
            "sal" to VoiceInfo("sal", "Sal"),
            "leo" to VoiceInfo("leo", "Leo")
        )

        val orpheus = linkedMapOf(
            "tara" to VoiceInfo("tara", "Tara", gender = "f", note = s.catalog.mostNatural),
            "leah" to VoiceInfo("leah", "Leah", gender = "f"),
            "jess" to VoiceInfo("jess", "Jess", gender = "f"),
            "leo" to VoiceInfo("leo", "Leo", gender = "m"),
            "dan" to VoiceInfo("dan", "Dan", gender = "m"),
            "mia" to VoiceInfo("mia", "Mia", gender = "f"),
            "zac" to VoiceInfo("zac", "Zac", gender = "m")
        )

        val sesame = linkedMapOf(
            "conversational_a" to s.catalog.conversationalA, "conversational_b" to s.catalog.conversationalB,
            "read_speech_a" to s.catalog.readSpeechA, "read_speech_b" to s.catalog.readSpeechB,
            "read_speech_c" to s.catalog.readSpeechC, "read_speech_d" to s.catalog.readSpeechD,
            "none" to s.catalog.modelDefault
        )

        val zonos = linkedMapOf(
            "american_female" to VoiceInfo("american_female", s.catalog.usFemale, "en", "f"),
            "american_male" to VoiceInfo("american_male", s.catalog.usMale, "en", "m"),
            "british_female" to VoiceInfo("british_female", s.catalog.ukFemale, "en", "f"),
            "british_male" to VoiceInfo("british_male", s.catalog.ukMale, "en", "m"),
            "random" to VoiceInfo("random", s.catalog.random, "en", note = s.catalog.variesEachTime)
        )

        val qwen = linkedMapOf(
            "loongjohn" to VoiceInfo("loongjohn", "Loong John", "zh", "m", s.catalog.chineseEnglish),
            "longanhuan_v3.6" to VoiceInfo("longanhuan_v3.6", "Longan Huan", "zh"),
            "longanlingxin" to VoiceInfo("longanlingxin", "Longan Lingxin", "zh"),
            "longanlufeng" to VoiceInfo("longanlufeng", "Longan Lufeng", "zh")
        )

        /** Qwen3 TTS 的 9 个预置说话人（官方模型卡 Qwen3-TTS-12Hz-1.7B-CustomVoice 的说话人表） */
        val qwen3 = linkedMapOf(
            "vivian" to VoiceInfo("vivian", "Vivian", "zh", "f", s.catalog.bright),
            "serena" to VoiceInfo("serena", "Serena", "zh", "f", s.catalog.gentleSoft),
            "uncle_fu" to VoiceInfo("uncle_fu", "Uncle Fu", "zh", "m", s.catalog.lowMellow),
            "dylan" to VoiceInfo("dylan", "Dylan", "zh", "m", s.catalog.beijingDialect),
            "eric" to VoiceInfo("eric", "Eric", "zh", "m", s.catalog.sichuanDialect),
            "ryan" to VoiceInfo("ryan", "Ryan", "en", "m", s.catalog.rhythmic),
            "aiden" to VoiceInfo("aiden", "Aiden", "en", "m", s.catalog.us),
            "ono_anna" to VoiceInfo("ono_anna", "Ono Anna", "ja", "f", s.catalog.playful),
            "sohee" to VoiceInfo("sohee", "Sohee", "ko", "f", s.common.warm)
        )

        val voxtralLocale = mapOf(
            "en" to LangNote("en", s.catalog.us), "gb" to LangNote("en", s.catalog.uk), "fr" to LangNote("fr"), "es" to LangNote("es"),
            "de" to LangNote("de"), "it" to LangNote("it"), "pt" to LangNote("pt"), "nl" to LangNote("nl")
        )

        /** Voxtral 音色名里的情绪词 */
        val emotion: Map<String, String> = mapOf(
            "neutral" to s.catalog.neutral, "happy" to s.catalog.happy, "cheerful" to s.catalog.cheerful,
            "excited" to s.catalog.excited, "confident" to s.catalog.confident, "curious" to s.catalog.curious,
            "sad" to s.catalog.sad, "frustrated" to s.catalog.frustrated, "angry" to s.catalog.angry,
            "sarcasm" to s.catalog.sarcastic, "confused" to s.catalog.confused, "shameful" to s.catalog.ashamed,
            "jealousy" to s.catalog.jealous
        )

        val minimaxSuggestions = listOf(
            VoiceInfo("audiobook_female_1", s.catalog.audiobookF1, "zh", "f"),
            VoiceInfo("audiobook_female_2", s.catalog.audiobookF2, "zh", "f"),
            VoiceInfo("audiobook_male_1", s.catalog.audiobookM1, "zh", "m"),
            VoiceInfo("audiobook_male_2", s.catalog.audiobookM2, "zh", "m"),
            VoiceInfo("presenter_female", s.catalog.femaleHost, "zh", "f"),
            VoiceInfo("presenter_male", s.catalog.maleHost, "zh", "m"),
            VoiceInfo("female-shaonv", s.catalog.youngGirl, "zh", "f"),
            VoiceInfo("female-tianmei", s.catalog.sweetFemale, "zh", "f"),
            VoiceInfo("female-yujie", s.catalog.poisedLady, "zh", "f"),
            VoiceInfo("female-chengshu", s.catalog.matureFemale, "zh", "f"),
            VoiceInfo("male-qn-qingse", s.catalog.shyYoungMan, "zh", "m"),
            VoiceInfo("male-qn-jingying", s.catalog.eliteYoungMan, "zh", "m"),
            VoiceInfo("male-qn-badao", s.catalog.boldYoungMan, "zh", "m"),
            VoiceInfo("male-qn-daxuesheng", s.catalog.collegeStudent, "zh", "m")
        )

        val fishFreeVoice = FreeVoiceHint(
            placeholder = s.catalog.fishVoicePlaceholder,
            hint = s.catalog.fishVoiceHint
        )
        val minimaxFreeVoice = FreeVoiceHint(
            placeholder = "audiobook_female_1",
            hint = s.catalog.minimaxVoiceHint,
            suggestions = minimaxSuggestions
        )

        private val multiZh = s.catalog.multilingualInclChinese

        val modelHints: Map<String, ModelHints> = mapOf(
            "hexgrad/kokoro-82m" to ModelHints("Kokoro", s.catalog.kokoroLangs, s.catalog.openLightweight, preferred = if (en) "af_heart" else "zf_xiaoxiao", zh = true),
            "qwen/qwen-audio-3.0-tts-flash" to ModelHints(s.catalog.qwenTtsFlash, s.catalog.mostlyChinese, s.catalog.alibabaDashscopeTts, zh = true),
            "qwen/qwen-audio-3.0-tts-plus" to ModelHints(s.catalog.qwenTtsPlus, s.catalog.mostlyChinese, s.catalog.alibabaDashscopeHq, zh = true),
            "minimax/speech-2.8-turbo" to ModelHints("MiniMax Turbo", s.catalog.chineseEnglishMore, freeVoice = minimaxFreeVoice, zh = true),
            "minimax/speech-2.8-hd" to ModelHints("MiniMax HD", s.catalog.chineseEnglishMore, freeVoice = minimaxFreeVoice, zh = true),
            "fish-audio/s1" to ModelHints("Fish S1", multiZh, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "fish-audio/s2-pro" to ModelHints("Fish S2 Pro", multiZh, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "fish-audio/s2.1-pro" to ModelHints("Fish S2.1 Pro", multiZh, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "fish-audio/s2.1-pro-free" to ModelHints(s.catalog.fishS21Free, multiZh, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "fish-audio/drama-3" to ModelHints("Fish Drama 3", multiZh, s.catalog.stageDirectionsInBrackets, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "google/gemini-3.1-flash-tts-preview" to ModelHints("Gemini TTS", s.catalog.n24Languages, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "google/gemini-3.8-flash-tts" to ModelHints("Gemini 3.8 Flash TTS", s.catalog.n130Languages, s.catalog.flagshipBestForAudiobooks, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "google/gemini-3.8-flash-lite-tts" to ModelHints("Gemini 3.8 Flash-Lite TTS", s.catalog.n101Languages, s.catalog.fasterCheaper, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "google/gemini-2.5-flash-tts" to ModelHints("Gemini 2.5 Flash TTS", multiZh, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "google/gemini-2.5-pro-tts" to ModelHints("Gemini 2.5 Pro TTS", multiZh, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "heygen/heygen-voice-1" to ModelHints("HeyGen Voice", note = s.catalog.bracketAudioTags, pcmOnly = true, pcmRate = 44100),
            "heygen/heygen-voice-1-turbo" to ModelHints("HeyGen Voice Turbo", note = s.catalog.lowLatencyAudioTags, pcmOnly = true, pcmRate = 44100),
            "qwen/qwen3-tts-1.7b" to ModelHints("Qwen3 TTS", s.catalog.n10Languages, s.catalog.includesBeijingSichuanDialect, preferred = if (en) "aiden" else "serena", zh = true),
            "microsoft/mai-voice-2.1" to ModelHints("MAI-Voice-2.1", s.catalog.n23Languages, s.catalog.consistentOverLongReads, zh = true),
            "microsoft/mai-voice-2.1-flash" to ModelHints("MAI-Voice-2.1 Flash", s.catalog.n23Languages, s.catalog.lowLatencyTier, zh = true),
            "elevenlabs/eleven-v4" to ModelHints("Eleven v4", s.catalog.n90Languages, s.catalog.mostExpressive, zh = true),
            "elevenlabs/eleven-v4-turbo" to ModelHints("Eleven v4 Turbo", s.catalog.n90Languages, s.catalog.lowLatency, zh = true),
            "elevenlabs/eleven-v3" to ModelHints("Eleven v3", s.catalog.n70Languages, s.catalog.maxCharsPerRequest, zh = true),
            "elevenlabs/eleven-v3-conversational" to ModelHints(s.catalog.elevenV3Conversational, s.catalog.n70Languages, zh = true),
            "elevenlabs/eleven-multilingual-v2" to ModelHints("Eleven Multilingual v2", s.catalog.n29Languages, s.catalog.longFormNarrationAudiobooks, zh = true),
            "elevenlabs/eleven-turbo-v2.5" to ModelHints("Eleven Turbo v2.5", s.catalog.n32Languages, zh = true),
            "elevenlabs/eleven-flash-v2.5" to ModelHints("Eleven Flash v2.5", s.catalog.n32Languages, s.catalog.ultraLowLatency, zh = true),
            "elevenlabs/eleven-turbo-v2" to ModelHints("Eleven Turbo v2", s.catalog.english),
            "elevenlabs/eleven-flash-v2" to ModelHints("Eleven Flash v2", s.catalog.english, s.catalog.ultraLowLatency),
            "mistralai/voxtral-mini-tts-3-0" to ModelHints("Voxtral TTS 3", note = s.catalog.emotionPaceTags, cloning = true),
            "speechify/simba-3.5" to ModelHints("Simba 3.5", s.catalog.n48Languages),
            "assemblyai/assemblyai-tts-1" to ModelHints("AssemblyAI TTS 1", s.catalog.multilingual),
            "deepgram/flux-tts" to ModelHints("Flux TTS", s.catalog.english),
            "x-ai/grok-voice-tts-1.0" to ModelHints("Grok Voice", s.catalog.n20LanguagesAutoDetected, zh = true),
            "microsoft/mai-voice-2" to ModelHints("MAI-Voice-2", s.catalog.n15Languages),
            "microsoft/mai-voice-2-flash" to ModelHints("MAI-Voice-2 Flash", s.catalog.n15Languages, s.catalog.lowLatencyTier),
            "deepgram/aura-2" to ModelHints("Aura-2", s.catalog.n7Languages),
            "zyphra/zonos-v0.1-transformer" to ModelHints("Zonos", s.catalog.english),
            "zyphra/zonos-v0.1-hybrid" to ModelHints("Zonos Hybrid", s.catalog.english),
            "canopylabs/orpheus-3b-0.1-ft" to ModelHints("Orpheus 3B", s.catalog.english),
            "sesame/csm-1b" to ModelHints("Sesame CSM", s.catalog.english),
            "mistralai/voxtral-mini-tts-2603" to ModelHints("Voxtral TTS", s.catalog.englishFrench, s.catalog.voxtralEmotionsNote, cloning = true)
        )

        /**
         * 按厂商前缀兜底（未来同厂新模型自动继承音色语义）。
         * google 与 heygen 这一条是必须的：OpenRouter 上它们只回 pcm，下一个新上架的 Gemini / HeyGen 模型
         * 如果没有被逐个登记，请求 mp3 就是 400 —— 0.3.x 对 Gemini 3.8 / 2.5 正是这样失败的。
         */
        val vendorHints: Map<String, ModelHints> = mapOf(
            "fish-audio" to ModelHints(langs = multiZh, freeVoice = fishFreeVoice, voiceOptional = true, cloning = true, instructions = true, zh = true),
            "minimax" to ModelHints(langs = s.catalog.chineseEnglishMore, freeVoice = minimaxFreeVoice, zh = true),
            "google" to ModelHints(langs = multiZh, pcmOnly = true, instructions = true, preferred = "Kore", zh = true),
            "heygen" to ModelHints(pcmOnly = true, pcmRate = 44100)
        )

        /**
         * 快捷推荐（听书场景优选，覆盖 免费/低价/中文/多语言 各档）。
         * 英文界面把偏中文的通义 / MiniMax 换成擅长英文长篇朗读的两家。
         */
        val recommended: List<RecommendedModel> = if (en) listOf(
            RecommendedModel("hexgrad/kokoro-82m", "Kokoro", s.catalog.cheapestTag),
            RecommendedModel("google/gemini-3.8-flash-tts", "Gemini 3.8", s.catalog.flagshipTag),
            RecommendedModel("elevenlabs/eleven-v4-turbo", "Eleven v4 Turbo", s.catalog.expressiveTag),
            RecommendedModel("fish-audio/s2.1-pro-free:free", "Fish S2.1", s.common.free),
            RecommendedModel("microsoft/mai-voice-2.1", "MAI-Voice-2.1", s.catalog.longFormTag),
            RecommendedModel("x-ai/grok-voice-tts-1.0", "Grok Voice", s.catalog.multilingual)
        ) else listOf(
            RecommendedModel("hexgrad/kokoro-82m", "Kokoro", s.catalog.cheapestTag),
            RecommendedModel("qwen/qwen-audio-3.0-tts-flash", s.catalog.qwenTts, s.common.chinese),
            RecommendedModel("minimax/speech-2.8-turbo", "MiniMax", s.catalog.audiobooksTag),
            RecommendedModel("fish-audio/s2.1-pro-free:free", "Fish S2.1", s.common.free),
            RecommendedModel("google/gemini-3.8-flash-tts", "Gemini 3.8", s.catalog.flagshipTag),
            RecommendedModel("x-ai/grok-voice-tts-1.0", "Grok Voice", s.catalog.multilingual)
        )
    }

    private val tables = PerLanguage { Tables(it) }
    private val T: Tables get() = tables.value

    fun modelHints(modelId: String): ModelHints? {
        val id = canonicalModelId(modelId)
        return T.modelHints[id] ?: T.vendorHints[id.substringBefore('/')]
    }

    private fun cap(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private val NAME_RE = Regex("^[a-z]+$")
    private val NAME_STYLE_VENDORS = setOf("elevenlabs", "heygen", "speechify")
    private val KOKORO_RE = Regex("^([a-z])([fm])_(.+)$")
    private val DEEPGRAM_RE = Regex("^aura-2-([a-z]+)-([a-z]{2})$")
    private val VOXTRAL_RE = Regex("^([a-z]{2})_([a-z]+)_([a-z]+)$")
    private val MAI_RE = Regex("^([a-z]{2})-([A-Z]{2})-([A-Za-z]+):")

    /** 解析单个音色 ID 为带语言/性别/风格的条目；无法识别时退化为原样标签 */
    fun describeVoice(modelId: String, voiceId: String): VoiceInfo {
        val cid = canonicalModelId(modelId)
        val vendor = cid.substringBefore('/')
        val t = T

        if (vendor == "hexgrad") {
            val m = KOKORO_RE.find(voiceId)
            val ln = m?.let { t.kokoroLang[it.groupValues[1]] }
            if (m != null && ln != null) {
                val name = m.groupValues[3]
                val label = if (ln.lang == "zh") t.kokoroZhNames[name] ?: cap(name) else cap(name)
                return VoiceInfo(voiceId, label, ln.lang, m.groupValues[2], ln.note)
            }
        }
        if (vendor == "deepgram") {
            val m = DEEPGRAM_RE.find(voiceId)
            if (m != null) {
                val lang = m.groupValues[2]
                return VoiceInfo(voiceId, cap(m.groupValues[1]), if (lang in LANG_KEYS) lang else "other", DEEPGRAM_GENDER[m.groupValues[1]])
            }
        }
        if (vendor == "mistralai") {
            val m = VOXTRAL_RE.find(voiceId)
            val loc = m?.let { t.voxtralLocale[it.groupValues[1]] }
            if (m != null && loc != null) {
                val emotion = t.emotion[m.groupValues[3]] ?: cap(m.groupValues[3])
                return VoiceInfo(voiceId, "${cap(m.groupValues[2])}·$emotion", loc.lang, VOXTRAL_GENDER[m.groupValues[2]], loc.note)
            }
        }
        if (vendor == "microsoft") {
            val m = MAI_RE.find(voiceId)
            if (m != null) {
                // MAI-Voice-2.1 起覆盖 23 种语言（含 zh-CN 的 6 个音色），语种直接取音色 ID 自带的地区标签
                return VoiceInfo(voiceId, m.groupValues[3], langGroupOf(m.groupValues[1]), MAI_GENDER[m.groupValues[3]], "${m.groupValues[1]}-${m.groupValues[2]}")
            }
        }
        if (vendor == "google") t.gemini[voiceId]?.let { return VoiceInfo(voiceId, voiceId, "multi", it.gender, it.note) }
        if (vendor == "x-ai") t.grok[voiceId]?.let { return it.copy(lang = "multi") }
        if (vendor == "zyphra") t.zonos[voiceId]?.let { return it }
        if (vendor == "canopylabs") t.orpheus[voiceId]?.let { return it.copy(lang = "en") }
        if (vendor == "sesame") t.sesame[voiceId]?.let { return VoiceInfo(voiceId, it, "en") }
        if (vendor == "qwen") (t.qwen[voiceId] ?: t.qwen3[voiceId])?.let { return it }
        // 纯小写人名式的音色 ID（ElevenLabs / HeyGen / Speechify…）：至少把首字母大写，别让音色表看起来像变量名
        if (vendor in NAME_STYLE_VENDORS && NAME_RE.matches(voiceId)) return VoiceInfo(voiceId, cap(voiceId))
        if (vendor == "minimax") t.minimaxSuggestions.firstOrNull { it.id == voiceId }?.let { return it }
        return VoiceInfo(voiceId, voiceId)
    }

    /* ---------- Gemini 原生接口的音色 ---------- */

    /** Gemini 3.8 的 30 个预置精选音色（官方「Prebuilt voices」表）。每个音色都能说全部支持的语言，归「多语言」 */
    fun geminiPrebuiltVoices(): List<VoiceInfo> = T.gemini.map { (id, gn) -> VoiceInfo(id, id, "multi", gn.gender, gn.note) }

    fun isGeminiPrebuilt(id: String): Boolean = id in GEMINI_IDS

    /** 自建音色：音色设计 / 改造（prompted）与音色复刻（replicated），只属于 Key 所在的项目 */
    fun isGeminiCustom(v: GeminiVoiceEntry): Boolean = v.type == "prompted" || v.type == "replicated" || v.id.startsWith("voice_")

    /**
     * 目录条目 → 音色表条目：精选音色沿用上面的描述；扩展音色库按 language_code 分到语种，
     * 备注取口音与适用场景（Audiobook 之类，原样保留官方英文标签，筛选时也按它匹配）；自建音色单独成组。
     */
    fun describeGeminiVoice(v: GeminiVoiceEntry): VoiceInfo {
        T.gemini[v.id]?.let { return VoiceInfo(v.id, v.id, "multi", it.gender, it.note) }
        val gender = when (v.gender) { "female" -> "f"; "male" -> "m"; else -> null }
        val note = listOfNotNull(v.accent, v.context ?: v.persona).distinct().joinToString("·").ifEmpty { null }
        val lang = if (isGeminiCustom(v)) "mine" else langGroupOf(v.languageCode)
        return VoiceInfo(v.id, v.displayName.ifBlank { v.id }, lang, gender, note)
    }

    /** 完整的 Gemini 音色表：自建音色 + 30 个精选 + 扩展音色库（去重，精选总在前） */
    fun geminiCatalog(remote: List<GeminiVoiceEntry>): List<VoiceInfo> {
        val out = LinkedHashMap<String, VoiceInfo>()
        remote.filter { isGeminiCustom(it) }.forEach { out[it.id] = describeGeminiVoice(it) }
        geminiPrebuiltVoices().forEach { out.putIfAbsent(it.id, it) }
        remote.forEach { out.putIfAbsent(it.id, describeGeminiVoice(it)) }
        return out.values.toList()
    }

    /** 模型的完整音色目录：在线列表优先，其次内置兜底表。开放音色模型返回空列表。 */
    fun catalogVoices(modelId: String, serverVoices: List<String>? = null): List<VoiceInfo> {
        if (modelHints(modelId)?.freeVoice != null) return emptyList()
        val ids = if (!serverVoices.isNullOrEmpty()) serverVoices else STATIC_VOICES[canonicalModelId(modelId)] ?: emptyList()
        return ids.map { describeVoice(modelId, it) }
    }

    /** 按语言分组：（自建 →）界面母语 → 多语言 → 另一种主语言 → … → 其他/未知 */
    fun groupVoices(voices: List<VoiceInfo>): List<VoiceGroup> {
        val order = if (I18n.english) ORDER_EN else ORDER_ZH
        val map = LinkedHashMap<String, MutableList<VoiceInfo>>()
        for (v in voices) map.getOrPut(v.lang ?: "other") { ArrayList() }.add(v)
        fun rank(lang: String): Int = order.indexOf(lang).let { if (it < 0) order.size else it }
        return map.entries.sortedBy { rank(it.key) }.map { VoiceGroup(it.key, langLabel(it.key), it.value) }
    }

    /** 切换模型后的默认音色：模型偏好 → 界面母语（中文 / 英语）→ 多语言 → 首个；开放音色模型给建议首项或空 */
    fun defaultVoiceFor(modelId: String, serverVoices: List<String>? = null): String {
        val hints = modelHints(modelId)
        if (hints?.freeVoice != null) {
            return if (hints.voiceOptional) "" else hints.freeVoice.suggestions.firstOrNull()?.id ?: ""
        }
        val all = catalogVoices(modelId, serverVoices)
        if (all.isEmpty()) return ""
        val preferred = hints?.preferred
        if (preferred != null && all.any { it.id == preferred }) return preferred
        val home = homeLang
        val best = all.firstOrNull { it.lang == home } ?: all.firstOrNull { it.lang == "multi" }
        return (best ?: all[0]).id
    }

    /** 快捷推荐（按界面语言） */
    val RECOMMENDED_MODELS: List<RecommendedModel> get() = T.recommended

    /** 模型信息行：价格 · 语言 · 音色数 · 能力标记 */
    fun formatModelMeta(info: TtsModelInfo?, modelId: String): String {
        val hints = modelHints(modelId)
        val parts = ArrayList<String>()
        val completion = info?.completionPrice
        val prompt = info?.promptPrice
        if (completion != null && completion > 0) {
            parts.add(S.common.perTokenPricing)
        } else if (prompt != null && prompt > 0) {
            val perM = prompt * 1e6
            val shown = if (perM >= 10) Math.round(perM).toString() else (Math.round(perM * 100) / 100.0).toString().removeSuffix(".0")
            parts.add(S.catalog.pricePerMillionChars(shown))
        } else if (modelId.contains(":free")) {
            parts.add(S.common.free)
        }
        hints?.langs?.let { parts.add(it) }
        val n = catalogVoices(modelId, info?.voices).size
        if (n > 0) parts.add(S.catalog.voicesCount(n)) else if (hints?.freeVoice != null) parts.add(S.catalog.openVoiceIds)
        if (hints?.cloning == true) parts.add(S.catalog.cloning)
        hints?.note?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }
}
