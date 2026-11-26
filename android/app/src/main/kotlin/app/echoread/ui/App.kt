package app.echoread.ui

import app.echoread.core.i18n.S
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.echoread.ui.glass.LocalBackdrop
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.rememberBackdrop
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.echoread.AppGraph
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.preemptable

/**
 * 外部传入配置的确认卡片：展示脱敏 Key 与目标服务，用户确认后才写入。
 * 写给哪个服务：指定了 provider 就是那一家；否则按 baseUrl 认；都没有就写给当前的 OpenAI 兼容服务。
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ConfigConfirmSheet(graph: AppGraph) {
    val c = echo
    val pending by graph.configRequests.collectAsState()
    var last by remember { mutableStateOf<AppGraph.PendingConfig?>(null) }
    if (pending != null) last = pending
    val p = pending ?: last
    EchoSheet(open = pending != null, onDismiss = { graph.configRequests.value = null }, title = S.settings.externalConfigTitle, maxHeightFraction = 0.6f) {
        if (p != null) {
            val tts = graph.settings.tts.value
            val key = p.apiKey?.let { app.echoread.tts.Providers.cleanSecret(it) }?.takeIf { it.isNotBlank() }
            val address = p.baseUrl?.let { app.echoread.tts.Providers.cleanAddress(it) }?.takeIf { it.isNotBlank() }
            val named = p.provider?.takeIf { it.isNotBlank() }?.let { app.echoread.tts.Providers.spec(it, tts) }
            val spec = named ?: address?.let { app.echoread.tts.Providers.specForBase(it) }
            val known = spec != null && spec.id != app.echoread.tts.Providers.CUSTOM
            // 目录里地址本来就要自己填的那几家（自建服务）：每次都是新建一个服务，直接用传入的地址
            val selfHosted = known && named != null && named.baseUrl.isBlank()
            // 给目录里的服务换地址（中转、自建网关）：只有这份配置自己带了 Key 才照办。
            // 否则就等于让一段外来的配置把手机里已存的 Key 发到别的主机去
            val reroute = known && named != null && !selfHosted && key != null && address != null && address.trimEnd('/') != named.baseUrl.trimEnd('/')
            val useAddress = address != null && (!known || reroute || selfHosted)
            // 卡片上列出来的，就是确认后真正会写进去的 —— 不会生效的地址不展示
            val rows = listOfNotNull(
                (spec?.name ?: p.provider)?.takeIf { it.isNotBlank() }?.let { S.providers.service to it },
                key?.let { S.providers.apiKey to (it.take(6) + "..." + it.takeLast(4)) },
                address?.takeIf { useAddress }?.let { S.providers.address to it },
                p.model?.takeIf { it.isNotBlank() }?.let { S.common.model to it },
                p.voice?.takeIf { it.isNotBlank() }?.let { S.common.voice to it }
            )
            androidx.compose.material3.Text(S.settings.externalConfigHint, color = c.text2, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            SettingsSection {
                for ((k, v) in rows) row(k, end = v)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                SoftButton(S.settings.cancel, Modifier.weight(1f)) { graph.configRequests.value = null }
                GradientButton(S.settings.apply, Modifier.weight(1f)) {
                    val settings = graph.settings
                    val cur = settings.tts.value
                    val target = when {
                        spec != null && spec.id != app.echoread.tts.Providers.CUSTOM -> settings.addProfile(spec)
                        address != null ->
                            // 认不出的地址：新建一个自定义端点，不覆盖已有服务的配置
                            cur.profiles.firstOrNull { it.baseUrl.trimEnd('/') == address.trimEnd('/') }
                                ?: settings.addProfile(app.echoread.tts.Providers.spec(app.echoread.tts.Providers.CUSTOM)!!)
                        else -> cur.activeProfile?.takeIf { app.echoread.tts.Providers.specOf(it, cur).kind == app.echoread.core.ProviderSpec.KIND_OPENAI }
                            ?: cur.profiles.firstOrNull { app.echoread.tts.Providers.specOf(it, cur).kind == app.echoread.core.ProviderSpec.KIND_OPENAI }
                            ?: settings.addProfile(app.echoread.tts.Providers.spec(app.echoread.tts.Providers.OPENROUTER)!!)
                    }
                    settings.updateProfile(target.id) { prof ->
                        prof.copy(
                            apiKey = key ?: prof.apiKey,
                            baseUrl = if (useAddress) address!! else prof.baseUrl
                        )
                    }
                    p.model?.takeIf { it.isNotBlank() }?.let { settings.setModel(target.id, it) }
                    p.voice?.takeIf { it.isNotBlank() }?.let { settings.setVoice(target.id, it) }
                    settings.setActive(target.id)
                    graph.configRequests.value = null
                    Toaster.success(S.settings.settingsApplied)
                }
            }
        }
    }
}

/** 配色选择的最小订阅单元：只有这四项变化才需要重建主题 */
private data class PaletteChoice(
    val dynamic: Boolean,
    val seed: Int,
    val style: app.echoread.core.ColorStyle,
    val contrast: Float,
    val glass: Boolean,
    val mode: app.echoread.core.ThemeMode,
    val black: Boolean
)

private const val SHELF_KEY = "__shelf__"

