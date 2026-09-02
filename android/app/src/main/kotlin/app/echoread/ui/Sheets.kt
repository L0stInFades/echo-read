package app.echoread.ui

import androidx.compose.ui.graphics.toArgb
import app.echoread.core.ColorStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.MaterialTheme
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
                    Text("${i + 1}", color = c.text3, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(30.dp))
                    Text(
                        t, color = if (active) c.accent else c.text, style = MaterialTheme.typography.bodyMedium,
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
        // 翻页手势单独开一层：内容多且带实时预览，塞进本表会把主题/字号挤到看不见。
        // 与其余各行同构：副标题就是当前设置的摘要，不点进去也知道现在是什么配置。
        SettingsSection {
            row(
                "翻页手势",
                value = gestureSummary(reader.gestures),
                icon = EchoIcons.SwipeH,
                trailing = { ChevronEnd() },
                onClick = onOpenGestures
            )
        }
        Spacer(Modifier.height(20.dp))

        /* ---- 应用配色：与原生「壁纸与个性化」同构 ----
         * 整套 48 个角色由 Google 的 material-color-utilities 按「种子色 × 风格」现算，
         * 不是几套写死的常量。跟随壁纸时直接用系统算好的那份。 */
        val dynAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        SettingsSection("应用配色") {
            switch(
                "跟随壁纸取色",
                value = if (dynAvailable) "用系统从壁纸提取的颜色" else "需要 Android 12 及以上",
                checked = reader.dynamicColor && dynAvailable,
                enabled = dynAvailable
            ) { on -> graph.settings.updateReader { r -> r.copy(dynamicColor = on) } }
            if (!(reader.dynamicColor && dynAvailable)) {
                customFullBleed {
                    Text("主色", color = c.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    LabeledSwatchRow {
                        for ((name, seed) in SEED_COLORS) {
                            val sel = reader.seedColor == seed.toArgb()
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SeedDot(seed, sel) {
                                    graph.settings.updateReader { r -> r.copy(seedColor = seed.toArgb()) }
                                }
                                SwatchLabel(name, sel)
                            }
                        }
                    }
                }
                customFullBleed {
                    Text("风格", color = c.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    LabeledSwatchRow {
                        for (st in ColorStyle.PICKABLE) {
                            val sel = reader.colorStyle == st
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PaletteSwatch(Color(reader.seedColor), st, sel) {
                                    graph.settings.updateReader { r -> r.copy(colorStyle = st) }
                                }
                                SwatchLabel(st.label, sel)
                            }
                        }
                    }
                }
            }
            custom {
                Text("对比度", color = c.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                val levels = listOf(0f to "标准", 0.5f to "中", 1f to "高")
                EchoSegmented(
                    items = levels.map { SegmentItem(it.second) },
                    selectedIndex = levels.indexOfFirst { kotlin.math.abs(it.first - reader.contrast) < 0.01f }.coerceAtLeast(0)
                ) { i -> graph.settings.updateReader { r -> r.copy(contrast = levels[i].first) } }
            }
        }
        Spacer(Modifier.height(20.dp))

        /* ---- 阅读：与「应用配色」同一套分组结构 ----
         * 主题 / 字号 / 行距 / 段距 / 字体 归为一组：它们共同决定书页长什么样，
         * 原来是五个平铺的小节标签，扫读时看到的是五个标签而不是五个当前值。 */
        SettingsSection("阅读") {
            customFullBleed {
                Text("主题", color = c.text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (t in READER_THEMES) {
                        val selected = reader.theme == t.id
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .background(t.bg, RoundedCornerShape(Radius.md))
                                    .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, RoundedCornerShape(Radius.md))
                                    .echoPress(pressedScale = PressScale.Chip) { graph.settings.updateReader { r -> r.copy(theme = t.id) } },
                                contentAlignment = Alignment.Center
                            ) { Text("文", color = t.text, style = MaterialTheme.typography.titleSmallEmphasized) }
                            Spacer(Modifier.height(6.dp))
                            SwatchLabel(t.label, selected)
                        }
                    }
                }
            }
            custom {
                SettingsSlider("字号", "${reader.fontSize}sp", reader.fontSize.toFloat(), 14f..28f, steps = 13) { v ->
                    graph.settings.updateReader { r -> r.copy(fontSize = v.toInt()) }
                }
            }
            custom {
                SettingsSlider("行距", String.format(java.util.Locale.ROOT, "%.1f", reader.lineHeight), reader.lineHeight, 1.4f..2.6f, steps = 11) { v ->
                    graph.settings.updateReader { r -> r.copy(lineHeight = (Math.round(v * 10) / 10f)) }
                }
            }
            custom {
                SettingsSlider("段距", String.format(java.util.Locale.ROOT, "%.1f", reader.paraSpacing), reader.paraSpacing, 0.4f..2f, steps = 15) { v ->
                    graph.settings.updateReader { r -> r.copy(paraSpacing = (Math.round(v * 10) / 10f)) }
                }
            }
            custom {
                Text("字体", color = c.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                // 二选一 → 连接式按钮组。字体名各自用对应字族渲染，选项本身就是预览。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    val serif = reader.fontFamily == "serif"
                    androidx.compose.material3.ToggleButton(
                        checked = serif,
                        onCheckedChange = { if (it) graph.settings.updateReader { r -> r.copy(fontFamily = "serif") } },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = connectedShapesAt(0, 2),
                        border = if (serif) null else BorderStroke(1.dp, c.border)
                    ) { Text("宋体 / 衬线", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    androidx.compose.material3.ToggleButton(
                        checked = !serif,
                        onCheckedChange = { if (it) graph.settings.updateReader { r -> r.copy(fontFamily = "sans") } },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = connectedShapesAt(1, 2),
                        border = if (!serif) null else BorderStroke(1.dp, c.border)
                    ) { Text("黑体 / 无衬线", fontFamily = FontFamily.SansSerif, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        SettingsSection("其他") {
            switch("触觉反馈", value = "翻页、吸附与返回时的轻微震动", checked = reader.haptics) { on ->
                graph.settings.updateReader { r -> r.copy(haptics = on) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------- AI 朗读设置（贴合 OpenRouter：自动同步模型/音色、余额与连接状态） ---------- */

private sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Ok(
        val count: Int,
        val credits: SpeechApi.Credits?,
        /** 余额单独失败：连接本身仍算成功，但必须显示出来 —— /credits 的 401 常常是 Key 被吊销的最早证据 */
        val creditsError: app.echoread.core.net.NetError? = null,
        /** 首次带鉴权的请求 401、兜底的匿名请求却成功：配置很可能是坏的，只是这次被网关放行了 */
        val suspiciousAuth: app.echoread.core.net.NetError? = null
    ) : SyncState
    data class Failed(val error: app.echoread.core.net.NetError) : SyncState
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
            val r = SpeechApi.fetchTtsModels(cfg)
            // 只有真正解析成功才回写缓存。旧实现会把「HTTP 200 + 一张网关登录页」解析成空列表
            // 并覆盖掉原本好好的模型缓存，再被 6 小时的新鲜度闸门挡住重试 —— 绿点后面空无一物。
            settings.setModels(r.models, settings.fingerprintOf(cfg))
            val credits = SpeechApi.fetchCredits(cfg)
            sync = SyncState.Ok(
                count = r.models.size,
                credits = credits.getOrNull(),
                creditsError = credits.exceptionOrNull()?.let {
                    app.echoread.core.net.NetErrors.fromThrowable(it, endpoint = cfg.baseUrl, kind = app.echoread.core.net.CallKind.CREDITS)
                },
                suspiciousAuth = r.suspiciousAuth
            )
            if (!silent) Toaster.success(if (r.models.isNotEmpty()) "已同步 ${r.models.size} 个语音模型" else "该服务未列出语音模型，可手动输入模型名")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err = app.echoread.core.net.NetErrors.fromThrowable(
                e, endpoint = cfg.baseUrl, method = "GET", kind = app.echoread.core.net.CallKind.MODEL_LIST, model = cfg.model
            )
            sync = SyncState.Failed(err)
            if (!silent) ErrorDetails.toast(err)
        }
    }

    // 自动同步：面板打开且 Key/端点变化（去抖 800ms）或列表超过 6 小时未刷新
    LaunchedEffect(open, tts.openai.apiKey, tts.openai.baseUrl) {
        if (!open || tts.openai.apiKey.isBlank()) return@LaunchedEffect
        delay(800)
        val fp = settings.fingerprintOf(tts.openai)
        val stale = models.isEmpty() || settings.modelsFingerprint != fp || System.currentTimeMillis() - settings.modelsSyncedAt > 6 * 3600_000L
        if (stale) syncModels(tts.openai, silent = true)
        else if (sync is SyncState.Idle) {
            val credits = SpeechApi.fetchCredits(tts.openai)
            sync = SyncState.Ok(
                count = models.size,
                credits = credits.getOrNull(),
                creditsError = credits.exceptionOrNull()?.let {
                    app.echoread.core.net.NetErrors.fromThrowable(it, endpoint = tts.openai.baseUrl, kind = app.echoread.core.net.CallKind.CREDITS)
                }
            )
        }
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
                r.error?.let { ErrorDetails.toast(it) } ?: Toaster.error(r.message)
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


    /* ---------------- 界面：按原生「设置」的组织方式重排 ----------------
     *
     * 旧版是一张长表单：八个「小节标签 + 控件」平铺，凭据、模型、音色、试听、倍速、
     * 高级选项全在一个层级上。它有三个具体问题：
     * ① 扫读时看到的全是标签，看不到值 —— 想知道现在用的哪个音色，得去输入框里读；
     * ② 一次性配好的凭据永远占着最上面两屏，而每次都可能改的音色和语速被压在下面；
     * ③ 「试听」是一个全宽渐变主按钮，夹在语气指令和倍速之间 —— 它验证的是连接，
     *    却离连接状态行隔了三个小节。
     *
     * 现在：每行的副标题就是当前值；凭据折叠进状态行；试听紧挨状态行；
     * 模型与音色改为独立选择器，主面板只留一行结论。
     */
    var showModelPicker by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    // 没填 Key 时凭据默认展开 —— 那是此刻唯一要做的事
    LaunchedEffect(open) { if (open) showCredentials = tts.openai.apiKey.isBlank() }

    val currentModelInfo = models.firstOrNull { it.id == model }
    val modelLabel = when {
        model.isBlank() -> "未选择"
        currentModelInfo != null -> currentModelInfo.name.substringAfter(": ").ifEmpty { model }
        else -> model
    }
    val currentVoice = tts.openai.voice
    val voiceLabel = when {
        currentVoice.isBlank() -> if (hints?.voiceOptional == true) "服务默认" else "未设置"
        else -> voiceCatalog.firstOrNull { it.id == currentVoice }?.let { v ->
            buildString { append(v.label); v.note?.let { append(" · ").append(it) } }
        } ?: currentVoice
    }

    EchoSheet(open = open, onDismiss = onClose, title = "AI 朗读设置") {
        SettingsSection("朗读引擎") {
            custom {
                EchoSegmented(
                    items = listOf(SegmentItem("AI 语音"), SegmentItem("系统语音")),
                    selectedIndex = if (tts.provider == TtsProvider.OPENAI) 0 else 1
                ) { i ->
                    settings.updateTts { it.copy(provider = if (i == 0) TtsProvider.OPENAI else TtsProvider.SYSTEM) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tts.provider == TtsProvider.OPENAI) "OpenRouter / OpenAI 兼容接口，音质最好"
                    else "系统内置语音，免费离线，音质取决于设备",
                    color = c.text2, style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (tts.provider == TtsProvider.OPENAI) {
            /* ---- 服务：状态即入口。点状态行展开凭据，试听就在旁边 ---- */
            SettingsSection("服务") {
                custom {
                    val st = sync
                    val (dot, statusText) = when (st) {
                        SyncState.Idle -> c.text3 to (if (tts.openai.apiKey.isBlank()) "填入 API Key 后自动同步模型与音色" else "等待同步…")
                        SyncState.Syncing -> c.accent to "正在同步模型列表…"
                        is SyncState.Ok -> {
                            // 有任何可疑信号就不给绿灯：一个「已连接」的绿点如果建立在未验证的字节上，
                            // 比没有指示器更糟。
                            val warn = st.suspiciousAuth != null || st.creditsError != null
                            (if (warn) warningColor(c.isDark) else Color(0xFF34C759)) to buildString {
                                append(if (isOpenRouter) "已连接 OpenRouter" else "已连接")
                                append(" · ${st.count} 个语音模型")
                                st.credits?.let { append(" · 余额 $" + String.format(java.util.Locale.ROOT, "%.2f", it.remaining)) }
                                st.creditsError?.let { append(" · 余额获取失败（${it.badge()}）") }
                                st.suspiciousAuth?.let { append(" · 鉴权可疑（${it.badge()}）") }
                            }
                        }
                        is SyncState.Failed -> c.danger to st.error.headline()
                    }
                    Row(
                        Modifier.fillMaxWidth().echoPress(pressedScale = PressScale.Tile) { showCredentials = !showCredentials },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (tts.openai.apiKey.isBlank()) "未配置" else if (isOpenRouter) "OpenRouter" else "自定义接口",
                                color = c.text, style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                statusText,
                                color = if (st is SyncState.Failed) c.danger else c.text2,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            if (showCredentials) EchoIcons.ChevronUp else EchoIcons.ChevronDown,
                            if (showCredentials) "收起" else "展开",
                            tint = c.text3, modifier = Modifier.size(20.dp)
                        )
                    }
                    // 「详情」与「刷新」：状态行放不下的原因都在详情里
                    val detailFor = when (val st2 = sync) {
                        is SyncState.Failed -> st2.error
                        is SyncState.Ok -> st2.suspiciousAuth ?: st2.creditsError
                        else -> null
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 试听验证的就是这条连接，所以放在这里，而不是隔着三个小节的表单中段
                        OutlineButton(
                            if (testing) (testResult.ifEmpty { "正在合成…" }) else "试听",
                            Modifier.weight(1f), color = c.accent, height = 40.dp
                        ) { if (!testing) runTest() }
                        if (tts.openai.apiKey.isNotBlank()) {
                            OutlineButton("刷新", Modifier.weight(1f), height = 40.dp) {
                                scope.launch { syncModels(tts.openai, silent = false) }
                            }
                        }
                        if (detailFor != null) {
                            OutlineButton("详情", Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(detailFor) }
                        }
                    }
                    AnimatedVisibility(showCredentials, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
                        Column(Modifier.padding(top = 14.dp)) {
                            EchoTextField(
                                tts.openai.baseUrl, { v -> settings.updateOpenAI { it.copy(baseUrl = v.trim()) } },
                                label = "Base URL", placeholder = "https://openrouter.ai/api/v1",
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                            )
                            Spacer(Modifier.height(10.dp))
                            EchoTextField(
                                tts.openai.apiKey, { v -> settings.updateOpenAI { it.copy(apiKey = v.trim()) } },
                                label = "API Key", placeholder = "sk-or-...", password = true
                            )
                            if (isOpenRouter) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "去 OpenRouter 创建 Key →", color = c.accent,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) {
                                        runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            /* ---- 声音：每行的副标题就是当前值，一眼扫完全部配置 ---- */
            SettingsSection("声音") {
                row("模型", value = modelLabel, onClick = { showModelPicker = true }, trailing = { ChevronEnd() })
                row("音色", value = voiceLabel, onClick = { showVoicePicker = true }, trailing = { ChevronEnd() })
                if (showInstructions) {
                    custom {
                        Text("语气指令", color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        EchoTextField(
                            tts.openai.instructions,
                            { v -> settings.updateOpenAI { it.copy(instructions = v) } },
                            placeholder = "如：用温暖沉静的女声朗读"
                        )
                    }
                }
                custom {
                    SettingsSlider(
                        "语速", String.format(java.util.Locale.ROOT, "%.2f×", tts.rate),
                        tts.rate, 0.5f..2.5f, steps = 39
                    ) { v -> settings.updateTts { it.copy(rate = (Math.round(v * 20) / 20f)) } }
                }
            }
            Spacer(Modifier.height(20.dp))

            /* ---- 高级：影响成本与延迟的旋钮，以及缓存 ---- */
            SettingsSection("高级") {
                custom {
                    SettingsSlider("单片段字数", "${tts.maxChunkChars}", tts.maxChunkChars.toFloat(), 80f..400f, steps = 31) { v ->
                        settings.updateTts { it.copy(maxChunkChars = (Math.round(v / 10) * 10)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("每次请求合成多少字。调大更省请求次数，但换段更慢。", color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                custom {
                    SettingsSlider("预取段数", "${tts.prefetch}", tts.prefetch.toFloat(), 0f..5f, steps = 4) { v ->
                        settings.updateTts { it.copy(prefetch = Math.round(v)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("提前合成几段。调大更不容易断，但弱网下可能白花钱。", color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                row(
                    "语音缓存",
                    value = cacheStats?.let { "${it.count} 条 · ${formatBytes(it.bytes)}" } ?: "统计中…",
                    trailing = {
                        Text("清空", color = c.danger, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.echoPress(pressedScale = PressScale.Chip) {
                                scope.launch {
                                    graph.audioCache.clear()
                                    cacheStats = graph.audioCache.stats()
                                    Toaster.success("语音缓存已清空")
                                }
                            })
                    }
                )
            }
        } else {
            /* ---- 系统语音：只有语速可调 ---- */
            SettingsSection("声音") {
                custom {
                    SettingsSlider(
                        "语速", String.format(java.util.Locale.ROOT, "%.2f×", tts.rate),
                        tts.rate, 0.5f..2.5f, steps = 39
                    ) { v -> settings.updateTts { it.copy(rate = (Math.round(v * 20) / 20f)) } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    ModelPickerSheet(
        open = showModelPicker, graph = graph, models = models, current = model,
        onClose = { showModelPicker = false }
    )
    VoicePickerSheet(
        open = showVoicePicker, graph = graph, model = model, current = currentVoice,
        onClose = { showVoicePicker = false }
    )
}

/** 尾部的「进入」箭头，所有可点进子界面的行共用 */
@Composable
private fun ChevronEnd() {
    Icon(EchoIcons.ChevronRight, null, tint = echo.text3, modifier = Modifier.size(20.dp))
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
                                is app.echoread.data.UpdateState.Error -> r.error?.let { ErrorDetails.toast(it) } ?: Toaster.error(r.message)
                                else -> Toaster.error("检查更新失败")
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (checking) "正在检查更新…" else "检查更新", color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("当前版本 v${graph.updater.currentVersionName}", color = c.text3, style = MaterialTheme.typography.labelSmall)
                }
                Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
        val sections = listOf(
            "导入书籍" to "点底部「导入书籍」会自动扫描本机所有 TXT / EPUB，勾选后批量入库，已在书架的会标出来。" +
                "首次使用需要授权：Android 11 起系统只允许「所有文件访问权限」做全盘扫描，不想开也可以只授权某个文件夹（书常放在 Download、Documents、Books 里）。" +
                "也可以随时用「从文件管理器选择」，或在文件管理器里用「打开方式」发给 Lector。",
            "点读" to "打开书籍后，轻点正文任意位置，AI 就从那个字开始朗读。底栏可以暂停、切章、调倍速，月亮按钮是睡眠定时。锁屏与通知栏可控制播放、切章。",
            "翻页手势" to "阅读页 → T 图标 → 「翻页手势」可以改：左右滑 / 上下滑 / 关闭滑动，点击翻页热区放在左右还是上下、各占多宽，以及要不要保留「轻点朗读」。" +
                "顶部有实时预览，拖滑块就能看到热区变化。提示：系统手势导航会吃掉屏幕左右边缘起手的横滑，改成「上下滑」可以完全避开这个冲突。",
            "声音" to "齿轮里填入 OpenRouter / OpenAI 兼容接口的 API Key，拉取模型后选音色即可。没有 Key 时切到「系统语音」，用手机自带的离线朗读引擎。",
            "离线" to "书架和已经听过的片段缓存在本机（最多 300MB，自动淘汰最旧）。断网也能继续读、继续听缓存过的句子。"
        )
        for ((h, p) in sections) {
            Text(h, color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(p, color = c.text2, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
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

/* ---------------- 模型 / 音色选择器 ----------------
 *
 * 从主面板里拆出来的理由：模型列表在 OpenRouter 上有几十条，音色可能上百个。
 * 它们内联在设置页里会把「其余所有设置」推到看不见的地方，而它们各自只被改一次。
 * 拆成选择器后，主面板每项只剩一行结论，列表本身反而能给到全屏高度与搜索。
 */

/** 模型选择器：推荐优先排序，可按名称/厂商/语言/免费筛选 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BoxScope.ModelPickerSheet(
    open: Boolean,
    graph: AppGraph,
    models: List<TtsModelInfo>,
    current: String,
    onClose: () -> Unit
) {
    val c = echo
    val settings = graph.settings
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(open) { if (open) filter = "" }

    EchoSheet(open = open, onDismiss = onClose, title = "选择模型", maxHeightFraction = 0.88f, scrollable = false) {
        if (models.isEmpty()) {
            Text(
                "还没有同步到模型列表。填入 API Key 后会自动同步；也可以先用下面的推荐模型。",
                color = c.text2, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in Voices.RECOMMENDED_MODELS) {
                    Chip(r.label, selected = current == r.id, trailing = r.tag) { settings.setModel(r.id); onClose() }
                }
            }
            Spacer(Modifier.height(16.dp))
            EchoTextField(current, { v -> settings.setModel(v.trim()) }, label = "或手动填写模型 ID", placeholder = "如 hexgrad/kokoro-82m")
            return@EchoSheet
        }

        EchoTextField(filter, { filter = it }, placeholder = "筛选模型（名称 / 厂商 / 中文 / 免费）")
        Spacer(Modifier.height(10.dp))
        val recommendedIds = Voices.RECOMMENDED_MODELS.map { it.id }
        val q = filter.trim().lowercase()
        val ordered = remember(models, q) {
            val list = models.sortedWith(
                compareBy<TtsModelInfo> { val i = recommendedIds.indexOf(it.id); if (i < 0) 99 else i }.thenBy { it.id }
            )
            if (q.isEmpty()) list else list.filter { m ->
                val h = Voices.modelHints(m.id)
                m.id.lowercase().contains(q) || m.name.lowercase().contains(q) || vendorLabel(m.id).lowercase().contains(q) ||
                    (h?.langs?.contains(q) == true) || (q == "免费" && m.id.contains(":free")) || (q == "中文" && (h?.langs?.contains("中") == true))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(ordered, key = { _, m -> m.id }) { _, m ->
                val selected = m.id == current
                val h = Voices.modelHints(m.id)
                val voicesN = Voices.catalogVoices(m.id, m.voices).size
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) c.accentSoft else c.cardAlt, RoundedCornerShape(Radius.md))
                        .border(1.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(Radius.md))
                        .echoPress(pressedScale = PressScale.Tile) { settings.setModel(m.id); onClose() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 厂商徽标：厂商名首字母，按厂商哈希配色
                    val hue = (app.echoread.core.Hash.cyrb53(vendorOf(m.id)).take(6).toLong(16) % 360).toFloat()
                    Box(
                        Modifier.size(36.dp).background(Color.hsl(hue, 0.55f, if (c.isDark) 0.38f else 0.52f), RoundedCornerShape(Radius.mdMinus)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(vendorLabel(m.id).take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmallEmphasized)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.name.substringAfter(": ").ifEmpty { m.name },
                                color = if (selected) c.accent else c.text,
                                style = MaterialTheme.typography.bodyLargeEmphasized,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (m.id.contains(":free")) Tag("免费", Color(0xFF34C759))
                            if (h?.langs?.contains("中") == true) Tag("中文", c.accent)
                            if (h?.cloning == true) Tag("克隆", Color(0xFFB47CFF))
                        }
                        val meta = listOfNotNull(
                            priceLabel(m, m.id), h?.langs,
                            if (voicesN > 0) "$voicesN 音色" else if (h?.freeVoice != null) "开放音色 ID" else null
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(meta, color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(m.id, color = c.text3, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(EchoIcons.Check, "已选中", tint = c.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (ordered.isEmpty()) {
                item { Text("没有匹配的模型", color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        EchoTextField(current, { v -> settings.setModel(v.trim()) }, label = "或手动填写列表外的模型 ID", placeholder = "如 hexgrad/kokoro-82m")
    }
}

/** 音色选择器：按语言分组，可筛选；模型不提供音色列表时退回手填 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BoxScope.VoicePickerSheet(
    open: Boolean,
    graph: AppGraph,
    model: String,
    current: String,
    onClose: () -> Unit
) {
    val c = echo
    val settings = graph.settings
    val models by settings.models.collectAsState()
    var lang by remember { mutableStateOf("") }
    LaunchedEffect(open, model) { if (open) lang = "" }

    val hints = remember(model) { Voices.modelHints(model) }
    val freeVoice = hints?.freeVoice
    val catalog = remember(model, models) { Voices.catalogVoices(model, settings.serverVoicesFor(model)) }
    val groups = remember(catalog) { Voices.groupVoices(catalog) }
    val shown = if (lang.isEmpty()) groups else groups.filter { it.lang == lang }

    EchoSheet(open = open, onDismiss = onClose, title = "选择音色", maxHeightFraction = 0.88f, scrollable = false) {
        when {
            freeVoice != null -> {
                Text(freeVoice.hint, color = c.text2, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                EchoTextField(current, { v -> settings.setVoice(v.trim()) }, label = "音色 ID", placeholder = freeVoice.placeholder)
                if (hints.voiceOptional || freeVoice.suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hints.voiceOptional) Chip("服务默认", selected = current.isEmpty()) { settings.setVoice(""); onClose() }
                        for (v in freeVoice.suggestions) {
                            Chip(v.label, selected = current == v.id, trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)) {
                                settings.setVoice(v.id); onClose()
                            }
                        }
                    }
                }
            }
            catalog.isNotEmpty() -> {
                if (groups.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("全部", selected = lang.isEmpty()) { lang = "" }
                        for (g in groups) Chip(g.label, selected = lang == g.lang, trailing = "${g.voices.size}") { lang = g.lang }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScrollCompat()) {
                    for (g in shown) {
                        if (shown.size > 1) {
                            Text(g.label, color = c.text3, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (v in g.voices) {
                                Chip(
                                    if (v.note != null) "${v.label}·${v.note}" else v.label,
                                    selected = current == v.id,
                                    trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)
                                ) { settings.setVoice(v.id); onClose() }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                EchoTextField(current, { v -> settings.setVoice(v.trim()) }, label = "或手动填写音色 ID", placeholder = "音色 ID")
            }
            else -> {
                Text("该模型未提供音色列表，可手动填写；留空则试用服务默认音色。",
                    color = c.text2, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                EchoTextField(current, { v -> settings.setVoice(v.trim()) }, label = "音色 ID", placeholder = "留空 = 服务默认")
            }
        }
    }
}
