package app.echoread.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.core.ReaderSettings
import app.echoread.data.DerivedChapter
import app.echoread.tts.SleepMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val RATE_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_OPTIONS: List<Pair<String, SleepMode>> = listOf(
    "15分" to SleepMode.Minutes(15), "30分" to SleepMode.Minutes(30), "60分" to SleepMode.Minutes(60),
    "90分" to SleepMode.Minutes(90), "播完本章" to SleepMode.Chapter, "关闭" to SleepMode.Off
)

/**
 * 整章分页排版结果：章节文本一次性 measure 成 TextLayoutResult，按行高切成整页；
 * 页面绘制只做 clip + translate + drawText（零重排），翻页、高亮、点读全部基于同一份布局。
 * 渲染串比章节纯文本多了「标题前缀」与每段之间的「间距空行」，两套偏移用 toRendered/toChapter 互转。
 */
class ChapterPages(
    val chapter: DerivedChapter,
    val layout: TextLayoutResult,
    private val prefixLen: Int,
    val pages: List<IntRange> // 每页覆盖的行号区间
) {
    val pageCount: Int get() = pages.size
    fun pageTop(p: Int): Float = layout.getLineTop(pages[p].first)

    /** 章节偏移 → 渲染偏移：渲染串 = 标题 + 占位符 + 段落…（段间占位符与章节文本的 \n 一一对应） */
    fun toRendered(chapterOffset: Int): Int = prefixLen + chapterOffset

    /** 渲染偏移 → 章节偏移（落在标题/段间占位上时钳到相邻段落的字） */
    fun toChapter(rendered: Int): Int {
        val text = chapter.text
        if (text.isEmpty()) return 0
        var o = (rendered - prefixLen).coerceIn(0, text.length - 1)
        if (text[o] == '\n') o = if (o + 1 < text.length) o + 1 else o - 1
        return o.coerceIn(0, text.length - 1)
    }

    fun pageOf(chapterOffset: Int): Int {
        val line = layout.getLineForOffset(toRendered(chapterOffset).coerceIn(0, maxOf(layout.layoutInput.text.length - 1, 0)))
        val idx = pages.indexOfFirst { line in it }
        return if (idx < 0) pages.size - 1 else idx
    }

    /** 本页首字的章节偏移（进度保存用） */
    fun pageStartOffset(p: Int): Int = toChapter(layout.getLineStart(pages[p].first))
}

private const val FIRST_LINE_INDENT_EM = 2f

/** 构建渲染串 + 整章 measure + 分页（在 Default 线程执行） */
private fun layoutChapter(
    measurer: TextMeasurer,
    chapter: DerivedChapter,
    style: TextStyle,
    reader: ReaderSettings,
    theme: ReaderTheme,
    width: Int,
    pageHeight: Float
): ChapterPages {
    val lineHeightSp = reader.fontSize * reader.lineHeight
    val gapSp = (lineHeightSp * 0.5f * reader.paraSpacing).coerceAtLeast(2f)
    // 段落之间不用换行符（Compose 会为行尾换行再生成一个空行），改用带独立行高的单字符占位段落
    val paraStyle = ParagraphStyle(textIndent = TextIndent(firstLine = FIRST_LINE_INDENT_EM.em), lineHeight = lineHeightSp.sp, textAlign = TextAlign.Justify)
    val gapStyle = ParagraphStyle(lineHeight = gapSp.sp)
    val titleStyle = ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (lineHeightSp * 1.3f).sp)
    val text = chapter.text
    val annotated: AnnotatedString = buildAnnotatedString {
        withStyle(titleStyle) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (reader.fontSize + 4).sp, color = theme.text)) { append(chapter.title) }
        }
        withStyle(gapStyle) { append(' ') }
        chapter.paras.forEachIndexed { pi, p ->
            withStyle(paraStyle) { append(text, p.start, p.end) }
            if (pi < chapter.paras.size - 1) withStyle(gapStyle) { append(' ') }
        }
    }
    val layout = measurer.measure(
        text = annotated,
        style = style,
        constraints = Constraints(maxWidth = width),
        skipCache = true
    )
    val pages = ArrayList<IntRange>()
    var first = 0
    val n = layout.lineCount
    while (first < n) {
        val top = layout.getLineTop(first)
        var last = first
        while (last + 1 < n && layout.getLineBottom(last + 1) - top <= pageHeight - 1f) last++
        pages.add(first..last)
        first = last + 1
    }
    if (pages.isEmpty()) pages.add(0..0)
    return ChapterPages(chapter, layout, chapter.title.length + 1, pages)
}

