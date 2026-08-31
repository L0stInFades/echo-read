package app.echoread.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
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

/** 外部传入配置的确认卡片：展示脱敏 Key 与目标模型，用户确认后才写入 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ConfigConfirmSheet(graph: AppGraph) {
    val c = echo
    val pending by graph.configRequests.collectAsState()
    var last by androidx.compose.runtime.remember { mutableStateOf<AppGraph.PendingConfig?>(null) }
    if (pending != null) last = pending
    val p = pending ?: last
    EchoSheet(open = pending != null, onDismiss = { graph.configRequests.value = null }, title = "应用外部传入的配置？", maxHeightFraction = 0.6f) {
        if (p != null) {
            val rows = listOfNotNull(
                p.apiKey?.takeIf { it.isNotBlank() }?.let { "API Key" to (it.take(10) + "…" + it.takeLast(4)) },
                p.baseUrl?.takeIf { it.isNotBlank() }?.let { "Base URL" to it },
                p.model?.takeIf { it.isNotBlank() }?.let { "模型" to it },
                p.voice?.takeIf { it.isNotBlank() }?.let { "音色" to it }
            )
            androidx.compose.material3.Text("来自深链 / 命令行的配置，只有你确认后才会写入。", color = c.text2, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            for ((k, v) in rows) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    androidx.compose.material3.Text(k, color = c.text3, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.width(72.dp))
                    androidx.compose.material3.Text(v, color = c.text, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                OutlineButton("取消", Modifier.weight(1f)) { graph.configRequests.value = null }
                GradientButton("使用", Modifier.weight(1f)) {
                    graph.settings.updateOpenAI { cfg ->
                        cfg.copy(
                            apiKey = p.apiKey?.takeIf { it.isNotBlank() } ?: cfg.apiKey,
                            baseUrl = p.baseUrl?.takeIf { it.isNotBlank() } ?: cfg.baseUrl
                        )
                    }
                    p.model?.takeIf { it.isNotBlank() }?.let { graph.settings.setModel(it) }
                    p.voice?.takeIf { it.isNotBlank() }?.let { graph.settings.setVoice(it) }
                    graph.settings.updateTts { it.copy(provider = app.echoread.core.TtsProvider.OPENAI) }
                    graph.configRequests.value = null
                    Toaster.success("配置已应用")
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
    val contrast: Float
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
            PaletteChoice(it.dynamicColor, it.seedColor, it.colorStyle, it.contrast)
        }.distinctUntilChanged()
    }.collectAsState(graph.settings.reader.value.let {
        PaletteChoice(it.dynamicColor, it.seedColor, it.colorStyle, it.contrast)
    })
    EchoTheme(
        dynamic = palette.dynamic,
        seed = Color(palette.seed),
        style = palette.style,
        contrast = palette.contrast
    ) {
        Box(Modifier.fillMaxSize().background(echo.canvas).semantics { testTagsAsResourceId = true }) {
            // 重入闸门：转场进行中忽略重复「打开」。返回必须永远生效，否则转场中按返回会 pause 播放却留在阅读器
            val navigate: (String?) -> Unit = { to -> if (to == null || !nav.isSettling) bookId = to }
            // 书架：常驻在底层，阅读器打开时向左退 30% 并压暗（视差），返回时随手指回来
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = -0.30f * size.width * nav.value
                }
            ) {
                ShelfScreen(graph) { navigate(it) }
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
            ConfigConfirmSheet(graph)
            // 错误详情：全局单宿主，任何界面都能唤起（网络错误可能来自设置、阅读器或书架）
            ErrorDetailHost()
            ToastHost()
        }
    }
}
