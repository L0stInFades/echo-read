package app.echoread.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.core.PlayerState
import app.echoread.core.i18n.S
import app.echoread.tts.EngineFailure
import app.echoread.tts.EngineSnapshot
import app.echoread.tts.PlayerController
import app.echoread.tts.SleepMode
import app.echoread.ui.EchoSegmented
import app.echoread.ui.ErrorDetails
import app.echoread.ui.ReaderTheme
import app.echoread.ui.SegmentItem
import app.echoread.ui.dangerColor
import app.echoread.ui.glass.Backdrop
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import app.echoread.ui.warningColor

/*
 * 悬浮在页面区底部的两块：状态药丸与睡眠定时面板。都不参与布局 —— 位置由调用方给（对齐到页面区底部），
 * 它们出现、消失都不会改变正文画布的尺寸。
 */

// getter 而非普通 val：文案要随界面语言重新求值（切换语言只重建 Activity，不重启进程）。
// SleepMode 是 data class / data object，每次新建的实例与已选中的模式照样按值相等。
private val SLEEP_OPTIONS: List<Pair<String, SleepMode>> get() = listOf(
    S.reader.sleep15 to SleepMode.Minutes(15), S.reader.sleep30 to SleepMode.Minutes(30), S.reader.sleep60 to SleepMode.Minutes(60),
    S.reader.sleep90 to SleepMode.Minutes(90), S.reader.sleepEndOfChapter to SleepMode.Chapter, S.reader.off to SleepMode.Off
)

/**
 * 状态药丸：错误 / 重试 / 合成中 / 回到朗读位置，没有要说的时候收起来。
 * 它读引擎快照 —— 快照每段都要变好几次，变化只重组这一枚药丸。
 *
 * @param isReturnable 视图偏离了朗读位置、且没在跟随：此时药丸可点，点了回到朗读位置（[onReturn]）。
 *   在这里调用（读到的状态记在药丸自己身上），阅读页不必为它重组
 */
@Composable
internal fun ReaderStatusPill(
    bookId: String,
    snapshot: State<EngineSnapshot>,
    isReturnable: () -> Boolean,
    theme: ReaderTheme,
    glass: GlassStyle,
    backdrop: Backdrop?,
    onReturn: () -> Unit,
    modifier: Modifier,
) {
    val snap = snapshot.value
    val failure = snap.failure?.takeIf { snap.bookId == bookId }
    val retryNote = snap.retryNote.takeIf { it.isNotEmpty() && snap.bookId == bookId }
    val busySynth = (snap.synthesizing || snap.state == PlayerState.LOADING) && snap.bookId == bookId
    val returnable = isReturnable()
    val showStatus = failure != null || retryNote != null || busySynth || returnable
    AnimatedVisibility(
        visible = showStatus,
        enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut,
        modifier = modifier
    ) {
        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .glassShadow(CircleShape, Color.Black.copy(alpha = if (theme.isDark) 0.4f else 0.16f), blur = 14.dp, dy = 4.dp)
                .glass(CircleShape, glass, backdrop)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusText = when {
                // 失败最优先：它是用户此刻唯一需要知道的事，且必定带状态码
                failure != null -> failure.headline()
                retryNote != null -> retryNote
                // 「回到朗读位置」压过「正在合成…」：后者是环境噪声，前者是用户的待办动作。
                // 反过来排的话（实测踩过）：系统 TTS 每段要合成 1~2 秒，播放中约一半时间
                // 状态行被合成提示占着且不可点 —— 返回入口一闪一闪、时点时不点。
                returnable -> S.reader.backToPlaybackButton
                busySynth -> S.common.generating
                else -> ""
            }
            Text(
                statusText,
                color = when {
                    failure != null -> if (failure is EngineFailure.SkippedSegments) warningColor(theme.isDark) else dangerColor(theme.isDark)
                    retryNote != null -> warningColor(theme.isDark)
                    returnable -> theme.accent
                    else -> theme.text
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    // 先给「详情」留出位置，长文案自己省略：英文的失败标题比一行宽，不让的话「详情」被挤成一竖条、点不到
                    .weight(1f, fill = false)
                    .widthIn(max = 300.dp)
                    .then(
                        if (returnable && failure == null && retryNote == null)
                            Modifier.echoPress(pressedScale = PressScale.Chip, onClickLabel = S.reader.backToPlaybackPosition, onClick = onReturn)
                        else Modifier
                    )
            )
            val detail = failure?.net ?: snap.retry?.error?.takeIf { snap.bookId == bookId }
            if (detail != null) {
                Text(
                    S.common.details,
                    color = theme.accent,
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .echoPress(pressedScale = PressScale.Chip) { ErrorDetails.show(detail) }
                )
            }
        }
    }
}

/** 睡眠定时的选项面板：选了就关（[onPicked]） */
@Composable
internal fun SleepTimerOverlay(
    visible: Boolean,
    player: PlayerController,
    theme: ReaderTheme,
    glass: GlassStyle,
    backdrop: Backdrop?,
    onPicked: () -> Unit,
    modifier: Modifier,
) {
    AnimatedVisibility(visible = visible, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut, modifier = modifier) {
        val sleepMode by player.sleepMode.collectAsState()
        Column(
            Modifier
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 22.dp)
                .widthIn(max = 380.dp)
                .glassShadow(RoundedCornerShape(26.dp), Color.Black.copy(alpha = if (theme.isDark) 0.45f else 0.18f), blur = 20.dp, dy = 6.dp)
                .glass(RoundedCornerShape(26.dp), glass, backdrop)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 六个选项是单选而非筛选，用分段控件。拆成两排各三个：一排六个在窄屏上放不下。
            // 配色显式传阅读主题色 —— 默认取的是 app 配色，
            // 浅色系统 + 暗夜阅读主题时会在深色面板上画出浅色容器。
            val sleepRows = listOf(SLEEP_OPTIONS.take(3), SLEEP_OPTIONS.drop(3))
            for (row in sleepRows) {
                EchoSegmented(
                    items = row.map { SegmentItem(it.first) },
                    selectedIndex = row.indexOfFirst { it.second == sleepMode },
                    containerColor = theme.text.copy(alpha = 0.08f),
                    contentColor = theme.text.copy(alpha = 0.8f),
                    checkedContainerColor = if (theme.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f),
                    checkedContentColor = theme.text,
                    height = 42.dp
                ) { i ->
                    player.setSleepTimer(row[i].second)
                    onPicked()
                }
            }
        }
    }
}
