package app.echoread.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import app.echoread.AppGraph
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion

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

private const val SHELF_KEY = "__shelf__"

/** 根导航：书架 ⇄ 阅读器，共享轴式弹簧滑动过渡 */
@Composable
fun EchoApp(graph: AppGraph) {
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoplayFor by rememberSaveable { mutableStateOf<String?>(null) }
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
    EchoTheme {
        Box(Modifier.fillMaxSize().background(echo.canvas)) {
            val nav = updateTransition(targetState = bookId, label = "nav")
            // 重入闸门：转场进行中忽略重复导航。旧代码连点两本书会让两份 ReaderScreen 同时存在、
            // 两份章节加载协程互相竞争 —— 这就是「快速连点造成状态错乱」。
            val navigate: (String?) -> Unit = { to -> if (!nav.isRunning) bookId = to }
            nav.AnimatedContent(
                transitionSpec = {
                    val forward = targetState != null
                    // 两侧位移比例对称、进出场同一条弹簧：新旧屏幕不会再相对滑动造成错位
                    val d = 0.30f
                    val spec = EchoMotion.Emphasized.spec(IntOffset.VisibilityThreshold)
                    val enter = slideInHorizontally(spec) { (it * if (forward) d else -d).toInt() } +
                        fadeIn(tween(Dur.Medium, easing = Ease.Linear))
                    val exit = slideOutHorizontally(spec) { (it * if (forward) -d else d).toInt() } +
                        fadeOut(tween(Dur.Medium, easing = Ease.Linear))
                    // clip = false：两屏同尺寸时不再多插一层尺寸动画容器
                    (enter togetherWith exit) using SizeTransform(clip = false)
                },
                contentKey = { it ?: SHELF_KEY }
            ) { id ->
                if (id == null) ShelfScreen(graph) { navigate(it) }
                else ReaderScreen(id, graph, autoplay = autoplayFor == id, onAutoplayConsumed = { autoplayFor = null }) { navigate(null) }
            }
            ConfigConfirmSheet(graph)
            ToastHost()
        }
    }
}