/**
 * 根导航：书架 ⇄ 阅读器。两屏叠放，一个 [MotionDriver] 决定一切（0 = 书架，1 = 阅读器）：
 * 打开 / 返回是同一条弹簧的两个方向，预测性返回把系统进度直接写进同一个值 ——
 * 「从哪里来（右侧滑入）到哪里去（右侧滑出）」，手指随时可以接管或放弃。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EchoApp(graph: AppGraph) {
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoplayFor by rememberSaveable { mutableStateOf<String?>(null) }
    // 正在被组合的阅读器：返回时先滑出、到位后才卸载，永远不会在半路消失
    var shownBook by rememberSaveable { mutableStateOf<String?>(null) }
    val nav = remember { MotionDriver(if (bookId != null) 1f else 0f) }
    // 打开请求（通知栏/深链）：用长期收集而非按值 keyed 的效应，清空请求不会取消处理中的协程
    LaunchedEffect(Unit) {
        graph.openRequests.collect { r ->
            if (r == null) return@collect
            graph.openRequests.value = null
            if (graph.library.books.value.isEmpty()) graph.library.refresh()
            val books = graph.library.books.value
            val target = books.firstOrNull { it.id == r.first } ?: books.firstOrNull { it.title == r.first } ?: return@collect
            autoplayFor = if (r.second) target.id else null
            bookId = target.id
        }
    }
    LaunchedEffect(bookId) {
        val id = bookId
        if (id != null) {
            shownBook = id
            preemptable { nav.animateTo(1f, spec = EchoMotion.Emphasized.float()) }
        } else if (shownBook != null) {
            preemptable { nav.animateTo(0f, spec = EchoMotion.Emphasized.float()) }
            if (nav.value < 0.01f) shownBook = null
        }
    }
    // 只订阅需要的这一个布尔，不要整个 ReaderSettings：
    // 后者每拖动一格字号/行距/热区滑块都会发一个新实例，会把整个组合根连同书架一起作废重组。
    // 只订阅配色相关的这几个字段，不要整个 ReaderSettings：
    // 后者每拖动一格字号/行距/热区滑块都会发一个新实例，会把整个组合根连同书架一起作废重组。
    val palette by remember(graph) {
        graph.settings.reader.map {
            PaletteChoice(it.dynamicColor, it.seedColor, it.colorStyle, it.contrast, it.glass, it.themeMode, it.pureBlack)
        }.distinctUntilChanged()
    }.collectAsState(graph.settings.reader.value.let {
        PaletteChoice(it.dynamicColor, it.seedColor, it.colorStyle, it.contrast, it.glass, it.themeMode, it.pureBlack)
    })
    val dark = when (palette.mode) {
        app.echoread.core.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        app.echoread.core.ThemeMode.LIGHT -> false
        app.echoread.core.ThemeMode.DARK -> true
    }
    // 书架在前台时，状态栏 / 导航栏图标的深浅跟应用自己的明暗走（应用内可以不跟随系统）；
    // 阅读器打开后由它按阅读主题接管
    val hostView = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        if (shownBook == null) {
            (hostView.context as? android.app.Activity)?.window?.let { w ->
                val bars = androidx.core.view.WindowCompat.getInsetsController(w, hostView)
                bars.isAppearanceLightStatusBars = !dark
                bars.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    EchoTheme(
        dark = dark,
        dynamic = palette.dynamic,
        glass = palette.glass,
        black = palette.black,
        seed = Color(palette.seed),
        style = palette.style,
        contrast = palette.contrast
    ) {
        val rootBackdrop = rememberBackdrop()
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
          // 两屏的画面合成一块背板：根层的面板（错误详情、外部配置确认）与提示条从这里采样做玻璃
          Box(Modifier.fillMaxSize().backdropSource(rootBackdrop, echo.canvas)) {
            // 重入闸门：转场进行中忽略重复「打开」。返回必须永远生效，否则转场中按返回会 pause 播放却留在阅读器
            val navigate: (String?) -> Unit = { to -> if (to == null || !nav.isSettling) bookId = to }
            // 书架：常驻在底层，阅读器打开时向左退 30% 并压暗（视差），返回时随手指回来
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = -0.30f * size.width * nav.value
                    // 阅读器完全盖住书架之后，书架这一层整个不画（透明度为 0 的图层会被直接跳过）：
                    // 听书动辄几小时，朗读高亮每动一下都不该顺带重画一遍看不见的书架
                    alpha = if (nav.value >= 0.999f) 0f else 1f
                }
            ) {
                ShelfScreen(graph, covered = { nav.value >= 0.999f }) { navigate(it) }
            }
            shownBook?.let { id ->
                // 压暗 + 遮挡层：只在阅读器存在时组合（否则会把书架整个盖住，任何点击都吞掉）。
                // 阅读器没有指针节点的位置（纯文本等）命中到这里就被吞掉，下面的书架不可触达。
                // 它在 z 序上位于阅读器之下，命中测试永远先选阅读器，因此绝不干扰阅读器内的任何手势
                // （放在阅读器的祖先节点上会在 Main 阶段之后消费事件，Slider / 弹层拖动会被判为「被别人消费」）。
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = 0.45f * nav.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }.background(Color.Black)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { if (!it.isConsumed) it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                        }
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = (1f - nav.value) * size.width }
                ) {
                    ReaderScreen(id, graph, nav, autoplay = autoplayFor == id, onAutoplayConsumed = { autoplayFor = null }) { navigate(null) }
                }
            }
          }
          CompositionLocalProvider(LocalBackdrop provides rootBackdrop) {
            ConfigConfirmSheet(graph)
            // 错误详情：全局单宿主，任何界面都能唤起（网络错误可能来自设置、阅读器或书架）
            ErrorDetailHost()
            ToastHost()
          }
        }
    }
}
