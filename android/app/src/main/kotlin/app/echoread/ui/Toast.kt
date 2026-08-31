package app.echoread.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress

enum class ToastKind { INFO, ERROR, SUCCESS }

/** toast 上的行动点。没有它，用户注意到问题的那一刻就没有任何可点的东西 */
class ToastAction(val label: String, val onClick: () -> Unit)

class ToastItem(val id: Long, val text: String, val kind: ToastKind, val action: ToastAction? = null) {
    /** 用 MutableTransitionState 而不是常量 `visible = true`：进场与退场都真的会播 */
    val visible = MutableTransitionState(false).apply { targetState = true }
    internal var dismissTask: Runnable? = null
}

/** 全局轻提示（顶部胶囊，Harmony 风格） */
object Toaster {
    /** 同屏上限：引擎自愈退避会连发 error，旧实现能堆满一屏 */
    private const val MAX = 3

    val items = mutableStateListOf<ToastItem>()
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0L

    fun show(text: String, kind: ToastKind = ToastKind.INFO, durationMs: Long = 2600, action: ToastAction? = null) {
        handler.post {
            // 同文案合并：只续期，不新增（连点倍速/重复报错时不再刷屏）。
            // 带行动点的提示不参与合并 —— 「又一次 429」必须看得见，不能被上一条同文案吞掉。
            val live = items.lastOrNull { it.visible.targetState }
            if (action == null && live != null && live.text == text && live.kind == kind && live.action == null) {
                schedule(live, durationMs)
                return@post
            }
            while (items.count { it.visible.targetState } >= MAX) {
                val oldest = items.firstOrNull { it.visible.targetState } ?: break
                beginDismiss(oldest)
            }
            val item = ToastItem(++seq, text, kind, action)
            items.add(item)
            schedule(item, durationMs)
        }
    }

    /** 带行动点时默认停留 8s：用户要先读懂状态码，再决定点不点「详情」 */
    fun error(text: String, durationMs: Long = -1L, action: ToastAction? = null) =
        show(text, ToastKind.ERROR, if (durationMs > 0) durationMs else if (action != null) 8000L else 4000L, action)

    fun success(text: String) = show(text, ToastKind.SUCCESS)

    private fun schedule(item: ToastItem, durationMs: Long) {
        item.dismissTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { beginDismiss(item) }
        item.dismissTask = task
        handler.postDelayed(task, durationMs)
    }

    /** 只是把目标态改成「隐藏」；真正摘除由 ToastHost 在退场动画播完后做 */
    private fun beginDismiss(item: ToastItem) {
        item.dismissTask?.let { handler.removeCallbacks(it) }
        item.dismissTask = null
        item.visible.targetState = false
    }

    internal fun remove(item: ToastItem) {
        items.remove(item)
    }
}

@Composable
fun ToastHost() {
    val c = echo
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Toaster.items) {
                key(t.id) {
                    AnimatedVisibility(visibleState = t.visible, enter = EchoTransitions.riseIn, exit = EchoTransitions.sinkOut) {
                        ToastPill(t.text, kindColor(t.kind, c), t.action)
                    }
                    // 退场播完才真正摘除：旧实现直接从列表里删，exit 永远没机会播（「闪一下就没」）
                    LaunchedEffect(t.visible.currentState, t.visible.targetState) {
                        if (t.visible.isIdle && !t.visible.targetState) Toaster.remove(t)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastPill(text: String, color: Color, action: ToastAction? = null) {
    val c = echo
    Row(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(c.card, RoundedCornerShape(18.dp))
            .border(1.dp, c.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = if (action == null) TextAlign.Center else TextAlign.Start,
            modifier = if (action == null) Modifier else Modifier.weight(1f, fill = false)
        )
        if (action != null) {
            Text(
                action.label,
                color = c.accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 14.dp)
                    .echoPress(pressedScale = PressScale.Chip) { action.onClick() }
            )
        }
    }
}

private fun kindColor(kind: ToastKind, c: EchoColors): Color = when (kind) {
    ToastKind.ERROR -> c.danger
    ToastKind.SUCCESS -> if (c.isDark) Color(0xFF6EE7B7) else Color(0xFF0F9D6E)
    ToastKind.INFO -> c.text
}
