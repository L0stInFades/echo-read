package app.echoread.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.echoread.core.Range
import app.echoread.core.ReaderSettings
import app.echoread.data.DerivedChapter
import app.echoread.ui.ReaderTheme

/**
 * 整章分页排版结果：章节文本一次性 measure 成 TextLayoutResult，按行高切成整页；
 * 页面绘制只做 clip + translate + drawText（零重排），翻页、高亮、点读全部基于同一份布局。
 * 渲染串比章节纯文本多了「标题前缀」与每段之间的「间距空行」，两套偏移用 toRendered/toChapter 互转。
 *
 * `@Immutable` 是显式契约：构造后所有字段与其内容都不再改变（改样式 = 换一个新实例）。
 * 没有它，Compose 会把它推断为 unstable，`PageCanvas` 永远无法跳过重组。
 */
@Immutable
class ChapterPages(
    val chapter: DerivedChapter,
    val layout: TextLayoutResult,
    private val prefixLen: Int,
    val pages: List<IntRange>
) {
    val pageCount: Int get() = pages.size
    fun pageTop(p: Int): Float = layout.getLineTop(pages[p].first)

    /** 章节偏移 → 渲染偏移：渲染串 = 标题 + 占位符 + 段落…（段间占位符与章节文本的 \n 一一对应） */
    fun toRendered(chapterOffset: Int): Int = prefixLen + chapterOffset

    /** 渲染偏移 → 章节偏移（落在标题/段间占位上时钳到相邻段落的字） */
    fun toChapter(rendered: Int): Int {
        val text = chapter.text
        if (text.isEmpty()) return 0
        var o = (rendered - prefixLen).coerceIn(0, text.length - 1)
        if (text[o] == '\n') o = if (o + 1 < text.length) o + 1 else o - 1
        return o.coerceIn(0, text.length - 1)
    }

    fun pageOf(chapterOffset: Int): Int {
        val line = layout.getLineForOffset(toRendered(chapterOffset).coerceIn(0, maxOf(layout.layoutInput.text.length - 1, 0)))
        val idx = pages.indexOfFirst { line in it }
        return if (idx < 0) pages.size - 1 else idx
    }

    /** 本页首字的章节偏移（进度保存用） */
    fun pageStartOffset(p: Int): Int = toChapter(layout.getLineStart(pages[p].first))

    /**
     * 本页覆盖的章节偏移区间 `[start, end)`：一个位置（书签）在不在这一页上，按它判。
     * 末页的区间多收一位，章尾那个位置也算在末页上；区间至少一个字符宽。
     */
    fun pageRange(p: Int): Range {
        val start = pageStartOffset(p)
        val end = if (p + 1 < pages.size) pageStartOffset(p + 1) else chapter.text.length + 1
        return Range(start, maxOf(end, start + 1))
    }
}

/**
 * 排版规格指纹：样式或页面尺寸任一变化即需重排（滑块拖动期由去抖收敛，不再每像素排一次整章）。
 *
 * 只收影响分页的五项阅读设置（阅读主题、字号、行距、字体、段距）与页面尺寸 —— 白名单。
 * 早先它持有整个 ReaderSettings、再把无关字段归一化掉（黑名单）：每给设置加一个外观字段（配色、对比度、
 * 语言、玻璃、纯黑……）都得记得排除，漏一个，换一下配色就会把当前章连同前后邻章一起重排 + 交叉淡入
 * （长章数百毫秒的卡顿）。阅读主题在内，是因为它的文字色在排版时就写进了布局（章节标题的颜色）。
 */
@Immutable
data class LayoutSpec(
    val theme: String,
    val fontSize: Int,
    val lineHeight: Float,
    val fontFamily: String,
    val paraSpacing: Float,
    val width: Int,
    val height: Int
) {
    companion object {
        fun of(reader: ReaderSettings, width: Int, height: Int): LayoutSpec =
            LayoutSpec(reader.theme, reader.fontSize, reader.lineHeight, reader.fontFamily, reader.paraSpacing, width, height)
    }
}

private const val FIRST_LINE_INDENT_EM = 2f

/** 正文文字样式：只依赖排版规格与主题，排版协程与绘制共用同一份 */
fun bodyTextStyle(spec: LayoutSpec, theme: ReaderTheme): TextStyle = TextStyle(
    color = theme.text,
    fontSize = spec.fontSize.sp,
    lineHeight = (spec.fontSize * spec.lineHeight).sp,
    fontFamily = if (spec.fontFamily == "serif") FontFamily.Serif else FontFamily.Default,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

/** 构建渲染串 + 整章 measure + 分页（必须在单线程排版调度器上执行：measure 不可协作取消） */
fun layoutChapter(
    measurer: TextMeasurer,
    chapter: DerivedChapter,
    spec: LayoutSpec,
    theme: ReaderTheme
): ChapterPages {
    val style = bodyTextStyle(spec, theme)
    val lineHeightSp = spec.fontSize * spec.lineHeight
    val gapSp = (lineHeightSp * 0.5f * spec.paraSpacing).coerceAtLeast(2f)
    // 段落之间不用换行符（Compose 会为行尾换行再生成一个空行），改用带独立行高的单字符占位段落
    val paraStyle = ParagraphStyle(textIndent = TextIndent(firstLine = FIRST_LINE_INDENT_EM.em), lineHeight = lineHeightSp.sp, textAlign = TextAlign.Justify)
    val gapStyle = ParagraphStyle(lineHeight = gapSp.sp)
    val titleStyle = ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (lineHeightSp * 1.3f).sp)
    val text = chapter.text
    val annotated: AnnotatedString = buildAnnotatedString {
        withStyle(titleStyle) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (spec.fontSize + 4).sp, color = theme.text)) { append(chapter.title) }
        }
        withStyle(gapStyle) { append(' ') }
        chapter.paras.forEachIndexed { pi, p ->
            withStyle(paraStyle) { append(text, p.start, p.end) }
            if (pi < chapter.paras.size - 1) withStyle(gapStyle) { append(' ') }
        }
    }
    // 不再 skipCache：同一份文本换页/换主题时命中缓存，冷启动成本明显低于每次强制重排
    val layout = measurer.measure(
        text = annotated,
        style = style,
        constraints = Constraints(maxWidth = spec.width)
    )
    val pageHeight = spec.height.toFloat()
    val pages = ArrayList<IntRange>()
    var first = 0
    val n = layout.lineCount
    while (first < n) {
        val top = layout.getLineTop(first)
        var last = first
        while (last + 1 < n && layout.getLineBottom(last + 1) - top <= pageHeight - 1f) last++
        pages.add(first..last)
        first = last + 1
    }
    if (pages.isEmpty()) pages.add(0..0)
    return ChapterPages(chapter, layout, chapter.title.length + 1, pages)
}
