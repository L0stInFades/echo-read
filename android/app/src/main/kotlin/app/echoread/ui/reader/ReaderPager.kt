package app.echoread.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.Thr
import app.echoread.ui.motion.preemptable
import app.echoread.ui.motion.settleTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class PageRef(val chapter: Int, val page: Int)

/**
 * 章节窗口：当前章及其邻章的排版结果（最多 3 章驻留）。
 * 有了邻章的排版结果，「翻过章尾」与「章内翻页」在渲染上完全同构 —— 这正是消灭「切章先空白」的关键。
 */
@Stable
class ChapterWindow {
    private val laid = mutableStateMapOf<Int, ChapterPages>()
    private val specs = HashMap<Int, LayoutSpec>()

    /** 全书章数，边界判定用 */
    var chapterCount by mutableIntStateOf(0)

    fun pagesOf(chapter: Int): ChapterPages? = laid[chapter]
    fun specOf(chapter: Int): LayoutSpec? = specs[chapter]

    fun put(chapter: Int, pages: ChapterPages, spec: LayoutSpec) {
        specs[chapter] = spec
        laid[chapter] = pages
    }

    /** 只保留 center±1，控制住 3 份整章 TextLayoutResult 的内存 */
    fun retain(center: Int) {
        val keep = setOf(center - 1, center, center + 1)
        laid.keys.filter { it !in keep }.forEach { laid.remove(it); specs.remove(it) }
        specs.keys.filter { it !in keep }.toList().forEach { specs.remove(it) }
    }

    /** (anchor, ±1) → 实际页；跨章时查邻章的排版结果，未就绪返回 null（由橡皮筋给出物理反馈） */
    fun resolve(a: PageRef, delta: Int): PageRef? {
        val cur = laid[a.chapter] ?: return null
        if (delta == 0) return PageRef(a.chapter, a.page.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0)))
        val p = a.page + delta
        return when {
            p in 0 until cur.pageCount -> PageRef(a.chapter, p)
            p >= cur.pageCount -> laid[a.chapter + 1]?.let { PageRef(a.chapter + 1, 0) }
            else -> laid[a.chapter - 1]?.let { PageRef(a.chapter - 1, (it.pageCount - 1).coerceAtLeast(0)) }
        }
    }
}

/**
 * 翻页器：模型层（[anchor]）与呈现层（`driver.value ∈ [-1,1]`）分离。
 *
 * - 手指位移直写 `driver`，1:1 跟手；松手按「逃逸速度 → 投影落点」判定，可打断、可反向。
 * - 到位后用 [MotionDriver.rebase] 原子换页：任何一帧都看不到「anchor 已变、位移未变」的中间态。
 * - 页码 / 章节名 / 高亮全部读 [displayed]（呈现派生，越过 50% 才翻转），不再与画面不一致。
 */
