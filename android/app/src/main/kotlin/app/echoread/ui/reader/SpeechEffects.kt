package app.echoread.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.echoread.AppGraph
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.tts.EngineSnapshot
import app.echoread.ui.ErrorDetails
import app.echoread.ui.Toaster
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.preemptable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/*
 * 阅读页跟着引擎走的那一半：位置分歧、跟随定位、片段跨页的自动翻页、失败提示、不在朗读时的进度保存。
 * 它们都要读引擎快照，而快照每段都要变好几次（段号、合成中、缓冲数……）：放在这个没有界面的节点里读，
 * 变化只重组这里，不牵动整个阅读页。书页高亮、状态药丸、播放坞也各自读快照，阅读页本身不读。
 */

/** 引擎此刻装着这本书、停在某一章上（播放、暂停、装载、出错都算，空闲不算） */
internal fun EngineSnapshot.isOn(bookId: String): Boolean =
    this.bookId == bookId && chapterIndex >= 0 && state != PlayerState.IDLE

/** 正在念的那一段（章内偏移）；引擎不在这本书上时为 null */
internal fun EngineSnapshot.segmentOn(bookId: String): Range? = if (isOn(bookId)) Range(segmentStart, segmentEnd) else null

/** 正在这本书上出声（暂停、装载都不算） */
internal fun EngineSnapshot.isPlaying(bookId: String): Boolean = state == PlayerState.PLAYING && this.bookId == bookId

private data class FollowKey(
    val follow: Boolean, val book: String, val state: PlayerState, val chapter: Int, val start: Int
)

/**
 * @param followState 自动跟随开关（为什么是开关而不是闩，见阅读页上它的说明）。用户自己翻回可听页时这里把它合上
 * @param divergentState 视图页是否偏离了正在念的那一页：只由这里写，状态药丸据此给出「回到朗读位置」
 * @param awaitState 视图主动发起播放 / 切章时锁定的目标章：引擎到了那一章（或装载失败）这里解锁
 * @param pendingChapter 还没落地的切章请求的目标章（没有为 null）
 */
