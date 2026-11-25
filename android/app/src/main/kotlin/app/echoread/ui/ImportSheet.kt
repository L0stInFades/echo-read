package app.echoread.ui

import app.echoread.core.i18n.S
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.echoread.AppGraph
import app.echoread.core.i18n.I18n
import app.echoread.data.AccessTier
import app.echoread.data.BookCandidate
import app.echoread.data.ScanSource
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date

private enum class TypeFilter {
    ALL, TXT, EPUB;

    // 文案必须是 getter：枚举常量只构造一次，写进构造参数会停在首次访问时的语言
    val label: String get() = when (this) {
        ALL -> S.common.all
        TXT -> "TXT"
        EPUB -> "EPUB"
    }
}

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
    EchoSheet(open = open, onDismiss = onClose, title = S.common.importBooks, maxHeightFraction = 0.92f) {
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

        Spacer(Modifier.height(12.dp))
        // 扫描状态收进卡片，与全应用的分组语言一致：
        // 它是一个「当前状态 + 一个动作」的单元，不是浮在底板上的一行字
        SettingsSection {
            custom {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        busy -> S.importer.scanningFound(progress.first)
                        candidates.isEmpty() -> S.importer.noBooksFoundYet
                        else -> S.importer.foundBooks(candidates.size) +
                            if (shown.size != candidates.size) S.importer.shown(shown.size) else ""
                    },
                    color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                )
                if (busy && progress.second > 0) {
                    Text(S.importer.checkedFiles(progress.second), color = c.text3, style = MaterialTheme.typography.labelSmall)
                } else if (!busy && truncated) {
                    // 触到遍历上限就如实说：把截断结果当完整结果展示，用户会以为「书就这些」
                    Text(S.importer.scanTruncated, color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                TextAction(S.importer.stop, color = c.danger) { graph.cancelScan() }
            } else {
                SoftButton(S.importer.rescan, color = c.accent, height = 34.dp, icon = EchoIcons.Refresh) { graph.rescan() }
            }
        }
        // M3 Expressive 的波形进度条：扫描是不定时长的任务，正好用不定态
        Box(Modifier.fillMaxWidth().height(10.dp).padding(top = 6.dp)) {
            if (busy) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 搜索 + 类型筛选
        SearchField(query, { query = it }, S.importer.searchFileNameOr)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (f in TypeFilter.entries) Chip(f.label, selected = filter == f) { filter = f }
            Spacer(Modifier.weight(1f))
            if (shown.isNotEmpty()) {
                val allSelected = shown.all { it.uri.toString() in selected }
                TextAction(if (allSelected) S.importer.deselectAll else S.importer.selectAll) {
                    if (allSelected) shown.forEach { selected.remove(it.uri.toString()) }
                    else shown.forEach { if (it.uri.toString() !in selected) selected.add(it.uri.toString()) }
                }
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
                        if (busy) S.importer.scanning
                        else if (candidates.isEmpty()) S.importer.noBooksScannedHint
                        else S.importer.noMatchingBooks,
                        color = c.text3, style = MaterialTheme.typography.bodyMedium,
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
            if (picked.isEmpty()) S.importer.selectBooksToImport else S.importer.importSelected(picked.size),
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
        SoftButton(S.importer.browseFiles, Modifier.fillMaxWidth()) {
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
                Modifier.fillMaxWidth().background(c.accentSoft, RoundedCornerShape(GroupRadius)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(EchoIcons.CheckCircle, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(S.importer.readyToScanAll, color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextAction(S.importer.changeFolder) { onPickFolder() }
            }
        }
        else -> {
            Column(
                Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(GroupRadius)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(EchoIcons.Scan, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(S.importer.letLectorFindBooks, color = c.text, style = MaterialTheme.typography.titleSmallEmphasized)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        scanner.needsLegacyPermission() -> S.importer.grantAccessHint
                        scanner.allFilesAccessSupported ->
                            S.importer.allFilesAccessHint +
                                S.importer.ifYoudRatherNot
                        scanner.safCanGrantRoot -> S.importer.chooseFolderOrRootHint
                        else -> S.importer.chooseFolderHint
                    },
                    color = c.text2, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanner.needsLegacyPermission()) {
                        GradientButton(S.importer.grantAccess, Modifier.weight(1f), height = 42.dp, fontSize = 13) { onRequestLegacy() }
                    } else if (scanner.allFilesAccessSupported) {
                        GradientButton(S.importer.enableFullScan, Modifier.weight(1f), height = 42.dp, fontSize = 13) { onGrantAllFiles() }
                    }
                    SoftButton(if (scanner.safCanGrantRoot) S.importer.chooseStorage else S.importer.chooseFolder, Modifier.weight(1f), height = 42.dp) { onPickFolder() }
                }
                if (trees.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(S.importer.grantedFolders, color = c.text3, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    for (t in trees) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(EchoIcons.Folder, null, tint = c.text3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(scanner.treeLabel(t), color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            // 授权会一直累积，每次扫描都要重走一遍；给一个撤销入口
                            TextAction(S.importer.remove, color = c.text2) { onForgetTree(t) }
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (checked) c.accentSoft else c.fill)
            .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { onToggle() }
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
                    color = if (checked) c.accent else c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (imported) {
                    Spacer(Modifier.width(6.dp))
                    Badge(S.importer.inLibrary)
                }
            }
            Text(
                buildString {
                    append(if (cand.isEpub) "EPUB" else "TXT")
                    append(" · ").append(formatBytes(cand.size))
                    if (cand.lastModified > 0) append(" · ").append(DateFormat.getDateInstance(DateFormat.SHORT, I18n.locale).format(Date(cand.lastModified)))
                    append(" · ").append(sourceLabel(cand.source))
                },
                color = c.text3, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val sub = cand.author ?: cand.path
            if (!sub.isNullOrBlank()) {
                Text(sub, color = c.text3, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (checked) CheckBadge() else Icon(EchoIcons.Plus, null, tint = c.text3, modifier = Modifier.size(18.dp))
    }
}

private fun sourceLabel(s: ScanSource): String = when (s) {
    ScanSource.FILES -> S.importer.device
    ScanSource.MEDIA_STORE -> S.importer.mediaLibrary
    ScanSource.FOLDER -> S.importer.grantedFolder
}