@Stable
class ReaderPager(
    val driver: MotionDriver,
    val window: ChapterWindow,
    private val scope: CoroutineScope,
) {
    /** 模型层基准页：只在 settle 完成或程序化提交时改变 */
    var anchor: PageRef by mutableStateOf(PageRef(0, 0))
        private set

    /** 视觉主导页：哪一页占据了大半屏幕，页码就是哪一页 */
    val displayed: State<PageRef> = derivedStateOf {
        val slot = driver.value.roundToInt().coerceIn(-1, 1)
        if (slot == 0) anchor else window.resolve(anchor, slot) ?: anchor
    }

    /** 「从无到有」的交叉淡入进度（1 = 新页完全显示）；只在 graphicsLayer 里读 */
    val fade = Animatable(1f, Thr.ALPHA)

    /** 淡出中的旧页（跨章 / 重排版时保留，绝不留白） */
    var outgoing: Pair<ChapterPages, Int>? by mutableStateOf(null)
        private set

    /** 手动翻页 / 开始拖动：播放跟随状态机据此转 DETACHED */
    var onManual: () -> Unit = {}

    /** 到边界（书首/书尾，或邻章尚未排好）时的回调 */
    var onBlocked: (Int) -> Unit = {}

    /**
     * 最近一次页面变化是否由用户驱动（手指 / 点按）。触觉反馈只对用户驱动的翻页发声：
     * 引擎跟随的自动翻页每几分钟一次，听书几小时下来不该一直震。
     */
    var userDriven: Boolean = false
        private set

    /** 手势开始：标记用户驱动并通知跟随状态机 */
    fun beginManual() {
        userDriven = true
        onManual()
    }

    fun bounds(): ClosedFloatingPointRange<Float> {
        val back = if (window.resolve(anchor, -1) != null) -1f else 0f
        val fwd = if (window.resolve(anchor, 1) != null) 1f else 0f
        return back..fwd
    }

    private fun candidates(): List<Float> = buildList {
        if (window.resolve(anchor, -1) != null) add(-1f)
        add(0f)
        if (window.resolve(anchor, 1) != null) add(1f)
    }

    private fun commit(delta: Int) {
        // 目标页在动画途中消失（罕见）时也必须 rebase：否则呈现值停在 ±1、锚点没动，屏幕上就是一片空白
        val next = window.resolve(anchor, delta)
        driver.rebase(delta.toFloat()) { if (next != null) anchor = next }
    }

    /** 松手：位置 + 速度共同决定落点，到位后原子换页 */
    fun settle(velocityPxPerSec: Float) {
        scope.launch {
            // 落点在互斥区内算：拿到锁之前 anchor 可能已被程序化 jumpTo 改过，
            // 用锁外的旧值判定会让 commit 相对新锚点多翻一页
            var chosen = 0f
            preemptable {
                driver.animateToBy(
                    velocityPxPerSec = velocityPxPerSec,
                    spec = EchoMotion.Track.float(),
                    onArrive = { if (chosen != 0f) commit(chosen.roundToInt()) },
                ) {
                    val unit = if (driver.unitPx > 0f) driver.unitPx else 1f
                    chosen = settleTarget(driver.value, velocityPxPerSec / unit, candidates())
                    chosen
                }
            }
        }
    }

    /** 点按翻页：与滑动走同一条通道，因此视觉完全一致，且可被下一次点按或手指打断 */
    fun flip(delta: Int) {
        beginManual()
        scope.launch {
            var blocked = 0
            preemptable {
                driver.animateToBy(
                    spec = EchoMotion.Standard.float(),
                    onArrive = { commit(delta) },
                ) {
                    // 连点：上一段动画已过半就先承认它翻过去了，避免目标堆叠出「三层内容」
                    val v = driver.value
                    if (abs(v) >= 0.5f) commit(if (v > 0f) 1 else -1)
                    if (window.resolve(anchor, delta) == null) {
                        blocked = delta
                        null
                    } else {
                        delta.toFloat()
                    }
                }
            }
            if (blocked != 0) onBlocked(blocked)
        }
    }

    /** 引擎跟随：相邻页走动画（手指可随时抢占），远跳/跨章交叉淡入 */
    suspend fun follow(target: PageRef) {
        if (target == anchor) return
        userDriven = false
        // -2 = 还没进到互斥区（被更高优先级挡下），此时什么都不该做
        var slot = -2
        preemptable {
            driver.animateToBy(
                spec = EchoMotion.Standard.float(),
                onArrive = { if (slot != 0) commit(slot) },
            ) {
                val a = anchor
                slot = when (target) {
                    a -> 0
                    window.resolve(a, 1) -> 1
                    window.resolve(a, -1) -> -1
                    else -> 0
                }
                if (slot == 0) null else slot.toFloat()
            }
        }
        // 远跳 / 跨章：不位移，交叉淡入
        if (slot == 0 && target != anchor) jumpTo(target)
    }

    /**
     * 直接落位到 [ref]：旧页留在屏幕上淡出、新页淡入（`Dur.Medium` 线性），永不出现空白或居中转圈。
     * [commitModel] 与 anchor 的写入在同一次 snapshot 内完成（例如同时把新排好的章塞进窗口）。
     */
    suspend fun jumpTo(ref: PageRef, crossFade: Boolean = true, commitModel: () -> Unit = {}) {
        userDriven = false
        val old = if (crossFade) window.pagesOf(anchor.chapter)?.let { it to anchor.page } else null
        if (old == null) {
            driver.snapTo(0f) { commitModel(); anchor = ref }
            fade.snapTo(1f)
            outgoing = null
            return
        }
        // try 必须罩住从「转录旧页」到淡入结束的全过程：fade.snapTo / driver.snapTo 都是真的挂起点
        // （要抢互斥锁），在中间被 collectLatest 取消会留下「新页 alpha 0 + 旧页 alpha 1」的死状态。
        try {
            // 先把旧页转录成 outgoing 并把新页压到 alpha 0，再换 anchor —— 中间不存在露白的一帧
            fade.snapTo(0f)
            outgoing = old
            driver.snapTo(0f) {
                commitModel()
                anchor = ref
            }
            fade.animateTo(1f, tween(Dur.Medium, easing = Ease.Linear))
        } finally {
            // 呈现侧用 `outgoing != null` 决定要不要读 fade，所以清空它就等于把新页拉回 alpha 1
            outgoing = null
        }
    }
}
