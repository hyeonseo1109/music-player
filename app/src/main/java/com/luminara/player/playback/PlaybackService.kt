package com.luminara.player.playback

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
import com.luminara.player.LuminaraApplication
import com.luminara.player.data.PlaybackQueueEntity
import com.luminara.player.data.PlaybackSessionEntity
import com.luminara.player.data.PlaybackHistoryEntity
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
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) resetPlaybackStats()
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(SessionCommands.Builder().add(PLAY_NEXT_COMMAND).build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != COMMAND_PLAY_NEXT) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            val itemBundle = args.getBundle(ARG_MEDIA_ITEM)
                ?: return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
            insertPlayNext(MediaItem.fromBundle(itemBundle))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

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
        runBlocking { persist() }
        session.release(); player.release(); scope.cancel(); super.onDestroy()
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_PLAY_NEXT = "play_next"
        const val COMMAND_PLAY_NEXT = "com.luminara.player.PLAY_NEXT"
        const val ARG_MEDIA_ITEM = "media_item"
        val PLAY_NEXT_COMMAND = SessionCommand(COMMAND_PLAY_NEXT, android.os.Bundle.EMPTY)
    }
}

private fun com.luminara.player.data.TrackEntity.toMediaItem(instanceId: String = UUID.randomUUID().toString()): MediaItem {
    val extras = android.os.Bundle().apply { putString(PlaybackService.KEY_TRACK_ID, id) }
    return MediaItem.Builder().setMediaId(instanceId).setUri(uri.toUri()).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setArtworkUri((customArtworkUri ?: albumArtUri)?.toUri()).setExtras(extras).build()
    ).build()
}

fun com.luminara.player.data.TrackEntity.asMediaItem() = toMediaItem()
