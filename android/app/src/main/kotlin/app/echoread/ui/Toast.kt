package app.echoread.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.motion.EchoTransitions

enum class ToastKind { INFO, ERROR, SUCCESS }

class ToastItem(val id: Long, val text: String, val kind: ToastKind)

/** 全局轻提示（顶部胶囊，Harmony 风格） */
object Toaster {
    val items = mutableStateListOf<ToastItem>()
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0L

    fun show(text: String, kind: ToastKind = ToastKind.INFO, durationMs: Long = 2600) {
        handler.post {
            val item = ToastItem(++seq, text, kind)
            items.add(item)
            handler.postDelayed({ items.remove(item) }, durationMs)
        }
    }

    fun error(text: String, durationMs: Long = 4000) = show(text, ToastKind.ERROR, durationMs)
    fun success(text: String) = show(text, ToastKind.SUCCESS)
}

@Composable
fun ToastHost() {
    val c = echo
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Toaster.items) {
                AnimatedVisibility(visible = true, enter = EchoTransitions.riseIn, exit = EchoTransitions.sinkOut) {
                    Text(
                        t.text,
                        color = when (t.kind) {
                            ToastKind.ERROR -> c.danger
                            ToastKind.SUCCESS -> if (c.isDark) androidx.compose.ui.graphics.Color(0xFF6EE7B7) else androidx.compose.ui.graphics.Color(0xFF0F9D6E)
                            ToastKind.INFO -> c.text
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .shadow(18.dp, RoundedCornerShape(18.dp), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                            .background(c.card, RoundedCornerShape(18.dp))
                            .border(1.dp, c.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
}
