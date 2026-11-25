package app.echoread.data

import android.content.Context
import android.content.SharedPreferences
import app.echoread.core.Discovery
import app.echoread.core.GestureSettings
import app.echoread.core.ProviderSpec
import app.echoread.core.ReaderSettings
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.VoiceSpec
import app.echoread.core.i18n.I18n
import app.echoread.tts.Providers
import app.echoread.tts.SpeechApi
import app.echoread.tts.Voices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设置仓库：JSON 存于 SharedPreferences，StateFlow 对外。
 * 读取时缺失键回落默认值、类型不符整体回退，并做值域守卫；写入即持久化。
 */
class SettingsStore(context: Context, private val vault: Vault = KeystoreVault()) {
    private val prefs: SharedPreferences = context.getSharedPreferences("echo-read", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }

    private val _reader = MutableStateFlow(sanitizeReader(load(KEY_READER, ReaderSettings.serializer()) ?: ReaderSettings()))
    val reader: StateFlow<ReaderSettings> = _reader

    init {
        // 界面语言要先于朗读设置定下来：首次安装时默认音色按界面语言挑（英文界面不该默认一个中文音色）
        I18n.apply(_reader.value.language)
    }

    // 落盘的是密文（见 KeyVault）：读进内存先解开，再做迁移与值域守卫
    private val _tts = MutableStateFlow(sanitizeTts(load(KEY_TTS, TtsSettings.serializer())?.let { Sealing.opened(it, vault) } ?: TtsSettings()))
    val tts: StateFlow<TtsSettings> = _tts

    /** 各服务在线拉到的模型表 / 音色表（缓存，离线可用），按服务 ID 存 */
    private val _discovery = MutableStateFlow(loadDiscovery())
    val discovery: StateFlow<Map<String, Discovery>> = _discovery

    init {
        // 迁移结果落盘一次：下次读档不必再迁；0.4.x 留在盘上的明文 Key 也在这一次被换成密文
        val raw = prefs.getString(KEY_TTS, null)
        if (raw == null || !raw.contains("\"profiles\"") || (_tts.value.profiles.any { it.apiKey.isNotEmpty() } && !raw.contains(KeystoreVault.PREFIX))) persistTts()
    }

