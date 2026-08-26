package com.luminara.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<MediaItem> = emptyList(),
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
)

class PlayerConnection(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { controller?.let(::publish); handler.postDelayed(this, 500) }
    }
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future = MediaController.Builder(context, token).buildAsync().also { pending ->
            pending.addListener({
                runCatching { pending.get() }.onSuccess { c -> controller = c; c.addListener(listener); publish(c) }
            }, context.mainExecutor)
        }
        handler.removeCallbacks(ticker); handler.post(ticker)
    }
    fun disconnect() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        handler.removeCallbacks(ticker); controller = null; future = null; mutableState.value = PlaybackState()
    }
    fun play(track: com.luminara.player.data.TrackEntity, library: List<com.luminara.player.data.TrackEntity>) {
        controller?.apply {
            val index = library.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            setMediaItems(library.map { it.asMediaItem() }, index, 0); prepare(); play()
        }
    }
    fun toggle() { controller?.let { player ->
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex.coerceAtLeast(0), 0)
            player.prepare(); player.play()
        }
    } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }
    fun cycleRepeat() { controller?.repeatMode = ((controller?.repeatMode ?: 0) + 1) % 3 }
    fun append(track: com.luminara.player.data.TrackEntity) { controller?.addMediaItem(track.asMediaItem()) }
    fun playNext(track: com.luminara.player.data.TrackEntity) {
        controller?.apply {
            val at = (currentMediaItemIndex + 1).coerceIn(0, mediaItemCount)
            val originalShuffle = shuffleModeEnabled
            if (originalShuffle) shuffleModeEnabled = false
            addMediaItem(at, track.asMediaItem())
            if (originalShuffle) shuffleModeEnabled = true
        }
    }
    fun move(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun clear() { controller?.clearMediaItems() }

    private fun publish(player: Player) {
        mutableState.value = PlaybackState(
            true, player.currentMediaItem, player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: 0, (0 until player.mediaItemCount).map(player::getMediaItemAt),
            player.repeatMode, player.shuffleModeEnabled,
        )
    }
}
