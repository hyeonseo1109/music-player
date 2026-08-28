package com.hendo.hendomusic.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hendo.hendomusic.domain.LoopRange
import com.hendo.hendomusic.domain.LoopRangePolicy
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
    val loopRange: LoopRange? = null,
)

@UnstableApi
class PlayerConnection(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { controller?.let(::publish); handler.postDelayed(this, 500) }
    }
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val mutableState = MutableStateFlow(PlaybackState())
    private var loopRange: LoopRange? = null
    private var loopTrackId: String? = null
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
    /** A direct tap starts a deliberate one-item queue; it must not silently enqueue the full library. */
    fun play(track: com.hendo.hendomusic.data.TrackEntity, library: List<com.hendo.hendomusic.data.TrackEntity> = emptyList()) {
        controller?.apply {
            setMediaItem(track.asMediaItem(), 0); prepare(); play()
        }
    }
    fun toggle() { controller?.let { player ->
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex.coerceAtLeast(0), 0)
            player.prepare(); player.play()
        }
    } }
    fun next() { clearLoop(); controller?.seekToNextMediaItem() }
    fun previous() { clearLoop(); controller?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { if (loopRange?.let { ms < it.startMs || ms > it.endMs } == true) clearLoop(); controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }
    fun cycleRepeat() { clearLoop(); controller?.repeatMode = ((controller?.repeatMode ?: 0) + 1) % 3 }
    fun setLoop(startMs: Long, endMs: Long) {
        val range = LoopRangePolicy.create(startMs, endMs) ?: return
        loopRange = range
        loopTrackId = controller?.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_ID)
        controller?.sendCustomCommand(PlaybackService.SET_LOOP_COMMAND, Bundle().apply { putLong(PlaybackService.ARG_LOOP_START, range.startMs); putLong(PlaybackService.ARG_LOOP_END, range.endMs) })
        controller?.let(::publish)
    }
    fun loopPrevious(durationMs: Long) { controller?.let { setLoop(it.currentPosition - durationMs, it.currentPosition) } }
    fun clearLoop() {
        loopRange = null; loopTrackId = null
        controller?.sendCustomCommand(PlaybackService.CLEAR_LOOP_COMMAND, Bundle.EMPTY)
        controller?.let(::publish)
    }
    fun append(track: com.hendo.hendomusic.data.TrackEntity) { controller?.addMediaItem(track.asMediaItem()) }
    fun playNext(track: com.hendo.hendomusic.data.TrackEntity) {
        controller?.sendCustomCommand(
            PlaybackService.PLAY_NEXT_COMMAND,
            Bundle().apply { putBundle(PlaybackService.ARG_MEDIA_ITEM, track.asMediaItem().toBundleIncludeLocalConfiguration()) },
        )
    }
    fun move(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun removeTrack(trackId: String) {
        controller?.apply {
            (mediaItemCount - 1 downTo 0)
                .filter { getMediaItemAt(it).mediaMetadata.extras?.getString(PlaybackService.KEY_TRACK_ID) == trackId }
                .forEach(::removeMediaItem)
        }
    }
    fun clear() { controller?.clearMediaItems() }

    private fun publish(player: Player) {
        if (loopRange != null && player.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_ID) != loopTrackId) { loopRange = null; loopTrackId = null }
        mutableState.value = PlaybackState(
            true, player.currentMediaItem, player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: 0, (0 until player.mediaItemCount).map(player::getMediaItemAt),
            player.repeatMode, player.shuffleModeEnabled, loopRange,
        )
    }
}
