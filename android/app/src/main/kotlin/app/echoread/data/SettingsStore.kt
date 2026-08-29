package app.echoread.data

import android.content.Context
import android.content.SharedPreferences
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.ReaderSettings
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsProvider
import app.echoread.core.TtsSettings
import app.echoread.tts.SpeechApi
import app.echoread.tts.Voices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设置仓库：JSON 存于 SharedPreferences（对应网页版 localStorage），StateFlow 对外。
 * 读取时缺失键回落默认值、类型不符整体回退，并做值域守卫；写入即持久化。
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("echo-read", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }

    private val _tts = MutableStateFlow(sanitizeTts(load(KEY_TTS, TtsSettings.serializer()) ?: TtsSettings()))
    val tts: StateFlow<TtsSettings> = _tts

    private val _reader = MutableStateFlow(sanitizeReader(load(KEY_READER, ReaderSettings.serializer()) ?: ReaderSettings()))
    val reader: StateFlow<ReaderSettings> = _reader

    /** 在线拉取到的 TTS 模型列表（缓存，离线可用；空列表表示未拉取过） */
    private val _models = MutableStateFlow(load(KEY_MODELS, ListSerializer(TtsModelInfo.serializer())) ?: emptyList())
    val models: StateFlow<List<TtsModelInfo>> = _models

    private fun <T> load(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Throwable) {
            null
        }
    }

    fun updateTts(block: (TtsSettings) -> TtsSettings) {
        _tts.update { sanitizeTts(block(it)) }
        prefs.edit().putString(KEY_TTS, json.encodeToString(_tts.value)).apply()
    }

    fun updateOpenAI(block: (OpenAISpeechConfig) -> OpenAISpeechConfig) = updateTts { it.copy(openai = block(it.openai)) }

    fun updateReader(block: (ReaderSettings) -> ReaderSettings) {
        _reader.update { sanitizeReader(block(it)) }
        prefs.edit().putString(KEY_READER, json.encodeToString(_reader.value)).apply()
    }

    fun setModels(list: List<TtsModelInfo>, fingerprint: String = "") {
        _models.value = list
        prefs.edit()
            .putString(KEY_MODELS, json.encodeToString(ListSerializer(TtsModelInfo.serializer()), list))
            .putLong(KEY_MODELS_AT, System.currentTimeMillis())
            .putString(KEY_MODELS_FP, fingerprint)
            .apply()
    }

    /** 模型列表的同步时间与「端点+Key」指纹：Key/端点变化或超过 6 小时即需重新同步 */
    val modelsSyncedAt: Long get() = prefs.getLong(KEY_MODELS_AT, 0)
    val modelsFingerprint: String get() = prefs.getString(KEY_MODELS_FP, "") ?: ""
    fun fingerprintOf(cfg: OpenAISpeechConfig): String = app.echoread.core.Hash.cyrb53("${cfg.baseUrl.trim()}|${cfg.apiKey.trim()}")

    fun serverVoicesFor(modelId: String): List<String>? = _models.value.firstOrNull { it.id == modelId }?.voices

    /** 切换模型：恢复该模型记忆的音色，否则用目录默认音色（中文优先） */
    fun setModel(id: String) {
        val cur = _tts.value
        if (cur.openai.model == id) return
        val mem = cur.voiceByModel[id]
        val usable = mem != null && (mem.isNotEmpty() || Voices.modelHints(id)?.voiceOptional == true)
        val voice = if (usable) mem!! else Voices.defaultVoiceFor(id, serverVoicesFor(id))
        updateTts { it.copy(openai = it.openai.copy(model = id, voice = voice), voiceByModel = it.voiceByModel + (id to voice)) }
    }

    /** 记住每个模型的音色选择 */
    fun setVoice(voice: String) {
        updateTts {
            val model = it.openai.model
            it.copy(openai = it.openai.copy(voice = voice), voiceByModel = if (model.isNotEmpty()) it.voiceByModel + (model to voice) else it.voiceByModel)
        }
    }

    companion object {
        private const val KEY_TTS = "tts-settings"
        private const val KEY_READER = "reader-settings"
        private const val KEY_MODELS = "tts-models"
        private const val KEY_MODELS_AT = "tts-models-at"
        private const val KEY_MODELS_FP = "tts-models-fp"

        /** OpenRouter 已下架的历史 TTS 模型：存量配置迁移到现役默认模型 */
        private val REMOVED_OR_MODELS = setOf(
            "openai/gpt-4o-mini-tts", "openai/gpt-4o-mini-tts-2025-12-15", "openai/tts-1", "openai/tts-1-hd",
            "google/gemini-2.5-flash-preview-tts", "google/gemini-2.5-pro-preview-tts"
        )

        /** 值域守卫：越界值会破坏播放（maxChunkChars≤0 分段死循环、倍速越界抛错），一律回退默认 */
        fun sanitizeTts(s: TtsSettings): TtsSettings {
            val fb = TtsSettings()
            var out = s
            if (out.openai.format != "mp3" && out.openai.format != "opus" && out.openai.format != "pcm") {
                out = out.copy(openai = out.openai.copy(format = fb.openai.format))
            }
            if (!(out.rate >= 0.25f && out.rate <= 4f)) out = out.copy(rate = fb.rate)
            if (out.maxChunkChars < 40) out = out.copy(maxChunkChars = fb.maxChunkChars)
            if (out.prefetch !in 0..5) out = out.copy(prefetch = fb.prefetch)
            if (SpeechApi.isOpenRouterBase(out.openai.baseUrl) && out.openai.model in REMOVED_OR_MODELS) {
                out = out.copy(openai = out.openai.copy(model = fb.openai.model, voice = fb.openai.voice))
            }
            if (out.provider != TtsProvider.OPENAI && out.provider != TtsProvider.SYSTEM) out = out.copy(provider = fb.provider)
            return out
        }

        fun sanitizeReader(s: ReaderSettings): ReaderSettings {
            val fb = ReaderSettings()
            var out = s
            if (out.theme !in setOf("dark", "light", "paper", "eye", "ink")) out = out.copy(theme = fb.theme)
            if (out.fontSize !in 12..40) out = out.copy(fontSize = fb.fontSize)
            if (!(out.lineHeight >= 1.2f && out.lineHeight <= 3f)) out = out.copy(lineHeight = fb.lineHeight)
            if (out.fontFamily != "serif" && out.fontFamily != "sans") out = out.copy(fontFamily = fb.fontFamily)
            if (!(out.paraSpacing >= 0f && out.paraSpacing <= 3f)) out = out.copy(paraSpacing = fb.paraSpacing)
            return out
        }
    }
}
