package app.echoread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.PlayerState
import app.echoread.core.bookFraction
import app.echoread.core.i18n.S
import app.echoread.data.SettingsStore
import app.echoread.tts.EngineSnapshot
import app.echoread.tts.PlayerController
import app.echoread.tts.SleepMode
import app.echoread.tts.TtsEngine
import app.echoread.ui.EchoIcons
import app.echoread.ui.IconButtonEcho
import app.echoread.ui.ReaderTheme
import app.echoread.ui.Toaster
import app.echoread.ui.dangerColor
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.glassSurface
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress

private val RATE_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * 底部播放坞：睡眠 | 上一章 | 播放 | 下一章 | 倍速，下面一条全书进度。
 *
 * 一直在变的输入都交给读它的那一小块自己读：睡眠倒计时每秒一跳只重组睡眠那一格，
 * 引擎快照只重组播放键与进度条，倍速只重组倍速那一格。坞本身只在章节、书目、主题变时才重组。
 */
@Composable
internal fun ReaderDock(
    bookId: String,
    graph: AppGraph,
    snapshot: State<EngineSnapshot>,
    meta: BookMeta?,
    pager: ReaderPager,
    window: ChapterWindow,
    theme: ReaderTheme,
    glass: GlassStyle,
    shadow: Color,
    onSleep: () -> Unit,
    onPrev: () -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(theme.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        /*
         * 播放坞是一枚**悬浮的玻璃胶囊**（0.6.0）：两侧留边、四角全圆，浮在书页底色上。
         *
         * 历史：最早它是一块横贯整屏的实心圆角卡，加上页脚与系统导航条，屏幕底部有三层「壳」，
         * 观感就是一堵墙，于是 0.3 起干脆不画容器。现在的胶囊不贴边、半透明，只有一圈高光与很淡的投影，
         * 不再是墙；它与顶栏的按钮组、书架上的悬浮件是同一种材质。
         *
         * 尺寸与原来逐 dp 相同（内边距没有变）：坞高恒定，正文画布的 pageSize 不受影响。
         * 状态行仍是**悬浮层**（挂在正文画布底部，见 [ReaderStatusPill] ——
         * 它绝不参与布局，否则每次闪现都会改变 pageSize 触发整章重排版）。
         */
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .glassShadow(RoundedCornerShape(30.dp), shadow, blur = 18.dp, dy = 6.dp)
                .glassSurface(RoundedCornerShape(30.dp), glass)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            // 显式行距。实测原来三条内容带之间只有 3.0 / 1.7 / 0.3dp，
            // 也就是元素直接贴在一起 —— M3 的最小内容间距是 8dp。
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            /*
             * 状态行不在这里 —— 它是**悬浮层**，挂在正文画布的底部（与睡眠面板同款）。
             *
             * 曾经它是坞 Column 的第一行，实测出一条深层反馈环（EchoFollow 日志钉死）：
             * 「正在合成…」每段闪现/消失 → 坞高变化 → 正文画布 pageSize 变化 →
             * LayoutSpec 变化 → **整章重排版**（每段一次！）；「回到朗读位置」点击后
             * 标签消失 → 重排版 → 分页移位 → 锚点掉页 → 又偏离 → 标签复现 → 再排版
             * （11 秒内三次 landOn）。状态行改为悬浮后坞高恒定，pageSize 与状态彻底解耦。
             */
            // 五个等权重槽位：播放键在第三格，因此**数学上**落在坞的正中。
            // 旧写法是 spacedBy(CenterHorizontally) 把五个控件当一组居中，
            // 而睡眠与倍速挂在右侧，实测把播放键推得偏左 99px（33dp）—— 最重要的控件不在中心。
            // 顺序也改成「次要 | 主要 | 主 | 主要 | 次要」的对称形，两侧视觉重量相当。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockSlot { SleepButton(graph.player, theme, onSleep) }
                DockSlot {
                    IconButtonEcho(EchoIcons.SkipPrev, S.reader.previousChapter, tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = pager.anchor.chapter > 0, onClick = onPrev)
                }
                DockSlot {
                    PlayButton(
                        playing = snapshot.value.isPlaying(bookId),
                        accent = theme.accent,
                        onAccent = theme.bg,
                        onClick = onPlay
                    )
                }
                DockSlot {
                    IconButtonEcho(EchoIcons.SkipNext, S.reader.nextChapter, tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, enabled = meta?.let { pager.anchor.chapter < it.chapterCount - 1 } ?: false, onClick = onNext)
                }
                DockSlot { RateButton(graph.settings, theme) }
            }
            DockProgress(bookId, snapshot, meta, pager, window, graph.engine, theme)
        }
    }
}