private data class PageKey(val chapter: Int, val page: Int)

/**
 * 分页阅读器：整章一次排版，左右两侧点按 / 横向滑动翻页，翻过章尾自动进入下一章；
 * 中间区域轻点任意字即从该字开始朗读；播放坞固定在底部，不遮挡正文。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: String, graph: AppGraph, autoplay: Boolean = false, onAutoplayConsumed: () -> Unit = {}, onBack: () -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val player = graph.player
    val engine = graph.engine
    val settings = graph.settings

    val snap by engine.snapshot.collectAsState()
    val reader by settings.reader.collectAsState()
    val tts by settings.tts.collectAsState()
    val sleepMode by player.sleepMode.collectAsState()
    val sleepRemaining by player.sleepRemaining.collectAsState()
    val theme = readerThemeOf(reader.theme)

    var meta by remember { mutableStateOf<BookMeta?>(null) }
    var derived by remember { mutableStateOf<DerivedChapter?>(null) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }

    var pages by remember { mutableStateOf<ChapterPages?>(null) }
    var page by remember { mutableIntStateOf(0) }
    /** 装载后要定位到的章节偏移；-1 表示最后一页（向前翻章） */
    var pendingOffset by remember { mutableIntStateOf(0) }
    /** 播放中视图是否跟随朗读位置翻页（手动翻页即暂停跟随，点播放/点读恢复） */
    var follow by remember { mutableStateOf(true) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }

    var showChapters by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val measurer = rememberTextMeasurer()
    val loadSeq = remember { intArrayOf(0) }

    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !theme.isDark
            WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    /* ---------- 装载（视图侧代际守卫：快速翻章/滑块连发时后到者胜） ---------- */

    suspend fun loadChapter(index: Int, offset: Int) {
        val my = ++loadSeq[0]
        val d = try {
            graph.chapterCache.get(bookId, index, settings.tts.value.maxChunkChars)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (my != loadSeq[0]) return
        if (d == null) {
            Toaster.error("章节内容缺失")
            return
        }
        pendingOffset = offset
        if (derived !== d) pages = null
        derived = d
        chapterIndex = index
    }

    val fontFamily = if (reader.fontFamily == "serif") FontFamily.Serif else FontFamily.Default
    val bodyStyle = TextStyle(
        color = theme.text,
        fontSize = reader.fontSize.sp,
        lineHeight = (reader.fontSize * reader.lineHeight).sp,
        fontFamily = fontFamily,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    )

    // 排版：章节 / 样式 / 页面尺寸任一变化即在后台重排；重排后按待定偏移（或当前页首字）定位
    LaunchedEffect(derived, reader, pageSize, theme.id) {
        val d = derived ?: return@LaunchedEffect
        if (pageSize.width <= 0 || pageSize.height <= 0) return@LaunchedEffect
        val keepOffset = pages?.let { old -> if (old.chapter === d && old.pageCount > page) old.pageStartOffset(page) else null }
        val laid = withContext(Dispatchers.Default) { layoutChapter(measurer, d, bodyStyle, reader, theme, pageSize.width, pageSize.height.toFloat()) }
        pages = laid
        page = when {
            keepOffset != null -> laid.pageOf(keepOffset)
            pendingOffset < 0 -> laid.pageCount - 1
            else -> laid.pageOf(pendingOffset)
        }
        pendingOffset = 0
    }

    LaunchedEffect(bookId) {
        val m = graph.library.book(bookId)
        if (m == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        meta = m
        val s = engine.current
        if (s.bookId == bookId && s.state != PlayerState.IDLE && s.chapterIndex >= 0) loadChapter(s.chapterIndex, s.segmentStart)
        else loadChapter(m.progress.chapterIndex, m.progress.offset)
    }

    /* ---------- 引擎跟随：跨章 + 翻到朗读所在页 ---------- */

    val activeSeg: Range? = if (snap.bookId == bookId && snap.chapterIndex == chapterIndex && snap.state != PlayerState.IDLE && snap.chapterIndex >= 0) Range(snap.segmentStart, snap.segmentEnd) else null
    val synthesizing = snap.bookId == bookId && snap.state != PlayerState.IDLE && snap.synthesizing
    val playing = snap.state == PlayerState.PLAYING && snap.bookId == bookId

    LaunchedEffect(snap.chapterIndex, snap.segmentStart, snap.state, pages) {
        val s = snap
        if (s.bookId != bookId || s.state != PlayerState.PLAYING || s.chapterIndex < 0 || !follow) return@LaunchedEffect
        if (s.chapterIndex != chapterIndex) {
            if (derived != null) loadChapter(s.chapterIndex, s.segmentStart)
            return@LaunchedEffect
        }
        val pg = pages ?: return@LaunchedEffect
        if (pg.chapter !== derived) return@LaunchedEffect
        val target = pg.pageOf(s.segmentStart)
        if (target != page) page = target
    }

    var lastError by remember { mutableStateOf("") }
    LaunchedEffect(snap.error) {
        if (snap.error.isNotEmpty() && snap.error != lastError && snap.bookId == bookId) Toaster.error(snap.error, 5000)
        lastError = snap.error
    }

    /* ---------- 进度：手动翻页（非播放中）也记录当前页首字 ---------- */

    var lastManualSave by remember { mutableStateOf(0L) }
    LaunchedEffect(page, pages) {
        val pg = pages ?: return@LaunchedEffect
        if (playing) return@LaunchedEffect
        delay(600)
        val now = System.currentTimeMillis()
        if (now - lastManualSave < 1500) return@LaunchedEffect
        lastManualSave = now
        graph.library.saveProgress(bookId, chapterIndex, pg.pageStartOffset(page.coerceIn(0, pg.pageCount - 1)))
    }

    /* ---------- 交互 ---------- */

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun playFrom(offset: Int) {
        ensureNotificationPermission()
        follow = true
        scope.launch {
            try {
                val s = engine.current
                if (s.bookId != bookId || s.chapterIndex != chapterIndex) {
                    if (!player.loadBook(bookId, chapterIndex, offset)) return@launch
                } else {
                    engine.seekToOffset(offset)
                }
                engine.play()
            } catch (e: Exception) {
                Toaster.error(e.message ?: "播放失败")
            }
        }
    }

    fun togglePlay() {
        ensureNotificationPermission()
        follow = true
        scope.launch {
            try {
                val s = engine.current
                val onThisChapter = s.bookId == bookId && s.chapterIndex == chapterIndex
                if (onThisChapter && (s.state == PlayerState.PLAYING || s.state == PlayerState.PAUSED || s.state == PlayerState.ERROR)) {
                    engine.toggle()
                    return@launch
                }
                // 从当前页首字开始（引擎尚未装载本章时）
                val offset = pages?.let { it.pageStartOffset(page.coerceIn(0, it.pageCount - 1)) } ?: 0
                if (player.loadBook(bookId, chapterIndex, offset)) engine.play()
            } catch (e: Exception) {
                Toaster.error(e.message ?: "播放失败")
            }
        }
    }

    fun gotoChapter(index: Int, lastPage: Boolean = false) {
        val m = meta ?: return
        if (index < 0 || index >= m.chapterCount) return
        scope.launch {
            val wasPlaying = engine.current.state == PlayerState.PLAYING && engine.current.bookId == bookId
            if (wasPlaying) engine.pause()
            loadChapter(index, if (lastPage) -1 else 0)
            if (wasPlaying) {
                follow = true
                try {
                    if (player.loadBook(bookId, index, 0)) engine.play()
                } catch (e: Exception) {
                    Toaster.error(e.message ?: "章节加载失败")
                }
            }
        }
    }

    /** 翻页：越过章首/章尾自动切章 */
    fun flip(delta: Int) {
        val pg = pages ?: return
        val m = meta ?: return
        if (playing) follow = false
        val next = page + delta
        when {
            next in 0 until pg.pageCount -> page = next
            delta > 0 && chapterIndex < m.chapterCount - 1 -> scope.launch { loadChapter(chapterIndex + 1, 0) }
            delta < 0 && chapterIndex > 0 -> scope.launch { loadChapter(chapterIndex - 1, -1) }
            delta > 0 -> Toaster.show("已经是最后一页", durationMs = 1200)
        }
    }

    fun cycleRate() {
        val cur = tts.rate
        val next = RATE_STEPS.firstOrNull { it > cur + 0.01f } ?: RATE_STEPS[0]
        settings.updateTts { it.copy(rate = next) }
        Toaster.show("${formatRate(next)}× 倍速", durationMs = 1200)
    }

    fun leave() {
        player.saveProgressNow()
        engine.pause()
        onBack()
    }
    BackHandler { leave() }

    LaunchedEffect(autoplay, pages) {
        if (autoplay && pages != null) {
            onAutoplayConsumed()
            togglePlay()
        }
    }

    val chapterProgress = if (snap.bookId == bookId && snap.chapterIndex == chapterIndex && snap.segmentCount > 0) (snap.segmentIndex.toFloat() / snap.segmentCount).coerceIn(0f, 1f) else 0f
    val dockBg = if (theme.isDark) Color(0xFF15171E) else Color.White
    val dockBorder = theme.text.copy(alpha = 0.12f)

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        if (loadFailed) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("书籍不存在或已被删除", color = theme.dim, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                OutlineButton("返回书架", color = theme.text) { onBack() }
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.bg)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButtonEcho(EchoIcons.Back, "返回", tint = theme.text) { leave() }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(derived?.title ?: "…", color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta?.title ?: "", color = theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButtonEcho(EchoIcons.Toc, "目录", tint = theme.text) { showChapters = true }
                IconButtonEcho(EchoIcons.TextStyle, "阅读样式", tint = theme.text) { showStyle = true }
                IconButtonEcho(EchoIcons.Waves, "朗读设置", tint = theme.text) { showTts = true }
            }

            // 页面区域：页面画布与页脚上下分列（页脚不与正文重叠），画布实际尺寸即分页高度
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 6.dp)) {
                val pg = pages
                val m = meta
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onSizeChanged { if (pageSize != it) pageSize = it }
                            .pointerInput(pg, playing) {
                                var drag = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onHorizontalDrag = { _, dx -> drag += dx },
                                    onDragEnd = {
                                        val threshold = 56.dp.toPx()
                                        if (drag <= -threshold) flip(1) else if (drag >= threshold) flip(-1)
                                    }
                                )
                            }
                            .pointerInput(pg) {
                                detectTapGestures { pos ->
                                    val w = this.size.width.toFloat()
                                    val p = pages ?: return@detectTapGestures
                                    when {
                                        pos.x < w * 0.2f -> flip(-1)
                                        pos.x > w * 0.8f -> flip(1)
                                        else -> {
                                            // 中间区域：任意字点读（点在页内文字下方的空白则忽略）
                                            val cur = page.coerceIn(0, p.pageCount - 1)
                                            val top = p.pageTop(cur)
                                            val lastLine = p.pages[cur].last
                                            if (pos.y + top > p.layout.getLineBottom(lastLine)) return@detectTapGestures
                                            val r = p.layout.getOffsetForPosition(Offset(pos.x, pos.y + top))
                                            playFrom(p.toChapter(r))
                                        }
                                    }
                                }
                            }
                    ) {
                        if (pg == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            }
                        } else {
                            val cur = page.coerceIn(0, pg.pageCount - 1)
                            AnimatedContent(
                                targetState = PageKey(chapterIndex, cur),
                                transitionSpec = {
                                    val forward = targetState.chapter > initialState.chapter || (targetState.chapter == initialState.chapter && targetState.page > initialState.page)
                                    val enter = slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { if (forward) it else -it } + fadeIn(tween(160))
                                    val exit = slideOutHorizontally(tween(220)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160))
                                    enter togetherWith exit
                                },
                                label = "page",
                                modifier = Modifier.fillMaxSize()
                            ) { key ->
                                if (key.chapter == chapterIndex && key.page < pg.pageCount) {
                                    PageCanvas(pg, key.page, activeSeg, synthesizing, theme, Modifier.fillMaxSize())
                                } else {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    // 页脚：页码 / 章节（独立占位，永不与正文重叠）
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pg != null) {
                            val cur = page.coerceIn(0, pg.pageCount - 1)
                            Text("${cur + 1} / ${pg.pageCount} 页", color = theme.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (m != null) Text("第 ${chapterIndex + 1} / ${m.chapterCount} 章", color = theme.dim, fontSize = 11.sp)
                    }
                }

                // 睡眠定时选项：悬浮在页面区底部（临时弹出，不改变页面尺寸）
                androidx.compose.animation.AnimatedVisibility(visible = showSleep, enter = Motion.expandIn, exit = Motion.collapseOut, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).zIndex(5f)) {
                    FlowRow(
                        Modifier
                            .shadow(18.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                            .background(dockBg, RoundedCornerShape(20.dp))
                            .border(1.dp, dockBorder, RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((label, mode) in SLEEP_OPTIONS) {
                            Chip(label, selected = sleepMode == mode) {
                                player.setSleepTimer(mode)
                                showSleep = false
                            }
                        }
                    }
                }
            }

            // 底部播放坞：固定占位，不遮挡正文
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(theme.bg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .shadow(14.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                        .background(dockBg, CircleShape)
                        .border(1.dp, dockBorder, CircleShape)
                        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f).height(40.dp).bounceClick(pressedScale = 0.99f) {
                            // 点标题：播放中回到朗读所在页并恢复跟随；否则打开目录
                            if (playing && !follow) {
                                follow = true
                                pages?.let { p -> if (snap.chapterIndex == chapterIndex) page = p.pageOf(snap.segmentStart) }
                            } else showChapters = true
                        },
                        verticalArrangement = Arrangement.Center
                    ) {
                        val statusText = when {
                            snap.retryNote.isNotEmpty() && snap.bookId == bookId -> snap.retryNote
                            playing && !follow -> "回到朗读位置 ↩"
                            playing || (snap.state == PlayerState.PAUSED && snap.bookId == bookId) -> snap.chapterTitle.ifEmpty { derived?.title ?: "" }
                            else -> "轻点正文任意字开始朗读"
                        }
                        Text(
                            statusText,
                            color = when {
                                snap.retryNote.isNotEmpty() && snap.bookId == bookId -> Color(0xFFFBBF24)
                                playing && !follow -> c.accent
                                else -> theme.text
                            },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        GradientBar(chapterProgress, Modifier.fillMaxWidth(), height = 2.dp, track = theme.text.copy(alpha = 0.12f))
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButtonEcho(EchoIcons.SkipPrev, "上一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = chapterIndex > 0) { gotoChapter(chapterIndex - 1) }
                    PlayButton(playing = playing, busy = snap.state == PlayerState.LOADING && snap.bookId == bookId || synthesizing) { togglePlay() }
                    IconButtonEcho(EchoIcons.SkipNext, "下一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = meta?.let { chapterIndex < it.chapterCount - 1 } ?: false) { gotoChapter(chapterIndex + 1) }
                    if (sleepMode === SleepMode.Off) {
                        IconButtonEcho(EchoIcons.Moon, "睡眠定时", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp) { showSleep = !showSleep }
                    } else {
                        Text(
                            if (sleepMode === SleepMode.Chapter) "本章" else "%d:%02d".format(java.util.Locale.ROOT, sleepRemaining / 60, sleepRemaining % 60),
                            color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.bounceClick { showSleep = !showSleep }.padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                    }
                    Text(
                        "${formatRate(tts.rate)}×", color = theme.text.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.bounceClick { cycleRate() }.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }

        ChapterListSheet(
            open = showChapters, titles = titles, current = chapterIndex,
            onClose = { showChapters = false }, onSelect = { gotoChapter(it) }
        )
        LaunchedEffect(showChapters) { if (showChapters && titles.isEmpty()) titles = graph.library.chapterTitles(bookId) }
        ReaderStyleSheet(open = showStyle, graph = graph) { showStyle = false }
        TtsSettingsSheet(open = showTts, graph = graph) { showTts = false }
    }
}

/** 单页绘制：clip + translate + drawText；高亮按行绘制（跳过段间空白行，行尾以可见文字为界，不重排文本） */
@Composable
private fun PageCanvas(pages: ChapterPages, page: Int, active: Range?, synthesizing: Boolean, theme: ReaderTheme, modifier: Modifier) {
    val top = pages.pageTop(page)
    val range = pages.pages[page]
    val layout = pages.layout
    val hlRects = remember(pages, page, active) {
        if (active == null) emptyList() else {
            val rs = pages.toRendered(active.start)
            val re = pages.toRendered(active.end) // 章节 end 为开区间，映射后仍为开区间
            val out = ArrayList<androidx.compose.ui.geometry.Rect>()
            val firstLine = maxOf(layout.getLineForOffset(rs), range.first)
            val lastLine = minOf(layout.getLineForOffset(maxOf(re - 1, rs)), range.last)
            for (line in firstLine..lastLine) {
                val ls = layout.getLineStart(line)
                val le = layout.getLineEnd(line, visibleEnd = true)
                if (le <= ls) continue // 段间占位 / 空白行
                val s = maxOf(rs, ls)
                val e = minOf(re, le)
                if (s >= e) continue
                // 起点用字符的真实水平位置（首行缩进不涂色）；终点到行尾时取行右边界
                val left = layout.getHorizontalPosition(s, usePrimaryDirection = true)
                val right = if (e >= le) layout.getLineRight(line) else layout.getHorizontalPosition(e, usePrimaryDirection = true)
                if (right > left) out.add(androidx.compose.ui.geometry.Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line)))
            }
            out
        }
    }
    val pulse = if (synthesizing && hlRects.isNotEmpty()) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulseAlpha").value
    } else 1f
    val hlColor = theme.hl.copy(alpha = (theme.hl.alpha * 1.6f * pulse).coerceAtMost(1f))
    val bottom = layout.getLineBottom(range.last)
    Canvas(modifier.clipToBounds()) {
        // 整章布局只画本页：裁剪到「本页最后一行底边」，下一页的行绝不漏出（画布余量只留白）
        clipRect(left = 0f, top = 0f, right = size.width, bottom = minOf(bottom - top, size.height)) {
            translate(top = -top) {
                for (r in hlRects) {
                    drawRoundRect(hlColor, topLeft = Offset(r.left - 2f, r.top + 2f), size = androidx.compose.ui.geometry.Size(r.width + 4f, r.height - 4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                }
                drawText(layout, color = theme.text)
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, busy: Boolean, onClick: () -> Unit) {
    // 播放中用静态柔光 + 状态切换时的弹簧缩放，而非持续 60fps 的呼吸环：听书动辄数小时，省电优先
    val glow by animateFloatAsState(if (playing) 1f else 0f, Motion.soft, label = "glow")
    val pop by animateFloatAsState(if (playing) 1.06f else 1f, Motion.bouncy, label = "pop")
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer { scaleX = 1f + glow * 0.18f; scaleY = 1f + glow * 0.18f; alpha = glow * 0.35f }
                .background(Aurora, CircleShape)
        )
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .shadow(12.dp, CircleShape, spotColor = Color(0xFF7C9BFF).copy(alpha = 0.6f))
                .background(Aurora, CircleShape)
                .bounceClick(pressedScale = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
            else Icon(if (playing) EchoIcons.Pause else EchoIcons.Play, if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private fun formatRate(r: Float): String = String.format(java.util.Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')

@Suppress("unused")
private fun unusedAbs(x: Float) = abs(x)
