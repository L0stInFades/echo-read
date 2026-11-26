package app.echoread.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Bookmark
import app.echoread.core.Bookmarks
import app.echoread.core.ColorStyle
import app.echoread.core.Range
import app.echoread.core.i18n.AppLanguage
import app.echoread.core.i18n.S
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import app.echoread.ui.reader.BookmarkList
import kotlinx.coroutines.launch

/* ---------- 目录与书签 ---------- */

/**
 * 目录面板：「目录」与「书签」两栏（0.7.0 起），标题是书名。
 *
 * 书签放在目录旁边，是读过的几款开源阅读器（Legado、Readium、Readest）共同的做法：
 * 两者回答的是同一个问题 ——「我要去书里的哪儿」。
 *
 * - 面板高度固定（[EchoSheet] 的 fillHeight）：两栏内容一长一短，高度跟着栏目跳会很晕；
 * - 关掉再打开，回到上次看的那一栏；
 * - 两栏打开时都滚到当前位置附近。
 *
 * @param page 当前页的章内偏移区间：落在里面的书签标成强调色
 */
@Composable
fun BoxScope.ChapterListSheet(
    open: Boolean,
    bookTitle: String,
    titles: List<String>,
    current: Int,
    bookmarks: List<Bookmark>,
    page: Range?,
    onClose: () -> Unit,
    onSelect: (Int) -> Unit,
    onOpenBookmark: (Bookmark) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit
) {
    val c = echo
    var tab by remember { mutableIntStateOf(0) }
    val tocState = rememberLazyListState()
    val markState = rememberLazyListState()
    // 章节名是第一次打开时才去库里取的：取到之后也要滚一次，否则第一次打开总停在第一章
    LaunchedEffect(open, titles.size) {
        if (open && titles.isNotEmpty()) tocState.scrollToItem((current - 4).coerceIn(0, titles.size - 1))
    }
    LaunchedEffect(open) {
        if (open && bookmarks.isNotEmpty()) {
            val near = Bookmarks.nearestBefore(bookmarks, current, page?.let { it.end - 1 } ?: 0)
            markState.scrollToItem((near - 1).coerceIn(0, bookmarks.size - 1))
        }
    }
    val here = remember(bookmarks, current, page) {
        page?.let { r -> Bookmarks.onPage(bookmarks, current, r.start, r.end).map { it.id }.toSet() } ?: emptySet()
    }
    EchoSheet(open = open, onDismiss = onClose, title = bookTitle.ifBlank { S.common.contents }, scrollable = false, fillHeight = true) {
        EchoSegmented(
            items = listOf(SegmentItem(S.common.contents, EchoIcons.Toc), SegmentItem(S.reader.bookmarksTab(bookmarks.size), EchoIcons.Bookmark)),
            selectedIndex = tab,
            height = 40.dp
        ) { tab = it }
        Spacer(Modifier.height(10.dp))
        SheetPages(page = tab, depthOf = { it }, modifier = Modifier.weight(1f).fillMaxWidth()) { shownTab ->
            if (shownTab == 1) {
                BookmarkList(
                    marks = bookmarks, here = here, state = markState,
                    chapterTitle = { titles.getOrNull(it).orEmpty() },
                    onOpen = { onOpenBookmark(it); onClose() },
                    onRemove = onRemoveBookmark,
                    modifier = Modifier.fillMaxSize()
                )
            } else LazyColumn(state = tocState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(titles) { i, t ->
                    val active = i == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) c.accentSoft else Color.Transparent)
                            .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { onSelect(i); onClose() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", color = if (active) c.accent else c.text3, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp))
                        Text(
                            t, color = if (active) c.accent else c.text, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (active) Icon(EchoIcons.PlaySmall, null, tint = c.accent, modifier = Modifier.size(15.dp))
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/* ---------- 阅读样式 ---------- */

@Composable
fun BoxScope.ReaderStyleSheet(open: Boolean, graph: AppGraph, onOpenGestures: () -> Unit = {}, onClose: () -> Unit) {
    val c = echo
    val reader by graph.settings.reader.collectAsState()
    EchoSheet(open = open, onDismiss = onClose, title = S.common.readingStyle, maxHeightFraction = 0.86f) {
        /* ---- 阅读：主题 / 字号 / 行距 / 段距 / 字体。这张面板是从书页上唤出来的，决定书页长相的排最前 ---- */
        SettingsSection(S.settings.reading) {
            customFullBleed {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (t in READER_THEMES) {
                        val selected = reader.theme == t.id
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .background(t.bg, RoundedCornerShape(Radius.md))
                                    .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(Radius.md))
                                    .echoPress(pressedScale = PressScale.Chip) { graph.settings.updateReader { r -> r.copy(theme = t.id) } },
                                contentAlignment = Alignment.Center
                            ) { Text(S.settings.themePreviewGlyph, color = t.text, style = MaterialTheme.typography.titleSmallEmphasized) }
                            Spacer(Modifier.height(4.dp))
                            SwatchLabel(t.label, selected)
                        }
                    }
                }
            }
            custom {
                SettingsSlider(S.settings.fontSize, "${reader.fontSize}", reader.fontSize.toFloat(), 14f..28f, steps = 13) { v ->
                    graph.settings.updateReader { r -> r.copy(fontSize = v.toInt()) }
                }
            }
            custom {
                SettingsSlider(S.settings.lineSpacing, String.format(java.util.Locale.ROOT, "%.1f", reader.lineHeight), reader.lineHeight, 1.4f..2.6f, steps = 11) { v ->
                    graph.settings.updateReader { r -> r.copy(lineHeight = (Math.round(v * 10) / 10f)) }
                }
            }
            custom {
                SettingsSlider(S.settings.paragraphSpacing, String.format(java.util.Locale.ROOT, "%.1f", reader.paraSpacing), reader.paraSpacing, 0.4f..2f, steps = 15) { v ->
                    graph.settings.updateReader { r -> r.copy(paraSpacing = (Math.round(v * 10) / 10f)) }
                }
            }
            custom {
                Text(S.settings.font, color = c.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                EchoSegmented(S.settings.serif, S.settings.sansSerif, firstSelected = reader.fontFamily == "serif") { serif ->
                    graph.settings.updateReader { r -> r.copy(fontFamily = if (serif) "serif" else "sans") }
                }
            }
            // 翻页手势单独开一层：内容多且带实时预览。副标题就是当前设置的摘要，不点进去也知道现在是什么配置
            row(
                S.common.pageTurnGestures,
                value = gestureSummary(reader.gestures),
                trailing = { ChevronEnd() },
                onClick = onOpenGestures
            )
        }
        Spacer(Modifier.height(22.dp))

        AppearanceSection(graph)
        Spacer(Modifier.height(22.dp))

        SettingsSection(S.providers.general) {
            switch(S.settings.haptics, value = S.settings.hapticsHint, checked = reader.haptics) { on ->
                graph.settings.updateReader { r -> r.copy(haptics = on) }
            }
            custom { LanguagePicker(graph) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 应用配色：与原生「壁纸与个性化」同构。整套 48 个角色由 Google 的 material-color-utilities
 * 按「种子色 × 风格」现算，不是几套写死的常量；跟随壁纸时直接用系统算好的那份。
 */
@Composable
private fun AppearanceSection(graph: AppGraph) {
    val c = echo
    val reader by graph.settings.reader.collectAsState()
    val dynAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    SettingsSection(S.providers.appearance) {
        custom {
            Text(S.providers.themeMode, color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            val modes = listOf(app.echoread.core.ThemeMode.SYSTEM, app.echoread.core.ThemeMode.LIGHT, app.echoread.core.ThemeMode.DARK)
            EchoSegmented(
                items = listOf(SegmentItem(S.providers.themeSystem), SegmentItem(S.providers.themeLight), SegmentItem(S.providers.themeDark)),
                selectedIndex = modes.indexOf(reader.themeMode).coerceAtLeast(0)
            ) { i -> graph.settings.updateReader { r -> r.copy(themeMode = modes[i]) } }
        }
        // 纯黑同时把「暗夜」书页换成「纯黑」书页（关掉时换回来）：打开它的人要的是整块屏幕都是黑的，
        // 只把外壳变黑、书页还是深蓝灰，算不上「纯黑模式」。之后仍可以在上面的阅读主题里另选
        switch(S.providers.pureBlack, value = S.providers.pureBlackHint, checked = reader.pureBlack) { on ->
            graph.settings.updateReader { r ->
                r.copy(
                    pureBlack = on,
                    theme = when {
                        on && r.theme == "dark" -> "ink"
                        !on && r.theme == "ink" -> "dark"
                        else -> r.theme
                    }
                )
            }
        }
        switch(S.providers.glass, value = S.providers.glassHint, checked = reader.glass) { on ->
            graph.settings.updateReader { r -> r.copy(glass = on) }
        }
        // 跟随壁纸取色需要 Android 12：更低的系统上这一行不出现，而不是摆一个永远点不动的开关
        if (dynAvailable) {
            switch(S.settings.useWallpaperColors, value = S.settings.wallpaperColorsHint, checked = reader.dynamicColor) { on ->
                graph.settings.updateReader { r -> r.copy(dynamicColor = on) }
            }
        }
        if (!(reader.dynamicColor && dynAvailable)) {
            customFullBleed {
                Text(S.settings.mainColor, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                LabeledSwatchRow {
                    for ((name, seed) in SEED_COLORS) {
                        val sel = reader.seedColor == seed.toArgb()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SeedDot(seed, sel) { graph.settings.updateReader { r -> r.copy(seedColor = seed.toArgb()) } }
                            SwatchLabel(name, sel)
                        }
                    }
                }
            }
            customFullBleed {
                Text(S.settings.style, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                LabeledSwatchRow {
                    for (st in ColorStyle.PICKABLE) {
                        val sel = reader.colorStyle == st
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PaletteSwatch(Color(reader.seedColor), st, sel) { graph.settings.updateReader { r -> r.copy(colorStyle = st) } }
                            SwatchLabel(st.label, sel)
                        }
                    }
                }
            }
        }
        custom {
            Text(S.settings.contrast, color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            val levels = listOf(0f to S.common.standard, 0.5f to S.settings.medium, 1f to S.settings.high)
            EchoSegmented(
                items = levels.map { SegmentItem(it.second) },
                selectedIndex = levels.indexOfFirst { kotlin.math.abs(it.first - reader.contrast) < 0.01f }.coerceAtLeast(0)
            ) { i -> graph.settings.updateReader { r -> r.copy(contrast = levels[i].first) } }
        }
    }
}

/* ---------- 帮助 ---------- */

@Composable
fun BoxScope.HelpSheet(open: Boolean, graph: AppGraph? = null, onClose: () -> Unit) {
    val c = echo
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    EchoSheet(open = open, onDismiss = onClose, title = S.common.howToUse, maxHeightFraction = 0.86f) {
        val topics = listOf(
            S.common.importBooks to listOf(S.settings.helpImport1, S.settings.helpImport2, S.settings.helpImport3),
            S.settings.helpTapToReadTitle to listOf(S.settings.helpTapToRead),
            S.common.pageTurnGestures to listOf(S.settings.helpGestures1, S.settings.helpGestures2),
            S.reader.bookmarks to listOf(S.settings.helpBookmarks),
            S.common.speech to listOf(S.settings.tapTheGearAnd),
            S.settings.helpOfflineTitle to listOf(S.settings.helpOffline)
        )
        // 一个话题一张卡片：一整面不分段的说明文字没有人会读，分开之后扫标题就能找到要看的那一段
        for ((title, paragraphs) in topics) {
            EchoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Text(title, color = c.text, style = MaterialTheme.typography.titleSmallEmphasized)
                for (p in paragraphs) {
                    Spacer(Modifier.height(6.dp))
                    Text(p.trim(), color = c.text2, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (graph != null) {
            Spacer(Modifier.height(12.dp))
            // 外观也放一份在这里：主题、纯黑、玻璃是整个应用的事，不该只藏在「阅读样式」里
            AppearanceSection(graph)
            Spacer(Modifier.height(22.dp))
            // 书架上就能找到的地方：中文系统的用户想用英文界面，不必先打开一本书
            SettingsSection(S.providers.general) {
                custom { LanguagePicker(graph) }
            }
            Spacer(Modifier.height(22.dp))
            SettingsSection(S.providers.about) {
                row(
                    if (checking) S.settings.checkingForUpdates else S.settings.checkForUpdates,
                    value = S.settings.currentVersion(graph.updater.currentVersionName),
                    trailing = { ChevronEnd() },
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val r = runCatching { graph.updater.check(force = true) }.getOrNull()
                            checking = false
                            when (r) {
                                is app.echoread.data.UpdateState.UpToDate -> Toaster.success(S.settings.youreUpToDate(graph.updater.currentVersionName))
                                is app.echoread.data.UpdateState.Available, is app.echoread.data.UpdateState.Ready -> { Toaster.show(S.settings.updateFoundSeeLibrary); onClose() }
                                is app.echoread.data.UpdateState.Error -> r.error?.let { ErrorDetails.toast(it) } ?: Toaster.error(r.message)
                                else -> Toaster.error(S.settings.couldntCheckForUpdates)
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 手势设置一行摘要，显示在「阅读样式」的入口行上 */
private fun gestureSummary(g: app.echoread.core.GestureSettings): String {
    val swipe = when (g.axis) {
        app.echoread.core.PageAxis.HORIZONTAL -> S.settings.swipeSideways
        app.echoread.core.PageAxis.VERTICAL -> S.settings.swipeUpDown
        app.echoread.core.PageAxis.OFF -> S.settings.noSwiping
    }
    val tap = when {
        !g.zonesActive -> S.settings.noTapZones
        g.tapAxis == app.echoread.core.PageAxis.VERTICAL -> S.settings.zonesSummaryTopBottom((g.prevZone * 100).toInt(), (g.nextZone * 100).toInt())
        else -> S.settings.zonesSummaryLeftRight((g.prevZone * 100).toInt(), (g.nextZone * 100).toInt())
    }
    return "$swipe · $tap"
}

/**
 * 界面语言：跟随系统 / 中文 / English。选中后 MainActivity 重建，整页按新语言重画。
 * 「跟随系统」也认 Android 13 起的「应用语言」设置（系统设置 → 应用 → Lector → 语言）。
 */
@Composable
internal fun LanguagePicker(graph: AppGraph) {
    val c = echo
    val reader by graph.settings.reader.collectAsState()
    val options = listOf(AppLanguage.SYSTEM, AppLanguage.ZH, AppLanguage.EN)
    Text(S.settings.language, color = c.text, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
    EchoSegmented(
        items = listOf(SegmentItem(S.settings.languageSystem), SegmentItem(S.settings.languageZh), SegmentItem(S.settings.languageEn)),
        selectedIndex = options.indexOf(reader.language).coerceAtLeast(0)
    ) { i -> graph.settings.updateReader { r -> r.copy(language = options[i]) } }
}
