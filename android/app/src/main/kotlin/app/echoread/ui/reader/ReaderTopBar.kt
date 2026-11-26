package app.echoread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.core.i18n.S
import app.echoread.ui.EchoIcons
import app.echoread.ui.GlassIcon
import app.echoread.ui.ReaderTheme
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.glassLight
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.glassSurface
import app.echoread.ui.motion.rememberGlassLight

/**
 * 阅读器顶栏：返回键、章节名与书名、四枚功能键。按钮组浮在书页底色上（玻璃面见阅读页的 chromeGlass）。
 * 只吃算好的值：朗读进度、睡眠倒计时这些一直在变的东西都不经过它。
 *
 * @param marked 这一页有书签：书签键画成实心，点一下是拿掉
 */
@Composable
internal fun ReaderTopBar(
    theme: ReaderTheme,
    glass: GlassStyle,
    shadow: Color,
    chapterTitle: String,
    bookTitle: String,
    marked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onContents: () -> Unit,
    onStyle: () -> Unit,
    onVoice: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChromeGroup(glass, shadow) {
            GlassIcon(EchoIcons.Back, S.reader.back, tint = theme.text, modifier = Modifier.testTag("reader.back"), onClick = onBack)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                chapterTitle,
                color = theme.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(bookTitle, color = theme.dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 四枚图标：每枚收到 40dp 宽（触控高度仍是 44dp），给中间的章节名多留一点地方
        ChromeGroup(glass, shadow, inset = 2.dp) {
            val slot = Modifier.width(40.dp)
            GlassIcon(
                if (marked) EchoIcons.BookmarkFilled else EchoIcons.Bookmark,
                if (marked) S.reader.removeBookmark else S.reader.addBookmark,
                tint = if (marked) theme.accent else theme.text,
                modifier = slot,
                onClick = onBookmark
            )
            GlassIcon(EchoIcons.Toc, S.common.contents, tint = theme.text, modifier = slot, onClick = onContents)
            GlassIcon(EchoIcons.TextStyle, S.common.readingStyle, tint = theme.text, modifier = slot, onClick = onStyle)
            GlassIcon(EchoIcons.Waves, S.common.voiceSettings, tint = theme.text, modifier = slot, onClick = onVoice)
        }
    }
}

/** 阅读器顶栏上的一组玻璃按钮：胶囊形，用阅读主题自己的玻璃面（见 chromeGlass） */
@Composable
private fun ChromeGroup(style: GlassStyle, shadow: Color, inset: Dp = 0.dp, content: @Composable RowScope.() -> Unit) {
    val light = rememberGlassLight()
    Row(
        Modifier
            .height(44.dp)
            .glassShadow(CircleShape, shadow, blur = 14.dp, dy = 4.dp)
            .glassSurface(CircleShape, style, light)
            .glassLight(light)
            .padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
