package app.echoread.tts

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.echoread.EchoReadApp
import app.echoread.MainActivity
import app.echoread.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台媒体服务：持有 MediaSession（包着 EnginePlayer），播放中自动升为前台并挂媒体通知，
 * 锁屏可控、后台连播；引擎本身为应用级单例，服务只是它的媒体外壳。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as EchoReadApp).graph
        val player = EnginePlayer(graph.player, graph.settings)
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(launch).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) }
        )
        // 通知栏点击直达正在朗读的那本书
        scope.launch {
            graph.player.book.collect { book ->
                val intent = Intent(this@PlaybackService, MainActivity::class.java).apply {
                    if (book != null) putExtra(MainActivity.EXTRA_BOOK_ID, book.id)
                }
                session?.setSessionActivity(
                    PendingIntent.getActivity(this@PlaybackService, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
