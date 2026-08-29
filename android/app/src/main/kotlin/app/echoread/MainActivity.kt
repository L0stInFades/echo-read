package app.echoread

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.echoread.core.PlayerState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.echoread.tts.PlaybackService
import app.echoread.ui.EchoApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val graph: AppGraph get() = (application as EchoReadApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { EchoApp(graph) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 「打开方式」/ 分享 送来的 TXT / EPUB */
    private fun handleIntent(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            intent?.action = Intent.ACTION_MAIN
            graph.pendingImports.update { it + uris }
        }
        // 深链：通知栏点击（或 adb --es bookId）直接打开指定书籍；autoplay 供自动化测试起播
        val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)
        if (!bookId.isNullOrEmpty()) {
            val autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
            intent.removeExtra(EXTRA_BOOK_ID)
            intent.removeExtra(EXTRA_AUTOPLAY)
            graph.openRequests.value = bookId to autoplay
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_AUTOPLAY = "autoplay"
    }

    /**
     * 前台期间挂一个 MediaController 拉起媒体服务（播放中离开应用也能持续、通知栏/锁屏可控）。
     * 延后到引擎首次装载再连接：冷启动路径上不创建服务与会话。
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            graph.engine.snapshot.map { it.state != PlayerState.IDLE }.distinctUntilChanged().collect { active ->
                if (active && controllerFuture == null) {
                    val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, PlaybackService::class.java))
                    controllerFuture = MediaController.Builder(this@MainActivity, token).buildAsync()
                }
            }
        }.also { watchJob = it }
    }

    private var watchJob: kotlinx.coroutines.Job? = null

    override fun onStop() {
        watchJob?.cancel()
        watchJob = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