@Composable
internal fun SpeechEffects(
    bookId: String,
    graph: AppGraph,
    snapshot: State<EngineSnapshot>,
    pager: ReaderPager,
    window: ChapterWindow,
    driver: MotionDriver,
    followState: MutableState<Boolean>,
    divergentState: MutableState<Boolean>,
    awaitState: MutableIntState,
    pendingChapter: () -> Int?,
    requestChapter: (index: Int, offset: Int) -> Unit,
) {
    val engine = graph.engine
    val snap by snapshot
    var followSpeech by followState
    var awaitEngineChapter by awaitState

    val engineOnThis = snap.isOn(bookId)
    val engineChapter = if (engineOnThis) snap.chapterIndex else -1
    val activeSeg: Range? = snap.segmentOn(bookId)
    val playing = snap.isPlaying(bookId)
    val curPages = window.pagesOf(pager.anchor.chapter)

    /* ---------- 位置分歧：派生事实，不是闩 ---------- */

    /** 引擎在本书上有一个有意义的朗读位置（播放中或暂停挂起） */
    val speechLoaded = engineOnThis && (snap.state == PlayerState.PLAYING || snap.state == PlayerState.PAUSED)

    /**
     * 视图页是否偏离了**正在念的那一页**（可听页 = segmentStart + 片段长 × 播放进度所在的页）。
     *
     * 为什么是效应维护的状态而不是组合期直接算：可听页要读 playbackFraction()——
     * 它不是快照状态，组合期读了既不纯也不会触发重组。效应在锚点/片段/播放态变化时重估一次。
     *
     * 为什么容差是**单页**而不是「片段覆盖的页范围」（第一版实现，实测被否）：
     * 测试书里一段能横跨三页，「范围内都算在位」意味着用户从朗读页翻走两页都不出标签。
     * 跟随中锚点本就是自动翻页按可听进度放置的，恒等于可听页；分歧对齐同一把尺，
     * 「跨页片段暂停时倒判一页」的老问题也不存在 —— 暂停时 playbackFraction 保持原值。
     *
     * 已知且接受的迟滞：用户停在可听页、语音跨段移走时，没有状态发射把这里叫醒，
     * 分歧要等下一个段边界（≤ 一段）才亮标签。为这几秒开常驻轮询不值得。
     */
    var divergent by divergentState
    LaunchedEffect(pager.anchor, activeSeg, snap.state, speechLoaded, followSpeech, curPages, driver.isDragging, driver.isSettling) {
        if (!speechLoaded) {
            divergent = false
            return@LaunchedEffect
        }
        val div = if (pager.anchor.chapter != snap.chapterIndex) true
        else {
            val pg = curPages
            val seg = activeSeg
            if (pg == null || seg == null || seg.end <= seg.start) false
            else {
                // 暂停不特判：playbackFraction 在暂停时保持原值（句柄还在），
                // 取段首反而会把跨页片段的暂停判成偏离一页；无句柄时它为 0，自然退化到段首
                val audible = (seg.start + ((seg.end - seg.start) * engine.playbackFraction()).toInt())
                    .coerceIn(seg.start, seg.end - 1)
                pager.anchor.page != pg.pageOf(audible)
            }
        }
        divergent = div
        /*
         * 自动复挂：用户**自己翻回**可听页时，跟随开关自动合上。
         * 没有这一条，手动翻回去之后自动翻页依然是死的（旧版正是如此 ——
         * 闩只有点标签或点读才解，看起来就是「自动翻页随机失灵」）。
         * 翻到语音尚未念到的页（跨页片段的尾页）：divergent=true → 不复挂 → 跟随不会把
         * 刚翻到的页拽回去（实测复现过被拽回），标签如实提示「语音还没到这页」。
         * 手指还按着时不复挂，松手落定后生效。
         */
        /*
         * 复挂必须等**结算落地**（!isSettling），不只是手指抬起（!isDragging）。
         * 松手到动画到达之间有约 300ms 的窗口：anchor 还停在旧页（commit 在到达时才发生），
         * 「人在可听页」在这个窗口里恒成立 —— 在此复挂，落地后 divergent 变真，
         * 跟随效应会立刻把用户刚翻到的页**拉回去**，翻页看起来就像失灵。
         * （EchoAnchor 日志钉死过这条时序：settle→re-attach→commit→divergent→拉回。）
         */
        if (!followSpeech && !div && !driver.isDragging && !driver.isSettling) followSpeech = true
    }

    /**
     * 跟随效应 —— 视图定位的**唯一写者**。
     *
     * 「回到朗读位置」的点击处理器原来还有一套自己的手工跳转（读一次 engine.current、
     * 查一次 window、自己 launch pager.follow），与这里双写同一目标。它有两个死分支：
     * 引擎恰在换章（chapterIndex = -1）或章面尚未排版时**静默什么都不做**，而 follow 闩
     * 已被清掉 —— 标签消失、画面不动，最长要等下一个片段边界（约 20 秒）才被这里救回。
     * 现在点击只翻 followSpeech 开关；开关一变这里必然收到发射，收不到条件就等下一次
     * 状态变化再收 —— 流驱动天然自愈，死分支不存在了。
     *
     * 与旧版的三处语义差异：
     * ① 允许 PAUSED：暂停时点「回到朗读位置」也要能定位（旧版只在 PLAYING 生效）；
     * ② 跨页片段（一段话横跨两页）期间，视图停在片段覆盖的任何一页都算「在位」，
     *    不再把视图拽回片段首页 —— 否则暂停瞬间会倒翻一页（自动翻页已按可听进度翻过去了）；
     * ③ 定位目标取**正在念的字**（segmentStart + 片段长 × 播放进度），不是片段首字 ——
     *    「回到朗读位置」应该落在声音所在的页。
     */
    LaunchedEffect(bookId) {
        snapshotFlow { FollowKey(followSpeech, snap.bookId, snap.state, snap.chapterIndex, snap.segmentStart) }
            .distinctUntilChanged()
            .collectLatest { k ->
                if (awaitEngineChapter >= 0) {
                    val reached = k.book == bookId && k.chapter == awaitEngineChapter
                    // 引擎回到空闲/报错说明这次装载没成功，锁必须解开，否则跟随永久失效
                    val aborted = k.state == PlayerState.IDLE || k.state == PlayerState.ERROR
                    if (reached || aborted) awaitEngineChapter = -1 else return@collectLatest
                }
                if (!k.follow || k.book != bookId) return@collectLatest
                if ((k.state != PlayerState.PLAYING && k.state != PlayerState.PAUSED) || k.chapter < 0) return@collectLatest
                val pg = window.pagesOf(k.chapter)
                if (k.chapter != pager.anchor.chapter || pg == null) {
                    if (pendingChapter() != k.chapter) requestChapter(k.chapter, k.start)
                    return@collectLatest
                }
                val segEnd = snap.segmentEnd
                // 同 divergence：暂停时 playbackFraction 保持原值，不特判（取段首会倒定位一页）
                val audible = if (segEnd > k.start) {
                    (k.start + ((segEnd - k.start) * engine.playbackFraction()).toInt()).coerceIn(k.start, segEnd - 1)
                } else k.start
                val target = PageRef(k.chapter, pg.pageOf(audible).coerceIn(0, pg.pageCount - 1))
                if (target != pager.anchor) preemptable { pager.follow(target) }
            }
    }

    // 片段跨页：按播放进度估算念到的字位，越过下页首字即自动翻页（不等下一句）
    LaunchedEffect(activeSeg, pager.anchor, playing, followSpeech, curPages) {
        val seg = activeSeg ?: return@LaunchedEffect
        val pg = curPages ?: return@LaunchedEffect
        if (!playing || !followSpeech || engineChapter != pager.anchor.chapter) return@LaunchedEffect
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

    var lastManualSave by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pager.anchor, curPages, playing) {
        val pg = curPages ?: return@LaunchedEffect
        if (playing) return@LaunchedEffect
        delay(600)
        val now = System.currentTimeMillis()
        if (now - lastManualSave < 1500) return@LaunchedEffect
        lastManualSave = now
        graph.library.saveProgress(bookId, pager.anchor.chapter, pg.pageStartOffset(pager.anchor.page.coerceIn(0, pg.pageCount - 1)))
    }
}
