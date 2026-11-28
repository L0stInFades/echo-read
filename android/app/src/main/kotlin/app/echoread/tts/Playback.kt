package app.echoread.tts

import app.echoread.core.i18n.S
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

/** 一个正在播放的片段句柄：自然播完 awaitEnded 返回；被 stop/出错时抛出 */
interface PlayHandle {
    suspend fun awaitEnded()
    fun pause()
    fun resume()
    fun stop()
    fun setRate(rate: Float)

    /** 已经出过声的一段（只肯自己朗读的系统语音引擎在合成时就把它读完了）：引擎照常推进到下一段 */
    object Ended : PlayHandle {
        override suspend fun awaitEnded() {}
        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun setRate(rate: Float) {}
    }
}

/**
 * 引擎眼里的播放层：串行播放片段、持有音频焦点、报告打断。生产实现是 [Playback]；
 * 抽成接口只为让引擎的状态机能在 JVM 单测里配上替身（ExoPlayer 在 JVM 上起不来）。
 */
interface AudioOutput {
    /** 失去焦点 / 拔耳机时由引擎侧注入的暂停回调；transient = 短暂失去焦点（来电、导航播报），焦点回来时会续播 */
    var onInterrupt: ((transient: Boolean) -> Unit)?

    /** 重新拿到焦点时的回调（短暂丢失或闪避结束）：续不续播由引擎判断 */
    var onResumeAfterInterrupt: (() -> Unit)?

    /** 引擎处于朗读会话时为 true：持有音频焦点、监听拔耳机 */
    fun setActive(active: Boolean)

    /** 当前片段的播放进度 0..1（无时长信息时为 0） */
    fun progressFraction(): Float

    /** 播放一个音频文件；deleteAfter 为临时文件（系统语音）播完即删 */
    fun play(file: File, rate: Float, deleteAfter: Boolean = false): PlayHandle
}

/**
 * [Playback] 用到的平台调用：音频焦点与「拔耳机」广播。抽出来只为让焦点处理能在 JVM 单测里直接驱动
 * （AudioFocusRequest 在 JVM 上构造不出来）；生产实现是 [SystemAudio]。
 */
interface AudioPlatform {
    /** 申请焦点；之后的焦点变化（AudioManager.AUDIOFOCUS_*）交给 [onChange]。返回是否获准 */
    fun requestFocus(onChange: (Int) -> Unit): Boolean
    fun abandonFocus()
    fun registerNoisy(onNoisy: () -> Unit)
    fun unregisterNoisy()
}

/**
 * 音频播放层：单个 ExoPlayer 实例串行播放片段文件（mp3/ogg/wav 按内容嗅探），
 * 自管音频焦点与「拔耳机即暂停」，引擎保证同一时刻至多一个句柄活跃。
 * 所有方法须在主线程调用。
 */
class Playback(
    private val context: Context,
    private val audio: AudioPlatform = SystemAudio(context)
) : AudioOutput {
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
                        current?.fail(IllegalStateException(S.playback.playbackError(error.errorCodeName)))
                    }
                })
            }
    }
    private var playerCreated = false

    override var onInterrupt: ((transient: Boolean) -> Unit)? = null
    override var onResumeAfterInterrupt: (() -> Unit)? = null

    private var current: FileHandle? = null
    private var focusHeld = false

    private val onFocusChange: (Int) -> Unit = { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                onInterrupt?.invoke(false)
            }
            // 短暂丢失：引擎暂停但不撤焦点登记，否则焦点回来时根本收不到 AUDIOFOCUS_GAIN
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onInterrupt?.invoke(true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playerCreated) player.volume = 0.25f
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playerCreated) player.volume = 1f
                onResumeAfterInterrupt?.invoke()
            }
        }
    }

    private val onNoisy: () -> Unit = { onInterrupt?.invoke(false) }
    private var noisyRegistered = false

    /** 引擎进入/离开朗读会话时调用（被短暂焦点丢失打断、等着续播也算在会话里）：持有音频焦点、监听拔耳机 */
    override fun setActive(active: Boolean) {
        if (active) {
            if (!focusHeld) focusHeld = audio.requestFocus(onFocusChange)
            if (!noisyRegistered) {
                audio.registerNoisy(onNoisy)
                noisyRegistered = true
            }
        } else {
            if (focusHeld) {
                audio.abandonFocus()
                focusHeld = false
            }
            if (noisyRegistered) {
                audio.unregisterNoisy()
                noisyRegistered = false
            }
        }
    }

    override fun progressFraction(): Float {
        // 没有在播的文件（系统语音由引擎自己朗读时）：播放器里留着的是上一段，进度不算数
        if (!playerCreated || current == null) return 0f
        val d = player.duration
        return if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    override fun play(file: File, rate: Float, deleteAfter: Boolean): PlayHandle {
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

/** 生产环境的 [AudioPlatform]：AudioManager 的焦点请求与「拔耳机」广播 */
private class SystemAudio(private val context: Context) : AudioPlatform {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var onFocusChange: (Int) -> Unit = {}
    private var onNoisy: () -> Unit = {}

    // 同一个请求对象用到底：交还焦点时必须传申请时的那一个
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            SysAudioAttributes.Builder().setUsage(SysAudioAttributes.USAGE_MEDIA).setContentType(SysAudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        .setOnAudioFocusChangeListener { onFocusChange(it) }
        .setWillPauseWhenDucked(false)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }

    override fun requestFocus(onChange: (Int) -> Unit): Boolean {
        onFocusChange = onChange
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun registerNoisy(onNoisy: () -> Unit) {
        this.onNoisy = onNoisy
        ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun unregisterNoisy() {
        runCatching { context.unregisterReceiver(noisyReceiver) }
    }
}
