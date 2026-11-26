package app.echoread.ui

import app.echoread.core.i18n.S
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.echoread.ui.glass.SurfaceStyle
import app.echoread.ui.glass.LocalBackdrop
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.rememberBackdrop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.Bookmark
import app.echoread.core.Bookmarks
import app.echoread.core.started
import app.echoread.core.PageAxis
import app.echoread.core.PlayerState
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.drivePaging
import app.echoread.ui.motion.preemptable
import app.echoread.ui.reader.BookmarkRibbon
import app.echoread.ui.reader.ChapterPages
import app.echoread.ui.reader.ChapterWindow
import app.echoread.ui.reader.LayoutSpec
import app.echoread.ui.reader.PageRef
import app.echoread.ui.reader.ReaderDock
import app.echoread.ui.reader.ReaderPager
import app.echoread.ui.reader.ReaderPages
import app.echoread.ui.reader.ReaderStatusPill
import app.echoread.ui.reader.ReaderTopBar
import app.echoread.ui.reader.SleepTimerOverlay
import app.echoread.ui.reader.SpeechEffects
import app.echoread.ui.reader.ThinProgressLine
import app.echoread.ui.reader.isOn
import app.echoread.ui.reader.layoutChapter
import app.echoread.ui.reader.segmentOn
import app.echoread.ui.voice.TtsSettingsSheet
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 切章请求：offset < 0 表示定位到该章末页 */
private data class ChapterRequest(val chapter: Int, val offset: Int, val seq: Int)

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

    // 引擎快照每段都要变好几次，睡眠倒计时每秒一跳：阅读页本身一样都不读（组合期读了就是整页重组）。
    // 快照只作为 State 往下传，由书页高亮、状态药丸、播放坞与 SpeechEffects 各自读；
    // 睡眠倒计时与倍速由播放坞里那一格自己订阅。
    val snapState = engine.snapshot.collectAsState()
    val reader by settings.reader.collectAsState()
    val theme = readerThemeOf(reader.theme)

    // 翻页手势配置。手势泵的 pointerInput key 恒定（业务状态绝不重启手势协程），
    // 因此传进去的 lambda 只能经 State 读取可变配置 —— 直接捕获组合期的普通值会永远停在首帧的设置。
    val gestures = reader.gestures
    val gesturesRef = rememberUpdatedState(gestures)
    val verticalPaging = gestures.axis == PageAxis.VERTICAL

    var meta by remember { mutableStateOf<BookMeta?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 这本书的书签，按书里的位置排；增删之后整表换新 */
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    LaunchedEffect(bookId) { bookmarks = graph.bookmarks.list(bookId) }

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
    /**
     * 自动跟随开关：true = 视图粘在朗读位置上。
     *
     * 0.2.x 的一个结构性错误在这里修掉：原来它是一个叫 DETACHED 的**手势闩** ——
     * 任何超过 slop 的拖动都会闩上（哪怕被橡皮筋弹回原页），而且没有任何路径自动解闩。
     * 于是「手动翻回朗读页」之后闩仍然闩着，跟随效应和自动翻页全部被它挡住，
     * 表现为「摸过一下屏幕之后自动翻页随机失灵」。
     *
     * 现在：这个开关只回答「要不要自动跟」；「现在偏没偏」是另一个**派生事实**（divergent，
     * 由视图页与朗读页逐帧比较得出），标签与返回入口由后者驱动；用户手动翻回朗读页时
     * 开关自动合上（见 SpeechEffects 里的复挂效应）。
     */
    val followState = remember { mutableStateOf(true) }
    var followSpeech by followState
    /** 视图页是否偏离了正在念的那一页：只由 SpeechEffects 维护（为什么是效应维护的状态，见那里） */
    val divergentState = remember { mutableStateOf(false) }
    /**
     * 视图主动发起播放/切章时锁定的目标章。引擎快照还停在旧章的那几十毫秒里，跟随不得把视图拽回去 ——
     * 否则「在第 3 章点读」会看到画面先闪回第 2 章再跳回来。
     */
    val awaitState = remember { mutableIntStateOf(-1) }
    var awaitEngineChapter by awaitState
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
        pager.onManual = { followSpeech = false }
        pager.onBlocked = { d ->
            // 只有真到书尾才提示；首屏未就绪或邻章还在排版时静默（橡皮筋已经给了物理反馈）
            val ready = window.chapterCount > 0 && window.pagesOf(pager.anchor.chapter) != null
            val atEdge = ready && (if (d > 0) pager.anchor.chapter >= window.chapterCount - 1 else pager.anchor.chapter <= 0)
            if (atEdge) Haptics.reject(view)
            if (d > 0 && atEdge) Toaster.show(S.reader.thisIsTheLast, durationMs = 1200)
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
            graph.chapterCache.get(bookId, index, app.echoread.core.Providers.chunkChars(settings.tts.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return withContext(layoutDispatcher) {
            layoutChapter(measurer, d, sp, readerThemeOf(sp.theme))
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
        // 规格只收影响分页的那几项设置（白名单，见 LayoutSpec）：拖一下热区滑块、换配色、切玻璃都不会重排。
        // 正文颜色只跟阅读主题 reader.theme 走，与应用配色无关。
        snapshotFlow {
            if (pageSize.width > 0 && pageSize.height > 0) LayoutSpec.of(reader, pageSize.width, pageSize.height) else null
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
                        Toaster.error(S.reader.chapterContentIsMissing)
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
                    graph.chapterCache.prefetch(bookId, n, app.echoread.core.Providers.chunkChars(settings.tts.value))
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

    /* ---------- 引擎跟随：分歧、跟随定位、自动翻页、失败提示、静默阅读的进度保存 ---------- */

    SpeechEffects(
        bookId = bookId,
        graph = graph,
        snapshot = snapState,
        pager = pager,
        window = window,
        driver = driver,
        followState = followState,
        divergentState = divergentState,
        awaitState = awaitState,
        pendingChapter = { request?.chapter },
        requestChapter = { index, offset -> requestChapter(index, offset) }
    )
    val curPages = window.pagesOf(pager.anchor.chapter)

    // 「轻点正文任意字开始朗读」是一次性教学，放进底栏会永久占掉一整条带。
    // 改成开一本没读过的书时提示一次：说完就走，不占版面。
    LaunchedEffect(bookId, meta) {
        val m = meta ?: return@LaunchedEffect
        if (!m.started()) {
            delay(700)
            Toaster.show(S.reader.tapAnyWordTo, ToastKind.INFO, 3800)
        }
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
        followSpeech = true
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
                Toaster.error(e.message ?: S.common.playbackFailed)
            }
        }
    }

    /**
     * 播放/暂停。规则一句话：**这颗键永远作用于「听」的位置，绝不搬动它**。
     *
     * 旧实现按「引擎是否在当前章」分叉：同章 → 恢复引擎位置；异章 → 把引擎重装到
     * 正看的页再播。后者正是丢进度的路径 —— 听到第 2 章中间，暂停，随手翻到第 5 章
     * 看了一眼，按播放：从第 5 章开始念，第 2 章的听书位置被覆盖（进度库也跟着被写掉）。
     * 同一颗键在两种情况下语义相反，用户无从预期。
     *
     * 现在：引擎只要在本书上装着（任何章），播放 = 恢复它的位置，视图由跟随效应拉回；
     * 「从正看的页开始念」的语义完整保留给点读与热区（handleTap → playFrom）。
     * 暂停则**不**动跟随开关 —— 用户按暂停可能正是为了停在当前看的页。
     */
    fun togglePlay() {
        ensureNotificationPermission()
        scope.launch {
            try {
                val a = pager.anchor
                val s = engine.current
                val engineOnBook = s.bookId == bookId && s.chapterIndex >= 0 &&
                    (s.state == PlayerState.PLAYING || s.state == PlayerState.PAUSED || s.state == PlayerState.ERROR)
                when {
                    // 换章装载的瞬间（LOADING）：这一下点击没有明确语义，忽略比误装载安全
                    s.bookId == bookId && s.state == PlayerState.LOADING -> return@launch
                    engineOnBook && s.state == PlayerState.PLAYING -> engine.pause()
                    engineOnBook -> {
                        followSpeech = true
                        engine.play()
                    }
                    else -> {
                        // 引擎不在本书上：从当前页首字开始
                        followSpeech = true
                        awaitEngineChapter = a.chapter
                        val offset = window.pagesOf(a.chapter)?.let { it.pageStartOffset(a.page.coerceIn(0, it.pageCount - 1)) } ?: 0
                        if (player.loadBook(bookId, a.chapter, offset)) engine.play()
                    }
                }
            } catch (e: Exception) {
                Toaster.error(e.message ?: S.common.playbackFailed)
            }
        }
    }

    /**
     * 跳到书里的一个位置：目录选章（[offset] = 0）与书签共用。
     * 正在朗读时朗读跟着过去、从那个位置接着念；没在朗读时只动视图，听书的位置不受影响。
     */
    fun gotoChapter(index: Int, offset: Int = 0) {
        val m = meta ?: return
        if (index < 0 || index >= m.chapterCount) return
        scope.launch {
            val wasPlaying = engine.current.state == PlayerState.PLAYING && engine.current.bookId == bookId
            if (wasPlaying) engine.pause()
            requestChapter(index, offset)
            if (wasPlaying) {
                followSpeech = true
                awaitEngineChapter = index
                try {
                    if (player.loadBook(bookId, index, offset)) engine.play()
                } catch (e: Exception) {
                    Toaster.error(e.message ?: S.reader.couldntLoadChapter)
                }
            }
        }
    }

    /* ---------- 书签 ---------- */

    /**
     * 呈现页（页码读的也是它）的章内偏移区间，与落在这一页上的书签：锚点在区间里就算。
     * 重排版之后区间跟着变，同一枚书签自然亮在它新落到的那一页上。
     */
    val shownRange = remember(shown, window.pagesOf(shown.chapter)) {
        window.pagesOf(shown.chapter)?.takeIf { shown.page in 0 until it.pageCount }?.pageRange(shown.page)
    }
    val pageMarks = remember(bookmarks, shown.chapter, shownRange) {
        shownRange?.let { Bookmarks.onPage(bookmarks, shown.chapter, it.start, it.end) }.orEmpty()
    }

    /**
     * 顶栏的书签键：这一页没有书签就加一枚，有就全拿掉（调小字号后，原本分属两页的书签会落到同一页）。
     * 加和减都不弹任何东西 —— 丝带落下 / 收回就是反馈，按错了再点一下。
     *
     * 锚点：这一页正在被朗读时取正在念的那一段的开头 —— 列表里看到的是一句完整的话，
     * 回来时也正好从这句接着听；否则取这一页的第一个字。
     */
    fun toggleBookmark() {
        val range = shownRange ?: return
        val text = window.pagesOf(shown.chapter)?.chapter?.text ?: return
        Haptics.tick(view)
        scope.launch {
            bookmarks = if (pageMarks.isNotEmpty()) graph.bookmarks.remove(bookId, pageMarks) else {
                // 正在念的那一段在这一刻从快照读：阅读页不在组合期订阅快照
                val s = snapState.value
                val engineChapter = if (s.isOn(bookId)) s.chapterIndex else -1
                val at = s.segmentOn(bookId)?.start?.takeIf { shown.chapter == engineChapter && it in range } ?: range.start
                graph.bookmarks.add(bookId, shown.chapter, at, Bookmarks.excerpt(text, at))
            }
        }
    }

    /**
     * 在书签列表里删一枚：不弹确认，给一个能撤销的轻提示（列表里删掉的没法像顶栏那样再点一下加回来）。
     * 撤销走应用级协程：点它的时候阅读页也许已经退出了。
     */
    fun removeFromList(mark: Bookmark) {
        scope.launch {
            bookmarks = graph.bookmarks.remove(bookId, listOf(mark))
            Toaster.show(S.reader.bookmarkRemoved, durationMs = 4000, action = ToastAction(S.reader.undo) {
                graph.mainScope.launch { bookmarks = graph.bookmarks.restore(bookId, listOf(mark)) }
            })
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

    val dockBg = if (theme.isDark) Color(0xFF15171E) else Color.White
    // 阅读页上的悬浮件用阅读主题自己的颜色做玻璃着色：阅读主题独立于系统深浅色，取 app 配色会错配
    val floatGlass = remember(theme.id) { regularGlass(theme.isDark, card = dockBg, fallback = dockBg) }
    // 顶栏按钮组与播放坞：浮在书页底色上的玻璃面。它们背后只有纯色的纸，不采样背板，
    // 玻璃感来自半透明的提亮、顶部反光、高光描边与投影
    val chromeGlass = remember(theme.id, c.glassOn) {
        SurfaceStyle(
            fill = if (!c.glassOn) Color.Transparent else if (theme.isDark) Color.White.copy(alpha = 0.065f) else Color.White.copy(alpha = 0.46f),
            rimHi = if (!c.glassOn) Color.Transparent else Color.White.copy(alpha = if (theme.isDark) 0.40f else 0.90f),
            rimLo = if (!c.glassOn) Color.Transparent else Color.White.copy(alpha = if (theme.isDark) 0.03f else 0.12f),
            rimWidth = 1.dp,
            edge = if (!c.glassOn) Color.Transparent else Color.Black.copy(alpha = if (theme.isDark) 0.30f else 0.05f)
        )
    }
    val chromeShadow = if (!c.glassOn) Color.Transparent else Color.Black.copy(alpha = if (theme.isDark) 0.34f else 0.09f)
    val backdrop = rememberBackdrop()
    val pageBackdrop = rememberBackdrop()
    val skeletonLine = with(density) { (reader.fontSize * reader.lineHeight).sp.toPx() }

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        if (loadFailed) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(S.reader.thisBookDoesntExist, color = theme.dim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                SoftButton(S.reader.backToLibrary, color = theme.text) { onBack() }
            }
            return@Box
        }

        Column(Modifier.fillMaxSize().backdropSource(backdrop, theme.bg)) {
            // 顶栏
            ReaderTopBar(
                theme = theme,
                glass = chromeGlass,
                shadow = chromeShadow,
                chapterTitle = window.pagesOf(shown.chapter)?.chapter?.title ?: "…",
                bookTitle = meta?.title ?: "",
                marked = pageMarks.isNotEmpty(),
                onBack = { leave() },
                onBookmark = { toggleBookmark() },
                onContents = { showChapters = true },
                onStyle = { showStyle = true },
                onVoice = { showTts = true }
            )
            // 排版中的细进度线（固定占位，不改变正文高度）
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (laying) ThinProgressLine(theme.accent, Modifier.fillMaxWidth())
            }

            // 页面区域：页面画布与页脚上下分列（页脚不与正文重叠），画布实际尺寸即分页高度
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 6.dp)) {
                val m = meta
                val shownPages = window.pagesOf(shown.chapter)
                // 书页本身是一块背板：悬浮在它上面的状态药丸与睡眠面板从这里采样
                Column(Modifier.fillMaxSize().backdropSource(pageBackdrop, theme.bg)) {
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
                        ReaderPages(bookId, snapState, pager, window, driver, verticalPaging, theme, skeletonLine)
                    }
                    // 页脚：页码/章号读「呈现页」，翻页期间与画面严格一致
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (shownPages != null) {
                            Text(S.reader.pageOf(shown.page + 1, shownPages.pageCount), color = theme.dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (m != null) Text(S.reader.chapterOf(shown.chapter + 1, m.chapterCount), color = theme.dim, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // 书签丝带：挂在页面区右上角，向外偏进正文右侧的留白里 —— 不占版面，也不接触摸
                BookmarkRibbon(pageMarks.isNotEmpty(), theme.accent, Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = (-6).dp))

                // 状态悬浮层：错误 / 重试 / 合成中 / 回到朗读位置。
                // 与睡眠面板同款 —— 悬浮在页面区底部、不参与布局，绝不改变正文 pageSize
                //（否则状态一闪一闪就是一章一章地重排版，见坞注释里的反馈环记录）。
                ReaderStatusPill(
                    bookId = bookId,
                    snapshot = snapState,
                    isReturnable = { divergentState.value && !followState.value },
                    theme = theme,
                    glass = floatGlass,
                    backdrop = pageBackdrop.takeIf { c.glassOn },
                    onReturn = { followSpeech = true },
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(5f)
                )

                // 睡眠定时选项：悬浮在页面区底部（临时弹出，不改变页面尺寸）
                SleepTimerOverlay(
                    visible = showSleep,
                    player = player,
                    theme = theme,
                    glass = floatGlass,
                    backdrop = pageBackdrop.takeIf { c.glassOn },
                    onPicked = { showSleep = false },
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(5f)
                )
            }

            // 底部播放坞：固定占位，不遮挡正文
            ReaderDock(
                bookId = bookId,
                graph = graph,
                snapshot = snapState,
                meta = meta,
                pager = pager,
                window = window,
                theme = theme,
                glass = chromeGlass,
                shadow = chromeShadow,
                onSleep = { showSleep = !showSleep },
                onPrev = { gotoChapter(pager.anchor.chapter - 1) },
                onPlay = { togglePlay() },
                onNext = { gotoChapter(pager.anchor.chapter + 1) }
            )
        }

        LaunchedEffect(showChapters) { if (showChapters && titles.isEmpty()) titles = graph.library.chapterTitles(bookId) }
        // 面板是书页的兄弟节点：从上面那块背板采样做玻璃
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            ChapterListSheet(
                open = showChapters, bookTitle = meta?.title.orEmpty(), titles = titles, current = shown.chapter,
                bookmarks = bookmarks, page = shownRange,
                onClose = { showChapters = false }, onSelect = { gotoChapter(it) },
                onOpenBookmark = { gotoChapter(it.chapter, it.offset) },
                onRemoveBookmark = { removeFromList(it) }
            )
            ReaderStyleSheet(
                open = showStyle,
                graph = graph,
                onOpenGestures = { showStyle = false; showGestures = true }
            ) { showStyle = false }
            GestureSettingsSheet(open = showGestures, graph = graph) { showGestures = false }
            TtsSettingsSheet(open = showTts, graph = graph) { showTts = false }
        }
    }
}