/** 睡眠定时的一格：没开时是月亮，开着时是倒计时 */
@Composable
private fun SleepButton(player: PlayerController, theme: ReaderTheme, onClick: () -> Unit) {
    val sleepMode by player.sleepMode.collectAsState()
    val sleepRemaining by player.sleepRemaining.collectAsState()
    if (sleepMode === SleepMode.Off) {
        IconButtonEcho(EchoIcons.Moon, S.reader.sleepTimer, tint = theme.text.copy(alpha = 0.75f), size = 36.dp, iconSize = 18.dp, onClick = onClick)
    } else {
        val left = if (sleepMode === SleepMode.Chapter) S.reader.sleepChapterShort else "%d:%02d".format(java.util.Locale.ROOT, sleepRemaining / 60, sleepRemaining % 60)
        Text(
            left,
            color = theme.accent,
            style = MaterialTheme.typography.labelSmallEmphasized,
            // 读屏只念「12:34」听不出是什么：说明它是睡眠定时
            modifier = Modifier
                .semantics { contentDescription = S.reader.sleepTimerState(left) }
                .echoPress(pressedScale = PressScale.Chip, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp)
        )
    }
}

/** 倍速的一格：点一下换到下一档 */
@Composable
private fun RateButton(settings: SettingsStore, theme: ReaderTheme) {
    val tts by settings.tts.collectAsState()

    fun cycleRate() {
        val cur = tts.rate
        val next = RATE_STEPS.firstOrNull { it > cur + 0.01f } ?: RATE_STEPS[0]
        settings.updateTts { it.copy(rate = next) }
        Toaster.show(S.reader.speedLabel(formatRate(next)), durationMs = 1200)
    }

    val rate = formatRate(tts.rate)
    Text(
        "${rate}×",
        color = theme.text.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelSmallEmphasized,
        // 读屏只念「1.25×」听不出是什么：说明它是语速
        modifier = Modifier
            .semantics { contentDescription = S.reader.speedLabel(rate) }
            .echoPress(pressedScale = PressScale.Chip) { cycleRate() }.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/** 坞底的全书进度条：读引擎快照（播放中按朗读位置走），快照一变只重组这一条 */
@Composable
private fun DockProgress(
    bookId: String,
    snapshot: State<EngineSnapshot>,
    meta: BookMeta?,
    pager: ReaderPager,
    window: ChapterWindow,
    engine: TtsEngine,
    theme: ReaderTheme,
) {
    val snap = snapshot.value
    val playing = snap.isPlaying(bookId)
    val curPages = window.pagesOf(pager.anchor.chapter)

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
    // 都要往回弹几次。只有用户自己跳转（seek / 翻页 / 换章 / 换书）才允许回退（见 MonotonicProgress）。
    // 钳制在进度条绘制时才算（它到绘制期才读 progress），组合期不写任何状态。
    val progressClamp = remember { MonotonicProgress() }
    val progressAnchor = pager.anchor
    val progressSegment = snap.segmentStart
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
        progress = { progressClamp.at(bookId, progressAnchor, progressSegment, rawProgress) },
        modifier = Modifier.fillMaxWidth(),
        color = if (snap.failure != null && snap.bookId == bookId) dangerColor(theme.isDark) else theme.accent,
        trackColor = theme.text.copy(alpha = 0.12f)
    )
}

/**
 * 进度条的单调钳制：书、锚点（章 + 页）、段首都没变时只升不降，任何一样变了就从当前值重新开始。
 *
 * 锚点必须连页码一起比：不在朗读时进度取的是当前页首字，往回翻页时它理应变小。
 * 早先只比章号，同一章里往回翻，进度条停在翻到过的最远处纹丝不动。
 *
 * 在绘制期求值（读屏的语义块也会读一次），同一组输入算几次结果都一样。
 */
internal class MonotonicProgress {
    private var book: String? = null
    private var anchor: PageRef? = null
    private var segment = 0
    private var shown = 0f

    fun at(bookId: String, anchor: PageRef, segmentStart: Int, raw: Float): Float {
        if (bookId != book || anchor != this.anchor || segmentStart != segment) {
            book = bookId
            this.anchor = anchor
            segment = segmentStart
            shown = raw
        } else if (raw > shown) {
            shown = raw
        }
        return shown
    }
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
        // 48dp：与其余槽位的触控区等高。原来 52dp 比同行的 36dp 图标高出一截，
        // 撑爆行高并把上下的留白吃掉（实测上下间隙只剩 1.7dp 与 0.3dp）
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = if (playing) S.reader.pause else S.reader.play
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
