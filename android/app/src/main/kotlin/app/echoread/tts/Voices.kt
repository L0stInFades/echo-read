package app.echoread.tts

import app.echoread.core.TtsModelInfo

/**
 * 音色目录：OpenRouter TTS 模型的精细音色体系（与网页版 tts/voices.ts 同步）。
 * ① 结构化音色 ID —— 解析为 语言/性别/风格（Kokoro、Deepgram、Voxtral、MAI…）
 * ② 具名音色表 —— 逐个标注（Gemini、Grok、Orpheus…）
 * ③ 开放音色 ID —— 服务端不枚举（Fish Audio 参考 ID、MiniMax 系统音色），给精选建议
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
    val pcmOnly: Boolean = false,
    val cloning: Boolean = false,
    val preferred: String? = null
)

data class RecommendedModel(val id: String, val label: String, val tag: String)

object Voices {
    private val LANG_LABELS = mapOf(
        "zh" to "中文", "multi" to "多语言", "en" to "英语", "ja" to "日语", "es" to "西班牙语", "fr" to "法语",
        "de" to "德语", "it" to "意大利语", "nl" to "荷兰语", "pt" to "葡萄牙语", "hi" to "印地语", "other" to "其他"
    )
    private val LANG_ORDER = listOf("zh", "multi", "en", "ja", "es", "fr", "de", "it", "nl", "pt", "hi", "other")

    fun langLabel(lang: String): String = LANG_LABELS[lang] ?: lang

    /** 去掉 OpenRouter 模型 id 的变体后缀（:free / :nitro 等） */
    fun canonicalModelId(id: String): String {
        val i = id.indexOf(':')
        return if (i > 0) id.substring(0, i) else id
    }

    private data class LangNote(val lang: String, val note: String? = null)

    private val KOKORO_LANG = mapOf(
        "a" to LangNote("en", "美音"), "b" to LangNote("en", "英音"), "e" to LangNote("es"), "f" to LangNote("fr"),
        "h" to LangNote("hi"), "i" to LangNote("it"), "j" to LangNote("ja"), "p" to LangNote("pt", "巴西"), "z" to LangNote("zh")
    )
    private val KOKORO_ZH = mapOf(
        "xiaobei" to "晓北", "xiaoni" to "晓妮", "xiaoxiao" to "晓晓", "xiaoyi" to "晓伊",
        "yunjian" to "云健", "yunxi" to "云希", "yunxia" to "云夏", "yunyang" to "云扬"
    )

    private data class GN(val gender: String, val note: String)

    private val GEMINI_VOICES = linkedMapOf(
        "Zephyr" to GN("f", "明亮"), "Puck" to GN("m", "活泼"), "Charon" to GN("m", "知性"), "Kore" to GN("f", "坚定"),
        "Fenrir" to GN("m", "激昂"), "Leda" to GN("f", "年轻"), "Orus" to GN("m", "坚定"), "Aoede" to GN("f", "轻快"),
        "Callirrhoe" to GN("f", "随和"), "Autonoe" to GN("f", "明亮"), "Enceladus" to GN("m", "气声"), "Iapetus" to GN("m", "清晰"),
        "Umbriel" to GN("m", "随和"), "Algieba" to GN("m", "圆润"), "Despina" to GN("f", "圆润"), "Erinome" to GN("f", "清晰"),
        "Algenib" to GN("m", "沙哑"), "Rasalgethi" to GN("m", "知性"), "Laomedeia" to GN("f", "明快"), "Achernar" to GN("f", "轻柔"),
        "Alnilam" to GN("m", "坚实"), "Schedar" to GN("m", "平稳"), "Gacrux" to GN("f", "成熟"), "Pulcherrima" to GN("f", "直率"),
        "Achird" to GN("m", "亲切"), "Zubenelgenubi" to GN("m", "随性"), "Vindemiatrix" to GN("f", "温和"), "Sadachbia" to GN("m", "活泼"),
        "Sadaltager" to GN("m", "博识"), "Sulafat" to GN("f", "温暖")
    )

    private val GROK_VOICES = linkedMapOf(
        "eve" to VoiceInfo("eve", "Eve", gender = "f", note = "英音·温暖"),
        "ara" to VoiceInfo("ara", "Ara", gender = "f", note = "明快"),
        "rex" to VoiceInfo("rex", "Rex", gender = "m", note = "沉稳"),
        "sal" to VoiceInfo("sal", "Sal"),
        "leo" to VoiceInfo("leo", "Leo")
    )

    private val ORPHEUS_VOICES = linkedMapOf(
        "tara" to VoiceInfo("tara", "Tara", gender = "f", note = "最自然"),
        "leah" to VoiceInfo("leah", "Leah", gender = "f"),
        "jess" to VoiceInfo("jess", "Jess", gender = "f"),
        "leo" to VoiceInfo("leo", "Leo", gender = "m"),
        "dan" to VoiceInfo("dan", "Dan", gender = "m"),
        "mia" to VoiceInfo("mia", "Mia", gender = "f"),
        "zac" to VoiceInfo("zac", "Zac", gender = "m")
    )

    private val SESAME_VOICES = linkedMapOf(
        "conversational_a" to "对话风 A", "conversational_b" to "对话风 B", "read_speech_a" to "朗读风 A",
        "read_speech_b" to "朗读风 B", "read_speech_c" to "朗读风 C", "read_speech_d" to "朗读风 D", "none" to "模型默认"
    )

    private val ZONOS_VOICES = linkedMapOf(
        "american_female" to VoiceInfo("american_female", "美音·女声", "en", "f"),
        "american_male" to VoiceInfo("american_male", "美音·男声", "en", "m"),
        "british_female" to VoiceInfo("british_female", "英音·女声", "en", "f"),
        "british_male" to VoiceInfo("british_male", "英音·男声", "en", "m"),
        "random" to VoiceInfo("random", "随机音色", "en", note = "每次不同")
    )

    private val QWEN_VOICES = linkedMapOf(
        "loongjohn" to VoiceInfo("loongjohn", "Loong John", "zh", "m", "中英双语"),
        "longanhuan_v3.6" to VoiceInfo("longanhuan_v3.6", "Longan Huan", "zh"),
        "longanlingxin" to VoiceInfo("longanlingxin", "Longan Lingxin", "zh"),
        "longanlufeng" to VoiceInfo("longanlufeng", "Longan Lufeng", "zh")
    )

    private val VOXTRAL_LOCALE = mapOf(
        "en" to LangNote("en", "美音"), "gb" to LangNote("en", "英音"), "fr" to LangNote("fr"), "es" to LangNote("es"),
        "de" to LangNote("de"), "it" to LangNote("it"), "pt" to LangNote("pt"), "nl" to LangNote("nl")
    )
    private val VOXTRAL_GENDER = mapOf("paul" to "m", "oliver" to "m", "jane" to "f", "marie" to "f")
    private val EMOTION_ZH = mapOf(
        "neutral" to "中性", "happy" to "开心", "cheerful" to "欢快", "excited" to "兴奋", "confident" to "自信",
        "curious" to "好奇", "sad" to "悲伤", "frustrated" to "沮丧", "angry" to "愤怒", "sarcasm" to "讽刺",
        "confused" to "困惑", "shameful" to "羞愧", "jealousy" to "嫉妒"
    )

    private val MAI_GENDER = mapOf("Harper" to "f", "Valeria" to "f", "Soleil" to "f", "Klaus" to "m")
    private val MAI_LANG = mapOf(
        "en" to "en", "es" to "es", "fr" to "fr", "de" to "de", "it" to "it", "ja" to "ja", "zh" to "zh",
        "pt" to "pt", "nl" to "nl", "hi" to "hi"
    )

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

    private val MINIMAX_SUGGESTIONS = listOf(
        VoiceInfo("audiobook_female_1", "有声书·女 1", "zh", "f"),
        VoiceInfo("audiobook_female_2", "有声书·女 2", "zh", "f"),
        VoiceInfo("audiobook_male_1", "有声书·男 1", "zh", "m"),
        VoiceInfo("audiobook_male_2", "有声书·男 2", "zh", "m"),
        VoiceInfo("presenter_female", "女主播", "zh", "f"),
        VoiceInfo("presenter_male", "男主播", "zh", "m"),
        VoiceInfo("female-shaonv", "少女音", "zh", "f"),
        VoiceInfo("female-tianmei", "甜美女声", "zh", "f"),
        VoiceInfo("female-yujie", "御姐音", "zh", "f"),
        VoiceInfo("female-chengshu", "成熟女声", "zh", "f"),
        VoiceInfo("male-qn-qingse", "青涩青年", "zh", "m"),
        VoiceInfo("male-qn-jingying", "精英青年", "zh", "m"),
        VoiceInfo("male-qn-badao", "霸道青年", "zh", "m"),
        VoiceInfo("male-qn-daxuesheng", "大学生音", "zh", "m")
    )

    private val FISH_FREE_VOICE = FreeVoiceHint(
        placeholder = "留空使用官方默认音色",
        hint = "可填 fish.audio 音色库中任意音色的 Reference ID；正文中可用括号标注情绪（如 (excited)）。"
    )
    private val MINIMAX_FREE_VOICE = FreeVoiceHint(
        placeholder = "audiobook_female_1",
        hint = "支持 MiniMax 全部系统音色与自定义克隆音色 ID，下方为官方常用中文音色。",
        suggestions = MINIMAX_SUGGESTIONS
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

    /** 无在线列表时的兜底音色表（与 2026-08 线上 supported_voices 一致） */
    private val STATIC_VOICES: Map<String, List<String>> = mapOf(
        "hexgrad/kokoro-82m" to KOKORO_IDS,
        "qwen/qwen-audio-3.0-tts-flash" to listOf("loongjohn", "longanhuan_v3.6"),
        "qwen/qwen-audio-3.0-tts-plus" to listOf("longanlingxin", "longanlufeng"),
        "google/gemini-3.1-flash-tts-preview" to GEMINI_VOICES.keys.toList(),
        "x-ai/grok-voice-tts-1.0" to listOf("eve", "ara", "rex", "sal", "leo"),
        "zyphra/zonos-v0.1-transformer" to ZONOS_VOICES.keys.toList(),
        "zyphra/zonos-v0.1-hybrid" to ZONOS_VOICES.keys.toList(),
        "canopylabs/orpheus-3b-0.1-ft" to ORPHEUS_VOICES.keys.toList(),
        "sesame/csm-1b" to SESAME_VOICES.keys.toList(),
        "mistralai/voxtral-mini-tts-2603" to VOXTRAL_IDS,
        "microsoft/mai-voice-2" to MAI_IDS,
        "microsoft/mai-voice-2-flash" to MAI_IDS,
        "deepgram/aura-2" to DEEPGRAM_IDS
    )

    private val MODEL_HINTS: Map<String, ModelHints> = mapOf(
        "hexgrad/kokoro-82m" to ModelHints("Kokoro", "中/英/日/法/西/意/葡/印地 8 语", "开源轻量，约 $0.6/百万字符", preferred = "zf_xiaoxiao"),
        "qwen/qwen-audio-3.0-tts-flash" to ModelHints("通义 TTS Flash", "中文为主", "阿里 DashScope 语音合成"),
        "qwen/qwen-audio-3.0-tts-plus" to ModelHints("通义 TTS Plus", "中文为主", "阿里 DashScope 高音质档"),
        "minimax/speech-2.8-turbo" to ModelHints("MiniMax Turbo", "中/英等多语", freeVoice = MINIMAX_FREE_VOICE),
        "minimax/speech-2.8-hd" to ModelHints("MiniMax HD", "中/英等多语", freeVoice = MINIMAX_FREE_VOICE),
        "fish-audio/s1" to ModelHints("Fish S1", "多语言（含中文）", freeVoice = FISH_FREE_VOICE, voiceOptional = true, cloning = true),
        "fish-audio/s2-pro" to ModelHints("Fish S2 Pro", "多语言（含中文）", freeVoice = FISH_FREE_VOICE, voiceOptional = true, cloning = true),
        "fish-audio/s2.1-pro" to ModelHints("Fish S2.1 Pro", "多语言（含中文）", freeVoice = FISH_FREE_VOICE, voiceOptional = true, cloning = true),
        "fish-audio/s2.1-pro-free" to ModelHints("Fish S2.1 免费", "多语言（含中文）", freeVoice = FISH_FREE_VOICE, voiceOptional = true, cloning = true),
        "google/gemini-3.1-flash-tts-preview" to ModelHints("Gemini TTS", "24+ 语言（含中文）", pcmOnly = true, preferred = "Kore"),
        "x-ai/grok-voice-tts-1.0" to ModelHints("Grok Voice", "20+ 语言自动识别（含中文）"),
        "microsoft/mai-voice-2" to ModelHints("MAI-Voice-2", "15 语言"),
        "microsoft/mai-voice-2-flash" to ModelHints("MAI-Voice-2 Flash", "15 语言", "低延迟档"),
        "deepgram/aura-2" to ModelHints("Aura-2", "英/西/法/德/意/荷/日 7 语"),
        "zyphra/zonos-v0.1-transformer" to ModelHints("Zonos", "英语"),
        "zyphra/zonos-v0.1-hybrid" to ModelHints("Zonos Hybrid", "英语"),
        "canopylabs/orpheus-3b-0.1-ft" to ModelHints("Orpheus 3B", "英语"),
        "sesame/csm-1b" to ModelHints("Sesame CSM", "英语"),
        "mistralai/voxtral-mini-tts-2603" to ModelHints("Voxtral TTS", "英/法", "同一人名多情绪版本", cloning = true)
    )

    /** 按厂商前缀兜底（未来同厂新模型自动继承音色语义） */
    private val VENDOR_HINTS: Map<String, ModelHints> = mapOf(
        "fish-audio" to ModelHints(langs = "多语言（含中文）", freeVoice = FISH_FREE_VOICE, voiceOptional = true, cloning = true),
        "minimax" to ModelHints(langs = "中/英等多语", freeVoice = MINIMAX_FREE_VOICE)
    )

    fun modelHints(modelId: String): ModelHints? {
        val id = canonicalModelId(modelId)
        return MODEL_HINTS[id] ?: VENDOR_HINTS[id.substringBefore('/')]
    }

    private fun cap(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private val KOKORO_RE = Regex("^([a-z])([fm])_(.+)$")
    private val DEEPGRAM_RE = Regex("^aura-2-([a-z]+)-([a-z]{2})$")
    private val VOXTRAL_RE = Regex("^([a-z]{2})_([a-z]+)_([a-z]+)$")
    private val MAI_RE = Regex("^([a-z]{2})-([A-Z]{2})-([A-Za-z]+):")

    /** 解析单个音色 ID 为带语言/性别/风格的条目；无法识别时退化为原样标签 */
    fun describeVoice(modelId: String, voiceId: String): VoiceInfo {
        val cid = canonicalModelId(modelId)
        val vendor = cid.substringBefore('/')

        if (vendor == "hexgrad") {
            val m = KOKORO_RE.find(voiceId)
            val ln = m?.let { KOKORO_LANG[it.groupValues[1]] }
            if (m != null && ln != null) {
                val name = m.groupValues[3]
                val label = if (ln.lang == "zh") KOKORO_ZH[name] ?: cap(name) else cap(name)
                return VoiceInfo(voiceId, label, ln.lang, m.groupValues[2], ln.note)
            }
        }
        if (vendor == "deepgram") {
            val m = DEEPGRAM_RE.find(voiceId)
            if (m != null) {
                val lang = m.groupValues[2]
                return VoiceInfo(voiceId, cap(m.groupValues[1]), if (LANG_LABELS.containsKey(lang)) lang else "other", DEEPGRAM_GENDER[m.groupValues[1]])
            }
        }
        if (vendor == "mistralai") {
            val m = VOXTRAL_RE.find(voiceId)
            val loc = m?.let { VOXTRAL_LOCALE[it.groupValues[1]] }
            if (m != null && loc != null) {
                val emotion = EMOTION_ZH[m.groupValues[3]] ?: cap(m.groupValues[3])
                return VoiceInfo(voiceId, "${cap(m.groupValues[2])}·$emotion", loc.lang, VOXTRAL_GENDER[m.groupValues[2]], loc.note)
            }
        }
        if (vendor == "microsoft") {
            val m = MAI_RE.find(voiceId)
            if (m != null) {
                return VoiceInfo(voiceId, m.groupValues[3], MAI_LANG[m.groupValues[1]] ?: "other", MAI_GENDER[m.groupValues[3]], "${m.groupValues[1]}-${m.groupValues[2]}")
            }
        }
        if (vendor == "google") GEMINI_VOICES[voiceId]?.let { return VoiceInfo(voiceId, voiceId, "multi", it.gender, it.note) }
        if (vendor == "x-ai") GROK_VOICES[voiceId]?.let { return it.copy(lang = "multi") }
        if (vendor == "zyphra") ZONOS_VOICES[voiceId]?.let { return it }
        if (vendor == "canopylabs") ORPHEUS_VOICES[voiceId]?.let { return it.copy(lang = "en") }
        if (vendor == "sesame") SESAME_VOICES[voiceId]?.let { return VoiceInfo(voiceId, it, "en") }
        if (vendor == "qwen") QWEN_VOICES[voiceId]?.let { return it }
        if (vendor == "minimax") MINIMAX_SUGGESTIONS.firstOrNull { it.id == voiceId }?.let { return it }
        return VoiceInfo(voiceId, voiceId)
    }

    /** 模型的完整音色目录：在线列表优先，其次内置兜底表。开放音色模型返回空列表。 */
    fun catalogVoices(modelId: String, serverVoices: List<String>? = null): List<VoiceInfo> {
        if (modelHints(modelId)?.freeVoice != null) return emptyList()
        val ids = if (!serverVoices.isNullOrEmpty()) serverVoices else STATIC_VOICES[canonicalModelId(modelId)] ?: emptyList()
        return ids.map { describeVoice(modelId, it) }
    }

    /** 按语言分组：中文 → 多语言 → 英语 → … → 其他/未知 */
    fun groupVoices(voices: List<VoiceInfo>): List<VoiceGroup> {
        val map = LinkedHashMap<String, MutableList<VoiceInfo>>()
        for (v in voices) map.getOrPut(v.lang ?: "other") { ArrayList() }.add(v)
        fun rank(lang: String): Int = LANG_ORDER.indexOf(lang).let { if (it < 0) LANG_ORDER.size else it }
        return map.entries.sortedBy { rank(it.key) }.map { VoiceGroup(it.key, langLabel(it.key), it.value) }
    }

    /** 切换模型后的默认音色：模型偏好 → 中文 → 多语言 → 首个；开放音色模型给建议首项或空 */
    fun defaultVoiceFor(modelId: String, serverVoices: List<String>? = null): String {
        val hints = modelHints(modelId)
        if (hints?.freeVoice != null) {
            return if (hints.voiceOptional) "" else hints.freeVoice.suggestions.firstOrNull()?.id ?: ""
        }
        val all = catalogVoices(modelId, serverVoices)
        if (all.isEmpty()) return ""
        val preferred = hints?.preferred
        if (preferred != null && all.any { it.id == preferred }) return preferred
        val best = all.firstOrNull { it.lang == "zh" } ?: all.firstOrNull { it.lang == "multi" }
        return (best ?: all[0]).id
    }

    /** 快捷推荐（听书场景优选，覆盖 免费/低价/中文/多语言 各档） */
    val RECOMMENDED_MODELS = listOf(
        RecommendedModel("hexgrad/kokoro-82m", "Kokoro", "超低价"),
        RecommendedModel("qwen/qwen-audio-3.0-tts-flash", "通义 TTS", "中文"),
        RecommendedModel("minimax/speech-2.8-turbo", "MiniMax", "有声书"),
        RecommendedModel("fish-audio/s2.1-pro-free:free", "Fish S2.1", "免费"),
        RecommendedModel("google/gemini-3.1-flash-tts-preview", "Gemini TTS", "30 音色"),
        RecommendedModel("x-ai/grok-voice-tts-1.0", "Grok Voice", "多语言")
    )

    /** 模型信息行：价格 · 语言 · 音色数 · 能力标记 */
    fun formatModelMeta(info: TtsModelInfo?, modelId: String): String {
        val hints = modelHints(modelId)
        val parts = ArrayList<String>()
        val completion = info?.completionPrice
        val prompt = info?.promptPrice
        if (completion != null && completion > 0) {
            parts.add("按 token 计费")
        } else if (prompt != null && prompt > 0) {
            val perM = prompt * 1e6
            val shown = if (perM >= 10) Math.round(perM).toString() else (Math.round(perM * 100) / 100.0).toString().removeSuffix(".0")
            parts.add("≈$$shown/百万字符")
        } else if (modelId.contains(":free")) {
            parts.add("免费")
        }
        hints?.langs?.let { parts.add(it) }
        val n = catalogVoices(modelId, info?.voices).size
        if (n > 0) parts.add("$n 个音色") else if (hints?.freeVoice != null) parts.add("开放音色 ID")
        if (hints?.cloning == true) parts.add("支持克隆")
        hints?.note?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }
}
