package app.echoread.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as SysAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class AbortedException : RuntimeException("aborted")

/** 一个正在播放的片段句柄（对应网页版 PlayHandle）：自然播完 awaitEnded 返回；被 stop/出错时抛出 */
interface PlayHandle {
    suspend fun awaitEnded()
    fun pause()
    fun resume()
    fun stop()
    fun setRate(rate: Float)
}

/**
 * 音频播放层：单个 ExoPlayer 实例串行播放片段文件（mp3/ogg/wav 按内容嗅探），
 * 自管音频焦点与「拔耳机即暂停」，引擎保证同一时刻至多一个句柄活跃。
 * 所有方法须在主线程调用。
 */
class Playback(private val context: Context) {
    /** 惰性创建：首次播放时才初始化 ExoPlayer，冷启动路径零开销 */
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ false
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(false)
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) current?.finish()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        current?.fail(IllegalStateException("音频播放出错：${error.errorCodeName}"))
                    }
                })
            }
    }
    private var playerCreated = false

    /** 焦点丢失 / 拔耳机时由引擎侧注入的暂停回调 */
    var onInterrupt: (() -> Unit)? = null

    /** 短暂焦点丢失恢复后的续播回调 */
    var onResumeAfterInterrupt: (() -> Unit)? = null

    private var current: FileHandle? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusHeld = false
    private var resumeOnGain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                resumeOnGain = false
                onInterrupt?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = playerCreated && (player.isPlaying || player.playWhenReady)
                onInterrupt?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playerCreated) player.volume = 0.25f
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playerCreated) player.volume = 1f
                if (resumeOnGain) {
                    resumeOnGain = false
                    onResumeAfterInterrupt?.invoke()
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            SysAudioAttributes.Builder().setUsage(SysAudioAttributes.USAGE_MEDIA).setContentType(SysAudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        .setOnAudioFocusChangeListener(focusListener)
        .setWillPauseWhenDucked(false)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupt?.invoke()
        }
    }
    private var noisyRegistered = false

    /** 引擎进入/离开「播放中」时调用：持有音频焦点、监听拔耳机 */
    fun setActive(active: Boolean) {
        if (active) {
            if (!focusHeld) {
                focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
        } else {
            resumeOnGain = false
            if (focusHeld) {
                audioManager.abandonAudioFocusRequest(focusRequest)
                focusHeld = false
            }
            if (noisyRegistered) {
                runCatching { context.unregisterReceiver(noisyReceiver) }
                noisyRegistered = false
            }
        }
    }

    /** 当前片段的播放进度 0..1（无时长信息时为 0） */
    fun progressFraction(): Float {
        if (!playerCreated) return 0f
        val d = player.duration
        return if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    /** 播放一个音频文件；deleteAfter 为临时文件（系统语音）播完即删 */
    fun play(file: File, rate: Float, deleteAfter: Boolean = false): PlayHandle {
        current?.detach()
        val h = FileHandle(file, deleteAfter)
        current = h
        playerCreated = true
        player.volume = 1f
        player.setPlaybackSpeed(rate.coerceIn(0.25f, 4f))
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
        return h
    }

    fun release() {
        setActive(false)
        current?.detach()
        if (playerCreated) player.release()
    }

    private inner class FileHandle(private val file: File, private val deleteAfter: Boolean) : PlayHandle {
        private val done = CompletableDeferred<Unit>()
        private var attached = true

        fun finish() {
            if (!attached) return
            cleanup()
            done.complete(Unit)
        }

        fun fail(e: Throwable) {
            if (!attached) return
            cleanup()
            done.completeExceptionally(e)
        }

        /** 句柄与播放器解绑：不再接收后续事件（下一句柄接管） */
        fun detach() {
            if (!attached) return
            cleanup()
            done.completeExceptionally(AbortedException())
        }

        private fun cleanup() {
            attached = false
            if (current === this) current = null
            if (deleteAfter) runCatching { file.delete() }
        }

        override suspend fun awaitEnded() = done.await()

        override fun pause() {
            if (attached) player.pause()
        }

        override fun resume() {
            if (attached) player.play()
        }

        override fun stop() {
            if (!attached) return
            player.stop()
            player.clearMediaItems()
            detach()
        }

        override fun setRate(rate: Float) {
            if (attached) player.setPlaybackSpeed(rate.coerceIn(0.25f, 4f))
        }
    }
}
