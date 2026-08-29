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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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

enum class ToastKind { INFO, ERROR, SUCCESS }

class ToastItem(val id: Long, val text: String, val kind: ToastKind) {
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

    fun show(text: String, kind: ToastKind = ToastKind.INFO, durationMs: Long = 2600) {
        handler.post {
            // 同文案合并：只续期，不新增（连点倍速/重复报错时不再刷屏）
            val live = items.lastOrNull { it.visible.targetState }
            if (live != null && live.text == text && live.kind == kind) {
                schedule(live, durationMs)
                return@post
            }
            while (items.count { it.visible.targetState } >= MAX) {
                val oldest = items.firstOrNull { it.visible.targetState } ?: break
                beginDismiss(oldest)
            }
            val item = ToastItem(++seq, text, kind)
            items.add(item)
            schedule(item, durationMs)
        }
    }

    fun error(text: String, durationMs: Long = 4000) = show(text, ToastKind.ERROR, durationMs)
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
                        ToastPill(t.text, kindColor(t.kind, c))
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
private fun ToastPill(text: String, color: Color) {
    val c = echo
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(max = 340.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(c.card, RoundedCornerShape(18.dp))
            .border(1.dp, c.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 11.dp)
    )
}

private fun kindColor(kind: ToastKind, c: EchoColors): Color = when (kind) {
    ToastKind.ERROR -> c.danger
    ToastKind.SUCCESS -> if (c.isDark) Color(0xFF6EE7B7) else Color(0xFF0F9D6E)
    ToastKind.INFO -> c.text
}
