package app.echoread.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.bookFraction
import app.echoread.core.GestureSettings
import app.echoread.core.PageAxis
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.tts.SleepMode
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.drivePaging
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
fun ReaderScreen(bookId: String, graph: AppGraph, nav: MotionDriver, autoplay: Boolean = false, onAutoplayConsumed: () -> Unit = {}, onBack: () -> Unit) {
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

    // 翻页手势配置。手势泵的 pointerInput key 恒定（业务状态绝不重启手势协程），
    // 因此传进去的 lambda 只能经 State 读取可变配置 —— 直接捕获组合期的普通值会永远停在首帧的设置。
    val gestures = reader.gestures
    val gesturesRef = rememberUpdatedState(gestures)
    val verticalPaging = gestures.axis == PageAxis.VERTICAL

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
    var showGestures by remember { mutableStateOf(false) }
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
            val atEdge = ready && (if (d > 0) pager.anchor.chapter >= window.chapterCount - 1 else pager.anchor.chapter <= 0)
            if (atEdge) Haptics.reject(view)
            if (d > 0 && atEdge) Toaster.show("已经是最后一页", durationMs = 1200)
        }
    }

    // 触觉：呈现页越过半页的那一刻给一下，且只对手指/点按驱动的翻页（引擎自动翻页静默）
    LaunchedEffect(Unit) {
        snapshotFlow { pager.displayed.value }.drop(1).collect { if (pager.userDriven) Haptics.tick(view) }
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

    // 驱动器的「一个单位」= 翻页方向上的页面尺寸：横翻取宽、竖翻取高。
    // 换方向并不改变页面尺寸，所以不能只靠 onSizeChanged 更新，必须跟着轴向一起重算，
    // 否则竖翻时 1 个单位仍是页宽 —— 手指划过整页只推动 0.5 个单位，永远翻不过去。
    LaunchedEffect(pageSize, verticalPaging) {
        val unit = if (verticalPaging) pageSize.height else pageSize.width
        if (unit > 0) driver.unitPx = unit.toFloat()
    }

    // 换轴瞬间把呈现值归零：若此刻还有未收敛的 settle 或橡皮筋回弹，
    // 旧偏移是记在 X 上的，换轴后按 Y 解读，三张页面画布会整体跳一下。
    LaunchedEffect(verticalPaging) { preemptable { driver.snapTo(0f) } }

    // 排版规格去抖：字号/行距滑块每像素回调不再触发整章重排；首屏不等待
    LaunchedEffect(bookId) {
        // haptics / 手势 / 动态取色都不影响正文排版：一律归一化掉。
        // LayoutSpec 持有整个 ReaderSettings 并按结构相等比较，不归一化的话，
        // 拖一下热区滑块、或切一下动态取色，就会把当前章连同前后邻章一起重排 + 交叉淡入
        //（长章数百毫秒的卡顿）。正文颜色只跟阅读主题 reader.theme 走，与应用配色无关。
        snapshotFlow {
            if (pageSize.width > 0 && pageSize.height > 0) {
                LayoutSpec(reader.copy(haptics = true, gestures = GestureSettings(), dynamicColor = false), pageSize.width, pageSize.height)
            } else null
        }
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
    LaunchedEffect(snap.failure) {
        val f = snap.failure
        if (f == null) {
            lastError = ""
            return@LaunchedEffect
        }
        if (snap.bookId != bookId) return@LaunchedEffect
        val head = f.headline()
        if (head == lastError) return@LaunchedEffect
        lastError = head
        // 网络类失败带「详情」入口；章节/播放类没有可展开的结构化信息
        val net = f.net
        if (net != null) ErrorDetails.toast(net) else Toaster.error(head, 5000)
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

    /**
     * 轻点裁决：先看「点击翻页热区」，落在热区外才是点读。
     * 热区的轴向与两端占比都可在「翻页手势」设置里调（默认左右各 20%，与 0.1.x 完全一致）。
     */
    fun handleTap(pos: Offset, sz: IntSize) {
        val g = gestures
        if (g.zonesActive) {
            val vertical = g.tapAxis == PageAxis.VERTICAL
            val extent = (if (vertical) sz.height else sz.width).toFloat()
            val at = if (vertical) pos.y else pos.x
            if (extent > 0f) {
                // 起始边（左/上）默认 = 上一页；invertZones 时互换
                val startDelta = if (g.invertZones) 1 else -1
                if (g.prevZone > 0.001f && at < g.prevZone * extent) {
                    pager.flip(startDelta)
                    return
                }
                if (g.nextZone > 0.001f && at > extent - g.nextZone * extent) {
                    pager.flip(-startDelta)
                    return
                }
            }
        }
        if (!g.tapToRead) return
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
    /**
     * 预测性返回（API 33+ 手势导航）：系统上报的进度直接写进根导航驱动器，阅读器随手指被拉开、
     * 书架在下面露出来；松手提交走同一条弹簧回到书架，中途放弃则弹回原位。
     * 旧系统（无进度事件）流会立即完成，退化为普通返回 —— 视觉仍由同一驱动器的弹簧完成。
     */
    PredictiveBackHandler { events ->
        var committed = false
        try {
            nav.drive { events.collect { e -> dragTo(1f - e.progress) } }
            committed = true
        } catch (_: CancellationException) {
            scope.launch { preemptable { nav.animateTo(1f, spec = EchoMotion.Emphasized.float()) } }
        }
        if (committed) {
            Haptics.gestureEnd(view)
            leave()
        }
    }

    // 只消费一次：重排版会换出新的 ChapterPages，光靠宿主把 autoplay 置回 false 不够及时
    var autoplayDone by remember { mutableStateOf(false) }
    LaunchedEffect(autoplay, curPages) {
        if (autoplay && !autoplayDone && curPages != null) {
            autoplayDone = true
            onAutoplayConsumed()
            togglePlay()
        }
    }

    /**
     * 书级阅读进度（0..1），与书架用的是 core 里同一个函数。
     *
     * 旧实现是 `snap.segmentIndex / snap.segmentCount` —— 那测的是「这一章打了几次合成请求」，
     * 不是读到哪。它有三个实测缺陷：从没按过播放时恒为 0（纯阅读的用户永远看不到进度）、
     * 换章时会 100%→0% 倒退、拖动「单片段字数」滑块能让静止不动的进度条走 10 个百分点。
     *
     * 取偏移的优先级：正在本书朗读 → 朗读位置；本书暂停 → 冻结在段首；其余 → 当前页首字。
     * 最后一条保证它**永远不为 0**，因为翻到哪一页应用一直是知道的。
     */
    val rawProgress = run {
        val m = meta
        val onThisBook = snap.bookId == bookId && snap.chapterIndex >= 0
        val speaking = onThisBook && (snap.state == PlayerState.PLAYING || snap.state == PlayerState.PAUSED)
        if (m == null) 0f
        else if (speaking) {
            val segLen = (snap.segmentEnd - snap.segmentStart).coerceAtLeast(0)
            // 段内插值只在播放时做；暂停时冻结在段首，避免 playbackFraction() 归零造成回跳
            val within = if (playing && segLen > 0) (segLen * engine.playbackFraction()).toInt() else 0
            val chapLen = window.pagesOf(snap.chapterIndex)?.chapter?.text?.length
                ?: (if (m.chapterCount > 0) m.totalChars / m.chapterCount else m.totalChars)
            bookFraction(snap.chapterIndex, snap.segmentStart + within, chapLen, m.chapterCount)
        } else {
            val pg = curPages
            val off = pg?.pageStartOffset(pager.anchor.page.coerceIn(0, (pg.pageCount - 1).coerceAtLeast(0))) ?: 0
            val chapLen = pg?.chapter?.text?.length ?: (if (m.chapterCount > 0) m.totalChars / m.chapterCount else m.totalChars)
            bookFraction(pager.anchor.chapter, off, chapLen, m.chapterCount)
        }
    }
    // 单调钳制：playbackFraction() 在段间空档（含退避重试期）会归零，不钳的话进度条每分钟
    // 都要往回弹几次。只有用户自己跳转（seek / 翻页 / 换章 / 换书）才允许回退。
    var shownProgress by remember { mutableFloatStateOf(0f) }
    val progressResetKey = Triple(bookId, pager.anchor.chapter, snap.segmentStart)
    var lastResetKey by remember { mutableStateOf(progressResetKey) }
    val chapterProgress = run {
        if (progressResetKey != lastResetKey) {
            lastResetKey = progressResetKey
            shownProgress = rawProgress
        } else if (rawProgress > shownProgress) {
            shownProgress = rawProgress
        }
        shownProgress
    }
    val dockBg = if (theme.isDark) Color(0xFF15171E) else Color.White
    val dockBorder = theme.text.copy(alpha = 0.12f)
    val skeletonLine = with(density) { (reader.fontSize * reader.lineHeight).sp.toPx() }

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        if (loadFailed) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("书籍不存在或已被删除", color = theme.dim, style = MaterialTheme.typography.bodyMedium)
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
                IconButtonEcho(EchoIcons.Back, "返回", modifier = Modifier.testTag("reader.back"), tint = theme.text) { leave() }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        window.pagesOf(shown.chapter)?.chapter?.title ?: "…",
                        color = theme.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(meta?.title ?: "", color = theme.dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButtonEcho(EchoIcons.Toc, "目录", tint = theme.text) { showChapters = true }
                IconButtonEcho(EchoIcons.TextStyle, "阅读样式", tint = theme.text) { showStyle = true }
                IconButtonEcho(EchoIcons.Waves, "朗读设置", tint = theme.text) { showTts = true }
            }
            // 排版中的细进度线（固定占位，不改变正文高度）
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (laying) ThinProgressLine(theme.accent, Modifier.fillMaxWidth())
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
                            .testTag("reader.page")
                            .onSizeChanged { if (pageSize != it) pageSize = it }
                            .drivePaging(
                                driver = driver,
                                axis = {
                                    when (gesturesRef.value.axis) {
                                        PageAxis.HORIZONTAL -> Orientation.Horizontal
                                        PageAxis.VERTICAL -> Orientation.Vertical
                                        PageAxis.OFF -> null
                                    }
                                },
                                enabled = { true },
                                bounds = { pager.bounds() },
                                slopScale = { gesturesRef.value.slopScale },
                                onDragStart = { pager.beginManual() },
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
                                                // 两个轴都显式赋值：换方向时另一轴必须归零，不能依赖图层属性的复位时机
                                                val off = slot - v
                                                translationX = if (verticalPaging) 0f else off * size.width
                                                translationY = if (verticalPaging) off * size.height else 0f
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
                            Text("${shown.page + 1} / ${shownPages.pageCount} 页", color = theme.dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (m != null) Text("第 ${shown.chapter + 1} / ${m.chapterCount} 章", color = theme.dim, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // 睡眠定时选项：悬浮在页面区底部（临时弹出，不改变页面尺寸）
                androidx.compose.animation.AnimatedVisibility(visible = showSleep, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).zIndex(5f)) {
                    Column(
                        Modifier
                            .widthIn(max = 380.dp)
                            .shadow(18.dp, RoundedCornerShape(Radius.lg), spotColor = Color.Black.copy(alpha = 0.4f))
                            .background(dockBg, RoundedCornerShape(Radius.lg))
                            .border(1.dp, dockBorder, RoundedCornerShape(Radius.lg))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 六个选项是单选而非筛选，用 M3 Expressive 的连接式按钮组。
                        // 拆成两排各三个：一排六个在窄屏上放不下，而换行会让首/尾圆角落在错误的位置。
                        // 配色显式传阅读主题色 —— ToggleButtonDefaults 取的是 app 配色，
                        // 浅色系统 + 暗夜阅读主题时会在深色面板上画出浅色容器。
                        val sleepRows = listOf(SLEEP_OPTIONS.take(3), SLEEP_OPTIONS.drop(3))
                        for (row in sleepRows) {
                            EchoSegmented(
                                items = row.map { SegmentItem(it.first) },
                                selectedIndex = row.indexOfFirst { it.second == sleepMode },
                                containerColor = Color.Transparent,
                                contentColor = theme.text.copy(alpha = 0.75f),
                                checkedContainerColor = theme.accent,
                                checkedContentColor = theme.bg,
                                borderColor = dockBorder
                            ) { i ->
                                player.setSleepTimer(row[i].second)
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
                Column(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .shadow(14.dp, RoundedCornerShape(Radius.xl), spotColor = Color.Black.copy(alpha = 0.35f))
                        .background(dockBg, RoundedCornerShape(Radius.xl))
                        .border(1.dp, dockBorder, RoundedCornerShape(Radius.xl))
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    val failure = snap.failure?.takeIf { snap.bookId == bookId }
                    val retryNote = snap.retryNote.takeIf { it.isNotEmpty() && snap.bookId == bookId }
                    // 状态行独占一整行。它是错误信息的常驻通道 ——
                    // 「连续 2 段失败 · 服务商故障（503）」这类文案挤在三分之一宽的栏里必然被截断，
                    // 而截断掉的恰好是状态码本身。控件行下面只放动作，符合 M3「工具栏是动作容器」的定位。
                    // 状态行与控件行同轴居中：控件行是对称的五槽，状态行若左对齐会在右上角
                    // 留下一块 L 形空白，整个坞看上去像没排完
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f, fill = false).echoPress(pressedScale = PressScale.Tile) {
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
                            val busyNow = (snap.synthesizing || snap.state == PlayerState.LOADING) && snap.bookId == bookId
                            val statusText = when {
                                // 失败最优先：它是用户此刻唯一需要知道的事，且必定带状态码
                                failure != null -> failure.headline()
                                retryNote != null -> retryNote
                                // 合成中：按钮不再换图标，改由这里说明，避免每段都闪一次
                                busyNow -> "正在合成…"
                                playing && follow == Follow.DETACHED -> "回到朗读位置 ↩"
                                playing || (snap.state == PlayerState.PAUSED && snap.bookId == bookId) -> snap.chapterTitle.ifEmpty { window.pagesOf(pager.anchor.chapter)?.chapter?.title ?: "" }
                                else -> "轻点正文任意字开始朗读"
                            }
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    statusText,
                                    color = when {
                                        failure != null -> if (failure is app.echoread.tts.EngineFailure.SkippedSegments) warningColor(theme.isDark) else dangerColor(theme.isDark)
                                        retryNote != null -> warningColor(theme.isDark)
                                        playing && follow == Follow.DETACHED -> theme.accent
                                        else -> theme.text
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                // 「详情」：状态行放不下服务商原话、端点和响应体，这里是唯一稳定的入口
                                val detail = failure?.net ?: snap.retry?.error?.takeIf { snap.bookId == bookId }
                                if (detail != null) {
                                    Text(
                                        "详情",
                                        color = theme.accent,
                                        style = MaterialTheme.typography.labelMediumEmphasized,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .echoPress(pressedScale = PressScale.Chip) { ErrorDetails.show(detail) }
                                    )
                                }
                            }
                        }
                    }
                    // 五个等权重槽位：播放键在第三格，因此**数学上**落在坞的正中。
                    // 旧写法是 spacedBy(CenterHorizontally) 把五个控件当一组居中，
                    // 而睡眠与倍速挂在右侧，实测把播放键推得偏左 99px（33dp）—— 最重要的控件不在中心。
                    // 顺序也改成「次要 | 主要 | 主 | 主要 | 次要」的对称形，两侧视觉重量相当。
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DockSlot {
                            if (sleepMode === SleepMode.Off) {
                                IconButtonEcho(EchoIcons.Moon, "睡眠定时", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp) { showSleep = !showSleep }
                            } else {
                                Text(
                                    if (sleepMode === SleepMode.Chapter) "本章" else "%d:%02d".format(java.util.Locale.ROOT, sleepRemaining / 60, sleepRemaining % 60),
                                    color = theme.accent,
                                    style = MaterialTheme.typography.labelSmallEmphasized,
                                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { showSleep = !showSleep }.padding(horizontal = 6.dp, vertical = 8.dp)
                                )
                            }
                        }
                        DockSlot {
                            IconButtonEcho(EchoIcons.SkipPrev, "上一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = pager.anchor.chapter > 0) { gotoChapter(pager.anchor.chapter - 1) }
                        }
                        DockSlot {
                            PlayButton(
                                playing = playing,
                                accent = theme.accent,
                                onAccent = theme.bg
                            ) { togglePlay() }
                        }
                        DockSlot {
                            IconButtonEcho(EchoIcons.SkipNext, "下一章", tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = meta?.let { pager.anchor.chapter < it.chapterCount - 1 } ?: false) { gotoChapter(pager.anchor.chapter + 1) }
                        }
                        DockSlot {
                            Text(
                                "${formatRate(tts.rate)}×",
                                color = theme.text.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmallEmphasized,
                                modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { cycleRate() }.padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                    // 进度条独占一行、横贯整个坞：它表达的是「位置」，长度就是它的可读性 ——
                    // 挤在三分之一宽的栏里读不出来。顺带长错误文案也不再被两侧控件压扁。
                    //
                    // **这里用直条，不用 M3 的波形进度条。** 试过并实测否掉了：
                    // 波形要成立，活动段必须长到能画出好几个周期。Android 通知栏里那条 squiggly
                    // 进度条表示的是「单曲内的位置」，活动段通常占大半；而这条表示的是**全书位置**，
                    // 读到第 2/20 章时活动段只有约 5%（约 100px），24dp 波长也只够一个多周期，
                    // 画出来是一条肥虫子而不是波。振幅（±3dp）还比 4dp 的条本身更粗。
                    // 「正在播放」已经由播放键的圆↔方圆角形变表达（M3 自己的形状形变），
                    // 进度条不必再喊一遍，直条在任何进度下都干净，也不产生常驻动画。
                    LinearProgressIndicator(
                        progress = { chapterProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (failure != null) dangerColor(theme.isDark) else theme.accent,
                        trackColor = theme.text.copy(alpha = 0.12f)
                    )
                }
            }
        }

        ChapterListSheet(
            open = showChapters, titles = titles, current = shown.chapter,
            onClose = { showChapters = false }, onSelect = { gotoChapter(it) }
        )
        LaunchedEffect(showChapters) { if (showChapters && titles.isEmpty()) titles = graph.library.chapterTitles(bookId) }
        ReaderStyleSheet(
            open = showStyle,
            graph = graph,
            onOpenGestures = { showStyle = false; showGestures = true }
        ) { showStyle = false }
        GestureSettingsSheet(open = showGestures, graph = graph) { showGestures = false }
        TtsSettingsSheet(open = showTts, graph = graph) { showTts = false }
    }
}

/** 文字层录制指纹：只在页 / 章 / 文字色 / 画布尺寸变化时重录，draw 期比较不分配 */
private class PageRecordKey {
    private var pages: ChapterPages? = null
    private var page = -1
    private var color = Color.Unspecified
    private var size = Size.Zero
    fun matches(p: ChapterPages, pg: Int, c: Color, s: Size) = pages === p && page == pg && color == c && size == s
    fun set(p: ChapterPages, pg: Int, c: Color, s: Size) { pages = p; page = pg; color = c; size = s }
}

/**
 * 单页绘制：高亮按行绘制（跳过段间空白行，行尾以可见文字为界，不重排文本），正文文字走「Picture 缓存」——
 * 整章 drawText 只在 (页, 章, 文字色, 尺寸) 变化时录制一次进 [GraphicsLayer]（HWUI RenderNode 显示列表），
 * 之后每段高亮切换、每帧拖动都只是「重放显示列表 + 几个圆角矩形」，不再重新遍历整章段落生成绘制指令。
 * 这就是 iOS CALayer 的 shouldRasterize 思路，只是保留矢量显示列表而非位图，无采样模糊、零显存。
 */
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
    val textColor = theme.text
    val textLayer = rememberGraphicsLayer()
    val recorded = remember(textLayer) { PageRecordKey() }
    Spacer(
        modifier.clipToBounds().drawBehind {
            // 整章布局只画本页：裁剪到「本页最后一行底边」，下一页的行绝不漏出（画布余量只留白）
            val clipBottom = minOf(bottom - top, size.height)
            if (!recorded.matches(pages, page, textColor, size)) {
                textLayer.record(this, layoutDirection, size.toIntSize()) {
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = clipBottom) {
                        translate(top = -top) { drawText(layout, color = textColor) }
                    }
                }
                recorded.set(pages, page, textColor, size)
            }
            if (hlRects.isNotEmpty()) {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = clipBottom) {
                    translate(top = -top) {
                        for (r in hlRects) {
                            drawRoundRect(hlColor, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(6f, 6f))
                        }
                    }
                }
            }
            drawLayer(textLayer)
        }
    )
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

/**
 * 播放坞的一个等权重槽位。五个槽位平分整行宽度，居中的那个就在坞的正中 ——
 * 靠内容自身宽度去凑居中在控件宽度不同（图标 36dp vs 「1×」文字）时必然偏。
 */
@Composable
private fun RowScope.DockSlot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

/**
 * 主播放键 —— 直接用 M3 自己的 [FilledIconToggleButton]。
 *
 * 之前是手搓的：一个实心圆 + 放大 1.18 倍 alpha 0.35 的同色光晕 + 染成强调色的 12dp 投影。
 * 在浅色阅读主题上那团光晕就是一坨糊，而且它想表达的「正在播放」本来就有更好的说法。
 *
 * M3 Expressive 对这类状态按钮的标准做法是**让形状说话**：
 * [IconButtonDefaults.toggleableShapes] 给出未选中 / 按下 / 选中三种形状，
 * 组件自己在其间做形变动画（走主题里的 Expressive 弹簧，也就是我们那套 CA 管线）。
 * 于是暂停时是圆、播放时形变成方圆角 —— 一眼可辨，且全部由库负责，
 * 涟漪、状态层、无障碍语义一并到位，不用我们自己描一遍。
 *
 * 配色吃**阅读主题**色而非 app 配色：阅读主题是独立于系统深浅色的用户选择，
 * 且配色现在可由用户任意更换，写死或取 app 色都会错配。
 */
@Composable
private fun PlayButton(playing: Boolean, accent: Color, onAccent: Color, onClick: () -> Unit) {
    FilledIconToggleButton(
        checked = playing,
        onCheckedChange = { onClick() },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = Modifier.size(52.dp).semantics {
            contentDescription = if (playing) "暂停" else "播放"
        },
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = accent,
            contentColor = onAccent,
            checkedContainerColor = accent,
            checkedContentColor = onAccent
        )
    ) {
        // 恒显播放/暂停图标。原来合成中会换成 LoadingIndicator —— 那是个会形变的多边形，
        // 塞进 24dp 就是一团黑块，而且每段合成都要闪一次。
        // 「正在合成」由状态行说，按钮只负责它自己的那件事。
        Icon(if (playing) EchoIcons.Pause else EchoIcons.Play, null, modifier = Modifier.size(24.dp))
    }
}

private fun formatRate(r: Float): String = String.format(java.util.Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
