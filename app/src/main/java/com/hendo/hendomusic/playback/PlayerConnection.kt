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
    val hasPreviousQueue: Boolean = false,
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
    private var previousQueue: List<MediaItem> = emptyList()
    private var previousIndex: Int = 0
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
    /** A library tap starts at the selected item while retaining the browsed library as the queue. */
    fun play(track: com.hendo.hendomusic.data.TrackEntity, library: List<com.hendo.hendomusic.data.TrackEntity> = emptyList()) {
        controller?.apply {
            snapshotQueue(this)
            val queue = library.ifEmpty { listOf(track) }
            val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            setMediaItems(queue.map { it.asMediaItem() }, index, 0); prepare(); play()
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
    fun playAt(index: Int) { clearLoop(); controller?.let { it.seekTo(index, 0); it.prepare(); it.play() } }
    /** Queue UI may be in Media3's shuffle traversal order, so an ID is safer than an index. */
    fun playQueueItem(mediaId: String) {
        clearLoop()
        controller?.let { player ->
            val index = (0 until player.mediaItemCount).indexOfFirst { player.getMediaItemAt(it).mediaId == mediaId }
            if (index >= 0) { player.seekTo(index, 0); player.prepare(); player.play() }
        }
    }
    fun toggleShuffle() {
        // The service moves existing MediaItems in-place. Replacing the playlist here causes
        // ExoPlayer to re-buffer the current source and produces an audible playback hiccup.
        controller?.sendCustomCommand(PlaybackService.TOGGLE_QUEUE_SHUFFLE_COMMAND, Bundle.EMPTY)
    }
    fun cycleRepeat() { clearLoop(); controller?.repeatMode = ((controller?.repeatMode ?: 0) + 1) % 3 }
    fun setRepeatMode(mode: Int) { clearLoop(); controller?.repeatMode = mode }
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
    fun append(track: com.hendo.hendomusic.data.TrackEntity) {
        controller?.let { player ->
            snapshotQueue(player)
            player.sendCustomCommand(
                PlaybackService.APPEND_COMMAND,
                Bundle().apply { putBundle(PlaybackService.ARG_MEDIA_ITEM, track.asMediaItem().toBundleIncludeLocalConfiguration()) },
            )
        }
    }
    fun playNext(track: com.hendo.hendomusic.data.TrackEntity) {
        controller?.sendCustomCommand(
            PlaybackService.PLAY_NEXT_COMMAND,
            Bundle().apply { putBundle(PlaybackService.ARG_MEDIA_ITEM, track.asMediaItem().toBundleIncludeLocalConfiguration()) },
        )
    }
    fun move(from: Int, to: Int) { controller?.let { snapshotQueue(it); it.moveMediaItem(from, to) } }
    fun removeTrack(trackId: String) {
        controller?.apply {
            snapshotQueue(this)
            (mediaItemCount - 1 downTo 0)
                .filter { getMediaItemAt(it).mediaMetadata.extras?.getString(PlaybackService.KEY_TRACK_ID) == trackId }
                .forEach(::removeMediaItem)
        }
    }
    fun clear() { controller?.let { snapshotQueue(it); it.clearMediaItems() } }
    /** Exchanges the current queue with the immediately preceding queue. */
    fun restorePreviousQueue() { controller?.let { player ->
        if (previousQueue.isEmpty()) return
        val current = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val restore = previousQueue
        val restoreIndex = previousIndex.coerceIn(0, restore.lastIndex)
        previousQueue = current; previousIndex = currentIndex
        player.setMediaItems(restore, restoreIndex, 0); player.prepare()
        publish(player)
    } }

    private fun snapshotQueue(player: Player) {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        if (items.isNotEmpty()) { previousQueue = items; previousIndex = player.currentMediaItemIndex.coerceAtLeast(0) }
    }

    private fun publish(player: Player) {
        if (loopRange != null && player.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_ID) != loopTrackId) { loopRange = null; loopTrackId = null }
        val queue = if (player.shuffleModeEnabled) {
            buildList {
                val timeline = player.currentTimeline
                var index = timeline.getFirstWindowIndex(true)
                while (index != androidx.media3.common.C.INDEX_UNSET) {
                    add(player.getMediaItemAt(index))
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
            }
        } else (0 until player.mediaItemCount).map(player::getMediaItemAt)
        mutableState.value = PlaybackState(
            true, player.currentMediaItem, player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: 0, queue,
            player.repeatMode, player.shuffleModeEnabled, loopRange, previousQueue.isNotEmpty(),
        )
    }
}
