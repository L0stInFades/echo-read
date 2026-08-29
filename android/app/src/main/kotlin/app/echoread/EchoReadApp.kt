package app.echoread

import android.app.Application
import android.content.Context
import android.net.Uri
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
    val audioCache = AudioCache(File(context.cacheDir, "tts-audio"))
    val systemTts = SystemTts(context)
    val playback = Playback(context)
    val engine = TtsEngine(mainScope, chapterCache, audioCache, systemTts, playback, File(context.cacheDir, "tts-tmp"))
    val player = PlayerController(engine, library, settings, mainScope)
    val updater = Updater(context)

    /** 外部传入的朗读配置（深链 / adb），须用户确认后才写入设置 */
    data class PendingConfig(val apiKey: String?, val baseUrl: String?, val model: String?, val voice: String?)
    val configRequests = MutableStateFlow<PendingConfig?>(null)

    /** 打开指定书籍的请求（通知栏点击 / 外部深链）：Pair(bookId, autoplay) */
    val openRequests = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** 待导入文件队列：文件选择器、外部分享/「打开方式」统一投递到这里，由应用级协程串行消费（不随界面进出而取消） */
    val pendingImports = MutableStateFlow<List<Uri>>(emptyList())

    init {
        // 启动后静默检查更新（24 小时一次，失败无提示）
        mainScope.launch {
            kotlinx.coroutines.delay(3000)
            runCatching { updater.check(force = false) }
        }
        mainScope.launch {
            pendingImports.collect { list ->
                if (list.isEmpty()) return@collect
                pendingImports.value = emptyList()
                for (uri in list) importOne(uri)
            }
        }
    }

    private suspend fun importOne(uri: Uri) {
        try {
            val meta = library.importUri(uri)
            Toaster.show("《${meta.title}》导入成功，共 ${meta.chapterCount} 章", ToastKind.SUCCESS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toaster.error(e.message ?: "导入失败")
        }
    }
}

class EchoReadApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
