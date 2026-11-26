package app.echoread.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Discovery
import app.echoread.core.Hash
import app.echoread.core.ProviderSpec
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.i18n.S
import app.echoread.tts.AudioCache
import app.echoread.tts.Providers
import app.echoread.tts.SyncStatus
import app.echoread.tts.VoiceInfo
import app.echoread.tts.Voices
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 朗读设置（0.5.0）：一张面板、几页内容。
 *
 * 0.4.x 的结构是「引擎三选一（AI 语音 / Gemini / 系统语音）+ 各自一套设置界面」，
 * 每接一家新的服务商就要多一个分段、多一个设置文件、多两张选择器面板。现在：
 *
 *   主页        当前服务（一张卡片）· 模型 · 音色 · 语气 · 语速 · 高级
 *   └ 朗读服务   已添加的服务，点一下切换；每个服务各存各的 Key
 *      └ 添加服务  内置目录（按地区分组）+ 自定义端点 + 粘贴服务配置
 *      └ 服务设置  Key、地址、额外字段、连接检查、移除
 *   └ 模型 / 音色  同一张面板里的一页，不再另开一张面板叠在上面
 *
 * 界面完全由目录数据驱动：这里没有任何一家服务商的专属代码，新接入的服务商自动获得这一整套界面。
 */

private sealed interface VoicePage {
    val depth: Int

    data object Main : VoicePage { override val depth = 0 }
    data object Models : VoicePage { override val depth = 1 }
    data object Voices : VoicePage { override val depth = 1 }
    data object Services : VoicePage { override val depth = 1 }
    data object Add : VoicePage { override val depth = 2 }
    data class Service(val id: String) : VoicePage { override val depth = 3 }
    data object Import : VoicePage { override val depth = 3 }
}

/* ---------------- 名字、头像、状态：各页共用 ---------------- */

internal fun serviceName(spec: ProviderSpec, p: VoiceProfile?): String = when {
    !p?.name.isNullOrBlank() -> p!!.name
    spec.id == Providers.CUSTOM -> S.providers.customEndpoint
    else -> spec.name
}

@Composable
private fun serviceColor(spec: ProviderSpec): Color {
    val dark = echo.isDark
    return remember(spec.id, spec.color, dark) {
        val parsed = runCatching { Color(android.graphics.Color.parseColor(spec.color)) }.getOrNull()
        when {
            // 纯黑 / 近黑的品牌色在深色界面上会消失：提亮成深灰
            parsed != null -> if (dark && parsed.red + parsed.green + parsed.blue < 0.45f) Color(0xFF3A3D45) else parsed
            else -> Color.hsl((Hash.cyrb53(spec.id).take(6).toLong(16) % 360).toFloat(), 0.5f, if (dark) 0.42f else 0.5f)
        }
    }
}

/** 服务的头像：目录里带了标志就画标志，否则（自定义端点、没有标志的服务商）是名字的首字母 */
@Composable
private fun ServiceAvatar(spec: ProviderSpec, p: VoiceProfile?, size: Dp = 40.dp) {
    val tile = serviceColor(spec)
    val logo = remember(spec.logo) { ProviderLogos.vector(spec.logo) }
    if (logo != null) LogoTile(logo, tile, spec.logoScale, size)
    else Monogram(serviceName(spec, p), tile, size)
}

