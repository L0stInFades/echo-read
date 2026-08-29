package app.echoread.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.echoread.AppGraph

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
            AnimatedContent(
                targetState = bookId,
                transitionSpec = {
                    val forward = targetState != null
                    val enter = slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { if (forward) it / 3 else -it / 4 } + fadeIn(tween(220))
                    val exit = slideOutHorizontally(tween(260)) { if (forward) -it / 4 else it / 3 } + fadeOut(tween(200))
                    enter togetherWith exit
                },
                label = "nav"
            ) { id ->
                if (id == null) ShelfScreen(graph) { bookId = it }
                else ReaderScreen(id, graph, autoplay = autoplayFor == id, onAutoplayConsumed = { autoplayFor = null }) { bookId = null }
            }
            ToastHost()
        }
    }
}
