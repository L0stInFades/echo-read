package app.echoread.tts

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.echoread.core.PlayerState
import app.echoread.data.SettingsStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 把朗读引擎暴露为 Media3 Player：锁屏/通知栏/耳机线控经 MediaSession 直达引擎。
 * 播放列表只呈现「上一章 · 本章 · 下一章」三项窗口（长篇小说上千章无需整表下发），
 * 上一曲/下一曲即跳章；每章无时长（片段流式合成），不显示进度条。
 */
@OptIn(UnstableApi::class)
class EnginePlayer(
    private val controller: PlayerController,
    private val settings: SettingsStore
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastWindow: List<Int> = emptyList()
    private var lastChapter = 0

    private val baseCommands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        Player.COMMAND_RELEASE
    ).build()

    /** 上一章/下一章命令只在确有目标章时开放，通知栏按钮随之显隐 */
    private fun commandsFor(hasPrev: Boolean, hasNext: Boolean): Player.Commands =
        baseCommands.buildUpon().apply {
            if (hasNext) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (hasPrev) addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }.build()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()

    init {
        scope.launch {
            combine(controller.engine.snapshot, controller.book, controller.titles, settings.tts) { s, b, t, cfg -> Triple(s, b, t) to cfg.rate }
                .collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = controller.engine.current
        val book = controller.book.value
        val loaded = book != null && s.state != PlayerState.IDLE && s.bookId == book.id
        val builder = State.Builder().setAudioAttributes(audioAttributes)
        if (!loaded || book == null) {
            lastWindow = emptyList()
            return builder
                .setAvailableCommands(baseCommands)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val titles = controller.titles.value
        val cur = (if (s.chapterIndex >= 0) s.chapterIndex else lastChapter).coerceIn(0, maxOf(book.chapterCount - 1, 0))
        lastChapter = cur
        val window = listOfNotNull(if (cur > 0) cur - 1 else null, cur, if (cur < book.chapterCount - 1) cur + 1 else null)
        lastWindow = window
        val items = window.map { idx ->
            val title = titles.getOrNull(idx) ?: "第 ${idx + 1} 章"
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(book.title)
                .setAlbumTitle("Lector · AI 听书")
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply { book.cover?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
            MediaItemData.Builder(idx)
                .setMediaItem(MediaItem.Builder().setMediaId("${book.id}:$idx").setMediaMetadata(metadata).build())
                .setMediaMetadata(metadata)
                .setDurationUs(C.TIME_UNSET)
                .setIsSeekable(false)
                .build()
        }
        val playbackState = when (s.state) {
            PlayerState.LOADING -> Player.STATE_BUFFERING
            PlayerState.PLAYING -> if (s.synthesizing) Player.STATE_BUFFERING else Player.STATE_READY
            PlayerState.PAUSED, PlayerState.ERROR -> Player.STATE_READY
            PlayerState.IDLE -> Player.STATE_IDLE
        }
        return builder
            .setAvailableCommands(commandsFor(hasPrev = cur > 0, hasNext = cur < book.chapterCount - 1))
            .setPlaylist(items)
            .setCurrentMediaItemIndex(window.indexOf(cur))
            .setPlaybackState(playbackState)
            .setPlayWhenReady(s.state == PlayerState.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(settings.tts.value.rate))
            .setContentPositionMs(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.engine.play() else controller.engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = lastWindow.getOrNull(mediaItemIndex)
        if (target != null && target != controller.engine.current.chapterIndex) {
            controller.gotoChapter(target, autoplay = true)
        }
        return Futures.immediateVoidFuture()
    }
}
