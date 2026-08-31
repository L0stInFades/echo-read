package app.echoread.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.collectAsState
import app.echoread.AppGraph
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsProvider
import kotlinx.coroutines.delay
import app.echoread.tts.SpeechApi
import app.echoread.tts.Voices
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/* ---------- 目录 ---------- */

@Composable
fun BoxScope.ChapterListSheet(open: Boolean, titles: List<String>, current: Int, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    val c = echo
    val state = rememberLazyListState()
    LaunchedEffect(open) {
        if (open && titles.isNotEmpty()) state.scrollToItem((current - 4).coerceIn(0, maxOf(titles.size - 1, 0)))
    }
    EchoSheet(open = open, onDismiss = onClose, title = "目录", scrollable = false) {
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(titles) { i, t ->
                val active = i == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) c.accentSoft else Color.Transparent, RoundedCornerShape(Radius.md))
                        .echoPress(pressedScale = PressScale.Tile) { onSelect(i); onClose() }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}", color = c.text3, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                    Text(
                        t, color = if (active) c.accent else c.text, fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (active) Icon(EchoIcons.PlaySmall, null, tint = c.accent, modifier = Modifier.size(14.dp))
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/* ---------- 阅读样式 ---------- */

@Composable
fun BoxScope.ReaderStyleSheet(open: Boolean, graph: AppGraph, onOpenGestures: () -> Unit = {}, onClose: () -> Unit) {
    val c = echo
    val reader by graph.settings.reader.collectAsState()
    EchoSheet(open = open, onDismiss = onClose, title = "阅读样式") {
        // 翻页手势单独开一层：内容多且带实时预览，塞进本表会把主题/字号挤到看不见
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.cardAlt, RoundedCornerShape(Radius.md))
                .echoPress(pressedScale = PressScale.Tile) { onOpenGestures() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(EchoIcons.SwipeH, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("翻页手势", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(gestureSummary(reader.gestures), color = c.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(18.dp))

        SectionLabel("外观") {
            Text(if (reader.dynamicColor) "跟随壁纸" else "品牌配色", color = c.text3, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("品牌配色", selected = !reader.dynamicColor, modifier = Modifier.weight(1f)) { graph.settings.updateReader { r -> r.copy(dynamicColor = false) } }
            Chip("动态取色", selected = reader.dynamicColor, modifier = Modifier.weight(1f)) { graph.settings.updateReader { r -> r.copy(dynamicColor = true) } }
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(6.dp))
            Text("动态取色需要 Android 12 及以上", color = c.text3, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))

        SectionLabel("主题")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in READER_THEMES) {
                val selected = reader.theme == t.id
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(t.bg, RoundedCornerShape(Radius.md))
                            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, RoundedCornerShape(Radius.md))
                            .echoPress(pressedScale = PressScale.Chip) { graph.settings.updateReader { r -> r.copy(theme = t.id) } },
                        contentAlignment = Alignment.Center
                    ) { Text("文", color = t.text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    Spacer(Modifier.height(5.dp))
                    Text(t.label, color = if (selected) c.accent else c.text2, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SectionLabel("字号") { Text("${reader.fontSize}sp", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        EchoSlider(reader.fontSize.toFloat(), { v -> graph.settings.updateReader { r -> r.copy(fontSize = v.toInt()) } }, 14f..28f, steps = 13)
        Spacer(Modifier.height(12.dp))
        SectionLabel("行距") { Text(String.format(java.util.Locale.ROOT, "%.1f", reader.lineHeight), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        EchoSlider(reader.lineHeight, { v -> graph.settings.updateReader { r -> r.copy(lineHeight = (Math.round(v * 10) / 10f)) } }, 1.4f..2.6f, steps = 11)
        Spacer(Modifier.height(12.dp))
        SectionLabel("段距") { Text(String.format(java.util.Locale.ROOT, "%.1f", reader.paraSpacing), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        EchoSlider(reader.paraSpacing, { v -> graph.settings.updateReader { r -> r.copy(paraSpacing = (Math.round(v * 10) / 10f)) } }, 0.4f..2f, steps = 15)
        Spacer(Modifier.height(16.dp))
        SectionLabel("字体")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionTile("宋体 / 衬线", null, reader.fontFamily == "serif", Modifier.weight(1f).fillMaxHeight(), TextStyle(fontFamily = FontFamily.Serif)) {
                graph.settings.updateReader { r -> r.copy(fontFamily = "serif") }
            }
            OptionTile("黑体 / 无衬线", null, reader.fontFamily == "sans", Modifier.weight(1f).fillMaxHeight()) {
                graph.settings.updateReader { r -> r.copy(fontFamily = "sans") }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("触觉反馈")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("开", reader.haptics, Modifier.weight(1f)) { graph.settings.updateReader { r -> r.copy(haptics = true) } }
            Chip("关", !reader.haptics, Modifier.weight(1f)) { graph.settings.updateReader { r -> r.copy(haptics = false) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------- AI 朗读设置（贴合 OpenRouter：自动同步模型/音色、余额与连接状态） ---------- */

private sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Ok(val count: Int, val credits: SpeechApi.Credits?) : SyncState
    data class Failed(val message: String) : SyncState
}

private val VENDOR_LABELS = mapOf(
    "hexgrad" to "Hexgrad", "qwen" to "通义", "minimax" to "MiniMax", "fish-audio" to "Fish Audio", "google" to "Google",
    "x-ai" to "xAI", "microsoft" to "Microsoft", "deepgram" to "Deepgram", "zyphra" to "Zyphra", "canopylabs" to "Canopy",
    "sesame" to "Sesame", "mistralai" to "Mistral", "openai" to "OpenAI"
)

private fun vendorOf(id: String): String = id.substringBefore('/', missingDelimiterValue = "")
private fun vendorLabel(id: String): String = VENDOR_LABELS[vendorOf(id)] ?: vendorOf(id).replaceFirstChar { it.uppercase() }.ifEmpty { "自定义" }

/** 单价（$/百万字符）或「按 token」/「免费」 */
private fun priceLabel(info: TtsModelInfo?, id: String): String? = when {
    id.contains(":free") -> "免费"
    info?.completionPrice != null && info.completionPrice > 0 -> "按 token 计费"
    info?.promptPrice != null && info.promptPrice > 0 -> {
        val perM = info.promptPrice * 1e6
        "$" + (if (perM >= 10) Math.round(perM).toString() else String.format(java.util.Locale.ROOT, "%.2f", perM).trimEnd('0').trimEnd('.')) + " / 百万字"
    }
    else -> null
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BoxScope.TtsSettingsSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    val settings = graph.settings
    val tts by settings.tts.collectAsState()
    val models by settings.models.collectAsState()
    val uriHandler = LocalUriHandler.current

    var testing by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    var testResult by remember { mutableStateOf("") }
    var sync by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    var showAdvanced by remember { mutableStateOf(false) }
    var voiceLang by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("") }
    var cacheStats by remember { mutableStateOf<app.echoread.tts.AudioCache.Stats?>(null) }

    LaunchedEffect(open) { if (open) cacheStats = graph.audioCache.stats() }

    val model = tts.openai.model
    val isOpenRouter = SpeechApi.isOpenRouterBase(tts.openai.baseUrl)
    val hints = remember(model) { Voices.modelHints(model) }
    val freeVoice = hints?.freeVoice
    val voiceCatalog = remember(model, models) { Voices.catalogVoices(model, settings.serverVoicesFor(model)) }
    val voiceGroups = remember(voiceCatalog) { Voices.groupVoices(voiceCatalog) }
    LaunchedEffect(voiceGroups) { if (voiceLang.isNotEmpty() && voiceGroups.none { it.lang == voiceLang }) voiceLang = "" }
    LaunchedEffect(model) { voiceLang = "" }
    val shownGroups = if (voiceLang.isEmpty()) voiceGroups else voiceGroups.filter { it.lang == voiceLang }
    val modelMeta = remember(model, models) { Voices.formatModelMeta(models.firstOrNull { it.id == model }, model) }
    val showInstructions = !isOpenRouter

    /** 同步模型列表（+ OpenRouter 余额）；silent 时失败不弹 toast */
    suspend fun syncModels(cfg: app.echoread.core.OpenAISpeechConfig, silent: Boolean) {
        if (cfg.apiKey.isBlank()) return
        sync = SyncState.Syncing
        try {
            val list = SpeechApi.fetchTtsModels(cfg)
            settings.setModels(list, settings.fingerprintOf(cfg))
            val credits = SpeechApi.fetchCredits(cfg)
            sync = SyncState.Ok(list.size, credits)
            if (!silent) Toaster.success(if (list.isNotEmpty()) "已同步 ${list.size} 个语音模型" else "该服务未列出语音模型，可手动输入模型名")
        } catch (e: Exception) {
            sync = SyncState.Failed(e.message ?: "获取模型列表失败")
            if (!silent) Toaster.error(e.message ?: "获取模型列表失败")
        }
    }

    // 自动同步：面板打开且 Key/端点变化（去抖 800ms）或列表超过 6 小时未刷新
    LaunchedEffect(open, tts.openai.apiKey, tts.openai.baseUrl) {
        if (!open || tts.openai.apiKey.isBlank()) return@LaunchedEffect
        delay(800)
        val fp = settings.fingerprintOf(tts.openai)
        val stale = models.isEmpty() || settings.modelsFingerprint != fp || System.currentTimeMillis() - settings.modelsSyncedAt > 6 * 3600_000L
        if (stale) syncModels(tts.openai, silent = true)
        else if (sync is SyncState.Idle) sync = SyncState.Ok(models.size, SpeechApi.fetchCredits(tts.openai))
    }

    fun runTest() {
        if (tts.openai.apiKey.isBlank()) {
            Toaster.error("请先填写 API Key")
            return
        }
        testing = true
        testResult = ""
        scope.launch {
            val r = SpeechApi.testConfig(tts.openai)
            if (!r.ok || r.audio == null) {
                testing = false
                testResult = r.message
                Toaster.error(r.message)
                return@launch
            }
            // 试听就得真的响：合成成功后立刻播出来（占用同一个播放器，先暂停正在朗读的引擎）
            testResult = "试听中…"
            try {
                graph.engine.pause()
                val f = withContext(Dispatchers.IO) {
                    File(appContext.cacheDir, "preview-${System.currentTimeMillis()}.audio").also { it.writeBytes(r.audio) }
                }
                graph.playback.setActive(true)
                graph.playback.play(f, 1f, deleteAfter = true).awaitEnded()
                Toaster.success("试听完成")
            } catch (e: Exception) {
                Toaster.error(e.message ?: "播放失败")
            } finally {
                graph.playback.setActive(false)
                testing = false
                testResult = ""
            }
        }
    }

    EchoSheet(open = open, onDismiss = onClose, title = "AI 朗读设置") {
        SectionLabel("朗读引擎")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionTile("AI TTS", "OpenRouter / OpenAI 兼容接口", tts.provider == TtsProvider.OPENAI, Modifier.weight(1f).fillMaxHeight()) {
                settings.updateTts { it.copy(provider = TtsProvider.OPENAI) }
            }
            OptionTile("系统语音", "免费离线，质量取决于设备", tts.provider == TtsProvider.SYSTEM, Modifier.weight(1f).fillMaxHeight()) {
                settings.updateTts { it.copy(provider = TtsProvider.SYSTEM) }
            }
        }
        Spacer(Modifier.height(18.dp))

        if (tts.provider == TtsProvider.OPENAI) {
            SectionLabel(if (isOpenRouter) "OpenRouter" else "API 配置") {
                if (isOpenRouter) Text(
                    "创建 Key →", color = c.accent, fontSize = 12.sp,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") } }
                )
            }
            EchoTextField(tts.openai.baseUrl, { v -> settings.updateOpenAI { it.copy(baseUrl = v.trim()) } }, label = "Base URL", placeholder = "https://openrouter.ai/api/v1", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
            Spacer(Modifier.height(10.dp))
            EchoTextField(tts.openai.apiKey, { v -> settings.updateOpenAI { it.copy(apiKey = v.trim()) } }, label = "API Key", placeholder = "sk-or-...", password = true)
            // 连接状态行：自动同步的结果 / 余额
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dot, text) = when (val st = sync) {
                    SyncState.Idle -> (if (tts.openai.apiKey.isBlank()) c.text3 else c.text3) to (if (tts.openai.apiKey.isBlank()) "填入 Key 后自动同步模型与音色" else "等待同步…")
                    SyncState.Syncing -> c.accent to "正在同步模型列表…"
                    is SyncState.Ok -> Color(0xFF34C759) to buildString {
                        append(if (isOpenRouter) "已连接 OpenRouter" else "已连接")
                        append(" · ${st.count} 个语音模型")
                        st.credits?.let { append(" · 余额 $" + String.format(java.util.Locale.ROOT, "%.2f", it.remaining)) }
                    }
                    is SyncState.Failed -> c.danger to st.message
                }
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(text, color = if (sync is SyncState.Failed) c.danger else c.text2, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sync !is SyncState.Syncing && tts.openai.apiKey.isNotBlank()) {
                    Text("刷新", color = c.accent, fontSize = 12.sp, modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { scope.launch { syncModels(tts.openai, silent = false) } }.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))

            SectionLabel("模型") { if (models.isNotEmpty()) Text("${models.size} 个", color = c.text3, fontSize = 11.sp) }
            if (models.isNotEmpty()) {
                // OpenRouter 风格模型卡片：推荐优先，可按名称/厂商筛选
                if (models.size > 6) {
                    EchoTextField(modelFilter, { modelFilter = it }, placeholder = "筛选模型（名称 / 厂商 / 中文 / 免费）")
                    Spacer(Modifier.height(8.dp))
                }
                val recommendedIds = Voices.RECOMMENDED_MODELS.map { it.id }
                val q = modelFilter.trim().lowercase()
                val ordered = remember(models, q) {
                    val list = models.sortedWith(compareBy<TtsModelInfo> { val i = recommendedIds.indexOf(it.id); if (i < 0) 99 else i }.thenBy { it.id })
                    if (q.isEmpty()) list else list.filter { m ->
                        val h = Voices.modelHints(m.id)
                        m.id.lowercase().contains(q) || m.name.lowercase().contains(q) || vendorLabel(m.id).lowercase().contains(q) ||
                            (h?.langs?.contains(q) == true) || (q == "免费" && m.id.contains(":free")) || (q == "中文" && (h?.langs?.contains("中") == true))
                    }
                }
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(ordered, key = { _, m -> m.id }) { _, m ->
                        val selected = m.id == model
                        val h = Voices.modelHints(m.id)
                        val voicesN = Voices.catalogVoices(m.id, m.voices).size
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selected) c.accentSoft else c.cardAlt, RoundedCornerShape(Radius.md))
                                .border(1.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(Radius.md))
                                .echoPress(pressedScale = PressScale.Tile) { settings.setModel(m.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 厂商徽标：厂商名首字母，按厂商哈希配色
                            val hue = (app.echoread.core.Hash.cyrb53(vendorOf(m.id)).take(6).toLong(16) % 360).toFloat()
                            Box(Modifier.size(34.dp).background(Color.hsl(hue, 0.55f, if (c.isDark) 0.38f else 0.52f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                                Text(vendorLabel(m.id).take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m.name.substringAfter(": ").ifEmpty { m.name }, color = if (selected) c.accent else c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (m.id.contains(":free")) Tag("免费", Color(0xFF34C759))
                                    if (h?.langs?.contains("中") == true) Tag("中文", c.accent)
                                    if (h?.cloning == true) Tag("克隆", Color(0xFFB47CFF))
                                }
                                Text(m.id, color = c.text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(priceLabel(m, m.id), h?.langs, if (voicesN > 0) "$voicesN 音色" else if (h?.freeVoice != null) "开放音色 ID" else null).joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, color = c.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) {
                                Spacer(Modifier.width(6.dp))
                                Icon(EchoIcons.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (ordered.isEmpty()) item { Text("没有匹配的模型", color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in Voices.RECOMMENDED_MODELS) Chip(r.label, selected = model == r.id, trailing = r.tag) { settings.setModel(r.id) }
                }
                Spacer(Modifier.height(8.dp))
            }
            EchoTextField(model, { v -> settings.setModel(v.trim()) }, label = "模型 ID（可手动填写列表外的模型）", placeholder = "如 hexgrad/kokoro-82m")
            if (modelMeta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(modelMeta, color = c.text3, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(18.dp))

            SectionLabel("音色") { if (voiceCatalog.isNotEmpty()) Text("${voiceCatalog.size} 个" + if (settings.serverVoicesFor(model) != null) " · 来自 OpenRouter" else "", color = c.text3, fontSize = 11.sp) }
            when {
                freeVoice != null -> {
                    EchoTextField(tts.openai.voice, { v -> settings.setVoice(v.trim()) }, placeholder = freeVoice.placeholder)
                    Spacer(Modifier.height(8.dp))
                    Text(freeVoice.hint, color = c.text3, fontSize = 11.sp, lineHeight = 16.sp)
                    if (hints.voiceOptional || freeVoice.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (hints.voiceOptional) Chip("默认音色", selected = tts.openai.voice.isEmpty()) { settings.setVoice("") }
                            for (v in freeVoice.suggestions) {
                                Chip(v.label, selected = tts.openai.voice == v.id, trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)) { settings.setVoice(v.id) }
                            }
                        }
                    }
                }
                voiceCatalog.isNotEmpty() -> {
                    EchoTextField(tts.openai.voice, { v -> settings.setVoice(v.trim()) }, placeholder = "音色 ID")
                    if (voiceGroups.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("全部", selected = voiceLang.isEmpty()) { voiceLang = "" }
                            for (g in voiceGroups) Chip(g.label, selected = voiceLang == g.lang, trailing = "${g.voices.size}") { voiceLang = g.lang }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScrollCompat()
                    ) {
                        for (g in shownGroups) {
                            if (shownGroups.size > 1) Text(g.label, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp, top = 4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (v in g.voices) {
                                    Chip(
                                        if (v.note != null) "${v.label}·${v.note}" else v.label,
                                        selected = tts.openai.voice == v.id,
                                        trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)
                                    ) { settings.setVoice(v.id) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                else -> EchoTextField(tts.openai.voice, { v -> settings.setVoice(v.trim()) }, placeholder = "该模型未提供音色列表，可手动填写（留空试用服务默认）")
            }
            Spacer(Modifier.height(18.dp))

            if (showInstructions) {
                SectionLabel("语气指令（部分模型支持）")
                EchoTextField(tts.openai.instructions, { v -> settings.updateOpenAI { it.copy(instructions = v) } }, placeholder = "如：用温暖沉静的女声朗读")
                Spacer(Modifier.height(18.dp))
            }

            GradientButton(
                if (testing) (if (testResult.isNotEmpty()) testResult else "正在合成…") else if (testResult.isNotEmpty()) "结果：$testResult" else "试听测试（合成「你好」）",
                Modifier.fillMaxWidth(), enabled = !testing, height = 50.dp
            ) { runTest() }
            Spacer(Modifier.height(20.dp))
        }

        SectionLabel("播放倍速") { Text(String.format(java.util.Locale.ROOT, "%.2f×", tts.rate), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        EchoSlider(tts.rate, { v -> settings.updateTts { it.copy(rate = (Math.round(v * 20) / 20f)) } }, 0.5f..2.5f, steps = 39)
        Spacer(Modifier.height(14.dp))

        Text(
            if (showAdvanced) "收起高级选项 ▲" else "高级选项 ▼",
            color = c.text2, fontSize = 12.sp,
            modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { showAdvanced = !showAdvanced }.padding(vertical = 4.dp)
        )
        AnimatedVisibility(showAdvanced, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
            EchoCard(Modifier.padding(top = 8.dp), radius = Radius.lg, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), color = c.cardAlt) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("单片段字数 ", color = c.text, fontSize = 13.sp)
                    Text("(${tts.maxChunkChars})", color = c.text3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    EchoSlider(tts.maxChunkChars.toFloat(), { v -> settings.updateTts { it.copy(maxChunkChars = (Math.round(v / 10) * 10)) } }, 80f..400f, steps = 31, modifier = Modifier.width(150.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("预取片段数 ", color = c.text, fontSize = 13.sp)
                    Text("(${tts.prefetch})", color = c.text3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    EchoSlider(tts.prefetch.toFloat(), { v -> settings.updateTts { it.copy(prefetch = Math.round(v)) } }, 0f..5f, steps = 4, modifier = Modifier.width(150.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val st = cacheStats
                    Text(
                        if (st == null) "音频缓存 …" else "音频缓存 ${st.count} 条 · ${formatBytes(st.bytes)}",
                        color = c.text2, fontSize = 12.sp, modifier = Modifier.weight(1f)
                    )
                    Text("清空", color = c.danger, fontSize = 12.sp, modifier = Modifier.echoPress(pressedScale = PressScale.Chip) {
                        scope.launch {
                            graph.audioCache.clear()
                            cacheStats = graph.audioCache.stats()
                            Toaster.success("音频缓存已清空")
                        }
                    })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "填入 Key 后自动从 OpenRouter 同步全部语音模型、音色与单价（每 6 小时刷新）；也兼容 OpenAI 官方、SiliconFlow 等 OpenAI 格式接口。Fish S2.1 有免费档可先试听。",
            color = c.text3, fontSize = 11.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 5.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private fun genderMark(g: String?): String? = when (g) { "f" -> "♀"; "m" -> "♂"; else -> null }

@Composable
private fun genderColor(g: String?): Color? = when (g) { "f" -> Color(0xFFF472B6); "m" -> Color(0xFF38BDF8); else -> null }

fun formatBytes(n: Long): String = when {
    n > 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1024.0 / 1024.0)
    n > 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

/** 弹层内部的有界滚动区（外层已是 verticalScroll，内层需要独立滚动时使用） */
@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())

/* ---------- 帮助 ---------- */

@Composable
fun BoxScope.HelpSheet(open: Boolean, graph: AppGraph? = null, onClose: () -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    EchoSheet(open = open, onDismiss = onClose, title = "怎么用") {
        if (graph != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.cardAlt, RoundedCornerShape(Radius.md))
                    .echoPress(pressedScale = PressScale.Tile, enabled = !checking) {
                        checking = true
                        scope.launch {
                            val r = runCatching { graph.updater.check(force = true) }.getOrNull()
                            checking = false
                            when (r) {
                                is app.echoread.data.UpdateState.UpToDate -> Toaster.success("已是最新版本 v${graph.updater.currentVersionName}")
                                is app.echoread.data.UpdateState.Available, is app.echoread.data.UpdateState.Ready -> { Toaster.show("发现新版本，见书架顶部"); onClose() }
                                is app.echoread.data.UpdateState.Error -> Toaster.error(r.message)
                                else -> Toaster.error("检查更新失败")
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (checking) "正在检查更新…" else "检查更新", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("当前版本 v${graph.updater.currentVersionName}", color = c.text3, fontSize = 11.sp)
                }
                Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
        val sections = listOf(
            "导入书籍" to "点底部「导入书籍」会自动扫描本机所有 TXT / EPUB，勾选后批量入库，已在书架的会标出来。" +
                "首次使用需要授权：Android 11 起系统只允许「所有文件访问权限」做全盘扫描，不想开也可以只授权某个文件夹（书常放在 Download、Documents、Books 里）。" +
                "也可以随时用「从文件管理器选择」，或在文件管理器里用「打开方式」发给 EchoRead。",
            "点读" to "打开书籍后，轻点正文任意位置，AI 就从那个字开始朗读。底栏可以暂停、切章、调倍速，月亮按钮是睡眠定时。锁屏与通知栏可控制播放、切章。",
            "翻页手势" to "阅读页 → T 图标 → 「翻页手势」可以改：左右滑 / 上下滑 / 关闭滑动，点击翻页热区放在左右还是上下、各占多宽，以及要不要保留「轻点朗读」。" +
                "顶部有实时预览，拖滑块就能看到热区变化。提示：系统手势导航会吃掉屏幕左右边缘起手的横滑，改成「上下滑」可以完全避开这个冲突。",
            "声音" to "齿轮里填入 OpenRouter / OpenAI 兼容接口的 API Key，拉取模型后选音色即可。没有 Key 时切到「系统语音」，用手机自带的离线朗读引擎。",
            "离线" to "书架和已经听过的片段缓存在本机（最多 300MB，自动淘汰最旧）。断网也能继续读、继续听缓存过的句子。"
        )
        for ((h, p) in sections) {
            Text(h, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(p, color = c.text2, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 手势设置一行摘要，显示在「阅读样式」的入口行上 */
private fun gestureSummary(g: app.echoread.core.GestureSettings): String {
    val swipe = when (g.axis) {
        app.echoread.core.PageAxis.HORIZONTAL -> "左右滑"
        app.echoread.core.PageAxis.VERTICAL -> "上下滑"
        app.echoread.core.PageAxis.OFF -> "不滑动"
    }
    val tap = when {
        !g.zonesActive -> "无点击热区"
        g.tapAxis == app.echoread.core.PageAxis.VERTICAL -> "上下热区 ${(g.prevZone * 100).toInt()}/${(g.nextZone * 100).toInt()}%"
        else -> "左右热区 ${(g.prevZone * 100).toInt()}/${(g.nextZone * 100).toInt()}%"
    }
    return "$swipe · $tap" + if (g.tapToRead) " · 轻点朗读" else ""
}