    private fun <T> load(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadDiscovery(): Map<String, Discovery> {
        load(KEY_DISCOVERY, MapSerializer(String.serializer(), Discovery.serializer()))?.let { return it }
        // 0.4.x 的模型表缓存只有一份（给那一个 OpenAI 兼容配置）：挂到迁移出来的那个服务名下
        val legacy = load(KEY_MODELS, ListSerializer(TtsModelInfo.serializer())).orEmpty()
        val owner = _tts.value.profiles.firstOrNull { Providers.specOf(it, _tts.value).kind == ProviderSpec.KIND_OPENAI }
        return if (legacy.isNotEmpty() && owner != null) mapOf(owner.id to Discovery(models = legacy, at = prefs.getLong(KEY_MODELS_AT, 0))) else emptyMap()
    }

    private fun persistTts() {
        prefs.edit().putString(KEY_TTS, json.encodeToString(Sealing.sealed(_tts.value, vault))).apply()
    }

    fun updateTts(block: (TtsSettings) -> TtsSettings) {
        _tts.update { sanitizeTts(block(it)) }
        persistTts()
    }

    fun updateReader(block: (ReaderSettings) -> ReaderSettings) {
        _reader.update { sanitizeReader(block(it)) }
        prefs.edit().putString(KEY_READER, json.encodeToString(_reader.value)).apply()
    }

    /* ---------------- 服务（0.5.0） ---------------- */

    fun profile(id: String): VoiceProfile? = _tts.value.profiles.firstOrNull { it.id == id }

    fun updateProfile(id: String, block: (VoiceProfile) -> VoiceProfile) = updateTts { s ->
        s.copy(profiles = s.profiles.map { if (it.id == id) block(it).copy(id = it.id, spec = it.spec) else it })
    }

    /** 切换正在使用的服务（"system" = 系统语音） */
    fun setActive(id: String) = updateTts { it.copy(active = id) }

    /** 添加目录里的一家；内置服务商已经加过就返回已有的那个 */
    fun addProfile(spec: ProviderSpec): VoiceProfile {
        // 内置服务商每家只有一个实例；地址由用户填的（自定义端点、自建服务）可以加很多个
        val multi = spec.id == Providers.CUSTOM || spec.baseUrl.isBlank()
        if (!multi) _tts.value.profiles.firstOrNull { it.spec == spec.id }?.let { return it }
        val created = Providers.newProfile(spec, _tts.value.profiles)
        updateTts { it.copy(profiles = it.profiles + created) }
        return profile(created.id) ?: created
    }

    /** 移除一个服务（连同它的 Key 与列表缓存）。正在用的被移除时退到剩下的第一个，再没有就用系统语音 */
    fun removeProfile(id: String) {
        updateTts { s ->
            val rest = s.profiles.filterNot { it.id == id }
            // 用户导入的定义：没有任何服务再用它时一并清掉
            val specs = s.customSpecs.filter { c -> rest.any { it.spec == c.id } }
            s.copy(profiles = rest, customSpecs = specs, active = if (s.active == id) rest.firstOrNull()?.id ?: TtsSettings.SYSTEM else s.active)
        }
        _discovery.update { it - id }
        persistDiscovery()
    }

    /** 导入一份服务定义并为它建一个服务；同 ID 的旧定义被替换 */
    fun importSpec(spec: ProviderSpec): VoiceProfile {
        updateTts { s -> s.copy(customSpecs = s.customSpecs.filterNot { it.id == spec.id } + spec) }
        _tts.value.profiles.firstOrNull { it.spec == spec.id }?.let { return it }
        val created = Providers.newProfile(spec, _tts.value.profiles)
        updateTts { it.copy(profiles = it.profiles + created) }
        return profile(created.id) ?: created
    }

    private fun serverVoices(profileId: String, model: String): List<String>? =
        _discovery.value[profileId]?.models?.firstOrNull { it.id == model }?.voices

    /** 切换模型：恢复该模型记忆的音色，否则用默认音色（按界面语言挑） */
    fun setModel(profileId: String, model: String) {
        val s = _tts.value
        val p = profile(profileId) ?: return
        if (p.model == model) return
        val spec = Providers.specOf(p, s)
        val mem = p.voiceByModel[model]
        val optional = if (spec.id == Providers.OPENROUTER) Voices.modelHints(model)?.voiceOptional == true else spec.voiceOptional
        val voice = if (mem != null && (mem.isNotEmpty() || optional)) mem else when (spec.id) {
            Providers.OPENROUTER -> Voices.defaultVoiceFor(model, serverVoices(profileId, model))
            else -> Providers.defaultVoice(spec, model, _discovery.value[profileId]?.voices.orEmpty())
        }
        updateProfile(profileId) { it.copy(model = model, voice = voice, voiceByModel = it.voiceByModel + (model to voice)) }
    }

    /** 记住每个模型的音色选择 */
    fun setVoice(profileId: String, voice: String) = updateProfile(profileId) {
        val model = it.model
        it.copy(voice = voice, voiceByModel = if (model.isNotEmpty()) it.voiceByModel + (model to voice) else it.voiceByModel)
    }

    fun setDiscovery(profileId: String, d: Discovery) {
        _discovery.update { it + (profileId to d) }
        persistDiscovery()
    }

    private fun persistDiscovery() {
        prefs.edit().putString(
            KEY_DISCOVERY,
            json.encodeToString(MapSerializer(String.serializer(), Discovery.serializer()), _discovery.value)
        ).apply()
    }

    companion object {
        private const val KEY_TTS = "tts-settings"
        private const val KEY_READER = "reader-settings"
        private const val KEY_MODELS = "tts-models"
        private const val KEY_MODELS_AT = "tts-models-at"
        private const val KEY_DISCOVERY = "tts-discovery"

        /** OpenRouter 已下架的历史 TTS 模型：存量配置迁移到现役默认模型 */
        private val REMOVED_OR_MODELS = setOf(
            "openai/gpt-4o-mini-tts", "openai/gpt-4o-mini-tts-2025-12-15", "openai/tts-1", "openai/tts-1-hd",
            "google/gemini-2.5-flash-preview-tts", "google/gemini-2.5-pro-preview-tts"
        )

        private val FORMATS = setOf("", "mp3", "opus", "pcm", "wav", "flac", "aac")

        /**
         * 值域守卫 + 旧存档迁移：越界值会破坏播放（maxChunkChars≤0 分段死循环、倍速越界抛错），一律回退默认。
         * 0.5.0 起服务以 [TtsSettings.profiles] 为准；每次都把结果回写到 0.4.x 的三个旧字段上，降级安装不丢配置。
         */
        fun sanitizeTts(s: TtsSettings): TtsSettings {
            val fb = TtsSettings()
            var out = s
            if (!(out.rate >= 0.25f && out.rate <= 4f)) out = out.copy(rate = fb.rate)
            if (out.maxChunkChars < 40) out = out.copy(maxChunkChars = fb.maxChunkChars)
            if (out.prefetch !in 0..5) out = out.copy(prefetch = fb.prefetch)
            if (out.profiles.isEmpty()) out = migrate(out)
            // 导入的定义不能顶替内置服务商的 ID：否则一段粘贴来的 JSON 就能把已存的 Key 引到别的主机上
            val builtInIds = Providers.builtIn.map { it.id }.toSet()
            out = out.copy(customSpecs = out.customSpecs.filter { it.id !in builtInIds }.distinctBy { it.id })
            out = out.copy(profiles = out.profiles.distinctBy { it.id }.filter { it.id.isNotBlank() && it.id != TtsSettings.SYSTEM }.map { sanitizeProfile(it, out) })
            if (out.active != TtsSettings.SYSTEM && out.profiles.none { it.id == out.active }) {
                out = out.copy(active = out.profiles.firstOrNull()?.id ?: TtsSettings.SYSTEM)
            }
            return mirrorLegacy(out)
        }

        private fun sanitizeProfile(p: VoiceProfile, s: TtsSettings): VoiceProfile {
            val spec = Providers.specOf(p, s)
            var out = p
            if (out.format !in FORMATS) out = out.copy(format = "")
            if (out.model.isBlank() && spec.defaultModel.isNotBlank()) out = out.copy(model = spec.defaultModel)
            if (spec.id == Providers.OPENROUTER && out.model in REMOVED_OR_MODELS) {
                out = out.copy(model = spec.defaultModel, voice = Providers.defaultVoice(spec, spec.defaultModel))
            }
            return out
        }

        /** 0.4.x 的「AI 语音 + Gemini」两个槽位 → 两个服务。Key、模型、音色、每模型音色记忆原样带过来 */
        private fun migrate(s: TtsSettings): TtsSettings {
            val o = s.openai
            val known = Providers.specForBase(o.baseUrl)
            val untouched = o == TtsSettings().openai
            val first = if (known != null) {
                VoiceProfile(
                    id = known.id, spec = known.id, apiKey = o.apiKey, model = o.model,
                    // 从没配置过的新安装：默认音色跟界面语言走，而不是一律中文音色
                    voice = if (untouched) Providers.defaultVoice(known, o.model) else o.voice,
                    style = o.instructions, format = if (o.format == "mp3") "" else o.format, voiceByModel = s.voiceByModel
                )
            } else {
                VoiceProfile(
                    id = "custom-legacy", spec = Providers.CUSTOM,
                    name = runCatching { java.net.URI(o.baseUrl.trim()).host }.getOrNull().orEmpty(),
                    baseUrl = o.baseUrl.trim(), apiKey = o.apiKey, model = o.model, voice = o.voice,
                    style = o.instructions, format = o.format, voiceByModel = s.voiceByModel
                )
            }
            val g = s.gemini
            val gemini = VoiceProfile(id = Providers.GEMINI, spec = Providers.GEMINI, apiKey = g.apiKey, model = g.model, voice = g.voice, style = g.style)
            val list = if (first.id == gemini.id) listOf(first) else listOf(first, gemini)
            val active = when (s.provider) {
                TtsProvider.SYSTEM -> TtsSettings.SYSTEM
                TtsProvider.GEMINI -> gemini.id
                TtsProvider.OPENAI -> first.id
            }
            return s.copy(profiles = list, active = active)
        }

        /** 把现状回写到旧字段：旧版本读档看到的仍是一份自洽的配置 */
        private fun mirrorLegacy(s: TtsSettings): TtsSettings {
            val active = s.activeProfile
            fun kindOf(p: VoiceProfile) = Providers.specOf(p, s).kind
            val gem = s.profiles.firstOrNull { kindOf(it) == ProviderSpec.KIND_GEMINI }
            val oa = active?.takeIf { kindOf(it) == ProviderSpec.KIND_OPENAI } ?: s.profiles.firstOrNull { kindOf(it) == ProviderSpec.KIND_OPENAI }
            val provider = when {
                s.active == TtsSettings.SYSTEM -> TtsProvider.SYSTEM
                active != null && kindOf(active) == ProviderSpec.KIND_GEMINI -> TtsProvider.GEMINI
                else -> TtsProvider.OPENAI
            }
            return s.copy(
                provider = provider,
                gemini = gem?.let { Providers.geminiConfig(Providers.specOf(it, s), it) } ?: s.gemini,
                openai = oa?.let { p ->
                    val cfg = Providers.openAiConfig(Providers.specOf(p, s), p)
                    // 旧版本只认 mp3 / opus / pcm
                    cfg.copy(instructions = p.style, format = cfg.format.takeIf { it in setOf("mp3", "opus", "pcm") } ?: "mp3")
                } ?: s.openai,
                voiceByModel = oa?.voiceByModel ?: s.voiceByModel
            )
        }

        fun sanitizeReader(s: ReaderSettings): ReaderSettings {
            val fb = ReaderSettings()
            var out = s
            if (out.theme !in setOf("dark", "light", "paper", "eye", "ink")) out = out.copy(theme = fb.theme)
            if (out.fontSize !in 12..40) out = out.copy(fontSize = fb.fontSize)
            if (!(out.lineHeight >= 1.2f && out.lineHeight <= 3f)) out = out.copy(lineHeight = fb.lineHeight)
            if (out.fontFamily != "serif" && out.fontFamily != "sans") out = out.copy(fontFamily = fb.fontFamily)
            if (!(out.paraSpacing >= 0f && out.paraSpacing <= 3f)) out = out.copy(paraSpacing = fb.paraSpacing)
            return out.copy(gestures = sanitizeGestures(out.gestures))
        }

        /**
         * 手势值域守卫：热区比例越界会让「点读」永远点不到（两侧各占满半屏），
         * slopScale 归零则任何一次点按都会被判成拖动 —— 都是能把阅读器变砖的值。
         */
        fun sanitizeGestures(g: GestureSettings): GestureSettings {
            val fb = GestureSettings()
            var prev = if (g.prevZone.isFinite()) g.prevZone.coerceIn(0f, 0.5f) else fb.prevZone
            var next = if (g.nextZone.isFinite()) g.nextZone.coerceIn(0f, 0.5f) else fb.nextZone
            // 开着「轻点朗读」时两侧之和必须留出中间的可点读带；关掉朗读则允许各占一半、整页都是翻页区
            val maxSum = if (g.tapToRead) 0.8f else 1f
            if (prev + next > maxSum) {
                val k = maxSum / (prev + next)
                prev *= k
                next *= k
            }
            val slop = if (g.slopScale.isFinite()) g.slopScale.coerceIn(0.5f, 3f) else fb.slopScale
            return g.copy(prevZone = prev, nextZone = next, slopScale = slop)
        }
    }
}
