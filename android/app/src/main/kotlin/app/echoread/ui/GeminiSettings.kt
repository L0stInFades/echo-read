package app.echoread.ui

import app.echoread.core.i18n.S
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.core.GeminiTtsConfig
import app.echoread.core.net.NetError
import app.echoread.tts.GeminiTts
import app.echoread.tts.VoiceInfo
import app.echoread.tts.Voices
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress

/*
 * 「Gemini」引擎的设置界面：与「AI 语音」同一套组织方式（状态即入口、每行副标题就是当前值、
 * 模型与音色用独立选择器），只是内容换成 Gemini API 原生的概念 ——
 * 两档 3.8 模型、30 个精选音色 + 扩展音色库 + 自建音色、以及 speech_metadata.style。
 */

/** Gemini 引擎的连接状态。拉取音色目录顺带验证 Key（无效 Key 在这里就会拿到 400 API_KEY_INVALID） */
internal sealed interface GeminiSyncState {
    data object Idle : GeminiSyncState
    data object Syncing : GeminiSyncState
    data class Ok(val total: Int, val custom: Int) : GeminiSyncState
    data class Failed(val error: NetError) : GeminiSyncState
}

/**
 * 语气快捷项。值用英文：官方示例全部是英文短语（"calm and relaxed"、"speaking slowly"），
 * 官方也建议非英语正文照样用英文写行内标签；中文 style 同样能用，用户可以直接在输入框里改。
 */
private val STYLE_PRESETS: List<Pair<String, String>> get() = listOf(
    S.gemini.styleNatural to "",
    S.gemini.styleCalm to "calm and relaxed",
    S.common.warm to "warm and gentle",
    S.gemini.styleStorytelling to "warm, unhurried storytelling",
    S.gemini.styleSlow to "speaking slowly"
)

