package app.echoread.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.tts.SleepMode
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.driveHorizontally
import app.echoread.ui.motion.echoPress
import app.echoread.ui.motion.preemptable
import app.echoread.ui.reader.ChapterPages
import app.echoread.ui.reader.ChapterWindow
import app.echoread.ui.reader.LayoutSpec
import app.echoread.ui.reader.PageRef
import app.echoread.ui.reader.ReaderPager
import app.echoread.ui.reader.layoutChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val RATE_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_OPTIONS: List<Pair<String, SleepMode>> = listOf(
    "15分" to SleepMode.Minutes(15), "30分" to SleepMode.Minutes(30), "60分" to SleepMode.Minutes(60),
    "90分" to SleepMode.Minutes(90), "播完本章" to SleepMode.Chapter, "关闭" to SleepMode.Off
)

/** 播放跟随：手动翻页（含暂停中）即脱离，点播放 / 点读 / 「回到朗读位置」恢复 */
private enum class Follow { FOLLOWING, DETACHED }

/** 切章请求：offset < 0 表示定位到该章末页 */
private data class ChapterRequest(val chapter: Int, val offset: Int, val seq: Int)

private data class FollowKey(
    val follow: Follow, val book: String, val state: PlayerState, val chapter: Int, val start: Int
)