@Composable
private fun SystemAvatar(size: Dp = 40.dp) {
    val c = echo
    Box(Modifier.size(size).background(c.fillStrong, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Icon(EchoIcons.Waves, null, tint = c.text2, modifier = Modifier.size(size * 0.5f))
    }
}

/** 选中标记：一枚实心的强调色对勾（选中态不靠整行换底色表达） */
@Composable
internal fun CheckBadge(size: Dp = 22.dp) {
    val c = echo
    Box(Modifier.size(size).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(EchoIcons.Check, S.common.selected, tint = c.onAccent, modifier = Modifier.size(size * 0.6f))
    }
}

private data class StatusLine(val dot: Color, val text: String, val textColor: Color, val detail: app.echoread.core.net.NetError? = null)

@Composable
private fun statusLine(spec: ProviderSpec, p: VoiceProfile, st: SyncStatus?): StatusLine {
    val c = echo
    val ok = Color(0xFF34C759)
    return when {
        Providers.baseOf(spec, p).isEmpty() -> StatusLine(c.text3, S.providers.setAddress, c.accent)
        !Providers.validBase(Providers.baseOf(spec, p)) -> StatusLine(c.danger, S.providers.badAddress, c.danger)
        Providers.needsKey(spec, p) -> StatusLine(c.text3, S.providers.addApiKey, c.accent)
        st is SyncStatus.Syncing -> StatusLine(c.accent, S.providers.connecting, c.text2)
        st is SyncStatus.Failed -> StatusLine(c.danger, st.error.headline(), c.danger, st.error)
        st is SyncStatus.Ok -> {
            val warn = st.warning
            val parts = ArrayList<String>()
            parts += if (st.verified) S.providers.connected else S.providers.ready
            st.credits?.let { parts += S.providers.balance(String.format(java.util.Locale.ROOT, "%.2f", it.remaining)) }
            st.creditsError?.let { parts += S.providers.balanceFailed(it.badge()) }
            st.suspiciousAuth?.let { parts += S.providers.suspiciousAuth(it.badge()) }
            if (st.own > 0) parts += S.providers.ownVoices(st.own)
            StatusLine(if (warn != null) warningColor(c.isDark) else if (st.verified) ok else c.text3, parts.joinToString(" · "), c.text2, warn)
        }
        else -> StatusLine(c.text3, S.providers.ready, c.text2)
    }
}

@Composable
private fun StatusRow(line: StatusLine, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(line.dot, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(line.text, color = line.textColor, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private fun badgeLine(badges: List<String>, extra: List<String> = emptyList()): String =
    (extra + badges.filter { it in Providers.BADGES }.map { S.providers.badge(it) }).joinToString(" · ")

private fun modelLabel(options: List<TtsModelInfo>, model: String): String = when {
    model.isBlank() -> S.settings.notSelected
    else -> options.firstOrNull { it.id == model }?.name?.substringAfter(": ")?.ifEmpty { null } ?: model
}

private fun voiceLabel(catalog: List<VoiceInfo>, voice: String, optional: Boolean): String {
    val v = voice.trim()
    if (v.isEmpty()) return if (optional) S.providers.defaultVoice else S.settings.notSet
    val info = catalog.firstOrNull { it.id == v } ?: return v
    return info.label + (info.note?.let { " · $it" } ?: "")
}

private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

/* ---------------- 面板 ---------------- */

@Composable
fun BoxScope.TtsSettingsSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val settings = graph.settings
    val tts by settings.tts.collectAsState()
    val discovery by settings.discovery.collectAsState()
    val statuses by graph.services.status.collectAsState()

    var stack by remember { mutableStateOf(listOf<VoicePage>(VoicePage.Main)) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // 当前服务还缺 Key（或地址）：打开面板直接落在它的设置页 —— 那是此刻唯一要做的事，
        // 不该让人先在主页上找入口。返回箭头仍然回到主页
        val cur = settings.tts.value
        val p = cur.activeProfile
        stack = if (p != null && !cur.usesSystemVoice && !Providers.ready(Providers.specOf(p, cur), p)) listOf(VoicePage.Main, VoicePage.Service(p.id))
        else listOf(VoicePage.Main)
    }
    val page = stack.last()
    fun push(p: VoicePage) { stack = stack + p }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    val active = tts.activeProfile
    // 面板打开时（以及换了服务 / Key / 地址之后稍等片刻）确认一次连接：去抖 800ms，输入 Key 的过程中不逐字发请求
    LaunchedEffect(open, active?.id, active?.apiKey, active?.baseUrl, active?.fields, active?.model) {
        val id = active?.id ?: return@LaunchedEffect
        if (!open) return@LaunchedEffect
        delay(800)
        graph.services.ensureFresh(id)
    }

    val title = when (page) {
        VoicePage.Main -> S.common.voiceSettings
        VoicePage.Models -> S.common.model
        VoicePage.Voices -> S.common.voice
        VoicePage.Services -> S.providers.servicesTitle
        VoicePage.Add -> S.providers.addService
        is VoicePage.Service -> settings.profile(page.id)?.let { serviceName(Providers.specOf(it, tts), it) } ?: S.providers.service
        VoicePage.Import -> S.providers.pasteDefinition
    }

    EchoSheet(
        open = open, onDismiss = onClose, title = title,
        maxHeightFraction = 0.9f, scrollable = false, fillHeight = true,
        onBack = if (stack.size > 1) ({ pop() }) else null
    ) {
        SheetPages(page, depthOf = { it.depth }, modifier = Modifier.fillMaxSize()) { p ->
            when (p) {
                VoicePage.Main -> MainPage(graph, tts, discovery, statuses, onOpen = { push(it) })
                VoicePage.Services -> ServicesPage(graph, tts, discovery, statuses, onOpen = { push(it) })
                VoicePage.Add -> AddServicePage(
                    graph, tts,
                    onAdded = { id -> stack = stack.dropLast(1) + VoicePage.Service(id) },
                    onImport = { push(VoicePage.Import) }
                )
                is VoicePage.Service -> ServicePage(graph, tts, p.id, statuses[p.id], onRemoved = { pop() }, onUse = { stack = listOf(VoicePage.Main) })
                VoicePage.Import -> ImportPage(graph, onAdded = { id -> stack = stack.filterNot { it is VoicePage.Add || it is VoicePage.Import } + VoicePage.Service(id) })
                VoicePage.Models -> active?.let { ModelsPage(graph, tts, it, discovery[it.id], onPicked = { pop() }) }
                VoicePage.Voices -> active?.let { VoicesPage(graph, tts, it, discovery[it.id], statuses[it.id], onPicked = { pop() }) }
            }
        }
    }
}

/** 一页的滚动容器：各页自己滚，面板本身不滚（否则换页时滚动位置会串） */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    // 每次进入这一页都从顶部开始（不保存滚动位置）
    Column(Modifier.fillMaxSize().verticalScroll(remember { androidx.compose.foundation.ScrollState(0) }), content = content)
}

/* ---------------- 主页 ---------------- */

/** 语气快捷项。值用英文：各家的官方示例都是英文短语；中文同样能用，用户可以直接在输入框里改 */
private val STYLE_PRESETS: List<Pair<String, String>> get() = listOf(
    S.gemini.styleNatural to "",
    S.gemini.styleCalm to "calm and relaxed",
    S.common.warm to "warm and gentle",
    S.gemini.styleStorytelling to "warm, unhurried storytelling",
    S.gemini.styleSlow to "speaking slowly"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainPage(
    graph: AppGraph,
    tts: TtsSettings,
    discovery: Map<String, Discovery>,
    statuses: Map<String, SyncStatus>,
    onOpen: (VoicePage) -> Unit
) {
    val c = echo
    val scope = rememberCoroutineScope()
    val settings = graph.settings
    val appContext = LocalContext.current.applicationContext
    val system = tts.usesSystemVoice
    val p = tts.activeProfile
    val spec = p?.let { Providers.specOf(it, tts) }

    var previewing by remember { mutableStateOf(false) }
    var previewLabel by remember { mutableStateOf("") }
    var cacheStats by remember { mutableStateOf<AudioCache.Stats?>(null) }
    LaunchedEffect(Unit) { cacheStats = graph.audioCache.stats() }

    fun preview() {
        val profile = p ?: return
        val sp = spec ?: return
        if (Providers.needsKey(sp, profile)) {
            Toaster.error(S.settings.enterApiKeyFirst)
            onOpen(VoicePage.Service(profile.id))
            return
        }
        previewing = true
        previewLabel = ""
        // 入口快照：合成期间改了设置，试的仍是按下按钮那一刻的配置
        scope.launch {
            val r = Providers.test(profile, tts)
            if (!r.ok || r.audio == null) {
                previewing = false
                r.error?.let { ErrorDetails.toast(it) } ?: Toaster.error(r.message)
                return@launch
            }
            // 试听就得真的响：合成成功后立刻播出来（占用同一个播放器，先暂停正在朗读的引擎）
            previewLabel = S.settings.playing
            try {
                graph.engine.pause()
                val f = withContext(Dispatchers.IO) {
                    File(appContext.cacheDir, "preview-${System.currentTimeMillis()}.audio").also { it.writeBytes(r.audio) }
                }
                graph.playback.setActive(true)
                graph.playback.play(f, 1f, deleteAfter = true).awaitEnded()
            } catch (e: Exception) {
                Toaster.error(e.message ?: S.common.playbackFailed)
            } finally {
                graph.playback.setActive(false)
                previewing = false
                previewLabel = ""
            }
        }
    }

    PageColumn {
        /* ---- 当前服务：状态即入口，点开就是「朗读服务」 ---- */
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(GroupRadius)).background(c.fill)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { onOpen(VoicePage.Services) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (system || p == null || spec == null) SystemAvatar(44.dp) else ServiceAvatar(spec, p, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (system || p == null || spec == null) S.providers.systemVoice else serviceName(spec, p),
                        color = c.text, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (system || p == null || spec == null) {
                        Text(S.providers.systemVoiceDesc, color = c.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else {
                        StatusRow(statusLine(spec, p, statuses[p.id]))
                    }
                }
                Spacer(Modifier.width(8.dp))
                ChevronEnd()
            }
            if (!system && p != null && spec != null) {
                val line = statusLine(spec, p, statuses[p.id])
                Hairline(start = 16.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!Providers.ready(spec, p)) {
                        // 还用不了：这里唯一有意义的动作是去把 Key 填上
                        GradientButton(S.providers.enterKey, Modifier.weight(1f), icon = EchoIcons.Key, height = 42.dp) { onOpen(VoicePage.Service(p.id)) }
                    } else {
                        // 试听验证的就是这条连接，所以紧挨着状态
                        SoftButton(
                            if (previewing) previewLabel.ifEmpty { S.common.generating } else S.common.preview,
                            Modifier.weight(1f), color = c.accent, height = 40.dp, icon = if (previewing) null else EchoIcons.PlaySmall
                        ) { if (!previewing) preview() }
                    }
                    if (line.detail != null) {
                        SoftButton(S.common.details, Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(line.detail) }
                    }
                }
                if (spec.sunset.isNotBlank()) {
                    Text(
                        S.providers.retiresOn(spec.sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        if (!system && p != null && spec != null) {
            val d = discovery[p.id]
            val model = Providers.modelOf(spec, p)
            val options = remember(spec, d) { Providers.modelOptions(spec, d) }
            val catalog = remember(spec, p.model, p.apiKey, d) { Providers.voiceCatalog(spec, p, d) }
            val optional = Providers.voiceOptional(spec, model)
            // 按模板发请求、目录里又没列模型的服务商（Azure、xAI…）：模型由音色决定，不摆这一行
            val showModel = options.isNotEmpty() || spec.kind != ProviderSpec.KIND_HTTP
            val showStyle = Providers.supportsStyle(spec, model)

            /* ---- 声音：行尾就是当前值，一眼扫完全部配置 ---- */
            SettingsSection(S.providers.sound) {
                if (showModel) row(S.common.model, end = modelLabel(options, model), trailing = { ChevronEnd() }, onClick = { onOpen(VoicePage.Models) })
                if (spec.voiceMode != ProviderSpec.VOICE_NONE) {
                    row(S.common.voice, end = voiceLabel(catalog, p.voice, optional), trailing = { ChevronEnd() }, onClick = { onOpen(VoicePage.Voices) })
                }
                if (showStyle) {
                    custom {
                        Text(S.providers.style, color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        EchoTextField(p.style, { v -> settings.updateProfile(p.id) { it.copy(style = v) } }, placeholder = S.providers.stylePlaceholder)
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((label, value) in STYLE_PRESETS) {
                                Chip(label, selected = p.style.trim() == value) { settings.updateProfile(p.id) { it.copy(style = value) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        SettingsSection(S.providers.playback) {
            custom {
                SettingsSlider(
                    S.common.speed, String.format(java.util.Locale.ROOT, "%.2f×", tts.rate),
                    tts.rate, 0.5f..2.5f, steps = 39
                ) { v -> settings.updateTts { it.copy(rate = (Math.round(v * 20) / 20f)) } }
            }
        }

        if (!system && p != null && spec != null) {
            Spacer(Modifier.height(22.dp))
            val cap = Providers.maxChars(spec, Providers.modelOf(spec, p))
            /* ---- 高级：影响成本与延迟的旋钮，以及缓存 ---- */
            SettingsSection(S.settings.advanced) {
                custom {
                    SettingsSlider(S.settings.charactersPerSegment, "${tts.maxChunkChars}", tts.maxChunkChars.toFloat(), 80f..400f, steps = 31) { v ->
                        settings.updateTts { it.copy(maxChunkChars = (Math.round(v / 10) * 10)) }
                    }
                    Text(S.settings.chunkHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                    if (cap in 1 until tts.maxChunkChars) {
                        Spacer(Modifier.height(4.dp))
                        Text(S.providers.limitPerRequest(cap), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall)
                    }
                }
                custom {
                    SettingsSlider(S.settings.prefetchSegments, "${tts.prefetch}", tts.prefetch.toFloat(), 0f..5f, steps = 4) { v ->
                        settings.updateTts { it.copy(prefetch = Math.round(v)) }
                    }
                    Text(S.settings.prefetchHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                row(
                    S.settings.audioCache,
                    value = cacheStats?.let { S.settings.cacheStats(it.count, formatBytes(it.bytes)) } ?: S.settings.calculating,
                    trailing = {
                        TextAction(S.settings.clear, color = c.danger) {
                            scope.launch {
                                graph.audioCache.clear()
                                cacheStats = graph.audioCache.stats()
                                Toaster.success(S.settings.audioCacheCleared)
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 朗读服务：已添加的，点一下切换 ---------------- */

@Composable
private fun ServicesPage(
    graph: AppGraph,
    tts: TtsSettings,
    discovery: Map<String, Discovery>,
    statuses: Map<String, SyncStatus>,
    onOpen: (VoicePage) -> Unit
) {
    val c = echo
    val settings = graph.settings
    PageColumn {
        SettingsSection(S.providers.yourServices) {
            for (p in tts.profiles) {
                val spec = Providers.specOf(p, tts)
                val ready = Providers.ready(spec, p)
                val selected = tts.active == p.id
                val subtitle = when {
                    Providers.baseOf(spec, p).isEmpty() -> S.providers.setAddress
                    !Providers.validBase(Providers.baseOf(spec, p)) -> S.providers.badAddress
                    Providers.needsKey(spec, p) -> S.providers.addApiKey
                    else -> modelLabel(Providers.modelOptions(spec, discovery[p.id]), Providers.modelOf(spec, p)).takeIf { Providers.modelOf(spec, p).isNotBlank() }
                        ?: spec.baseUrl.ifBlank { p.baseUrl }.removePrefix("https://").removePrefix("http://").substringBefore('/')
                }
                row(
                    serviceName(spec, p),
                    value = subtitle,
                    valueColor = if (!ready) c.accent else if (statuses[p.id] is SyncStatus.Failed) c.danger else null,
                    leading = { ServiceAvatar(spec, p) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) CheckBadge()
                            IconButtonEcho(EchoIcons.Settings, S.providers.configure, size = 34.dp, iconSize = 18.dp, touchSize = 44.dp) { onOpen(VoicePage.Service(p.id)) }
                        }
                    },
                    // 还不能用的（没填 Key）点了是去填，而不是切过去对着一个报错发呆
                    onClick = { if (ready) settings.setActive(p.id) else onOpen(VoicePage.Service(p.id)) }
                )
            }
            row(
                S.providers.systemVoice,
                value = S.providers.systemVoiceDesc,
                leading = { SystemAvatar() },
                trailing = { if (tts.usesSystemVoice) CheckBadge() },
                onClick = { settings.setActive(TtsSettings.SYSTEM) }
            )
        }
        Spacer(Modifier.height(16.dp))
        GradientButton(S.providers.addService, Modifier.fillMaxWidth(), icon = EchoIcons.Plus) { onOpen(VoicePage.Add) }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 添加服务：内置目录 ---------------- */

@Composable
private fun AddServicePage(graph: AppGraph, tts: TtsSettings, onAdded: (String) -> Unit, onImport: () -> Unit) {
    val c = echo
    val settings = graph.settings
    var query by remember { mutableStateOf("") }
    val now = remember { today() }
    val q = query.trim().lowercase()
    val all = remember(tts.customSpecs, now) { (Providers.builtIn + tts.customSpecs).filterNot { Providers.retired(it, now) } }
    val shown = remember(all, q) {
        if (q.isEmpty()) all else all.filter { s ->
            s.name.lowercase().contains(q) || s.id.contains(q) || s.models.any { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
        }
    }

    fun add(spec: ProviderSpec) {
        val multi = spec.id == Providers.CUSTOM || spec.baseUrl.isBlank()
        val existing = tts.profiles.firstOrNull { it.spec == spec.id }
        onAdded(if (existing != null && !multi) existing.id else settings.addProfile(spec).id)
    }

    PageColumn {
        SearchField(query, { query = it }, S.providers.searchServices)
        Spacer(Modifier.height(18.dp))
        val groups = listOf("global" to S.providers.regionGlobal, "cn" to S.providers.regionCn, "local" to S.providers.regionLocal)
        var any = false
        for ((region, label) in groups) {
            val list = shown.filter { it.region == region }
            val withImport = region == "local" && q.isEmpty()
            if (list.isEmpty() && !withImport) continue
            any = true
            SettingsSection(label) {
                for (spec in list) {
                    val added = spec.id != Providers.CUSTOM && spec.baseUrl.isNotBlank() && tts.profiles.any { it.spec == spec.id }
                    val custom = spec.id == Providers.CUSTOM
                    row(
                        if (custom) S.providers.customEndpoint else spec.name,
                        value = (if (custom) S.providers.customEndpointDesc else badgeLine(spec.badges)).ifBlank { null },
                        leading = { ServiceAvatar(spec, null) },
                        trailing = {
                            if (added) Text(S.providers.added, color = c.text3, style = MaterialTheme.typography.labelMedium)
                            else Icon(EchoIcons.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        },
                        onClick = { add(spec) }
                    )
                }
                if (withImport) {
                    row(
                        S.providers.pasteDefinition,
                        value = S.providers.pasteDefinitionDesc,
                        leading = {
                            Box(Modifier.size(40.dp).background(c.fillStrong, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(EchoIcons.FileText, null, tint = c.text2, modifier = Modifier.size(20.dp))
                            }
                        },
                        trailing = { ChevronEnd() },
                        onClick = onImport
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (!any) {
            Text(S.providers.noServicesMatch, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}

/* ---------------- 服务设置：Key、地址、连接检查 ---------------- */

@Composable
private fun ServicePage(
    graph: AppGraph,
    tts: TtsSettings,
    id: String,
    status: SyncStatus?,
    onRemoved: () -> Unit,
    onUse: () -> Unit
) {
    val c = echo
    val settings = graph.settings
    val uriHandler = LocalUriHandler.current
    val p = tts.profiles.firstOrNull { it.id == id } ?: return
    val spec = Providers.specOf(p, tts)
    val ownAddress = spec.baseUrl.isBlank()
    var showAddress by remember(id) { mutableStateOf(p.baseUrl.isNotBlank()) }

    // 改了 Key / 地址 / 字段：稍等片刻自动检查一次连接
    LaunchedEffect(id, p.apiKey, p.baseUrl, p.fields) {
        delay(900)
        graph.services.ensureFresh(id)
    }

    PageColumn {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceAvatar(spec, p, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val regionLabel = when (spec.region) { "cn" -> S.providers.regionCn; "local" -> S.providers.regionLocal; else -> S.providers.regionGlobal }
                Text(
                    badgeLine(spec.badges, listOf(regionLabel)), color = c.text2, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (spec.keyUrl.isNotBlank()) TextAction(S.providers.getApiKey, icon = EchoIcons.ChevronRight) { runCatching { uriHandler.openUri(spec.keyUrl) } }
                    if (spec.docsUrl.isNotBlank()) TextAction(S.providers.apiDocs, icon = EchoIcons.ChevronRight) { runCatching { uriHandler.openUri(spec.docsUrl) } }
                }
            }
        }
        if (spec.sunset.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(S.providers.retiresOn(spec.sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))

        SettingsSection(footer = S.providers.keyStaysOnDevice) {
            custom {
                if (ownAddress) {
                    EchoTextField(p.name, { v -> settings.updateProfile(id) { it.copy(name = v) } }, label = S.providers.displayName, placeholder = serviceName(spec, null))
                    Spacer(Modifier.height(12.dp))
                    EchoTextField(
                        p.baseUrl, { v -> settings.updateProfile(id) { it.copy(baseUrl = Providers.cleanAddress(v)) } },
                        label = S.providers.address, placeholder = spec.baseHint.ifBlank { "https://" }, keyboardType = KeyboardType.Uri, mono = true
                    )
                    Spacer(Modifier.height(12.dp))
                }
                EchoTextField(
                    p.apiKey, { v -> settings.updateProfile(id) { it.copy(apiKey = Providers.cleanSecret(v)) } },
                    label = S.providers.apiKey, placeholder = spec.keyHint, password = true, mono = true
                )
                for (f in spec.fields) {
                    Spacer(Modifier.height(12.dp))
                    EchoTextField(
                        p.fields[f.id] ?: f.default, { v -> settings.updateProfile(id) { it.copy(fields = it.fields + (f.id to Providers.cleanSecret(v))) } },
                        label = S.providers.fieldLabel(f.id), placeholder = f.hint.ifBlank { f.default }, password = f.secret, mono = true
                    )
                }
                if (!ownAddress) {
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(showAddress, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
                        EchoTextField(
                            p.baseUrl, { v -> settings.updateProfile(id) { it.copy(baseUrl = Providers.cleanAddress(v)) } },
                            label = S.providers.addressOverride, placeholder = spec.baseUrl, keyboardType = KeyboardType.Uri, mono = true,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    if (!showAddress) TextAction(S.providers.addressOverride, color = c.text2) { showAddress = true }
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        SettingsSection {
            custom {
                val line = statusLine(spec, p, status)
                StatusRow(line, Modifier.fillMaxWidth(), maxLines = 3)
                if (status is SyncStatus.Ok && !status.verified) {
                    Spacer(Modifier.height(6.dp))
                    Text(S.providers.notVerifiedHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(
                        if (status is SyncStatus.Syncing) S.providers.connecting else S.providers.checkConnection,
                        Modifier.weight(1f), color = c.accent, height = 40.dp, icon = EchoIcons.Refresh,
                        enabled = Providers.ready(spec, p) && status !is SyncStatus.Syncing
                    ) {
                        graph.services.refresh(id) { st -> if (st is SyncStatus.Failed) ErrorDetails.toast(st.error) }
                    }
                    if (line.detail != null) {
                        SoftButton(S.common.details, Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(line.detail) }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        if (tts.active == id) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CheckBadge(18.dp)
                Spacer(Modifier.width(8.dp))
                Text(S.providers.inUse, color = c.text2, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            GradientButton(S.providers.useThisService, Modifier.fillMaxWidth(), enabled = Providers.ready(spec, p)) {
                if (Providers.ready(spec, p)) {
                    settings.setActive(id)
                    onUse()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction(S.providers.removeService, color = c.danger) {
                graph.services.forget(id)
                settings.removeProfile(id)
                Toaster.show(S.providers.serviceRemoved)
                onRemoved()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 粘贴服务配置 ---------------- */

@Composable
private fun ImportPage(graph: AppGraph, onAdded: (String) -> Unit) {
    val c = echo
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    PageColumn {
        Text(S.providers.importHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        EchoTextField(
            text, { text = it; error = "" }, placeholder = "{ \"id\": \"my-tts\", \"name\": \"My TTS\", \"kind\": \"http\", ... }",
            singleLine = false, mono = true, minLines = 9
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(S.providers.paste, Modifier.weight(1f)) {
                clipboard.getText()?.text?.let { text = it; error = "" }
            }
            GradientButton(S.providers.add, Modifier.weight(1f), enabled = text.isNotBlank()) {
                Providers.parseSpec(text).fold(
                    onSuccess = { spec ->
                        // 导入的定义不能顶替内置服务商：否则一段粘贴来的 JSON 就能把已存的 Key 引到别的主机上
                        if (Providers.builtIn.any { it.id == spec.id }) error = S.providers.importFailed("id \"${spec.id}\" is built in")
                        else {
                            val p = graph.settings.importSpec(spec)
                            Toaster.success(S.providers.serviceAdded)
                            onAdded(p.id)
                        }
                    },
                    onFailure = { error = S.providers.importFailed(it.message ?: "?") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 模型 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsPage(graph: AppGraph, tts: TtsSettings, p: VoiceProfile, d: Discovery?, onPicked: () -> Unit) {
    val c = echo
    val settings = graph.settings
    val spec = Providers.specOf(p, tts)
    val current = Providers.modelOf(spec, p)
    val options = remember(spec, d) { Providers.modelOptions(spec, d) }
    val openRouter = spec.id == Providers.OPENROUTER
    var filter by remember { mutableStateOf("") }
    val q = filter.trim().lowercase()

    fun metaOf(m: TtsModelInfo): String = if (openRouter) Voices.formatModelMeta(m, m.id) else {
        val ms = spec.models.firstOrNull { it.id == m.id }
        badgeLine(ms?.badges.orEmpty(), listOfNotNull(ms?.langs?.takeIf { it > 0 }?.let { S.providers.languages(it) }))
    }

    val ordered = remember(options, q, openRouter) {
        val recommended = if (openRouter) Voices.RECOMMENDED_MODELS.map { it.id } else emptyList()
        val list = if (openRouter) options.sortedWith(compareBy<TtsModelInfo> { val i = recommended.indexOf(it.id); if (i < 0) 99 else i }.thenBy { it.id }) else options
        if (q.isEmpty()) list else list.filter { m ->
            m.id.lowercase().contains(q) || m.name.lowercase().contains(q) || metaOf(m).lowercase().contains(q) ||
                ((q == "free" || q == "免费") && m.id.contains(":free"))
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (options.size > 8) {
            SearchField(filter, { filter = it }, S.providers.searchModels)
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (options.isEmpty() && openRouter) {
                item("hint") {
                    Column {
                        Text(S.settings.noModelsSyncedHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in Voices.RECOMMENDED_MODELS) {
                                Chip(r.label, selected = current == r.id, trailing = r.tag) { settings.setModel(p.id, r.id); onPicked() }
                            }
                        }
                    }
                }
            }
            items(ordered, key = { it.id }) { m ->
                val selected = m.id == current
                val meta = metaOf(m)
                val sunset = spec.models.firstOrNull { it.id == m.id }?.sunset.orEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.fill)
                        .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { settings.setModel(p.id, m.id); onPicked() }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name.substringAfter(": ").ifEmpty { m.name },
                            color = c.text, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (meta.isNotEmpty()) Text(meta, color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (sunset.isNotBlank()) Text(S.providers.retiresOn(sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall)
                        Text(m.id, color = c.text3, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) {
                        Spacer(Modifier.width(10.dp))
                        CheckBadge()
                    }
                }
            }
            if (ordered.isEmpty() && options.isNotEmpty()) {
                item("none") { Text(S.settings.noMatchingModels, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 列表里没有的模型永远可以手填：新模型上线不必等目录更新
        EchoTextField(p.model, { v -> settings.setModel(p.id, v.trim()) }, label = S.providers.modelId, placeholder = spec.defaultModel.ifBlank { S.settings.modelIdPlaceholder }, mono = true)
    }
}

/* ---------------- 音色 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicesPage(graph: AppGraph, tts: TtsSettings, p: VoiceProfile, d: Discovery?, status: SyncStatus?, onPicked: () -> Unit) {
    val c = echo
    val settings = graph.settings
    val spec = Providers.specOf(p, tts)
    val model = Providers.modelOf(spec, p)
    val openRouter = spec.id == Providers.OPENROUTER
    val hints = remember(model, openRouter) { if (openRouter) Voices.modelHints(model) else null }
    val freeVoice = hints?.freeVoice
    val catalog = remember(spec, p.model, p.apiKey, d) { Providers.voiceCatalog(spec, p, d) }
    val optional = Providers.voiceOptional(spec, model)
    val canRefresh = spec.listVoices.isNotEmpty() || spec.kind == ProviderSpec.KIND_GEMINI
    val current = p.voice

    var lang by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(catalog, q) {
        if (q.isEmpty()) catalog
        else catalog.filter { v -> v.id.lowercase().contains(q) || v.label.lowercase().contains(q) || v.note?.lowercase()?.contains(q) == true }
    }
    val groups = remember(filtered) { Voices.groupVoices(filtered) }
    LaunchedEffect(groups) { if (lang.isNotEmpty() && groups.none { it.lang == lang }) lang = "" }
    val shown = if (lang.isEmpty()) groups else groups.filter { it.lang == lang }

    fun pick(id: String) {
        settings.setVoice(p.id, id)
        onPicked()
    }

    Column(Modifier.fillMaxSize()) {
        val hint = when {
            freeVoice != null -> freeVoice.hint
            spec.voiceMode == ProviderSpec.VOICE_FREE -> S.providers.anyVoiceIdHint
            catalog.isEmpty() -> S.providers.noVoiceList
            else -> null
        }
        if (hint != null) {
            Text(hint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (catalog.size > 12) {
            SearchField(query, { query = it }, S.providers.searchVoices)
            Spacer(Modifier.height(10.dp))
        }
        if (groups.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.common.all, selected = lang.isEmpty()) { lang = "" }
                for (g in groups) Chip(g.label, selected = lang == g.lang, trailing = "${g.voices.size}") { lang = g.lang }
            }
            Spacer(Modifier.height(12.dp))
        }
        // 每个语种一项：屏幕外的语种不参与组合，几百个音色也不会一次性摆满
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (optional || freeVoice?.suggestions?.isNotEmpty() == true) {
                item("quick") {
                    FlowRow(
                        Modifier.padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (optional) Chip(S.providers.defaultVoice, selected = current.isEmpty()) { pick("") }
                        for (v in freeVoice?.suggestions.orEmpty()) {
                            Chip(v.label, selected = current == v.id, trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)) { pick(v.id) }
                        }
                    }
                }
            }
            items(shown, key = { it.lang }) { g ->
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    if (shown.size > 1) {
                        Text(g.label, color = c.text2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in g.voices) {
                            Chip(
                                if (v.note != null) "${v.label} · ${v.note}" else v.label,
                                selected = current == v.id,
                                trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)
                            ) { pick(v.id) }
                        }
                    }
                }
            }
            if (shown.isEmpty() && catalog.isNotEmpty()) {
                item("none") { Text(S.gemini.noMatchingVoices, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
            if (canRefresh) {
                item("refresh") {
                    TextAction(
                        if (status is SyncStatus.Syncing) S.providers.connecting else S.providers.refreshList,
                        icon = EchoIcons.Refresh, modifier = Modifier.padding(start = 2.dp)
                    ) {
                        if (status !is SyncStatus.Syncing) graph.services.refresh(p.id) { st ->
                            if (st is SyncStatus.Failed) ErrorDetails.toast(st.error) else if (st is SyncStatus.Ok) Toaster.success(S.providers.listUpdated)
                        }
                    }
                }
            }
        }
        if (spec.voiceMode != ProviderSpec.VOICE_NONE) {
            Spacer(Modifier.height(12.dp))
            // 手填永远可用：复刻音色、音色库 ID、列表里还没有的新音色
            EchoTextField(
                current, { v -> settings.setVoice(p.id, v.trim()) }, label = S.settings.voiceId,
                placeholder = freeVoice?.placeholder ?: if (optional) S.settings.blankServiceDefault else spec.defaultVoice.ifBlank { S.settings.voiceId },
                mono = true
            )
        }
    }
}

internal fun genderMark(g: String?): String? = when (g) { "f" -> "♀"; "m" -> "♂"; else -> null }

@Composable
internal fun genderColor(g: String?): Color? = when (g) { "f" -> Color(0xFFF472B6); "m" -> Color(0xFF38BDF8); else -> null }

fun formatBytes(n: Long): String = when {
    n > 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1024.0 / 1024.0)
    n > 1024 -> "${n / 1024} KB"
    else -> "$n B"
}
