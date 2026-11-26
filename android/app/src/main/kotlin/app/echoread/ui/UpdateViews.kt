package app.echoread.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.AppGraph
import app.echoread.core.ReleaseNote
import app.echoread.core.ReleaseNotes
import app.echoread.core.i18n.S
import app.echoread.data.UpdateInfo
import app.echoread.data.UpdateState
import app.echoread.data.offered
import app.echoread.ui.glass.glassSurface
import app.echoread.ui.motion.EchoTransitions
import kotlinx.coroutines.launch

/*
 * 应用内更新的两处界面：书架顶上的卡片，与完整的「更新说明」面板。
 *
 * 0.7.1 及以前，卡片把整段说明塞进四行小字：头一行还是发布脚本给老客户端垫的实验版提示
 * （连同卡片上的标记、底部的警告，同一句话说了三遍），真正的改动露出一行半就被省略号截断，而且哪里都看不到全文。
 * 现在说明拆成一条一条（见 ReleaseNotes）：卡片只露前两条，全文在面板里，实验版的提示只说一遍。
 */

/** 卡片上露出几条说明 */
private const val TEASER_NOTES = 2

/** 书架顶上的更新卡片：没有新版本时什么都不画（零高） */
@Composable
fun UpdateCard(graph: AppGraph, onShowNotes: () -> Unit) {
    val c = echo
    val live by graph.updater.state.collectAsState()
    // 收起动画期间状态已经回到 Idle：用最后一次摆出来的状态画完（标题、按钮都不在收起途中变）
    val last = remember { arrayOfNulls<UpdateState>(1) }
    if (live.offered != null) last[0] = live
    AnimatedVisibility(live.offered != null, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
        val s = if (live.offered != null) live else last[0] ?: return@AnimatedVisibility
        val info = s.offered ?: return@AnimatedVisibility
        val notes = remember(info) { ReleaseNotes.parse(info.displayNotes) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .glassSurface(RoundedCornerShape(Radius.lgPlus), c.surfaceGlass())
                .padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(c.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(EchoIcons.Download, null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // 放不下时标记换到下一行，而不是把版本号截成省略号（英文的标题和标记都长）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(headline(s, info), color = c.text, style = MaterialTheme.typography.titleSmall)
                        if (info.experimental) Badge(S.shelf.experimental, color = warningColor(c.isDark))
                    }
                    Text(
                        if (s is UpdateState.Error) s.message else S.shelf.currentVersionShort(graph.updater.currentVersionName),
                        color = if (s is UpdateState.Error) c.danger else c.text3,
                        style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                // 叉掉 = 这一版不再提醒（手动「检查更新」仍能找到它）。下载途中不给叉
                if (s is UpdateState.Downloading) Spacer(Modifier.width(10.dp))
                else IconButtonEcho(EchoIcons.Close, S.shelf.skipThisVersion, size = 30.dp, iconSize = 15.dp, tint = c.text3, touchSize = 44.dp) { graph.updater.dismiss() }
            }
            if (notes.isNotEmpty()) {
                Column(Modifier.padding(top = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    notes.take(TEASER_NOTES).forEach { NoteRow(it, compact = true) }
                }
            }
            Row(Modifier.padding(top = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (notes.isNotEmpty()) TextAction(S.shelf.whatsNew, icon = EchoIcons.ChevronRight, onClick = onShowNotes)
                Spacer(Modifier.weight(1f))
                UpdateAction(s, graph)
            }
        }
    }
}

/** 完整的更新说明：一条一条列出来，底下是与卡片同一个动作 */
@Composable
fun BoxScope.UpdateNotesSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val c = echo
    val live by graph.updater.state.collectAsState()
    val offered = live.offered
    val last = remember { arrayOfNulls<UpdateState>(1) }
    if (offered != null) last[0] = live
    // 这一版被忽略了：面板跟着收起（收起途中照旧画最后那个状态）
    LaunchedEffect(offered == null) { if (offered == null) onClose() }
    val state = if (offered != null) live else last[0]
    val info = state?.offered
    EchoSheet(open = open && offered != null, onDismiss = onClose, title = S.shelf.whatsNewIn(info?.versionName.orEmpty())) {
        if (state == null || info == null) return@EchoSheet
        val notes = remember(info) { ReleaseNotes.parse(info.displayNotes) }
        Text(
            S.shelf.currentVersionShort(graph.updater.currentVersionName), color = c.text3,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp)
        )
        if (info.experimental) {
            val warn = warningColor(c.isDark)
            Row(
                Modifier.fillMaxWidth().background(warn.copy(alpha = 0.10f), RoundedCornerShape(Radius.md)).padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(EchoIcons.Warning, null, tint = warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(S.shelf.experimentalWarning, color = c.text2, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            notes.forEach { NoteRow(it, compact = false) }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state !is UpdateState.Downloading) TextAction(S.shelf.skipThisVersion, color = c.text2) { graph.updater.dismiss() }
            Spacer(Modifier.weight(1f))
            UpdateAction(state, graph)
        }
    }
}

private fun headline(s: UpdateState, info: UpdateInfo): String = when (s) {
    is UpdateState.Downloading ->
        if (s.progress <= 0f) S.shelf.connectingToDownloadServer else S.shelf.downloadingUpdate(info.versionName, (s.progress * 100).toInt())
    is UpdateState.Ready -> S.shelf.updateDownloaded(info.versionName)
    is UpdateState.Error -> S.shelf.updateFailed
    else -> S.shelf.updateAvailable(info.versionName)
}

/** 一条改动：前面一粒圆点，小标题加粗接在正文前。[compact] 是卡片上的单行摘要 */
@Composable
private fun NoteRow(note: ReleaseNote, compact: Boolean) {
    val c = echo
    val style = if (compact) MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
    else MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    // 圆点对齐第一行的中线
    val dot = 5.dp
    val dotTop = with(LocalDensity.current) { ((style.lineHeight.toPx() - dot.toPx()) / 2f).toDp() }
    val text = remember(note, c.text) {
        buildAnnotatedString {
            note.title?.let { t ->
                withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) {
                    append(t)
                    append(if (t.last().code >= 0x2E80) "：" else ": ")
                }
            }
            append(note.body)
        }
    }
    Row {
        Box(Modifier.padding(top = dotTop).size(dot).background(c.accent.copy(alpha = 0.7f), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = c.text2, style = style, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

/** 卡片与面板共用的那一个动作：立即更新 → 下载进度 → 安装；失败了重试 */
@Composable
private fun UpdateAction(state: UpdateState, graph: AppGraph) {
    val c = echo
    val context = LocalContext.current
    // 下载放在应用级的作用域里：卡片滚出书架、面板关上，都不该把下载掐掉
    val download = { info: UpdateInfo -> graph.mainScope.launch { graph.updater.download(info) }; Unit }
    when (state) {
        is UpdateState.Available -> GradientButton(S.shelf.updateNow, height = 38.dp, fontSize = 13) { download(state.info) }
        is UpdateState.Downloading ->
            if (state.progress <= 0f) LoadingIndicator(Modifier.size(34.dp), color = c.accent)
            else LoadingIndicator(progress = { state.progress }, modifier = Modifier.size(34.dp), color = c.accent)
        is UpdateState.Ready -> GradientButton(S.shelf.install, height = 38.dp, fontSize = 13) {
            if (!graph.updater.install(context, state.file)) Toaster.show(S.shelf.allowInstallHint, durationMs = 4000)
        }
        is UpdateState.Error -> state.info?.let { info -> GradientButton(S.shelf.retry, height = 38.dp, fontSize = 13) { download(info) } }
        else -> {}
    }
}
