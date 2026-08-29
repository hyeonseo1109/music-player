package com.hendo.hendomusic.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hendo.hendomusic.LuminaraApplication
import com.hendo.hendomusic.data.PlaybackQueueEntity
import com.hendo.hendomusic.data.PlaybackSessionEntity
import com.hendo.hendomusic.data.PlaybackHistoryEntity
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.domain.LoopRange
import com.hendo.hendomusic.domain.LoopRangePolicy
import kotlinx.coroutines.*
import java.util.UUID

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao by lazy { (application as LuminaraApplication).container.database.dao() }
    private val handler = Handler(Looper.getMainLooper())
    private val periodicSave = object : Runnable {
        override fun run() { scope.launch { persist() }; handler.postDelayed(this, 5_000) }
    }
    private var statsTrackId: String? = null
    private var listenedMs = 0L
    private var lastStatsTick = 0L
    private var countedCurrentPlay = false
    private val statsTicker = object : Runnable {
        override fun run() {
            if (::player.isInitialized) updatePlaybackStats()
            handler.postDelayed(this, 1_000)
        }
    }
    private var loopRange: LoopRange? = null
    private var loopTrackId: String? = null
    private var loopSeekPending = false
    private val loopTicker = object : Runnable {
        override fun run() {
            loopRange?.let { range ->
                if (player.isPlaying && player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID) == loopTrackId && player.currentPosition >= range.endMs) { loopSeekPending = true; player.seekTo(range.startMs) }
            }
            handler.postDelayed(this, 40)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).setCallback(sessionCallback).build()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (loopSeekPending) { loopSeekPending = false; return }
                loopRange?.let { range -> if (newPosition.positionMs < range.startMs || newPosition.positionMs > range.endMs) clearLoop() }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) { resetPlaybackStats(); clearLoop() }
                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED) && loopRange != null && player.repeatMode != Player.REPEAT_MODE_OFF) clearLoop()
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                    )) scope.launch { persist() }
            }
        })
        scope.launch { restore() }
        handler.post(periodicSave)
        handler.post(statsTicker)
        handler.post(loopTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(SessionCommands.Builder().add(PLAY_NEXT_COMMAND).add(TOGGLE_QUEUE_SHUFFLE_COMMAND).add(SET_LOOP_COMMAND).add(CLEAR_LOOP_COMMAND).build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_SET_LOOP -> {
                    val range = LoopRangePolicy.create(args.getLong(ARG_LOOP_START), args.getLong(ARG_LOOP_END))
                        ?: return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
                    enableLoop(range)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_CLEAR_LOOP -> { clearLoop(); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                COMMAND_TOGGLE_QUEUE_SHUFFLE -> {
                    toggleQueueShuffle()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_PLAY_NEXT -> Unit
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            val itemBundle = args.getBundle(ARG_MEDIA_ITEM)
                ?: return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
            insertPlayNext(MediaItem.fromBundle(itemBundle))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun enableLoop(range: LoopRange) {
        loopRange = range
        loopTrackId = player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)
        player.repeatMode = Player.REPEAT_MODE_OFF
        loopSeekPending = true
        player.seekTo(player.currentMediaItemIndex.coerceAtLeast(0), range.startMs)
        player.prepare()
        player.play()
    }
    private fun clearLoop() { loopRange = null; loopTrackId = null; loopSeekPending = false }

    private fun insertPlayNext(item: MediaItem) {
        val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        if (!player.shuffleModeEnabled) { player.addMediaItem(at, item); return }
        val timeline = player.currentTimeline
        val oldTraversal = buildList {
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
        }
        val shifted = oldTraversal.map { if (it >= at) it + 1 else it }.toMutableList()
        val shiftedCurrent = if (player.currentMediaItemIndex >= at) player.currentMediaItemIndex + 1 else player.currentMediaItemIndex
        val currentTraversalIndex = shifted.indexOf(shiftedCurrent).coerceAtLeast(0)
        shifted.add(currentTraversalIndex + 1, at)
        player.addMediaItem(at, item)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shifted.toIntArray(), System.nanoTime()))
    }

    /** Reorder the visible queue itself, so UI, Media3 and Room all share the same shuffle order. */
    private fun toggleQueueShuffle() {
        val current = player.currentMediaItem ?: run {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            return
        }
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val shuffled = !player.shuffleModeEnabled
        val ordered = if (shuffled) {
            items.shuffled().let { random ->
                // Do not interrupt the current song while the surrounding queue is randomized.
                listOf(current) + random.filterNot { it.mediaId == current.mediaId }
            }
        } else {
            items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.mediaMetadata.title?.toString().orEmpty() })
        }
        val index = ordered.indexOfFirst { it.mediaId == current.mediaId }.coerceAtLeast(0)
        player.setMediaItems(ordered, index, player.currentPosition)
        player.shuffleModeEnabled = shuffled
        player.prepare()
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val savedQueue = dao.queue()
        val savedSession = dao.session()
        if (savedQueue.isEmpty()) return@withContext
        val tracks = dao.tracks(savedQueue.map { it.trackId }).associateBy { it.id }
        val valid = savedQueue.mapNotNull { item -> tracks[item.trackId]?.toMediaItem(item.queueItemId) }
        if (valid.isEmpty()) return@withContext
        val wantedId = savedSession?.currentTrackId
        val savedIndex = savedSession?.currentIndex ?: 0
        val indexedTrackId = valid.getOrNull(savedIndex)?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)
        val index = if (indexedTrackId == wantedId) savedIndex
        else valid.indexOfFirst { it.mediaMetadata.extras?.getString(KEY_TRACK_ID) == wantedId }.coerceAtLeast(0)
        withContext(Dispatchers.Main) {
            player.setMediaItems(valid, index, savedSession?.positionMs ?: 0)
            player.repeatMode = savedSession?.repeatMode ?: Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = savedSession?.shuffleEnabled ?: false
            player.prepare()
            player.pause()
        }
    }

    private suspend fun persist() {
        if (!::player.isInitialized) return
        val items = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            PlaybackQueueEntity(
                queueItemId = item.mediaId.ifBlank { UUID.randomUUID().toString() },
                trackId = item.mediaMetadata.extras?.getString(KEY_TRACK_ID) ?: item.mediaId,
                position = index,
                isPlayNext = item.mediaMetadata.extras?.getBoolean(KEY_PLAY_NEXT) ?: false,
            )
        }
        val currentTrackId = player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val repeatMode = player.repeatMode
        val shuffleEnabled = player.shuffleModeEnabled
        withContext(Dispatchers.IO) {
            dao.replaceQueue(items)
            dao.saveSession(
                PlaybackSessionEntity(
                    currentTrackId = currentTrackId, currentIndex = currentIndex,
                    positionMs = positionMs, repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    shuffleOrder = "", updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun resetPlaybackStats() {
        statsTrackId = player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)
        listenedMs = 0
        countedCurrentPlay = false
        lastStatsTick = android.os.SystemClock.elapsedRealtime()
    }

    private fun updatePlaybackStats() {
        val currentId = player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)
        if (currentId != statsTrackId) resetPlaybackStats()
        val now = android.os.SystemClock.elapsedRealtime()
        if (player.isPlaying && currentId != null) listenedMs += (now - lastStatsTick).coerceIn(0, 2_000)
        lastStatsTick = now
        val duration = player.duration.takeIf { it > 0 } ?: 0
        if (!countedCurrentPlay && currentId != null && PlayCountPolicy.qualifies(listenedMs, duration)) {
            countedCurrentPlay = true
            scope.launch(Dispatchers.IO) {
                val timestamp = System.currentTimeMillis()
                dao.countPlay(currentId, timestamp)
                dao.insertHistory(PlaybackHistoryEntity(trackId = currentId, playedAt = timestamp, listenedMs = listenedMs))
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { scope.launch { persist() }; super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        handler.removeCallbacks(periodicSave)
        handler.removeCallbacks(statsTicker)
        handler.removeCallbacks(loopTicker)
        runBlocking { persist() }
        session.release(); player.release(); scope.cancel(); super.onDestroy()
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_PLAY_NEXT = "play_next"
        const val COMMAND_PLAY_NEXT = "com.hendo.hendomusic.PLAY_NEXT"
        const val COMMAND_TOGGLE_QUEUE_SHUFFLE = "com.hendo.hendomusic.TOGGLE_QUEUE_SHUFFLE"
        const val ARG_MEDIA_ITEM = "media_item"
        const val COMMAND_SET_LOOP = "com.hendo.hendomusic.SET_LOOP"
        const val COMMAND_CLEAR_LOOP = "com.hendo.hendomusic.CLEAR_LOOP"
        const val ARG_LOOP_START = "loop_start"
        const val ARG_LOOP_END = "loop_end"
        val PLAY_NEXT_COMMAND = SessionCommand(COMMAND_PLAY_NEXT, android.os.Bundle.EMPTY)
        val TOGGLE_QUEUE_SHUFFLE_COMMAND = SessionCommand(COMMAND_TOGGLE_QUEUE_SHUFFLE, android.os.Bundle.EMPTY)
        val SET_LOOP_COMMAND = SessionCommand(COMMAND_SET_LOOP, android.os.Bundle.EMPTY)
        val CLEAR_LOOP_COMMAND = SessionCommand(COMMAND_CLEAR_LOOP, android.os.Bundle.EMPTY)
    }
}

private fun com.hendo.hendomusic.data.TrackEntity.toMediaItem(instanceId: String = UUID.randomUUID().toString()): MediaItem {
    val extras = android.os.Bundle().apply { putString(PlaybackService.KEY_TRACK_ID, id) }
    return MediaItem.Builder().setMediaId(instanceId).setUri(uri.toUri()).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setArtworkUri(displayArtworkUri()?.toUri()).setExtras(extras).build()
    ).build()
}

fun com.hendo.hendomusic.data.TrackEntity.asMediaItem() = toMediaItem()
