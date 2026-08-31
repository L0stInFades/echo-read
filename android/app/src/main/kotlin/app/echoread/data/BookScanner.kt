package app.echoread.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.echoread.core.EpubParser
import java.io.File
import java.util.ArrayDeque

/** 候选书籍的来源，决定界面上的来源标签与可用性提示 */
enum class ScanSource { FILES, MEDIA_STORE, FOLDER }

/**
 * 当前能拿到的最高存储访问档位。
 *
 * 分档的依据不是文档措辞而是 AOSP 源码：`MediaProvider` 的 `AccessChecker.getWhereForConstrainedAccess`
 * 在 API 29+ 拼出的 WHERE 子句里，**根本没有一条分支会放行别的应用拥有的
 * `MEDIA_TYPE_DOCUMENT`**（txt / epub 正是被归到这一类）。也就是说：
 * 没有「所有文件访问权限」时，MediaStore 查询在 29+ 上只会返回本应用自己写入的文件。
 * 唯一的总开关是 `checkCallingPermissionGlobal`，它由 MANAGE_EXTERNAL_STORAGE 打开，
 * 打开后查询不带任何 WHERE —— 全设备一条 SQL 查完，比遍历文件系统快一个数量级。
 *
 * 同样地，SAF 在 API 30+ 也给不了「整机」：`ExternalStorageProvider.shouldBlockDirectoryFromTree`
 * 明确挡掉存储卷根目录、`Download`、`Android` 三者（USB-OTG 根例外，SD 卡根同样被挡）。
 * 恰好在 **API 29** 上根目录还能授权 —— 那一版可以给用户一个「授权整个手机存储」的一键入口。
 */
enum class AccessTier {
    /** API 30+ 且已授予「所有文件访问权限」，或 API ≤ 28 且已授予读存储：可全设备扫描 */
    ALL_FILES,

    /** API ≤ 28 的传统读权限（等价于 ALL_FILES，但走的是旧路径） */
    LEGACY,

    /** 只有若干个被持久授权的 SAF 目录 */
    SAF_TREES,

    /** 什么都没有：只能用系统文件选择器逐个挑 */
    NONE
}

/**
 * 扫描到的一本候选书。[uri] 一定可以直接交给 [LibraryRepo.importUri]
 * （file:// 与 content:// 都走 contentResolver.openInputStream）。
 */
data class BookCandidate(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isEpub: Boolean,
    val source: ScanSource,
    /** 展示用的可读路径；SAF / MediaStore 下可能为 null */
    val path: String? = null,
    /** EPUB 元数据（仅本地文件顺带读取，失败为 null，不影响导入） */
    val title: String? = null,
    val author: String? = null
) {
    /** 去重键：同名同大小视为同一本（同一本书常同时被多条路径发现） */
    val dedupKey: String get() = "${name.lowercase()}:$size"

    /** 与书架比对用的书名（去扩展名） */
    val baseName: String get() = name.substringBeforeLast('.', name)
}

/**
 * 全盘扫描 TXT / EPUB。
 *
 * Android 的分区存储把「读到别人放在手机里的文档」拆成了三条互不重叠的路径，
 * 这里三条都实现，能用哪条用哪条，结果合并去重：
 *
 * | 路径 | 可用条件 | 覆盖面 |
 * |---|---|---|
 * | [ScanSource.FILES] 直接遍历文件系统 | API ≤ 28 且授予 READ_EXTERNAL_STORAGE；或 API ≥ 30 且授予「所有文件访问权限」 | 全盘，最完整 |
 * | [ScanSource.FOLDER] SAF 目录授权 | 用户用系统目录选择器授权过某个目录（权限持久化） | 该目录树，任何版本都可用 |
 * | [ScanSource.MEDIA_STORE] 媒体库查询 | 有存储读权限 | 已被媒体库索引的文档，各 ROM 差异大，只作补充 |
 *
 * 全部在 IO 线程执行并逐层检查取消：目录树可能有几十万个文件。
 */
class BookScanner(private val context: Context) {