/**
 * 分页阅读器：整章一次排版，三槽位画布跟手横滑翻页（可打断、可反向、书首书尾橡皮筋），
 * 左右两侧点按翻页、中间区域轻点任意字即从该字开始朗读；邻章预排版，跨章与章内翻页在渲染上同构。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(bookId: String, graph: AppGraph, autoplay: Boolean = false, onAutoplayConsumed: () -> Unit = {}, onBack: () -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
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
    var loadFailed by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }

    val measurer = rememberTextMeasurer()
    // 排版必须串行：measurer.measure() 是不可协作取消的阻塞调用，放任 Dispatchers.Default 并发，
    // 字号滑块每像素回调就会在 8 个核上堆出 8 份整章 measure，把主线程彻底饿死。
    val layoutDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }

    val driver = remember { MotionDriver() }
    val window = remember { ChapterWindow() }
    val pager = remember { ReaderPager(driver, window, scope) }
    val shown by pager.displayed

    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var spec by remember { mutableStateOf<LayoutSpec?>(null) }
    var request by remember { mutableStateOf<ChapterRequest?>(null) }
    var laying by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(Follow.FOLLOWING) }
    /**
     * 视图主动发起播放/切章时锁定的目标章。引擎快照还停在旧章的那几十毫秒里，跟随不得把视图拽回去 ——
     * 否则「在第 3 章点读」会看到画面先闪回第 2 章再跳回来。
     */
    var awaitEngineChapter by remember { mutableIntStateOf(-1) }
    val reqSeq = remember { intArrayOf(0) }

    var showChapters by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !theme.isDark
            WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !theme.isDark
        }
        window.chapterCount = meta?.chapterCount ?: 0
        pager.onManual = { follow = Follow.DETACHED }
        pager.onBlocked = { d ->
            // 只有真到书尾才提示；首屏未就绪或邻章还在排版时静默（橡皮筋已经给了物理反馈）
            val ready = window.chapterCount > 0 && window.pagesOf(pager.anchor.chapter) != null
            if (d > 0 && ready && pager.anchor.chapter >= window.chapterCount - 1) Toaster.show("已经是最后一页", durationMs = 1200)
        }
    }

    /* ---------- 装载与排版 ---------- */

    fun requestChapter(index: Int, offset: Int) {
        val total = meta?.chapterCount ?: return
        if (index < 0 || index >= total) return
        request = ChapterRequest(index, offset, ++reqSeq[0])
    }

    suspend fun layoutOne(index: Int, sp: LayoutSpec): ChapterPages? {
        val d = try {
            graph.chapterCache.get(bookId, index, settings.tts.value.maxChunkChars)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return withContext(layoutDispatcher) {
            layoutChapter(measurer, d, sp.reader, readerThemeOf(sp.reader.theme), sp.width, sp.height.toFloat())
        }
    }

    /** 新排版就位：旧页留在屏幕上淡出、新页淡入，永不「置空 + 居中转圈」 */
    suspend fun landOn(target: Int, laid: ChapterPages, sp: LayoutSpec, req: ChapterRequest?) {
        val a = pager.anchor
        val prev = window.pagesOf(a.chapter)
        val landing = when {
            req != null && req.offset < 0 -> laid.pageCount - 1
            req != null -> laid.pageOf(req.offset)
            // 重排版（换字号/主题/转屏）：保持当前页首字所在的位置
            prev != null && a.chapter == target && prev.pageCount > a.page -> laid.pageOf(prev.pageStartOffset(a.page))
            else -> a.page
        }
        var landed = false
        preemptable {
            pager.jumpTo(PageRef(target, landing.coerceIn(0, laid.pageCount - 1))) { window.put(target, laid, sp) }
            landed = true
        }
        // 被更高优先级抢占时请求必须留着：preemptable 会把取消吞掉，这里若照常清空就等于把切章请求丢了
        if (landed && req != null) request = null
    }

    // 排版规格去抖：字号/行距滑块每像素回调不再触发整章重排；首屏不等待
    LaunchedEffect(bookId) {
        snapshotFlow { if (pageSize.width > 0 && pageSize.height > 0) LayoutSpec(reader, pageSize.width, pageSize.height) else null }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce { if (spec == null) 0L else 90L }
            .collect { spec = it }
    }

    // 排版泵：当前章优先，随后预排邻章（翻过章尾时下一章已经就位）
    LaunchedEffect(bookId) {
        combine(
            snapshotFlow { request },
            snapshotFlow { pager.anchor.chapter },
            snapshotFlow { spec },
        ) { req, ch, sp -> Triple(req, ch, sp) }
            .conflate()
            .collectLatest { (req, anchorCh, sp) ->
                // combine 会带出「已被更新请求取代」的中间组合，处理它只会白白多做一次交叉淡入
                if (req != null && req !== request) return@collectLatest
                if (sp == null) return@collectLatest
                val total = meta?.chapterCount ?: return@collectLatest
                val target = req?.chapter ?: anchorCh
                val fresh = window.pagesOf(target)?.takeIf { window.specOf(target) == sp }
                if (fresh == null) {
                    laying = true
                    val laid = try {
                        layoutOne(target, sp)
                    } finally {
                        laying = false
                    }
                    if (laid == null) {
                        Toaster.error("章节内容缺失")
                        request = null
                        return@collectLatest
                    }
                    landOn(target, laid, sp, req)
                } else if (req != null) {
                    landOn(target, fresh, sp, req)
                }
                for (n in intArrayOf(target + 1, target - 1)) {
                    if (n < 0 || n >= total) continue
                    if (window.pagesOf(n) != null && window.specOf(n) == sp) continue
                    graph.chapterCache.prefetch(bookId, n, settings.tts.value.maxChunkChars)
                    val laid = layoutOne(n, sp) ?: continue
                    window.put(n, laid, sp)
                }
                window.retain(pager.anchor.chapter)
            }
    }

    LaunchedEffect(bookId) {
        val m = graph.library.book(bookId)
        if (m == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        meta = m
        window.chapterCount = m.chapterCount
        val s = engine.current
        if (s.bookId == bookId && s.state != PlayerState.IDLE && s.chapterIndex >= 0) requestChapter(s.chapterIndex, s.segmentStart)
        else requestChapter(m.progress.chapterIndex, m.progress.offset)
    }

    /* ---------- 引擎跟随 ---------- */

    val engineOnThis = snap.bookId == bookId && snap.chapterIndex >= 0 && snap.state != PlayerState.IDLE
    val engineChapter = if (engineOnThis) snap.chapterIndex else -1
    val activeSeg: Range? = if (engineOnThis) Range(snap.segmentStart, snap.segmentEnd) else null
    val synthesizing = snap.bookId == bookId && snap.state != PlayerState.IDLE && snap.synthesizing
    val playing = snap.state == PlayerState.PLAYING && snap.bookId == bookId
    val curPages = window.pagesOf(pager.anchor.chapter)

    fun segFor(ref: PageRef): Range? = if (ref.chapter == engineChapter) activeSeg else null

    // 只监听真正的语义输入（不再把 pages 当 key）：换主题/换字号不会再把页码拉回朗读位置
    LaunchedEffect(bookId) {
        snapshotFlow { FollowKey(follow, snap.bookId, snap.state, snap.chapterIndex, snap.segmentStart) }
            .distinctUntilChanged()
            .collectLatest { k ->
                if (awaitEngineChapter >= 0) {
                    val reached = k.book == bookId && k.chapter == awaitEngineChapter
                    // 引擎回到空闲/报错说明这次装载没成功，锁必须解开，否则跟随永久失效
                    val aborted = k.state == PlayerState.IDLE || k.state == PlayerState.ERROR
                    if (reached || aborted) awaitEngineChapter = -1 else return@collectLatest
                }
                if (k.follow != Follow.FOLLOWING || k.book != bookId) return@collectLatest
                if (k.state != PlayerState.PLAYING || k.chapter < 0) return@collectLatest
                if (k.chapter != pager.anchor.chapter) {
                    if (request?.chapter != k.chapter) requestChapter(k.chapter, k.start)
                    return@collectLatest
                }
                val pg = window.pagesOf(k.chapter) ?: return@collectLatest
                val target = PageRef(k.chapter, pg.pageOf(k.start).coerceIn(0, pg.pageCount - 1))
                if (target != pager.anchor) preemptable { pager.follow(target) }
            }
    }

    // 片段跨页：按播放进度估算念到的字位，越过下页首字即自动翻页（不等下一句）
    LaunchedEffect(activeSeg, pager.anchor, playing, follow, curPages) {
        val seg = activeSeg ?: return@LaunchedEffect
        val pg = curPages ?: return@LaunchedEffect
        if (!playing || follow != Follow.FOLLOWING || engineChapter != pager.anchor.chapter) return@LaunchedEffect
        val cur = pager.anchor.page.coerceIn(0, pg.pageCount - 1)
        if (cur >= pg.pageCount - 1) return@LaunchedEffect
        val nextStart = pg.pageStartOffset(cur + 1)
        if (seg.end <= nextStart || seg.start >= nextStart) return@LaunchedEffect
        val len = (seg.end - seg.start).coerceAtLeast(1)
        while (isActive) {
            delay(250)
            if (driver.isDragging) continue
            val est = seg.start + (len * engine.playbackFraction()).toInt()
            if (est + 2 >= nextStart) {
                preemptable { pager.follow(PageRef(pager.anchor.chapter, cur + 1)) }
                break
            }
        }
    }

    var lastError by remember { mutableStateOf("") }
    LaunchedEffect(snap.error) {
        if (snap.error.isNotEmpty() && snap.error != lastError && snap.bookId == bookId) Toaster.error(snap.error, 5000)
        lastError = snap.error
    }

    /* ---------- 进度：手动翻页（非播放中）也记录当前页首字 ---------- */

    var lastManualSave by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(pager.anchor, curPages, playing) {
        val pg = curPages ?: return@LaunchedEffect
        if (playing) return@LaunchedEffect
        delay(600)
        val now = System.currentTimeMillis()
        if (now - lastManualSave < 1500) return@LaunchedEffect
        lastManualSave = now
        graph.library.saveProgress(bookId, pager.anchor.chapter, pg.pageStartOffset(pager.anchor.page.coerceIn(0, pg.pageCount - 1)))
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
        follow = Follow.FOLLOWING
        awaitEngineChapter = pager.anchor.chapter
        scope.launch {
            try {
                val a = pager.anchor
                val s = engine.current
                if (s.bookId != bookId || s.chapterIndex != a.chapter) {
                    if (!player.loadBook(bookId, a.chapter, offset)) return@launch
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
        follow = Follow.FOLLOWING
        awaitEngineChapter = pager.anchor.chapter
        scope.launch {
            try {
                val a = pager.anchor
                val s = engine.current
                val onThisChapter = s.bookId == bookId && s.chapterIndex == a.chapter
                if (onThisChapter && (s.state == PlayerState.PLAYING || s.state == PlayerState.PAUSED || s.state == PlayerState.ERROR)) {
                    engine.toggle()
                    return@launch
                }
                // 从当前页首字开始（引擎尚未装载本章时）
                val offset = window.pagesOf(a.chapter)?.let { it.pageStartOffset(a.page.coerceIn(0, it.pageCount - 1)) } ?: 0
                if (player.loadBook(bookId, a.chapter, offset)) engine.play()
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
            requestChapter(index, if (lastPage) -1 else 0)
            if (wasPlaying) {
                follow = Follow.FOLLOWING
                awaitEngineChapter = index
                try {
                    if (player.loadBook(bookId, index, 0)) engine.play()
                } catch (e: Exception) {
                    Toaster.error(e.message ?: "章节加载失败")
                }
            }
        }
    }

    fun handleTap(pos: Offset, sz: IntSize) {
        val w = sz.width.toFloat()
        when {
            pos.x < w * 0.2f -> pager.flip(-1)
            pos.x > w * 0.8f -> pager.flip(1)
            else -> {
                // 滑动/回弹进行中不接受点读：此刻模型页与画面上的页可能不是同一页
                if (driver.isDragging || driver.isSettling) return
                val ref = pager.anchor
                val p = window.pagesOf(ref.chapter) ?: return
                val cur = ref.page.coerceIn(0, p.pageCount - 1)
                val top = p.pageTop(cur)
                val lastLine = p.pages[cur].last
                if (pos.y + top > p.layout.getLineBottom(lastLine)) return
                val r = p.layout.getOffsetForPosition(Offset(pos.x, pos.y + top))
                playFrom(p.toChapter(r))
            }
        }
    }
    val tapRef = rememberUpdatedState<(Offset, IntSize) -> Unit>({ p, s -> handleTap(p, s) })

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

    // 只消费一次：重排版会换出新的 ChapterPages，光靠宿主把 autoplay 置回 false 不够及时
    var autoplayDone by remember { mutableStateOf(false) }
    LaunchedEffect(autoplay, curPages) {
        if (autoplay && !autoplayDone && curPages != null) {
            autoplayDone = true
            onAutoplayConsumed()
            togglePlay()
        }
    }

    val chapterProgress = if (snap.bookId == bookId && snap.chapterIndex == pager.anchor.chapter && snap.segmentCount > 0) (snap.segmentIndex.toFloat() / snap.segmentCount).coerceIn(0f, 1f) else 0f
    val dockBg = if (theme.isDark) Color(0xFF15171E) else Color.White
    val dockBorder = theme.text.copy(alpha = 0.12f)
    val skeletonLine = with(density) { (reader.fontSize * reader.lineHeight).sp.toPx() }

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
                    Text(
                        window.pagesOf(shown.chapter)?.chapter?.title ?: "…",
                        color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(meta?.title ?: "", color = theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButtonEcho(EchoIcons.Toc, "目录", tint = theme.text) { showChapters = true }
                IconButtonEcho(EchoIcons.TextStyle, "阅读样式", tint = theme.text) { showStyle = true }
                IconButtonEcho(EchoIcons.Waves, "朗读设置", tint = theme.text) { showTts = true }
            }
            // 排版中的细进度线（固定占位，不改变正文高度）
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (laying) ThinProgressLine(c.accent, Modifier.fillMaxWidth())
            }

            // 页面区域：页面画布与页脚上下分列（页脚不与正文重叠），画布实际尺寸即分页高度
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 6.dp)) {
                val m = meta
                val shownPages = window.pagesOf(shown.chapter)
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .onSizeChanged {
                                if (pageSize != it) pageSize = it
                                if (it.width > 0) driver.unitPx = it.width.toFloat()
                            }
                            .driveHorizontally(
                                driver = driver,
                                enabled = { true },
                                bounds = { pager.bounds() },
                                onDragStart = { pager.onManual() },
                                onTap = { pos, sz -> tapRef.value(pos, sz) },
                                onSettle = { v -> pager.settle(v) },
                            )
                    ) {
                        val anchorPages = window.pagesOf(pager.anchor.chapter)
                        if (anchorPages == null) {
                            PageSkeleton(theme.text, skeletonLine, Modifier.fillMaxSize())
                        } else {
                            // 三槽位：上一页 / 当前页 / 下一页，各自一个 RenderNode，位移只写 graphicsLayer。
                            // 静止时左右两页 alpha=0，HWUI 直接跳过，正文只画一份。
                            for (slot in -1..1) {
                                val ref = (if (slot == 0) pager.anchor else window.resolve(pager.anchor, slot)) ?: continue
                                val pg = window.pagesOf(ref.chapter) ?: continue
                                if (ref.page >= pg.pageCount) continue
                                key(ref.chapter, ref.page) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val v = driver.value
                                                translationX = (slot - v) * size.width
                                                alpha = when {
                                                    slot == 0 -> if (pager.outgoing != null) pager.fade.value else 1f
                                                    abs(v) > 0.0005f -> 1f
                                                    else -> 0f
                                                }
                                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                            }
                                    ) {
                                        PageCanvas(pg, ref.page, segFor(ref), synthesizing, theme, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                        // 跨章 / 重排版时淡出的旧页：新页排好之前屏幕上一直有内容
                        pager.outgoing?.let { old ->
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    alpha = 1f - pager.fade.value
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }
                            ) {
                                PageCanvas(old.first, old.second.coerceIn(0, old.first.pageCount - 1), null, false, theme, Modifier.fillMaxSize())
                            }
                        }
                    }
                    // 页脚：页码/章号读「呈现页」，翻页期间与画面严格一致
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (shownPages != null) {
                            Text("${shown.page + 1} / ${shownPages.pageCount} 页", color = theme.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (m != null) Text("第 ${shown.chapter + 1} / ${m.chapterCount} 章", color = theme.dim, fontSize = 11.sp)
                    }
                }

                // 睡眠定时选项：悬浮在页面区底部（临时弹出，不改变页面尺寸）
                androidx.compose.animation.AnimatedVisibility(visible = showSleep, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).zIndex(5f)) {
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
                        Modifier.weight(1f).height(40.dp).echoPress(pressedScale = PressScale.Tile) {
                            // 点标题：播放中回到朗读所在页（跨章则装载朗读章）并恢复跟随；否则打开目录
                            if (playing && follow == Follow.DETACHED) {
                                follow = Follow.FOLLOWING
                                val s = engine.current
                                if (s.chapterIndex == pager.anchor.chapter) {
                                    window.pagesOf(s.chapterIndex)?.let { p ->
                                        val t = PageRef(s.chapterIndex, p.pageOf(s.segmentStart).coerceIn(0, p.pageCount - 1))
                                        scope.launch { preemptable { pager.follow(t) } }
                                    }
                                } else if (s.chapterIndex >= 0) requestChapter(s.chapterIndex, s.segmentStart)
                            } else showChapters = true
                        },
                        verticalArrangement = Arrangement.Center
                    ) {
                        val statusText = when {
                            snap.retryNote.isNotEmpty() && snap.bookId == bookId -> snap.retryNote
                            playing && follow == Follow.DETACHED -> "回到朗读位置 ↩"
                            playing || (snap.state == PlayerState.PAUSED && snap.bookId == bookId) -> snap.chapterTitle.ifEmpty { window.pagesOf(pager.anchor.chapter)?.chapter?.title ?: "" }
                            else -> "轻点正文任意字开始朗读"
                        }
                        Text(
                            statusText,
                            color = when {
                                snap.retryNote.isNotEmpty() && snap.bookId == bookId -> Color(0xFFFBBF24)
                                playing && follow == Follow.DETACHED -> c.accent
                                else -> theme.text
                            },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        GradientBar(chapterProgress, Modifier.fillMaxWidth(), height = 2.dp, track = theme.text.copy(alpha = 0.12f))
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButtonEcho(EchoIcons.SkipPrev, "上一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = pager.anchor.chapter > 0) { gotoChapter(pager.anchor.chapter - 1) }
                    PlayButton(playing = playing, busy = snap.state == PlayerState.LOADING && snap.bookId == bookId || synthesizing) { togglePlay() }
                    IconButtonEcho(EchoIcons.SkipNext, "下一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = meta?.let { pager.anchor.chapter < it.chapterCount - 1 } ?: false) { gotoChapter(pager.anchor.chapter + 1) }
                    if (sleepMode === SleepMode.Off) {
                        IconButtonEcho(EchoIcons.Moon, "睡眠定时", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp) { showSleep = !showSleep }
                    } else {
                        Text(
                            if (sleepMode === SleepMode.Chapter) "本章" else "%d:%02d".format(java.util.Locale.ROOT, sleepRemaining / 60, sleepRemaining % 60),
                            color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { showSleep = !showSleep }.padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                    }
                    Text(
                        "${formatRate(tts.rate)}×", color = theme.text.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { cycleRate() }.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }

        ChapterListSheet(
            open = showChapters, titles = titles, current = shown.chapter,
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
    // 高亮矩形连内缩量一起算好：draw lambda 里零分配
    val hlRects = remember(pages, page, active) {
        if (active == null) emptyList() else {
            val rs = pages.toRendered(active.start)
            val re = pages.toRendered(active.end) // 章节 end 为开区间，映射后仍为开区间
            val out = ArrayList<Rect>()
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
                if (right > left) out.add(Rect(left - 2f, layout.getLineTop(line) + 2f, right + 2f, layout.getLineBottom(line) - 2f))
            }
            out
        }
    }
    // 合成中不再用 infiniteRepeatable：60fps 常驻动画在听书场景（动辄数小时）纯属耗电，
    // 且 .value 在组合期读会让整页每帧重组、每帧重新提交整章 drawText。改为静态压暗。
    val hlColor = theme.hl.copy(alpha = (theme.hl.alpha * if (synthesizing) 1.0f else 1.6f).coerceAtMost(1f))
    val bottom = layout.getLineBottom(range.last)
    Canvas(modifier.clipToBounds()) {
        // 整章布局只画本页：裁剪到「本页最后一行底边」，下一页的行绝不漏出（画布余量只留白）
        clipRect(left = 0f, top = 0f, right = size.width, bottom = minOf(bottom - top, size.height)) {
            translate(top = -top) {
                for (r in hlRects) {
                    drawRoundRect(hlColor, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(6f, 6f))
                }
                drawText(layout, color = theme.text)
            }
        }
    }
}

/** 首次进入（没有任何旧页可留）时的骨架：从「页面消失了」变成「页面正在成形」，绝不居中转圈 */
@Composable
private fun PageSkeleton(textColor: Color, lineHeightPx: Float, modifier: Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(300, easing = LinearEasing)) }
    Spacer(
        modifier.drawBehind {
            val lh = lineHeightPx.coerceAtLeast(20f)
            var y = lh * 0.8f
            var i = 0
            while (y + lh * 0.5f < size.height && i < 30) {
                val w = size.width * (if (i % 6 == 5) 0.52f else 0.97f)
                drawRoundRect(
                    textColor,
                    topLeft = Offset(0f, y),
                    size = Size(w, lh * 0.46f),
                    cornerRadius = CornerRadius(3f, 3f),
                    alpha = a.value * 0.10f
                )
                y += lh
                i++
            }
        }
    )
}

/** 2dp 不定进度线：位置只在 draw lambda 里读，不触发重组；仅在排版进行中存在 */
@Composable
private fun ThinProgressLine(color: Color, modifier: Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            p.snapTo(0f)
            p.animateTo(1f, tween(1100, easing = LinearEasing))
        }
    }
    Spacer(
        modifier.height(2.dp).drawBehind {
            val w = size.width * 0.32f
            drawRect(color, topLeft = Offset((size.width + w) * p.value - w, 0f), size = Size(w, size.height), alpha = 0.9f)
        }
    )
}

@Composable
private fun PlayButton(playing: Boolean, busy: Boolean, onClick: () -> Unit) {
    // 播放中用静态柔光 + 状态切换时的弹簧缩放，而非持续 60fps 的呼吸环：听书动辄数小时，省电优先
    val glow by animateFloatAsState(if (playing) 1f else 0f, EchoMotion.Gentle.float(), label = "glow")
    val pop by animateFloatAsState(if (playing) 1.06f else 1f, EchoMotion.Playful.float(), label = "pop")
    val brush = rememberAurora()
    val ringBrush = rememberAurora()
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = 1f + glow * 0.18f; scaleY = 1f + glow * 0.18f; alpha = glow * 0.35f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .background(ringBrush, CircleShape)
        )
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .shadow(12.dp, CircleShape, spotColor = Color(0xFF7C9BFF).copy(alpha = 0.6f))
                .echoPress(pressedScale = PressScale.Button, onClickLabel = if (playing) "暂停" else "播放", onClick = onClick)
                .background(brush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
            else Icon(if (playing) EchoIcons.Pause else EchoIcons.Play, if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private fun formatRate(r: Float): String = String.format(java.util.Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
