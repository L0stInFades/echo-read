package app.echoread.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.TtsProvider
import app.echoread.data.UpdateState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import app.echoread.ui.motion.echoTap

private enum class SortMode(val label: String) { RECENT("最近阅读"), ADDED("最近导入"), TITLE("书名") }

/**
 * 书架：One UI 式大标题下沉（滚动收起为紧凑标题栏）+ Harmony 卡片分组 + 底部拇指可达的「导入」主按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(graph: AppGraph, onOpenBook: (String) -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    val library = graph.library
    val books by library.books.collectAsState()
    val importing by library.importing.collectAsState()
    val tts by graph.settings.tts.collectAsState()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.RECENT) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookMeta?>(null) }

    LaunchedEffect(Unit) { library.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) graph.pendingImports.update { it + uris }
    }
    val pickFiles = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*")) }

    fun openSample() = scope.launch {
        try {
            val m = library.importSample()
            onOpenBook(m.id)
        } catch (e: Exception) {
            Toaster.error(e.message ?: "示例导入失败")
        }
    }

    val collator = remember { Collator.getInstance(Locale.CHINESE) }
    // remember 的 key 已经覆盖全部输入，外面再包一层 derivedStateOf 只是多一层观察开销
    val filtered = remember(books, query, sort) {
        val q = query.trim().lowercase()
        var list = books
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
        when (sort) {
            SortMode.ADDED -> list.sortedByDescending { it.createdAt }
            SortMode.TITLE -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
            SortMode.RECENT -> list
        }
    }
    val continueBook = if (query.isBlank()) books.firstOrNull { it.lastReadAt != null } else null

    val bigTitleBrush = rememberAurora()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val compactBarPx = with(density) { 52.dp.toPx() }
    // 大标题实测高度：收起行程取「header 高 − 紧凑栏高」，header 刚滚出视口时 collapse 恰好到 1，无跳变
    var headerPx by remember { mutableFloatStateOf(with(density) { 148.dp.toPx() }) }
    // One UI：大标题收起是「绑定」不是「动画」。绝不再套一层 animateFloatAsState —— 那会让标题
    // 滞后手指 200ms 以上，而且把连续的滚动值读进组合期，滚动每一像素都重组整个 ShelfScreen。
    val collapse = remember(compactBarPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / (headerPx - compactBarPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 120.dp)
        ) {
            // 大标题区（One UI 下沉标题）
            item("header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { if (it.height > 0) headerPx = it.height.toFloat() }
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 56.dp, bottom = 22.dp)
                ) {
                    Text(
                        "EchoRead",
                        style = TextStyle(brush = bigTitleBrush, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
                        modifier = Modifier.graphicsLayer {
                            // 大/小标题的交叉淡入只发生在行程两端，中段不会出现两个标题都半透明的「糊」
                            val p = collapse.value
                            alpha = 1f - (p / 0.6f).coerceIn(0f, 1f)
                            translationY = -p * 12.dp.toPx()
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (books.isNotEmpty()) "书架 · ${books.size} 本" else "AI 听书 · 声临其境",
                        color = c.text3, fontSize = 12.sp, letterSpacing = 3.sp,
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - (collapse.value / 0.6f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    )
                }
            }

            item("update") { UpdateCard(graph) }

            if (tts.provider == TtsProvider.OPENAI && tts.openai.apiKey.isBlank()) {
                item("keyBanner") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                            .background(c.card, RoundedCornerShape(Radius.lg))
                            .border(1.dp, c.border, RoundedCornerShape(Radius.lg))
                            .echoPress(pressedScale = PressScale.Tile) { showSettings = true }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(38.dp).background(rememberAurora(), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(EchoIcons.Key, null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("配置 API Key，开启 AI 朗读", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("支持 OpenRouter / OpenAI 兼容语音接口", color = c.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (books.isEmpty()) {
                item("empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(96.dp).background(c.card, RoundedCornerShape(Radius.xl)).border(1.dp, c.border, RoundedCornerShape(Radius.xl)),
                            contentAlignment = Alignment.Center
                        ) { Icon(EchoIcons.Book, null, tint = c.accent, modifier = Modifier.size(40.dp)) }
                        Spacer(Modifier.height(20.dp))
                        Text("书架还是空的", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("导入 TXT 或 EPUB 书籍，轻点任意文字，\nAI 便从那里开始为你朗读", color = c.text2, fontSize = 13.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        OutlineButton("没有书？先听示例 →") { openSample() }
                        Spacer(Modifier.height(12.dp))
                        Text("怎么用？", color = c.text3, fontSize = 12.sp, modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { showHelp = true }.padding(6.dp))
                    }
                }
            } else {
                if (continueBook != null) {
                    item("continue") {
                        ContinueCard(continueBook, Modifier.padding(bottom = 14.dp)) { onOpenBook(continueBook.id) }
                    }
                }
                item("search") {
                    Column(Modifier.padding(bottom = 14.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(c.card, CircleShape)
                                .border(1.dp, c.border, CircleShape)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(EchoIcons.Search, null, tint = c.text3, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            // 固定行高 + 去掉字体内边距：自定义系统字体（如手写体）下光标与文字基线才能对齐
                            val fieldStyle = fieldTextStyle(c.text, 13)
                            BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = fieldStyle, cursorBrush = SolidColor(c.accent),
                                modifier = Modifier.weight(1f).height(22.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) Text("搜索书名或作者", style = fieldStyle.copy(color = c.text3))
                                        inner()
                                    }
                                }
                            )
                            if (query.isNotEmpty()) Text("清除", color = c.text3, fontSize = 11.sp, modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { query = "" })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in SortMode.entries) Chip(s.label, selected = sort == s) { sort = s }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item("nores") { Text("没有找到「$query」", color = c.text3, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                } else {
                    // 网格逐行懒加载：每行一个 item，卡片背景按首/中/尾行分段拼出整块大圆角卡片
                    val cols = 3
                    val rows = filtered.chunked(cols)
                    // 稳定且零分配的 key（旧代码每次组合为每行拼一个字符串）
                    itemsIndexed(rows, key = { _, row -> row.first().id }, contentType = { _, _ -> "bookRow" }) { ri, row ->
                        val first = ri == 0
                        val last = ri == rows.size - 1
                        val shape = RoundedCornerShape(
                            topStart = if (first) Radius.xl else 0.dp, topEnd = if (first) Radius.xl else 0.dp,
                            bottomStart = if (last) Radius.xl else 0.dp, bottomEnd = if (last) Radius.xl else 0.dp
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(c.card, shape)
                                .padding(start = 14.dp, end = 14.dp, top = if (first) 14.dp else 0.dp, bottom = if (last) 14.dp else 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (b in row) BookCell(b, Modifier.weight(1f), onClick = { onOpenBook(b.id) }, onLongClick = { actionBook = b })
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // 紧凑标题栏：始终承载动作按钮；收起态才显示底色与小标题
        val compactTitleBrush = rememberAurora()
        val canvasColor = c.canvas
        val borderColor = c.border
        Column(Modifier.fillMaxWidth().zIndex(10f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // 底色在 draw 阶段读收起进度：不再每帧重组紧凑栏、也不再每帧新分配一个 Color 与 background 修饰符
                    .drawBehind { drawRect(canvasColor, alpha = collapse.value * 0.96f) }
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "EchoRead",
                        style = TextStyle(brush = compactTitleBrush, fontSize = 17.sp, fontWeight = FontWeight.Black),
                        modifier = Modifier.weight(1f).padding(start = 6.dp).graphicsLayer {
                            val sm = ((collapse.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                            alpha = sm
                            translationY = (1f - sm) * 10f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    )
                    IconButtonEcho(EchoIcons.Help, "怎么用", background = c.card.copy(alpha = 0.9f)) { showHelp = true }
                    Spacer(Modifier.width(8.dp))
                    IconButtonEcho(EchoIcons.Settings, "朗读设置", background = c.card.copy(alpha = 0.9f)) { showSettings = true }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(borderColor, alpha = collapse.value) })
        }

        // 底部主动作：拇指可达的「导入」
        Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 22.dp).zIndex(10f)) {
            GradientButton("导入书籍", icon = EchoIcons.Plus, height = 52.dp, modifier = Modifier.shadow(20.dp, CircleShape, spotColor = c.accent.copy(alpha = 0.55f))) { pickFiles() }
        }

        // 导入中遮罩
        AnimatedVisibility(
            importing,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Dur.Long, easing = Ease.Linear)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Dur.Medium, easing = Ease.Linear)),
            modifier = Modifier.matchParentSize().zIndex(60f)
        ) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).echoTap {},
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(14.dp))
                Text("正在解析书籍…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }

        TtsSettingsSheet(open = showSettings, graph = graph) { showSettings = false }
        HelpSheet(open = showHelp, graph = graph) { showHelp = false }
        BookActionSheet(book = actionBook, onClose = { actionBook = null }, onOpen = { b -> actionBook = null; onOpenBook(b.id) }) { b ->
            scope.launch {
                library.remove(b.id)
                if (graph.engine.current.bookId == b.id) graph.engine.stopAll()
                Toaster.success("已从书架移除")
            }
            actionBook = null
        }
    }
}

/** 应用内更新卡片：发现新版本 → 下载进度 → 安装 */
@Composable
private fun UpdateCard(graph: AppGraph) {
    val c = echo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by graph.updater.state.collectAsState()
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Ready || (state is UpdateState.Error && (state as UpdateState.Error).info != null)
    AnimatedVisibility(visible, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
        val s = state
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .background(c.card, RoundedCornerShape(Radius.lg))
                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(Radius.lg))
                .padding(14.dp)
        ) {
            val info = when (s) {
                is UpdateState.Available -> s.info
                is UpdateState.Downloading -> s.info
                is UpdateState.Ready -> s.info
                is UpdateState.Error -> s.info
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (s) {
                            is UpdateState.Ready -> "新版本 v${info?.versionName} 已下载"
                            is UpdateState.Downloading -> if (s.progress <= 0f) "正在连接下载服务器…" else "正在下载 v${info?.versionName} · ${(s.progress * 100).toInt()}%"
                            is UpdateState.Error -> "更新失败"
                            else -> "发现新版本 v${info?.versionName}"
                        },
                        color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    val sub = when (s) {
                        is UpdateState.Error -> s.message
                        else -> info?.notes?.ifBlank { "当前 v${graph.updater.currentVersionName}" } ?: ""
                    }
                    if (sub.isNotEmpty()) Text(sub, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(10.dp))
                when (s) {
                    is UpdateState.Downloading -> CircularProgressIndicator(progress = { s.progress }, color = c.accent, trackColor = c.border, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                    is UpdateState.Ready -> GradientButton("安装", height = 38.dp, fontSize = 13) {
                        if (!graph.updater.install(context, s.file)) Toaster.show("请允许 EchoRead 安装应用，然后回来点「安装」", durationMs = 4000)
                    }
                    is UpdateState.Available -> GradientButton("立即更新", height = 38.dp, fontSize = 13) { scope.launch { graph.updater.download(s.info) } }
                    is UpdateState.Error -> if (info != null) GradientButton("重试", height = 38.dp, fontSize = 13) { scope.launch { graph.updater.download(info) } }
                    else -> {}
                }
            }
            if (s is UpdateState.Available || s is UpdateState.Error) {
                Text("稍后再说", color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).echoPress(pressedScale = PressScale.Chip) { graph.updater.dismiss() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCell(b: BookMeta, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = echo
    val brush = rememberAurora()
    Column(modifier.echoPress(pressedScale = PressScale.Tile, onLongClick = onLongClick, onClick = onClick)) {
        // 去掉每格一个 elevation 阴影：3 列 × 4 行 = 12 个额外 RenderNode + outline 阴影，
        // 是 Adreno 610 上书架滚动掉帧的主力，而封面本身对比度已足够
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))) {
            BookCover(b, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(progressFraction(b)).height(4.dp).background(brush))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(b.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${progressText(b)} · ${formatChars(b.totalChars)}", color = c.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContinueCard(b: BookMeta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = echo
    Row(
        modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(Radius.xl))
            .border(1.dp, c.border, RoundedCornerShape(Radius.xl))
            .echoPress(pressedScale = PressScale.Tile, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(52.dp).height(76.dp).shadow(12.dp, RoundedCornerShape(10.dp))) { BookCover(b, Modifier.fillMaxSize(), radius = 10.dp, titleSize = 11) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("继续阅读", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(2.dp))
            Text(b.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(b.author); append(" · "); append(progressText(b))
                    b.lastReadAt?.let { append(" · "); append(relativeTime(it)) }
                },
                color = c.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            GradientBar(progressFraction(b), Modifier.fillMaxWidth(), height = 4.dp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun BoxScope.BookActionSheet(book: BookMeta?, onClose: () -> Unit, onOpen: (BookMeta) -> Unit, onDelete: (BookMeta) -> Unit) {
    val c = echo
    var last by remember { mutableStateOf<BookMeta?>(null) }
    if (book != null) last = book
    val b = book ?: last
    EchoSheet(open = book != null, onDismiss = onClose, title = b?.title ?: "", maxHeightFraction = 0.6f) {
        if (b != null) {
            Text(
                "${b.author} · ${b.format.label.uppercase()} · ${b.chapterCount} 章 · ${formatChars(b.totalChars)}" + (b.intro?.let { "\n$it" } ?: ""),
                color = c.text3, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 6, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().background(c.cardAlt, RoundedCornerShape(Radius.md)).echoPress(pressedScale = PressScale.Tile) { onOpen(b) }.padding(16.dp)
            ) { Text("继续阅读", color = c.text, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.12f), RoundedCornerShape(Radius.md)).echoPress(pressedScale = PressScale.Tile) { onDelete(b) }.padding(16.dp)
            ) { Text("从书架删除", color = c.danger, fontSize = 14.sp) }
        }
    }
}

private fun progressFraction(b: BookMeta): Float {
    if (b.chapterCount <= 1) {
        if (b.progress.offset == 0) return 0f
        return (b.progress.offset.toFloat() / maxOf(b.totalChars, 1)).coerceIn(0f, 1f)
    }
    if (b.progress.chapterIndex == 0 && b.progress.offset == 0) return 0f
    return (b.progress.chapterIndex.toFloat() / maxOf(b.chapterCount - 1, 1)).coerceIn(0f, 1f)
}

private fun progressText(b: BookMeta): String {
    val started = if (b.chapterCount <= 1) b.progress.offset != 0 else !(b.progress.chapterIndex == 0 && b.progress.offset == 0)
    return if (!started) "未开始" else "已读 ${(progressFraction(b) * 100).toInt()}%"
}

fun formatChars(n: Int): String = if (n >= 10000) String.format(java.util.Locale.ROOT, "%.1f 万字", n / 10000.0) else "$n 字"

private fun relativeTime(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        d < 7 * 86_400_000 -> "${d / 86_400_000} 天前"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.CHINA).format(java.util.Date(ts))
    }
}