    private val prefs = context.getSharedPreferences("echo-read-scan", Context.MODE_PRIVATE)

    /**
     * 上一次扫描是否因为触到遍历上限而提前结束。
     * 三个上限（目录深度 / 总条目数 / SAF 目录数）任一触发都会置位 ——
     * 把截断的结果当成完整结果展示，会让用户以为「书就这些」而放弃。
     */
    @Volatile
    var truncated: Boolean = false
        private set

    /* ---------------- 权限与授权状态 ---------------- */

    /** 是否持有「所有文件访问权限」（API 30+）。API ≤ 28 时由 READ_EXTERNAL_STORAGE 等价替代 */
    fun hasAllFilesAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> hasLegacyReadPermission()
    }

    fun hasLegacyReadPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** API ≤ 28 才需要（也才有用）运行时存储权限；29 起旧权限拿到了也不能遍历文件系统 */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyReadPermission()

    /** 当前档位 */
    fun tier(): AccessTier = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> AccessTier.ALL_FILES
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && hasLegacyReadPermission() -> AccessTier.LEGACY
        grantedTrees().isNotEmpty() -> AccessTier.SAF_TREES
        else -> AccessTier.NONE
    }

    /** 「所有文件访问权限」这个开关在本机是否存在（API 30+ 才有） */
    val allFilesAccessSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 恰好在 API 29 上，SAF 还能授权整个内部存储根目录
     * （`FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE` 是 API 30 才加的），可以给一键入口。
     */
    val safCanGrantRoot: Boolean get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q

    /**
     * 目录选择器的建议起始位置：直接落在主存储卷上，省掉用户在侧边栏里找。
     * API 29 起 [android.os.storage.StorageVolume.createOpenDocumentTreeIntent] 提供这个 Uri。
     */
    fun initialTreeUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            val primary = sm?.primaryStorageVolume ?: return null
            @Suppress("DEPRECATION")
            primary.createOpenDocumentTreeIntent().getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        }.getOrNull()
    }

    /** 跳到系统的「所有文件访问权限」设置页（API 30+） */
    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 已被持久授权、且仍然有效的目录树 */
    fun grantedTrees(): List<Uri> {
        val saved = prefs.getStringSet(KEY_TREES, emptySet()) ?: emptySet()
        val live = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val kept = saved.filter { it in live }
        if (kept.size != saved.size) prefs.edit().putStringSet(KEY_TREES, kept.toSet()).apply()
        return kept.map { Uri.parse(it) }
    }

    /** 记住一次目录授权（调用方需先 takePersistableUriPermission） */
    fun rememberTree(uri: Uri) {
        val set = (prefs.getStringSet(KEY_TREES, emptySet()) ?: emptySet()).toMutableSet()
        set.add(uri.toString())
        prefs.edit().putStringSet(KEY_TREES, set).apply()
    }

    fun forgetTree(uri: Uri) {
        val set = (prefs.getStringSet(KEY_TREES, emptySet()) ?: emptySet()).toMutableSet()
        set.remove(uri.toString())
        prefs.edit().putStringSet(KEY_TREES, set).apply()
        runCatching {
            // 取的时候只取了读权限，但历史授权可能带写权限，两个都释放才干净
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** 目录树的可读名字（取最后一段 documentId） */
    fun treeLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.lastPathSegment ?: "目录"
        return id.substringAfterLast(':').ifEmpty { id }.substringAfterLast('/').ifEmpty { id }
    }

    /* ---------------- 扫描 ---------------- */

    /**
     * 执行一次全量扫描。[onProgress] 在 IO 线程上被频繁调用，传入「当前已发现数」，
     * 调用方需自行切回主线程更新 UI。
     */
    suspend fun scan(onProgress: (found: Int, scanned: Int) -> Unit = { _, _ -> }): List<BookCandidate> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, BookCandidate>()
            var scanned = 0
            truncated = false

            // 先到先得 + 升级：媒体库跑在最前面（一条 SQL 就能出首批结果），但它给不出真实路径，
            // 也无法顺带读 EPUB 书名。随后文件遍历发现同一本时必须**顶掉**那条记录，
            // 否则 path / title 永远是空的 —— 先到先得会让更完整的那份数据白白丢弃。
            fun offer(c: BookCandidate) {
                val old = found[c.dedupKey]
                if (old == null) {
                    found[c.dedupKey] = c
                    onProgress(found.size, scanned)
                } else if (old.source != ScanSource.FILES && c.source == ScanSource.FILES) {
                    found[c.dedupKey] = c
                }
            }

            val tier = tier()
            val wholeDevice = tier == AccessTier.ALL_FILES || tier == AccessTier.LEGACY

            // 有整机权限时，媒体库是**最快**的一条路：一条无 WHERE 的 SQL 查完全设备。
            // 没有整机权限则完全跳过 —— 29+ 上它只会返回本应用自己写过的文件，纯属浪费。
            if (wholeDevice) runCatching { scanMediaStore { c -> scanned++; offer(c) } }

            // 文件系统遍历作为补齐：媒体库尚未扫描到的新文件只能靠它发现
            if (wholeDevice) {
                for (root in storageRoots()) {
                    walk(
                        root,
                        onVisit = {
                            scanned++
                            if (scanned % 512 == 0) onProgress(found.size, scanned)
                        },
                        onHit = { f -> offer(fromFile(f)) }
                    )
                }
            }

            // SAF 目录授权：任何版本都可用，也是 API 29 与未授予整机权限时的唯一自动来源
            for (tree in grantedTrees()) {
                currentCoroutineContext().ensureActive()
                runCatching { scanTree(tree) { c -> scanned++; offer(c) } }
            }

            // EPUB 书名预览放在遍历之后单独做：开 zip 读中央目录虽快，放进目录遍历的热路径上
            // 仍会把一次全盘扫描拖慢一个数量级。只对本地文件、且只做前 MAX_PEEKS 本。
            var peeks = 0
            for ((k, c) in found) {
                if (peeks >= MAX_PEEKS) break
                if (!c.isEpub || c.uri.scheme != "file" || c.title != null) continue
                currentCoroutineContext().ensureActive()
                peeks++
                val f = c.uri.path?.let { File(it) } ?: continue
                val meta = EpubParser.peekMeta(f) ?: continue
                found[k] = c.copy(title = meta.first, author = meta.second.ifBlank { null })
            }

            onProgress(found.size, scanned)
            found.values.sortedByDescending { it.lastModified }
        }

    /* ---------------- 路径一：文件系统遍历 ---------------- */

    /** 主存储 + 可能存在的 SD 卡根目录（由 app 私有目录反推卷根，兼容各版本） */
    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { if (it.isDirectory) roots.add(it) }
        runCatching {
            for (d in context.getExternalFilesDirs(null)) {
                if (d == null) continue
                // /storage/XXXX-XXXX/Android/data/<pkg>/files → /storage/XXXX-XXXX
                val marker = "/Android/data/"
                val p = d.absolutePath
                val i = p.indexOf(marker)
                if (i > 0) {
                    val vol = File(p.substring(0, i))
                    if (vol.isDirectory && vol.canRead()) roots.add(vol)
                }
            }
        }
        return roots.toList()
    }

    /** [onVisit] 每遍历到一个条目就回调一次（进度用），[onHit] 只在命中书籍时回调 */
    private suspend inline fun walk(root: File, crossinline onVisit: () -> Unit, crossinline onHit: (File) -> Unit) {
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.push(root to 0)
        var visited = 0
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.pop()
            if (depth > MAX_DEPTH) {
                truncated = true
                continue
            }
            val children = dir.listFiles() ?: continue
            for (f in children) {
                onVisit()
                if (++visited > MAX_VISITS) {
                    truncated = true
                    return
                }
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    // Android/ 下全是应用私有数据，几十万个文件且不会放书
                    if (depth == 0 && (name == "Android" || name == "LOST.DIR")) continue
                    stack.push(f to depth + 1)
                } else if (isBook(name)) {
                    val len = f.length()
                    if (len > 0 && len <= MAX_BOOK_BYTES) onHit(f)
                }
            }
        }
    }

    private fun fromFile(f: File): BookCandidate {
        val epub = f.name.endsWith(".epub", ignoreCase = true)
        return BookCandidate(
            uri = Uri.fromFile(f),
            name = f.name,
            size = f.length(),
            lastModified = f.lastModified(),
            isEpub = epub,
            source = ScanSource.FILES,
            path = f.parent
        )
    }

    /* ---------------- 路径二：SAF 目录树 ---------------- */

    /**
     * 用 [DocumentsContract] 批量查询子文档，而不是 DocumentFile.listFiles()：
     * 后者每个条目都要单独发一次 query，一个几千文件的目录能跑上十几秒。
     */
    private suspend fun scanTree(tree: Uri, onHit: (BookCandidate) -> Unit) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val stack = ArrayDeque<String>()
        stack.push(DocumentsContract.getTreeDocumentId(tree))
        var dirs = 0
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++dirs > MAX_TREE_DIRS) {
                truncated = true
                return
            }
            val parentId = stack.pop()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = runCatching { context.contentResolver.query(childrenUri, projection, null, null, null) }.getOrNull() ?: continue
            cursor.use { cur ->
                while (cur.moveToNext()) {
                    val docId = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && name != "Android") stack.push(docId)
                        continue
                    }
                    if (!isBook(name)) continue
                    val size = if (cur.isNull(3)) 0L else cur.getLong(3)
                    if (size > MAX_BOOK_BYTES) continue
                    val modified = if (cur.isNull(4)) 0L else cur.getLong(4)
                    onHit(
                        BookCandidate(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            name = name,
                            size = size,
                            lastModified = modified,
                            isEpub = name.endsWith(".epub", ignoreCase = true),
                            source = ScanSource.FOLDER,
                            path = docId.substringAfter(':', "").substringBeforeLast('/', "").ifEmpty { null }
                        )
                    )
                }
            }
        }
    }

    /* ---------------- 路径三：媒体库 ---------------- */

    private fun scanMediaStore(onHit: (BookCandidate) -> Unit) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        // 按扩展名筛：各 ROM 给 txt/epub 标的 MIME 五花八门（含 application/octet-stream）
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%.txt", "%.epub")
        val cursor = runCatching {
            context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
        }.getOrNull() ?: return
        cursor.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val name = cur.getString(1) ?: continue
                if (!isBook(name)) continue
                val size = if (cur.isNull(2)) 0L else cur.getLong(2)
                if (size <= 0L || size > MAX_BOOK_BYTES) continue
                // MediaStore 的 DATE_MODIFIED 是秒
                val modified = (if (cur.isNull(3)) 0L else cur.getLong(3)) * 1000L
                onHit(
                    BookCandidate(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        size = size,
                        lastModified = modified,
                        isEpub = name.endsWith(".epub", ignoreCase = true),
                        source = ScanSource.MEDIA_STORE
                    )
                )
            }
        }
    }

    companion object {
        private const val KEY_TREES = "trees"
        private const val MAX_DEPTH = 12
        private const val MAX_VISITS = 400_000
        private const val MAX_TREE_DIRS = 4_000
        private const val MAX_PEEKS = 400

        /**
         * 单本书体积上限。厂商的 bugreport、导出的聊天记录、数据库 dump 都叫 .txt，
         * 动辄上百 MB；导入时 `readBytes()` 会一次性读进内存直接 OOM，
         * 而 OOM 是 Error，`catch (e: Exception)` 根本接不住。宁可扫不到也不能让它进列表。
         * 64MB 对中文小说极其宽裕（UTF-8 下约合两千万字）。
         */
        const val MAX_BOOK_BYTES = 64L * 1024 * 1024

        fun isBook(name: String): Boolean =
            name.endsWith(".txt", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true)
    }
}
