package app.echoread.ui

import app.echoread.core.i18n.S
import app.echoread.ui.motion.EchoMotion
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.echoread.AppGraph
import app.echoread.core.BookMeta
import app.echoread.core.readFraction
import app.echoread.core.started
import app.echoread.core.Providers
import app.echoread.tts.ready
import app.echoread.ui.glass.LocalBackdrop
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.glassSurface
import app.echoread.ui.motion.liquidPress
import app.echoread.ui.motion.rememberGlassLight
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.rememberBackdrop
import androidx.compose.runtime.CompositionLocalProvider
import app.echoread.core.i18n.I18n
import app.echoread.data.UpdateState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import app.echoread.ui.motion.echoTap

private enum class SortMode {
    RECENT, ADDED, TITLE;

    // 文案必须是 getter：枚举常量只构造一次，写进构造参数会停在首次访问时的语言
    val label: String get() = when (this) {
        RECENT -> S.shelf.recent
        ADDED -> S.shelf.added
        TITLE -> S.shelf.title
    }
}

/**
 * 书架：One UI 式大标题下沉（滚动收起为紧凑标题栏）+ Harmony 卡片分组 + 底部拇指可达的「导入」主按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    graph: AppGraph,
    /** 书架此刻是否被阅读器完全盖住：盖住时悬浮件不再采样背板（它们看不见，没有理由每帧去模糊书架） */
    covered: () -> Boolean = { false },
    onOpenBook: (String) -> Unit
) {
    val c = echo
    val scope = rememberCoroutineScope()
    val library = graph.library
    val books by library.books.collectAsState()
    val importing by library.importing.collectAsState()
    val tts by graph.settings.tts.collectAsState()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.RECENT) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookMeta?>(null) }

    LaunchedEffect(Unit) { library.refresh() }

    fun openSample() = scope.launch {
        try {
            val m = library.importSample()
            onOpenBook(m.id)
        } catch (e: Exception) {
            Toaster.error(e.message ?: S.shelf.sampleImportFailed)
        }
    }

    // 按界面语言的规则排序：中文按拼音，英文按字母（不再一律用中文排序规则）
    val collator = remember(I18n.english) { Collator.getInstance(I18n.locale) }
    // remember 的 key 已经覆盖全部输入，外面再包一层 derivedStateOf 只是多一层观察开销
    val filtered = remember(books, query, sort) {
        val q = query.trim().lowercase()
        var list = books
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
        when (sort) {
            SortMode.ADDED -> list.sortedByDescending { it.createdAt }
            SortMode.TITLE -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
            SortMode.RECENT -> list
        }
    }
    val continueBook = if (query.isBlank()) books.firstOrNull { it.lastReadAt != null } else null

    val bigTitleBrush = rememberAurora()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val compactBarPx = with(density) { 52.dp.toPx() }
    // 大标题实测高度：收起行程取「header 高 − 紧凑栏高」，header 刚滚出视口时 collapse 恰好到 1，无跳变
    var headerPx by remember { mutableFloatStateOf(with(density) { 148.dp.toPx() }) }
    // One UI：大标题收起是「绑定」不是「动画」。绝不再套一层 animateFloatAsState —— 那会让标题
    // 滞后手指 200ms 以上，而且把连续的滚动值读进组合期，滚动每一像素都重组整个 ShelfScreen。
    val collapse = remember(compactBarPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / (headerPx - compactBarPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }

    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
      // 书架的画面（连同页面底色）交给背板；下面的面板是它的兄弟节点，从这里采样做玻璃
      Box(Modifier.fillMaxSize().backdropSource(backdrop, c.canvas)) {
        AmbientLight(listState, Modifier.fillMaxSize())
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 16dp = M3 紧凑窗口（<600dp）的正文边距。全应用统一到这一个值：
            // 原来书架 18dp、弹层 20dp、弹层标题 22dp，三处各不相同
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
        ) {
            // 大标题区（One UI 下沉标题）
            item("header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { if (it.height > 0) headerPx = it.height.toFloat() }
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 56.dp, bottom = 22.dp)
                ) {
                    Text(
                        "Lector",
                        style = TextStyle(brush = bigTitleBrush, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
                        modifier = Modifier.graphicsLayer {
                            // 大/小标题的交叉淡入只发生在行程两端，中段不会出现两个标题都半透明的「糊」
                            val p = collapse.value
                            alpha = 1f - (p / 0.6f).coerceIn(0f, 1f)
                            translationY = -p * 12.dp.toPx()
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (books.isNotEmpty()) S.shelf.libraryBooks(books.size)
                        else S.shelf.tagline,
                        // 字距归零：与 Theme.kt 里定下的中文排版规则一致。
                        // 3sp 加在 12sp 的中文上是 25% 的字距，「书架」会被拉成「书 架」
                        color = c.text3, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - (collapse.value / 0.6f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    )
                }
            }

            item("update") { UpdateCard(graph) }

            // 当前服务还缺 Key（或自建服务还没填地址）：书架顶上提醒一次，点了直接进设置
            val activeProfile = tts.activeProfile
            val activeSpec = activeProfile?.let { Providers.specOf(it, tts) }
            if (activeProfile != null && activeSpec != null && !Providers.ready(activeSpec, activeProfile)) {
                item("keyBanner") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                            .echoPress(pressedScale = PressScale.Tile) { showSettings = true }
                            .glassSurface(RoundedCornerShape(Radius.lgPlus), c.surfaceGlass())
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(38.dp).background(rememberAurora(), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(EchoIcons.Key, null, tint = c.onAccent, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                S.shelf.keyBannerTitle(serviceName(activeSpec, activeProfile)),
                                color = c.text, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                S.shelf.keyBannerSubtitle,
                                color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (books.isEmpty()) {
                item("empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(96.dp).glassSurface(RoundedCornerShape(Radius.xl), c.surfaceGlass()),
                            contentAlignment = Alignment.Center
                        ) { Icon(EchoIcons.Book, null, tint = c.accent, modifier = Modifier.size(40.dp)) }
                        Spacer(Modifier.height(20.dp))
                        Text(S.shelf.emptyTitle, color = c.text, style = MaterialTheme.typography.titleLargeEmphasized)
                        Spacer(Modifier.height(6.dp))
                        Text(S.shelf.emptyHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                        Spacer(Modifier.height(24.dp))
                        GradientButton(S.shelf.importYourFirstBook, height = 46.dp) { showImport = true }
                        Spacer(Modifier.height(10.dp))
                        SoftButton(S.shelf.trySample, Modifier.testTag("shelf.sample")) { openSample() }
                        Spacer(Modifier.height(12.dp))
                        TextAction(S.shelf.howToUseLink, color = c.text2) { showHelp = true }
                    }
                }
            } else {
                if (continueBook != null) {
                    item("continue") {
                        ContinueCard(continueBook, Modifier.padding(bottom = 14.dp)) { onOpenBook(continueBook.id) }
                    }
                }
                item("search") {
                    Column(Modifier.padding(bottom = 14.dp)) {
                        SearchField(query, { query = it }, S.shelf.searchTitleOrAuthor, background = c.fill)
                        Spacer(Modifier.height(10.dp))
                        // 排序在语义上是单选，不是筛选标签：分段控件把「三选一」直接画出来，读屏也会读成单选组
                        EchoSegmented(
                            items = SortMode.entries.map { SegmentItem(it.label) },
                            selectedIndex = SortMode.entries.indexOf(sort),
                            containerColor = c.fill,
                            height = 40.dp
                        ) { sort = SortMode.entries[it] }
                    }
                }
                if (filtered.isEmpty()) {
                    item("nores") { Text(S.shelf.noResultsFor(query), color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                } else {
                    // 网格逐行懒加载：每行一个 item，卡片背景按首/中/尾行分段拼出整块大圆角卡片
                    val cols = 3
                    val rows = filtered.chunked(cols)
                    // 稳定且零分配的 key（旧代码每次组合为每行拼一个字符串）
                    itemsIndexed(rows, key = { _, row -> row.first().id }, contentType = { _, _ -> "bookRow" }) { ri, row ->
                        val first = ri == 0
                        val last = ri == rows.size - 1
                        val shape = RoundedCornerShape(
                            topStart = if (first) Radius.xl else 0.dp, topEnd = if (first) Radius.xl else 0.dp,
                            bottomStart = if (last) Radius.xl else 0.dp, bottomEnd = if (last) Radius.xl else 0.dp
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // 删书、搜索过滤会让整列重排：没有它，行是瞬移的。
                                // 位移用和转场同一条弹簧，全应用的运动语汇保持一致。
                                // 淡入/淡出必须显式关掉（默认是弹簧而非 null）：行 key 取行首书 id，
                                // 删一本书会让后续所有行换 key —— 旧行淡出、新行淡入，交叉期被删的
                                // 封面半透明地叠在新行上残留约半秒（删除后封面"不消失"的元凶）。
                                .animateItem(
                                    fadeInSpec = null,
                                    placementSpec = EchoMotion.Standard.spec(IntOffset.VisibilityThreshold),
                                    fadeOutSpec = null
                                )
                                .background(c.fill, shape)
                                .padding(start = 14.dp, end = 14.dp, top = if (first) 14.dp else 0.dp, bottom = if (last) 14.dp else 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (b in row) BookCell(b, Modifier.weight(1f), onClick = { onOpenBook(b.id) }, onLongClick = { actionBook = b })
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

      }

      // 悬浮的玻璃件都是书架画面的**兄弟**节点（不在背板里）：封面从它们底下滚过时，在玻璃的边缘被掰弯
      val hidden by remember { derivedStateOf(covered) }
      val glassBackdrop = backdrop.takeIf { c.glassOn && !hidden }
      val canvasColor = c.canvas
      // 内容滚进状态栏底下之后，顶上垫一段由实到透的底色：状态栏的图标始终读得清
      Box(
          Modifier
              .fillMaxWidth()
              .zIndex(9f)
              .drawBehind {
                  drawRect(
                      Brush.verticalGradient(0f to canvasColor.copy(alpha = 0.9f), 0.7f to canvasColor.copy(alpha = 0.55f), 1f to canvasColor.copy(alpha = 0f)),
                      alpha = collapse.value
                  )
              }
              .windowInsetsPadding(WindowInsets.statusBars)
              .height(6.dp)
      )
      val compactTitleBrush = rememberAurora()
      Row(
          Modifier
              .fillMaxWidth()
              .zIndex(10f)
              .windowInsetsPadding(WindowInsets.statusBars)
              .height(56.dp)
              .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
          // 小标题：大标题滚出去之后才浮现的一枚玻璃药丸。没浮现时不组合它 —— 看不见的玻璃照样要采样背板
          val showPill by remember { derivedStateOf { collapse.value > 0.6f } }
          if (showPill) Box(
              Modifier
                  .graphicsLayer {
                      val sm = ((collapse.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                      alpha = sm
                      translationY = (1f - sm) * 10f
                      scaleX = 0.9f + 0.1f * sm
                      scaleY = 0.9f + 0.1f * sm
                  }
                  .glassShadow(CircleShape, Color.Black.copy(alpha = if (c.isDark) 0.20f else 0.10f))
                  .glass(CircleShape, c.pillGlass, glassBackdrop)
                  .padding(horizontal = 16.dp, vertical = 10.dp)
          ) {
              Text("Lector", style = TextStyle(brush = compactTitleBrush, fontSize = 17.sp, fontWeight = FontWeight.Black))
          }
          Spacer(Modifier.weight(1f))
          GlassIconGroup(glassBackdrop) {
              GlassIcon(EchoIcons.Help, S.common.howToUse) { showHelp = true }
              GlassIcon(EchoIcons.Settings, S.common.voiceSettings) { showSettings = true }
          }
      }

      // 底部主动作：拇指可达的「导入」。一枚染了强调色的玻璃胶囊，常驻；
      // 往下翻书时它收成只剩加号的一颗圆（尺寸走弹簧，像一滴水缩回去），回到顶部再展开
      val fabLight = rememberGlassLight()
      val fabCompact by remember { derivedStateOf { collapse.value >= 0.85f } }
      val fabStyle = remember(c) {
          GlassStyle(
              // 一颗染了强调色的玻璃：与其它悬浮件同一个配方（提饱和、4dp、边缘透镜），只是着色换成强调色、浓一些
              tint = c.accent.copy(alpha = if (c.glassOn) 0.62f else 1f), fallback = c.accent,
              blur = if (c.glassOn) 4.dp else 0.dp, saturation = if (c.glassOn) 1.5f else 1f,
              rimHi = Color.White.copy(alpha = 0.75f), rimLo = Color.White.copy(alpha = 0.06f),
              inputScale = 1, lensDepth = 18.dp, lensAmount = 42.dp, dispersion = 0.3f
          )
      }
      Row(
          Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(bottom = 22.dp)
              .zIndex(10f)
              .testTag("shelf.import")
              .liquidPress(light = fabLight, onClickLabel = S.common.importBooks) { showImport = true }
              .glassShadow(CircleShape, Color.Black.copy(alpha = if (c.isDark) 0.30f else 0.14f), blur = 14.dp, dy = 5.dp)
              .glass(CircleShape, fabStyle, glassBackdrop, light = fabLight)
              .animateContentSize(EchoMotion.Playful.spec(IntSize.VisibilityThreshold))
              .height(54.dp)
              .padding(horizontal = if (fabCompact) 17.dp else 22.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
          Icon(EchoIcons.Plus, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
          if (!fabCompact) {
              Spacer(Modifier.width(8.dp))
              Text(
                  S.common.importBooks, color = c.onAccent, fontSize = 15.sp, style = MaterialTheme.typography.labelLargeEmphasized,
                  maxLines = 1, softWrap = false
              )
          }
      }

      // 导入中遮罩
      AnimatedVisibility(
          importing,
          enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Dur.Long, easing = Ease.Linear)),
          exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Dur.Medium, easing = Ease.Linear)),
          modifier = Modifier.matchParentSize().zIndex(60f)
      ) {
          Column(
              Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).echoTap {},
              horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
          ) {
              ContainedLoadingIndicator(modifier = Modifier.size(56.dp))
              Spacer(Modifier.height(14.dp))
              Text(S.shelf.importingBook, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
          }
      }

      CompositionLocalProvider(LocalBackdrop provides backdrop) {
        ImportSheet(open = showImport, graph = graph) { showImport = false }
        TtsSettingsSheet(open = showSettings, graph = graph) { showSettings = false }
        HelpSheet(open = showHelp, graph = graph) { showHelp = false }
        BookActionSheet(book = actionBook, onClose = { actionBook = null }, onOpen = { b -> actionBook = null; onOpenBook(b.id) }) { b ->
            scope.launch {
                library.remove(b.id)
                if (graph.engine.current.bookId == b.id) graph.engine.stopAll()
                Toaster.success(S.shelf.removedFromLibrary)
            }
            actionBook = null
        }
      }
    }
}

/** 应用内更新卡片：发现新版本 → 下载进度 → 安装 */
@Composable
private fun UpdateCard(graph: AppGraph) {
    val c = echo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by graph.updater.state.collectAsState()
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Ready || (state is UpdateState.Error && (state as UpdateState.Error).info != null)
    AnimatedVisibility(visible, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
        val s = state
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .glassSurface(RoundedCornerShape(Radius.lgPlus), c.surfaceGlass())
                .border(1.dp, c.accent.copy(alpha = 0.3f), RoundedCornerShape(Radius.lgPlus))
                .padding(14.dp)
        ) {
            val info = when (s) {
                is UpdateState.Available -> s.info
                is UpdateState.Downloading -> s.info
                is UpdateState.Ready -> s.info
                is UpdateState.Error -> s.info
                else -> null
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (info?.experimental == true) {
                            Text(
                                S.shelf.experimental, color = warningColor(c.isDark), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 6.dp).background(warningColor(c.isDark).copy(alpha = 0.16f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        when (s) {
                            is UpdateState.Ready -> S.shelf.updateDownloaded(s.info.versionName)
                            is UpdateState.Downloading -> if (s.progress <= 0f) S.shelf.connectingToDownloadServer
                            else S.shelf.downloadingUpdate(s.info.versionName, (s.progress * 100).toInt())
                            is UpdateState.Error -> S.shelf.updateFailed
                            else -> S.shelf.updateAvailable(info?.versionName.orEmpty())
                        },
                        color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                    )
                    val sub = when (s) {
                        is UpdateState.Error -> s.message
                        else -> info?.displayNotes?.ifBlank { S.shelf.currentVersionShort(graph.updater.currentVersionName) } ?: ""
                    }
                    if (sub.isNotEmpty()) Text(sub, color = c.text2, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(10.dp))
                when (s) {
                    is UpdateState.Downloading -> LoadingIndicator(progress = { s.progress }, modifier = Modifier.size(34.dp), color = c.accent)
                    is UpdateState.Ready -> GradientButton(S.shelf.install, height = 38.dp, fontSize = 13) {
                        if (!graph.updater.install(context, s.file)) Toaster.show(S.shelf.allowInstallHint, durationMs = 4000)
                    }
                    is UpdateState.Available -> GradientButton(S.shelf.updateNow, height = 38.dp, fontSize = 13) { scope.launch { graph.updater.download(s.info) } }
                    is UpdateState.Error -> if (info != null) GradientButton(S.shelf.retry, height = 38.dp, fontSize = 13) { scope.launch { graph.updater.download(info) } }
                    else -> {}
                }
                // 可以叉掉：按版本永久忽略，重启也不会再弹。手动「检查更新」仍能找到它。
                if (s !is UpdateState.Downloading) {
                    IconButtonEcho(EchoIcons.Close, S.shelf.skipThisVersion, size = 30.dp, iconSize = 15.dp, tint = c.text3) { graph.updater.dismiss() }
                }
            }
            if (info?.experimental == true) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(EchoIcons.Warning, null, tint = warningColor(c.isDark), modifier = Modifier.size(14.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        S.shelf.experimentalWarning,
                        color = c.text3, style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCell(b: BookMeta, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = echo
    val brush = rememberAurora()
    Column(modifier.testTag("shelf.book").echoPress(pressedScale = PressScale.Tile, onLongClick = onLongClick, onClick = onClick)) {
        // 去掉每格一个 elevation 阴影：3 列 × 4 行 = 12 个额外 RenderNode + outline 阴影，
        // 是 Adreno 610 上书架滚动掉帧的主力，而封面本身对比度已足够
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))) {
            BookCover(b, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(progressFraction(b)).height(4.dp).background(brush))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(b.title, color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${progressText(b)} · ${formatChars(b.totalChars)}", color = c.text3, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContinueCard(b: BookMeta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = echo
    // 层级用「容器色阶」表达，这正是 M3 色调容器阶梯的用途：
    // 继续阅读卡 = surfaceContainerHigh + 最大圆角（书架的主角），
    // 书格卡 = surfaceContainer（同级并列），横幅 = surfaceContainer（附属通知）。
    // 之前四个同级容器全用 surfaceContainer —— 有「围合」，但没有「层级」。
    Row(
        modifier
            .fillMaxWidth()
            .echoPress(pressedScale = PressScale.Tile, onClick = onClick)
            .glassSurface(RoundedCornerShape(Radius.xl), c.surfaceGlass())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(52.dp).height(76.dp).shadow(12.dp, RoundedCornerShape(10.dp))) { BookCover(b, Modifier.fillMaxSize(), radius = 10.dp, titleSize = 11) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(S.shelf.continueReading, color = c.accent, style = MaterialTheme.typography.labelSmallEmphasized)
            Spacer(Modifier.height(2.dp))
            Text(b.title, color = c.text, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(b.author); append(" · "); append(progressText(b))
                    b.lastReadAt?.let { append(" · "); append(relativeTime(it)) }
                },
                color = c.text3, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            GradientBar(progressFraction(b), Modifier.fillMaxWidth(), height = 4.dp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(EchoIcons.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun BoxScope.BookActionSheet(book: BookMeta?, onClose: () -> Unit, onOpen: (BookMeta) -> Unit, onDelete: (BookMeta) -> Unit) {
    val c = echo
    var last by remember { mutableStateOf<BookMeta?>(null) }
    if (book != null) last = book
    val b = book ?: last
    EchoSheet(open = book != null, onDismiss = onClose, title = b?.title ?: "", maxHeightFraction = 0.6f) {
        if (b != null) {
            Text(
                S.shelf.bookMeta(b.author, b.format.label.uppercase(), b.chapterCount, formatChars(b.totalChars)) + (b.intro?.let { "\n$it" } ?: ""),
                color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(14.dp))
            SettingsSection {
                row(S.shelf.continueReading, trailing = { ChevronEnd() }, onClick = { onOpen(b) })
                row(S.shelf.deleteFromLibrary, icon = EchoIcons.Trash, onClick = { onDelete(b) })
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 书架进度：直接用 core 里那一个书级函数，和阅读器底栏完全一致。
 *
 * 旧实现是 `chapterIndex / (chapterCount - 1)`：它无视章内偏移，且在**到达**最后一章的
 * 瞬间就报 100%。12 章的书刚翻到第 12 章时，书架显示「已读 100%」，阅读器显示 0%，
 * 真值是 91.7% —— 同一本书、同一时刻、两个数字差了 100 个百分点。
 */
private fun progressFraction(b: BookMeta): Float = b.readFraction()

private fun progressText(b: BookMeta): String =
    if (!b.started()) S.shelf.notStarted else S.shelf.readPercent((progressFraction(b) * 100).toInt())

/** 英文没有「万」：按千进位，百万字以上再进一级（长篇网文动辄几百万字，「3500.0k」读不出来） */
/** 书的篇幅：中文按「万字」，英文按 k / M（各语言包里的 bookLength） */
fun formatChars(n: Int): String = app.echoread.core.i18n.S.shelf.bookLength(n)

/** 「刚刚 / 3 分钟前 / 2 天前」，一周以上给日期。书架的「上次阅读」与书签的「什么时候加的」共用 */
internal fun relativeTime(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> S.shelf.justNow
        d < 3_600_000 -> S.shelf.minAgo(d / 60_000)
        d < 86_400_000 -> S.shelf.hoursAgo(d / 3_600_000)
        d < 7 * 86_400_000 -> S.shelf.daysAgo(d / 86_400_000)
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, I18n.locale).format(java.util.Date(ts))
    }
}


/**
 * 书架的环境光：三团很淡的配色光晕，铺在页面底色上、所有内容的后面。
 *
 * 玻璃要有东西可透才是玻璃 —— 一张纯色的底上，再好的折射也只是一块白。有了这层带颜色的光，
 * 半透明的卡片透出深浅不一的色调，悬浮件的边缘能把它掰弯。
 * 光晕随书架滚动缓缓上移（视差），不做常驻动画：不滚动就没有一帧额外的绘制。
 * 纯黑模式下不画（那里要的就是关着的像素），关掉玻璃时也不画。
 *
 * 三团光先画进一张十分之一大小的小图，之后每帧只是把这张图放大贴一次。
 * 直接每帧画三个整屏的径向渐变，在 Adreno 610 上是三层整屏的混合 —— 实测书架滚动每帧多出好几毫秒；
 * 光晕本来就没有细节，放大十倍看不出任何差别。
 */
@Composable
private fun AmbientLight(listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier) {
    val c = echo
    if (!c.glassOn || c.isBlack) return
    val scheme = MaterialTheme.colorScheme
    val one = if (c.isDark) scheme.primary.copy(alpha = 0.20f) else scheme.primaryContainer.copy(alpha = 0.95f)
    val two = if (c.isDark) scheme.tertiary.copy(alpha = 0.16f) else scheme.tertiaryContainer.copy(alpha = 0.85f)
    val three = if (c.isDark) scheme.secondary.copy(alpha = 0.13f) else scheme.secondaryContainer.copy(alpha = 0.80f)
    val cache = remember(one, two, three) { arrayOfNulls<androidx.compose.ui.graphics.ImageBitmap>(1) }
    Spacer(
        modifier.drawBehind {
            // 图比屏幕高两成：上移时底下不露边
            val tall = size.height * 1.2f
            val image = cache[0]?.takeIf { it.width == (size.width / AMBIENT_SCALE).toInt().coerceAtLeast(1) } ?: run {
                val w = (size.width / AMBIENT_SCALE).toInt().coerceAtLeast(1)
                val h = (tall / AMBIENT_SCALE).toInt().coerceAtLeast(1)
                val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, h)
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())
                ) {
                    fun glow(color: Color, x: Float, y: Float, r: Float) {
                        val at = Offset(x, y)
                        drawCircle(Brush.radialGradient(0f to color, 1f to color.copy(alpha = 0f), center = at, radius = r), radius = r, center = at)
                    }
                    val sw = w.toFloat()
                    val sh = h / 1.2f
                    glow(one, sw * 0.10f, sh * 0.08f, sw * 0.90f)
                    glow(two, sw * 1.02f, sh * 0.36f, sw * 0.78f)
                    glow(three, sw * 0.22f, sh * 0.94f, sw * 0.95f)
                }
                cache[0] = bitmap
                bitmap
            }
            val scrolled = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else size.height
            val drift = -scrolled.coerceAtMost(size.height) * 0.16f
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(0, drift.toInt()),
                dstSize = IntSize(size.width.toInt(), tall.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
            )
        }
    )
}

private const val AMBIENT_SCALE = 10f
