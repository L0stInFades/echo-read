package app.echoread

import app.echoread.core.i18n.S
import android.app.Application
import android.content.Context
import android.net.Uri
import app.echoread.data.AccessTier
import app.echoread.data.AndroidNetworkStatus
import app.echoread.data.BookCandidate
import app.echoread.data.BookmarkRepo
import app.echoread.data.BookScanner
import app.echoread.data.ChapterCache
import app.echoread.data.EchoDb
import app.echoread.data.LibraryRepo
import app.echoread.data.SettingsStore
import app.echoread.data.Updater
import app.echoread.tts.AudioCache
import app.echoread.tts.Playback
import app.echoread.tts.PlayerController
import app.echoread.tts.SystemTts
import app.echoread.tts.TtsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.echoread.ui.ToastKind
import app.echoread.ui.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 应用级对象图（极简手工依赖注入）：模块方向 core ← data ← tts ← ui 单向 */
class AppGraph(context: Context) {
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = EchoDb.open(context)
    val settings = SettingsStore(context)
    val chapterCache = ChapterCache(db.dao())
    val library = LibraryRepo(context, db.dao(), chapterCache)
    val bookmarks = BookmarkRepo(db.dao())
    val audioCache = AudioCache(File(context.cacheDir, "tts-audio"))
    val systemTts = SystemTts(context)
    val playback = Playback(context)
    val network = AndroidNetworkStatus(context)
    val engine = TtsEngine(mainScope, chapterCache, audioCache, systemTts, playback, File(context.cacheDir, "tts-tmp"), network)
    val player = PlayerController(engine, library, settings, mainScope)
    val updater = Updater(context)

    /** 各朗读服务的同步与连接状态（面板关掉也不取消） */
    val services = app.echoread.tts.ServiceSync(settings, mainScope)

    /** 外部传入的朗读配置（深链 / adb），须用户确认后才写入设置 */
    data class PendingConfig(val apiKey: String?, val baseUrl: String?, val model: String?, val voice: String?, val provider: String? = null)
    val configRequests = MutableStateFlow<PendingConfig?>(null)

    /** 打开指定书籍的请求（通知栏点击 / 外部深链）：Pair(bookId, autoplay) */
    val openRequests = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** 待导入文件队列：文件选择器、外部分享/「打开方式」统一投递到这里，由应用级协程串行消费（不随界面进出而取消） */
    val pendingImports = SerialQueue<Uri>(mainScope) { importOne(it) }

    /* ---------------- 本机书籍扫描（0.2.0 的应用内导入） ---------------- */

    val scanner = BookScanner(context)

    /** 上一次扫描结果。放在应用级而非界面里：关掉导入面板再打开无需重扫 */
    val scanResults = MutableStateFlow<List<BookCandidate>>(emptyList())
    val scanBusy = MutableStateFlow(false)

    /** 扫描进度：已发现数 to 已遍历数 */
    val scanProgress = MutableStateFlow(0 to 0)

    /** 上一次扫描是否因触到遍历上限而提前结束（结果不完整） */
    val scanTruncated = MutableStateFlow(false)

    /** 上一次结果是在哪个访问档位下扫出来的：档位升级后要重扫，否则「已可扫描整机」下摆着的还是文件夹结果 */
    val scannedTier = MutableStateFlow<AccessTier?>(null)
    private var scanJob: Job? = null

    /**
     * 扫描代际。协程取消是异步的：连点两次「重新扫描」时，第一次的 finally 很可能在第二次
     * 已经把 scanBusy 置 true 之后才执行，于是 scanBusy 卡在 false —— 界面显示「未在扫描」，
     * 进度条消失，但后台还在跑。所有共享状态的回写都必须先校验代际。
     * （与 TtsEngine 里 generation 守卫同一套写法。）
     */
    @Volatile
    private var scanGen = 0

    /** 发起一次全量扫描；重复调用会取消上一次 */
    fun rescan() {
        scanJob?.cancel()
        val gen = ++scanGen
        // 同步置位：否则 launch 之前有一个「已请求但 busy=false」的窗口，界面会闪一下「未在扫描」
        scanBusy.value = true
        scanProgress.value = 0 to 0
        scanJob = mainScope.launch {
            try {
                val result = scanner.scan { found, scanned ->
                    if (gen == scanGen) scanProgress.value = found to scanned
                }
                if (gen == scanGen) {
                    scanResults.value = result.candidates
                    scanTruncated.value = result.truncated
                    scannedTier.value = result.tier
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == scanGen) Toaster.error(e.message ?: S.importer.scanFailed)
            } finally {
                if (gen == scanGen) scanBusy.value = false
            }
        }
    }

    fun cancelScan() {
        scanGen++
        scanJob?.cancel()
        scanJob = null
        scanBusy.value = false
    }

    init {
        // 触觉开关跟随阅读设置（Haptics 是无状态单例，绘制/手势路径里不读设置流）
        mainScope.launch { settings.reader.collect { app.echoread.ui.motion.Haptics.enabled = it.haptics } }
        // 启动后静默检查更新（24 小时一次，失败无提示）
        mainScope.launch {
            kotlinx.coroutines.delay(3000)
            runCatching { updater.check(force = false) }
        }
    }

    private suspend fun importOne(uri: Uri) {
        try {
            val meta = library.importUri(uri)
            Toaster.show(S.importer.importedChapters(meta.title, meta.chapterCount), ToastKind.SUCCESS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 刻意接到 Throwable：超大文件会抛 OutOfMemoryError，那是 Error 而非 Exception，
            // 只接 Exception 的话整个导入协程会连同 mainScope 里的后续任务一起被带走。
            Toaster.error(e.message ?: S.importer.importFailed)
        }
    }
}

class EchoReadApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 界面语言在 SettingsStore 读档时就定下了（通知栏、后台导入的提示、首次安装的默认音色都依赖它）
        graph = AppGraph(this)
    }
}