/** 服务：状态行（点开 Key）+ 试听 / 刷新 / 详情 */
@Composable
internal fun GeminiServiceSection(
    cfg: GeminiTtsConfig,
    sync: GeminiSyncState,
    testing: Boolean,
    testLabel: String,
    showCredentials: Boolean,
    onToggleCredentials: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onKeyChange: (String) -> Unit
) {
    val c = echo
    val uriHandler = LocalUriHandler.current
    SettingsSection(S.common.service) {
        custom {
            val (dot, statusText) = when (sync) {
                GeminiSyncState.Idle -> c.text3 to (if (cfg.apiKey.isBlank()) S.gemini.keyHint else S.gemini.waitingToConnect)
                GeminiSyncState.Syncing -> c.accent to S.gemini.fetchingTheVoiceCatalog
                is GeminiSyncState.Ok -> Color(0xFF34C759) to buildString {
                    append(S.gemini.connectedVoices(sync.total))
                    if (sync.custom > 0) append(S.gemini.customVoicesSuffix(sync.custom))
                }
                is GeminiSyncState.Failed -> c.danger to sync.error.headline()
            }
            Row(
                Modifier.fillMaxWidth().echoPress(pressedScale = PressScale.Tile) { onToggleCredentials() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (cfg.apiKey.isBlank()) S.common.notSetUp else "Gemini API", color = c.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        statusText,
                        color = if (sync is GeminiSyncState.Failed) c.danger else c.text2,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (showCredentials) EchoIcons.ChevronUp else EchoIcons.ChevronDown,
                    if (showCredentials) S.common.collapse else S.common.expand,
                    tint = c.text3, modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(
                    if (testing) testLabel.ifEmpty { S.common.generating } else S.common.preview,
                    Modifier.weight(1f), color = c.accent, height = 40.dp
                ) { if (!testing) onTest() }
                if (cfg.apiKey.isNotBlank()) {
                    OutlineButton(S.common.refresh, Modifier.weight(1f), height = 40.dp) { onRefresh() }
                }
                if (sync is GeminiSyncState.Failed) {
                    OutlineButton(S.common.details, Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(sync.error) }
                }
            }
            AnimatedVisibility(showCredentials, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
                Column(Modifier.padding(top = 14.dp)) {
                    EchoTextField(
                        cfg.apiKey, { v -> onKeyChange(v.trim()) },
                        label = "Gemini API Key", placeholder = "AIza...", password = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        S.gemini.createAKeyIn, color = c.accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.echoPress(pressedScale = PressScale.Chip) {
                            runCatching { uriHandler.openUri(GeminiTts.KEY_PAGE) }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S.gemini.privacyNote,
                        color = c.text3, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** 声音：模型、音色、语气（speech_metadata.style）、语速 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeminiVoiceSection(
    cfg: GeminiTtsConfig,
    voiceLabel: String,
    rate: Float,
    onOpenModel: () -> Unit,
    onOpenVoice: () -> Unit,
    onStyleChange: (String) -> Unit,
    onRateChange: (Float) -> Unit
) {
    val c = echo
    val model = GeminiTts.modelOf(cfg)
    val modelLabel = GeminiTts.MODELS.firstOrNull { it.id == model }?.label ?: model
    SettingsSection(S.common.speech) {
        row(S.common.model, value = modelLabel, onClick = onOpenModel, trailing = { ChevronEnd() })
        row(S.common.voice, value = voiceLabel, onClick = onOpenVoice, trailing = { ChevronEnd() })
        custom {
            Text(S.gemini.style, color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            EchoTextField(cfg.style, onStyleChange, placeholder = S.gemini.stylePlaceholder)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, value) in STYLE_PRESETS) {
                    Chip(label, selected = cfg.style.trim() == value) { onStyleChange(value) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                S.gemini.styleHint,
                color = c.text3, style = MaterialTheme.typography.bodySmall
            )
        }
        custom {
            SettingsSlider(
                S.common.speed, String.format(java.util.Locale.ROOT, "%.2f×", rate),
                rate, 0.5f..2.5f, steps = 39
            ) { v -> onRateChange(Math.round(v * 20) / 20f) }
        }
    }
}

/** 模型选择器：3.8 的两档，外加手填（留给之后的新模型） */
@Composable
fun BoxScope.GeminiModelPickerSheet(open: Boolean, current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val c = echo
    EchoSheet(open = open, onDismiss = onClose, title = S.gemini.chooseAGeminiModel, maxHeightFraction = 0.72f) {
        for (m in GeminiTts.MODELS) {
            val selected = m.id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(if (selected) c.accentSoft else c.cardAlt, RoundedCornerShape(Radius.md))
                    .border(1.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(Radius.md))
                    .echoPress(pressedScale = PressScale.Tile) { onPick(m.id); onClose() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = if (selected) c.accent else c.text, style = MaterialTheme.typography.bodyLargeEmphasized)
                    Spacer(Modifier.height(2.dp))
                    Text("${m.langs} · ${m.note}", color = c.text2, style = MaterialTheme.typography.bodySmall)
                    Text(S.gemini.paidTier(m.price), color = c.text3, style = MaterialTheme.typography.labelSmall)
                }
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(EchoIcons.Check, S.common.selected, tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            S.gemini.tiersNote,
            color = c.text3, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(14.dp))
        EchoTextField(current, { v -> onPick(v.trim()) }, label = S.common.orEnterAModel, placeholder = GeminiTts.MODEL_FLASH)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 音色选择器：我的音色（自建）→ 30 个精选（全语言）→ 扩展音色库（按母语分组）。
 * 扩展音色库有几百个，所以在语种筛选之外再给一个搜索框（名字 / 口音 / 场景都能搜）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.GeminiVoicePickerSheet(
    open: Boolean,
    catalog: List<VoiceInfo>,
    current: String,
    hasKey: Boolean,
    syncing: Boolean,
    libraryLoaded: Boolean,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit
) {
    val c = echo
    var lang by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(open) {
        if (open) {
            lang = ""
            query = ""
        }
    }
    val q = query.trim().lowercase()
    val filtered = remember(catalog, q) {
        if (q.isEmpty()) catalog
        else catalog.filter { v -> v.id.lowercase().contains(q) || v.label.lowercase().contains(q) || v.note?.lowercase()?.contains(q) == true }
    }
    val groups = remember(filtered) { Voices.groupVoices(filtered) }
    LaunchedEffect(groups) { if (lang.isNotEmpty() && groups.none { it.lang == lang }) lang = "" }
    val shown = if (lang.isEmpty()) groups else groups.filter { it.lang == lang }

    EchoSheet(open = open, onDismiss = onClose, title = S.common.chooseVoice, maxHeightFraction = 0.88f, scrollable = false) {
        Text(
            when {
                libraryLoaded -> S.gemini.libraryLoadedHint
                syncing -> S.gemini.fetchingTheVoiceLibrary
                hasKey -> S.gemini.libraryNotLoadedHint
                else -> S.gemini.noKeyVoicesHint
            },
            color = c.text2, style = MaterialTheme.typography.bodyMedium
        )
        if (hasKey && !libraryLoaded && !syncing) {
            Spacer(Modifier.height(10.dp))
            OutlineButton(S.gemini.fetchVoiceLibrary, color = c.accent, height = 38.dp) { onRefresh() }
        }
        Spacer(Modifier.height(12.dp))
        EchoTextField(query, { query = it }, placeholder = S.gemini.voiceSearchPlaceholder)
        Spacer(Modifier.height(10.dp))
        if (groups.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.common.all, selected = lang.isEmpty()) { lang = "" }
                for (g in groups) Chip(g.label, selected = lang == g.lang, trailing = "${g.voices.size}") { lang = g.lang }
            }
            Spacer(Modifier.height(12.dp))
        }
        // 每个语种一项：屏幕外的语种不参与组合，几百个音色也不会一次性摆满
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(shown, key = { it.lang }) { g ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (shown.size > 1) {
                        Text(g.label, color = c.text3, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in g.voices) {
                            Chip(
                                if (v.note != null) "${v.label}·${v.note}" else v.label,
                                selected = current == v.id,
                                trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)
                            ) { onPick(v.id); onClose() }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text(S.gemini.noMatchingVoices, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        EchoTextField(
            current, { v -> onPick(v.trim()) },
            label = S.gemini.voiceIdField, placeholder = GeminiTts.DEFAULT_VOICE
        )
    }
}

/** 当前音色在主面板上的一行结论 */
internal fun geminiVoiceLabel(voice: String, catalog: List<VoiceInfo>): String {
    val v = voice.trim()
    if (v.isEmpty()) return S.common.serviceDefault
    val info = catalog.firstOrNull { it.id == v } ?: return v
    return info.label + (info.note?.let { " · $it" } ?: "")
}
