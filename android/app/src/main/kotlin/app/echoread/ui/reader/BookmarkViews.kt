package app.echoread.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.core.Bookmark
import app.echoread.core.i18n.S
import app.echoread.ui.EchoIcons
import app.echoread.ui.IconButtonEcho
import app.echoread.ui.echo
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.echoPress
import app.echoread.ui.relativeTime

/*
 * 书签的两块界面：书页右上角的丝带，目录面板里的书签列表。两者都只管「画」——
 * 哪一页算有书签、加在哪个位置由阅读页决定（它知道当前页），存取在 data/BookmarkRepo。
 */

/**
 * 页角的书签丝带：这一页有书签时从页面区的上沿垂下来，落到位回弹一下；没有时收回去。
 *
 * 它是一条从上沿「长」出来的带子（上端始终贴着上沿，只有下端在动），回弹冲过头时是带子多垂下来
 * 一点再缩回去，上面不会露出缝。位置只在绘制期读：动画过程不触发重组，收起之后一笔都不画。
 */
@Composable
internal fun BookmarkRibbon(visible: Boolean, color: Color, modifier: Modifier = Modifier) {
    val drop = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) EchoMotion.Playful.float() else tween(Dur.Short, easing = Ease.Standard),
        label = "bookmarkRibbon"
    )
    val path = remember { Path() }
    Spacer(
        modifier
            .size(width = 12.dp, height = 30.dp)
            .clipToBounds()
            .drawBehind {
                val t = drop.value
                if (t <= 0.004f) return@drawBehind
                val bottom = 22.dp.toPx() * t
                path.rewind()
                path.moveTo(0f, 0f)
                path.lineTo(size.width, 0f)
                path.lineTo(size.width, bottom)
                path.lineTo(size.width / 2f, bottom - 5.dp.toPx() * t.coerceAtMost(1f))
                path.lineTo(0f, bottom)
                path.close()
                drawPath(path, color)
            }
    )
}

/**
 * 书签列表：按书里的位置排（传进来时已经排好）。一行 = 两行摘录 + 一行出处（章节名 · 何时加的），
 * 点一下跳过去，行尾直接删 —— 不弹确认，由调用方给一个可以撤销的轻提示。没有书签时是一句怎么加的说明。
 *
 * @param here 落在当前页上的那几枚，标成强调色
 */
@Composable
internal fun BookmarkList(
    marks: List<Bookmark>,
    here: Set<Long>,
    state: LazyListState,
    chapterTitle: (Int) -> String,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = echo
    if (marks.isEmpty()) {
        Column(modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(EchoIcons.Bookmark, null, tint = c.text3, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text(S.reader.noBookmarks, color = c.text, style = MaterialTheme.typography.titleSmallEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(S.reader.noBookmarksHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            // 视觉重心略高于正中：空状态摆在正中会显得往下坠
            Spacer(Modifier.height(48.dp))
        }
        return
    }
    LazyColumn(state = state, modifier = modifier) {
        items(marks, key = { it.id }) { mark ->
            val active = mark.id in here
            val title = chapterTitle(mark.chapter)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) c.accentSoft else Color.Transparent)
                    .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { onOpen(mark) }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(EchoIcons.BookmarkFilled, null, tint = if (active) c.accent else c.text3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        mark.excerpt.ifBlank { title },
                        color = if (active) c.accent else c.text, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Row {
                        // 章节名可以被截断，后面的时间不行
                        Text(
                            title, color = c.text3, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(" · ${relativeTime(mark.createdAt)}", color = c.text3, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }
                IconButtonEcho(EchoIcons.Close, S.reader.removeBookmark, size = 34.dp, iconSize = 14.dp, tint = c.text3, touchSize = 44.dp) { onRemove(mark) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
