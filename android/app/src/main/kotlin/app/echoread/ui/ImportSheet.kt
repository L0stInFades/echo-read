package app.echoread.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.echoread.AppGraph
import app.echoread.data.AccessTier
import app.echoread.data.BookCandidate
import app.echoread.data.ScanSource
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class TypeFilter(val label: String) { ALL("全部"), TXT("TXT"), EPUB("EPUB") }

/**
 * 应用内导入界面：自动扫描本机的 TXT / EPUB，选中即批量入库。
 *
 * 取代原来「只能开系统文件选择器逐个挑」的做法。系统选择器仍保留为兜底入口 ——
 * 在完全没有存储授权的设备上它是唯一能用的路径。
 *
 * 扫描能覆盖到多少，取决于系统给到的访问档位（见 [AccessTier]）：
 * 顶部的引导卡片会按当前档位给出唯一有意义的那一步操作，而不是把所有权限一股脑要一遍。
 */
@Composable
fun BoxScope.ImportSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val c = echo
    val context = LocalContext.current
    val scanner = graph.scanner

    val candidates by graph.scanResults.collectAsState()
    val busy by graph.scanBusy.collectAsState()
    val progress by graph.scanProgress.collectAsState()
    val truncated by graph.scanTruncated.collectAsState()
    val books by graph.library.books.collectAsState()

    var tier by remember { mutableStateOf(scanner.tier()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TypeFilter.ALL) }
    val selected = remember { mutableStateListOf<String>() }

    // 从系统设置页/目录选择器回来时重新判档：用户可能刚刚把权限打开
    LifecycleResumeEffect(Unit) {
        tier = scanner.tier()
        onPauseOrDispose { }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                // 只取读权限：扫描与导入全程只 query / openInputStream，要写权限属于过度索取
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scanner.rememberTree(uri)
            tier = scanner.tier()
            graph.rescan()
        }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tier = scanner.tier()
        if (granted) graph.rescan()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            graph.pendingImports.update { it + uris }
            onClose()
        }
    }

    // 首次打开且从未扫过时自动扫一遍（已有结果就直接展示，避免每次开面板都重扫）。
    // key 里必须带上 tier：用户从系统设置页授权回来时 tier 才变成可扫状态，
    // 只用 open 做 key 的话，那一次「授权 → 返回」不会自动扫，用户会看到一个空列表还得自己点「重新扫描」。
    // （模拟器实测发现，不是推演出来的。）
    LaunchedEffect(open, tier) {
        if (!open || busy || tier == AccessTier.NONE) return@LaunchedEffect
        // 结果为空要扫；「档位变好了」也要扫 —— 先授权了一个文件夹拿到 3 本，
        // 再去开「所有文件访问权限」回来，结果集若不刷新，顶部写着「已可扫描整机存储」
        // 底下却仍是那 3 本，用户会以为整机就这么点书。
        if (candidates.isEmpty() || graph.scannedTier.value != tier) graph.rescan()
    }
    LaunchedEffect(open) { if (!open) selected.clear() }

    // 书架里已有的书名（去扩展名比对）。没有为此改数据库表结构，
    // 靠书名匹配覆盖绝大多数情况：TXT 的书名本来就取自文件名。
    val importedNames = remember(books) { books.map { it.title.trim().lowercase() }.toHashSet() }

    val shown = remember(candidates, query, filter) {
        val q = query.trim().lowercase()
        candidates.asSequence()
            .filter { filter == TypeFilter.ALL || (filter == TypeFilter.EPUB) == it.isEpub }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || (it.title?.lowercase()?.contains(q) == true) }
            .toList()
    }

    // 整表可滚 + 列表自带 heightIn 上界：与 TtsSettingsSheet 同一套已验证的组合。
    // 不能用 scrollable = false —— 访问引导卡片 + 已授权文件夹一多，底部的「导入选中」按钮
    // 就会被挤出弹层且没有任何办法滚到（弹层内容区在 scrollable=false 时不可滚动）。
    EchoSheet(open = open, onDismiss = onClose, title = "导入书籍", maxHeightFraction = 0.92f) {
        AccessCard(tier = tier, scanner = scanner, context = context,
            onGrantAllFiles = { scanner.allFilesAccessIntent()?.let { runCatching { context.startActivity(it) } } },
            onPickFolder = { runCatching { treePicker.launch(scanner.initialTreeUri()) } },
            onRequestLegacy = { legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE) },
            onForgetTree = { t ->
                scanner.forgetTree(t)
                tier = scanner.tier()
                graph.rescan()
            }
        )

        // 扫描状态
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        busy -> "正在扫描…已发现 ${progress.first} 本"
                        candidates.isEmpty() -> "尚未发现书籍"
                        else -> "共发现 ${candidates.size} 本" + if (shown.size != candidates.size) "，筛选出 ${shown.size} 本" else ""
                    },
                    color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                if (busy && progress.second > 0) {
                    Text("已遍历 ${progress.second} 个文件", color = c.text3, fontSize = 11.sp)
                } else if (!busy && truncated) {
                    // 触到遍历上限就如实说：把截断结果当完整结果展示，用户会以为「书就这些」
                    Text("目录太多，只扫了一部分；可改为授权具体文件夹再扫", color = warningColor(c.isDark), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Text("停止", color = c.danger, fontSize = 12.sp,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { graph.cancelScan() }.padding(6.dp))
            } else {
                Row(
                    Modifier
                        .border(1.dp, c.border, CircleShape)
                        .echoPress(pressedScale = PressScale.Chip) { graph.rescan() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(EchoIcons.Refresh, null, tint = c.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重新扫描", color = c.accent, fontSize = 12.sp)
                }
            }
        }
        // M3 Expressive 的波形进度条：扫描是不定时长的任务，正好用不定态
        Box(Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp)) {
            if (busy) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(10.dp))

        // 搜索 + 类型筛选
        Row(
            Modifier.fillMaxWidth().background(c.cardAlt, CircleShape).border(1.dp, c.border, CircleShape)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(EchoIcons.Search, null, tint = c.text3, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            val style = fieldTextStyle(c.text, 13)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = style, cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f).height(22.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("搜索文件名或书名", style = style.copy(color = c.text3))
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text("清除", color = c.text3, fontSize = 11.sp,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { query = "" })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (f in TypeFilter.entries) Chip(f.label, selected = filter == f) { filter = f }
            Spacer(Modifier.weight(1f))
            if (shown.isNotEmpty()) {
                val allSelected = shown.all { it.uri.toString() in selected }
                Text(
                    if (allSelected) "取消全选" else "全选",
                    color = c.accent, fontSize = 12.sp,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) {
                        if (allSelected) shown.forEach { selected.remove(it.uri.toString()) }
                        else shown.forEach { if (it.uri.toString() !in selected) selected.add(it.uri.toString()) }
                    }.padding(4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 候选列表
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(shown, key = { it.uri.toString() }) { cand ->
                CandidateRow(
                    cand = cand,
                    checked = cand.uri.toString() in selected,
                    imported = cand.baseName.trim().lowercase() in importedNames,
                    onToggle = {
                        val k = cand.uri.toString()
                        if (k in selected) selected.remove(k) else selected.add(k)
                    }
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (busy) "扫描中…" else if (candidates.isEmpty()) "还没扫描到书。可以先授权访问，或用下面的「从文件管理器选择」。" else "没有符合条件的书",
                        color = c.text3, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 标签、可用态、实际导入必须读**同一个**集合。
        // 早先三者分别读 selected / selected / shown：用户在「全部」里勾 10 本再切到 EPUB 页签，
        // 按钮仍写「导入选中的 10 本」却只会导入当前可见的那 2 本，另外 8 本被静默丢弃；
        // 若筛选后一本都不可见，按钮甚至看着可点却毫无反应。
        // 这里统一从全量 candidates 解析，顺带把重扫后已消失的陈旧勾选自然排除掉。
        val picked = remember(candidates, selected.size, selected.toList()) {
            candidates.filter { it.uri.toString() in selected }
        }
        GradientButton(
            if (picked.isEmpty()) "选择要导入的书" else "导入选中的 ${picked.size} 本",
            Modifier.fillMaxWidth(),
            icon = EchoIcons.Download,
            enabled = picked.isNotEmpty(),
            height = 50.dp
        ) {
            val uris = picked.map { it.uri }
            if (uris.isNotEmpty()) {
                graph.pendingImports.update { it + uris }
                selected.clear()
                onClose()
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlineButton("从文件管理器选择…", Modifier.fillMaxWidth()) {
            filePicker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*"))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 按当前访问档位给出**唯一一步**有意义的操作，而不是把所有权限一次要完 */
@Composable
private fun AccessCard(
    tier: AccessTier,
    scanner: app.echoread.data.BookScanner,
    context: android.content.Context,
    onGrantAllFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRequestLegacy: () -> Unit,
    onForgetTree: (android.net.Uri) -> Unit
) {
    val c = echo
    // 这里是一次 binder IPC（persistedUriPermissions）且可能落一次 SharedPreferences 写。
    // AccessCard 在扫描期间会随进度每帧重组，绝不能放在组合期直接调用。
    val trees = remember(tier) { scanner.grantedTrees() }
    when (tier) {
        AccessTier.ALL_FILES, AccessTier.LEGACY -> {
            Row(
                Modifier.fillMaxWidth().background(c.accentSoft, RoundedCornerShape(Radius.md)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(EchoIcons.CheckCircle, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("已可扫描整机存储", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("换文件夹", color = c.accent, fontSize = 12.sp,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { onPickFolder() }.padding(4.dp))
            }
        }
        else -> {
            Column(
                Modifier.fillMaxWidth().background(c.cardAlt, RoundedCornerShape(Radius.md))
                    .border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(EchoIcons.Scan, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("让 EchoRead 找到手机里的书", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        scanner.needsLegacyPermission() -> "授予存储读取权限后，可自动扫描整个手机里的 TXT / EPUB。"
                        scanner.allFilesAccessSupported ->
                            "Android 11 起，系统只允许「所有文件访问权限」做全盘扫描；" +
                                "不想开也可以只授权某个文件夹（书籍常放在 Download、Documents、Books 里）。"
                        scanner.safCanGrantRoot -> "选择文件夹即可扫描。本系统版本还允许直接选中「内部存储」根目录，一次授权覆盖全机。"
                        else -> "选择一个存放书籍的文件夹即可自动扫描。"
                    },
                    color = c.text2, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanner.needsLegacyPermission()) {
                        GradientButton("授予存储权限", Modifier.weight(1f), height = 42.dp, fontSize = 13) { onRequestLegacy() }
                    } else if (scanner.allFilesAccessSupported) {
                        GradientButton("开启全盘扫描", Modifier.weight(1f), height = 42.dp, fontSize = 13) { onGrantAllFiles() }
                    }
                    OutlineButton(if (scanner.safCanGrantRoot) "选择整个存储" else "选择文件夹", Modifier.weight(1f), height = 42.dp) { onPickFolder() }
                }
                if (trees.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("已授权的文件夹", color = c.text3, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    for (t in trees) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(EchoIcons.Folder, null, tint = c.text3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(scanner.treeLabel(t), color = c.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            // 授权会一直累积，每次扫描都要重走一遍；给一个撤销入口
                            Text(
                                "移除", color = c.text3, fontSize = 11.sp,
                                modifier = Modifier.echoPress(pressedScale = PressScale.Chip) { onForgetTree(t) }.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(cand: BookCandidate, checked: Boolean, imported: Boolean, onToggle: () -> Unit) {
    val c = echo
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (checked) c.accentSoft else c.cardAlt, RoundedCornerShape(Radius.md))
            .border(1.dp, if (checked) c.accent else Color.Transparent, RoundedCornerShape(Radius.md))
            .echoPress(pressedScale = PressScale.Tile) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(if (cand.isEpub) c.accent.copy(alpha = 0.16f) else c.text3.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(EchoIcons.FileText, null, tint = if (cand.isEpub) c.accent else c.text2, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cand.title ?: cand.baseName,
                    color = if (checked) c.accent else c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (imported) {
                    Text(
                        "已在书架", color = c.text3, fontSize = 9.sp,
                        modifier = Modifier.padding(start = 5.dp).background(c.text3.copy(alpha = 0.14f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                buildString {
                    append(if (cand.isEpub) "EPUB" else "TXT")
                    append(" · ").append(formatBytes(cand.size))
                    if (cand.lastModified > 0) append(" · ").append(DateFormat.getDateInstance(DateFormat.SHORT, Locale.CHINA).format(Date(cand.lastModified)))
                    append(" · ").append(sourceLabel(cand.source))
                },
                color = c.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val sub = cand.author ?: cand.path
            if (!sub.isNullOrBlank()) {
                Text(sub, color = c.text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (checked) EchoIcons.CheckCircle else EchoIcons.Plus,
            null,
            tint = if (checked) c.accent else c.text3,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun sourceLabel(s: ScanSource): String = when (s) {
    ScanSource.FILES -> "本机"
    ScanSource.MEDIA_STORE -> "媒体库"
    ScanSource.FOLDER -> "授权文件夹"
}
