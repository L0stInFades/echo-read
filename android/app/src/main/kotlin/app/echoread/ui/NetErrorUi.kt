package app.echoread.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.echoread.core.net.NetError
import app.echoread.core.net.StatusClass

/**
 * 全局错误详情入口。做成单例宿主（与 [Toaster] 同构）而非各屏各自持有状态：
 * 报错可能来自设置面板、阅读器 dock、书架任意一处，它们不共享 BoxScope。
 */
object ErrorDetails {
    var current by mutableStateOf<NetError?>(null)
        private set

    fun show(e: NetError?) {
        if (e != null) current = e
    }

    fun dismiss() {
        current = null
    }

    /** 生成一个带「详情」行动点的错误提示 */
    fun toast(e: NetError) {
        Toaster.error(e.headline(), action = ToastAction("详情") { show(e) })
    }
}

/** 挂在组合根（App.kt）里一次即可 */
@Composable
fun BoxScope.ErrorDetailHost() {
    ErrorDetailSheet(ErrorDetails.current) { ErrorDetails.dismiss() }
}

/**
 * 错误详情面板：把 [NetError] 里所有结构化字段摊开，并提供一键复制。
 *
 * 存在的理由很直接 —— 0.1.x 里应用**没有任何**地方能看到状态码：
 * 一次 500 在界面上的全部表现是「轻点正文任意字开始朗读」，而后台已经打了 49 次请求。
 */
@Composable
fun BoxScope.ErrorDetailSheet(error: NetError?, onDismiss: () -> Unit) {
    val c = echo
    val context = LocalContext.current
    // 关闭动画期间 error 会先变 null，留一份最后值，否则面板会在滑出时突然空掉
    var last by remember { mutableStateOf<NetError?>(null) }
    if (error != null) last = error
    val e = error ?: last

    EchoSheet(open = error != null, onDismiss = onDismiss, title = "错误详情", maxHeightFraction = 0.8f) {
        if (e == null) return@EchoSheet

        StatusBadgeRow(e)
        Spacer(Modifier.height(14.dp))

        Text(e.headline(), color = c.text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(e.advice(), color = c.text2, style = MaterialTheme.typography.bodyMedium)

        e.providerMessage?.takeIf { it.isNotBlank() }?.let { msg ->
            Spacer(Modifier.height(16.dp))
            SectionLabel("服务商原话")
            Text(msg, color = c.text, style = MaterialTheme.typography.bodyMedium)
            e.providerCode?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text("代码：$it", color = c.text3, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("请求")
        KeyValue("端点", if (e.endpoint.isEmpty()) "—" else "${e.method} ${e.endpoint}")
        e.model?.takeIf { it.isNotBlank() }?.let { KeyValue("模型", it) }
        e.transport?.let { KeyValue("传输层", it) }
        if (e.maxAttempts > 1) KeyValue("尝试", "第 ${e.attempt} / ${e.maxAttempts} 次")
        if (e.elapsedMs > 0) KeyValue("耗时", "${e.elapsedMs} ms")
        e.retryAfterSec?.let { KeyValue("Retry-After", "${it} 秒") }

        e.bodySnippet?.takeIf { it.isNotBlank() }?.let { snippet ->
            Spacer(Modifier.height(16.dp))
            SectionLabel("响应体片段")
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .background(c.card, RoundedCornerShape(Radius.sm))
                    .border(1.dp, c.border, RoundedCornerShape(Radius.sm))
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(snippet, color = c.text2, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("复制诊断信息", Modifier.weight(1f)) {
                copyToClipboard(context, e.detail())
                Toaster.success("已复制")
            }
            GradientButton("知道了", Modifier.weight(1f)) { onDismiss() }
        }
    }
}

/** 4xx / 5xx / 无响应 —— 用户最想一眼看到的那件事 */
@Composable
private fun StatusBadgeRow(e: NetError) {
    val c = echo
    val tint = when (e.statusClass) {
        StatusClass.CLIENT -> warningColor(c.isDark)   // 4xx：你这边要改配置
        StatusClass.SERVER -> c.danger                 // 5xx：对方的问题
        StatusClass.NONE -> c.text3
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            e.badge(),
            color = tint,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(tint.copy(alpha = 0.14f), RoundedCornerShape(Radius.sm))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        Text(e.statusClassLabel(), color = tint, style = MaterialTheme.typography.labelLarge)
        Text("·", color = c.text3)
        Text(e.categoryLabel(), color = c.text2, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    val c = echo
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = c.text3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
        Text(v, color = c.text2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Lector 诊断", text))
    }
}
