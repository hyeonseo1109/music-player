package com.hendo.hendomusic.playback

import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.graphics.BitmapFactory
import android.widget.RemoteViews
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
import androidx.media3.session.MediaNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
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
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.Locale

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
        setMediaNotificationProvider(HendoNotificationProvider(this))
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
                .setAvailableSessionCommands(SessionCommands.Builder().add(PLAY_NEXT_COMMAND).add(APPEND_COMMAND).add(TOGGLE_QUEUE_SHUFFLE_COMMAND).add(SET_LOOP_COMMAND).add(CLEAR_LOOP_COMMAND).add(SessionCommand(COMMAND_STOP_PLAYBACK, android.os.Bundle.EMPTY)).add(SessionCommand(COMMAND_TOGGLE_FAVORITE, android.os.Bundle.EMPTY)).build())
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
                COMMAND_PLAY_NEXT, COMMAND_APPEND -> Unit
                COMMAND_TOGGLE_FAVORITE -> {
                    player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_TRACK_ID)?.let { id ->
                        scope.launch { dao.toggleFavorite(id, System.currentTimeMillis()) }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_STOP_PLAYBACK -> { player.pause(); player.clearMediaItems(); stopSelf(); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            val itemBundle = args.getBundle(ARG_MEDIA_ITEM)
                ?: return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
            if (customCommand.customAction == COMMAND_APPEND) appendMediaItem(MediaItem.fromBundle(itemBundle))
            else insertPlayNext(MediaItem.fromBundle(itemBundle))
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
        if (player.mediaItemCount == 0) { player.addMediaItem(item); return }
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

    /** Appending during shuffle joins the existing traversal at a fresh random later position. */
    private fun appendMediaItem(item: MediaItem) {
        if (!player.shuffleModeEnabled) { player.addMediaItem(item); return }
        if (player.mediaItemCount == 0) { player.addMediaItem(item); return }
        val timeline = player.currentTimeline
        val oldTraversal = buildList {
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
        }
        val addedIndex = player.mediaItemCount
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentTraversal = oldTraversal.indexOf(current).coerceAtLeast(0)
        val insertion = (currentTraversal + 1..oldTraversal.size).random()
        player.addMediaItem(item)
        val order = oldTraversal.toMutableList().apply { add(insertion, addedIndex) }
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    /** Change Media3's shuffle traversal atomically; never loop thousands of main-thread moves. */
    private fun toggleQueueShuffle() {
        val current = player.currentMediaItem ?: run {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            return
        }
        val shuffled = !player.shuffleModeEnabled
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val order = if (shuffled) {
            // Explicit current-first traversal guarantees Next starts with a random other song
            // while neither the current source nor the 2,000+ item timeline is replaced.
            val rest = (0 until player.mediaItemCount).filter { it != currentIndex }.shuffled()
            intArrayOf(currentIndex, *rest.toIntArray())
        } else {
            IntArray(player.mediaItemCount) { it }
        }
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        player.shuffleModeEnabled = shuffled
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
            val shuffleEnabled = savedSession?.shuffleEnabled ?: false
            if (shuffleEnabled) {
                val savedOrder = savedSession?.shuffleOrder.orEmpty().split('|').filter { it.isNotBlank() }
                val positions = savedOrder.mapNotNull { id -> valid.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 } }
                    .distinct().toMutableList()
                positions += valid.indices.filter { it !in positions }
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(positions.toIntArray(), System.nanoTime()))
            }
            player.shuffleModeEnabled = shuffleEnabled
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
        val shuffleOrder = if (shuffleEnabled) {
            buildList {
                val timeline = player.currentTimeline
                var index = timeline.getFirstWindowIndex(true)
                while (index != C.INDEX_UNSET) {
                    add(player.getMediaItemAt(index).mediaId)
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
            }.joinToString("|")
        } else ""
        withContext(Dispatchers.IO) {
            dao.replaceQueue(items)
            dao.saveSession(
                PlaybackSessionEntity(
                    currentTrackId = currentTrackId, currentIndex = currentIndex,
                    positionMs = positionMs, repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    shuffleOrder = shuffleOrder, updatedAt = System.currentTimeMillis(),
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
                if (!(application as LuminaraApplication).container.preferences.settings.first().trackListening) return@launch
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
        const val COMMAND_APPEND = "com.hendo.hendomusic.APPEND"
        const val COMMAND_TOGGLE_QUEUE_SHUFFLE = "com.hendo.hendomusic.TOGGLE_QUEUE_SHUFFLE"
        const val ARG_MEDIA_ITEM = "media_item"
        const val COMMAND_SET_LOOP = "com.hendo.hendomusic.SET_LOOP"
        const val COMMAND_CLEAR_LOOP = "com.hendo.hendomusic.CLEAR_LOOP"
        const val COMMAND_STOP_PLAYBACK = "com.hendo.hendomusic.STOP_PLAYBACK"
        const val COMMAND_TOGGLE_FAVORITE = "com.hendo.hendomusic.TOGGLE_FAVORITE"
        const val ARG_LOOP_START = "loop_start"
        const val ARG_LOOP_END = "loop_end"
        val PLAY_NEXT_COMMAND = SessionCommand(COMMAND_PLAY_NEXT, android.os.Bundle.EMPTY)
        val APPEND_COMMAND = SessionCommand(COMMAND_APPEND, android.os.Bundle.EMPTY)
        val TOGGLE_QUEUE_SHUFFLE_COMMAND = SessionCommand(COMMAND_TOGGLE_QUEUE_SHUFFLE, android.os.Bundle.EMPTY)
        val SET_LOOP_COMMAND = SessionCommand(COMMAND_SET_LOOP, android.os.Bundle.EMPTY)
        val CLEAR_LOOP_COMMAND = SessionCommand(COMMAND_CLEAR_LOOP, android.os.Bundle.EMPTY)
    }
}

@UnstableApi
private class HendoNotificationProvider(private val appContext: android.content.Context) : MediaNotification.Provider {
    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "HendoMusic 재생", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun createNotification(
        session: MediaSession,
        mediaButtons: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = session.player
        val metadata = player.mediaMetadata
        val launch = Intent(appContext, com.hendo.hendomusic.MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val contentIntent = PendingIntent.getActivity(appContext, 2001, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val previous = actionFactory.createMediaAction(session, IconCompat.createWithResource(appContext, android.R.drawable.ic_media_previous), "이전 곡", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        val playPause = actionFactory.createMediaAction(session, IconCompat.createWithResource(appContext, if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (player.isPlaying) "일시정지" else "재생", Player.COMMAND_PLAY_PAUSE)
        val next = actionFactory.createMediaAction(session, IconCompat.createWithResource(appContext, android.R.drawable.ic_media_next), "다음 곡", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val favorite = actionFactory.createCustomAction(session, IconCompat.createWithResource(appContext, android.R.drawable.btn_star_big_on), "좋아요", PlaybackService.COMMAND_TOGGLE_FAVORITE, android.os.Bundle.EMPTY)
        val close = actionFactory.createCustomAction(session, IconCompat.createWithResource(appContext, android.R.drawable.ic_menu_close_clear_cancel), "재생 종료", PlaybackService.COMMAND_STOP_PLAYBACK, android.os.Bundle.EMPTY)
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val views = RemoteViews(appContext.packageName, com.hendo.hendomusic.R.layout.notification_hendo_player).apply {
            setTextViewText(com.hendo.hendomusic.R.id.notification_title, metadata.title ?: "HendoMusic")
            setTextViewText(com.hendo.hendomusic.R.id.notification_artist, metadata.artist ?: "알 수 없는 아티스트")
            setProgressBar(com.hendo.hendomusic.R.id.notification_progress, duration.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), player.currentPosition.coerceIn(0L, duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
            setTextViewText(com.hendo.hendomusic.R.id.notification_elapsed, formatNotificationTime(player.currentPosition))
            setTextViewText(com.hendo.hendomusic.R.id.notification_duration, formatNotificationTime(duration))
            setImageViewResource(com.hendo.hendomusic.R.id.notification_play_pause, if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setOnClickPendingIntent(com.hendo.hendomusic.R.id.notification_previous, previous.actionIntent)
            setOnClickPendingIntent(com.hendo.hendomusic.R.id.notification_play_pause, playPause.actionIntent)
            setOnClickPendingIntent(com.hendo.hendomusic.R.id.notification_next, next.actionIntent)
            setOnClickPendingIntent(com.hendo.hendomusic.R.id.notification_favorite, favorite.actionIntent)
            setOnClickPendingIntent(com.hendo.hendomusic.R.id.notification_close, close.actionIntent)
            metadata.artworkUri?.let { uri ->
                runCatching { appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }
                    .getOrNull()?.let { setImageViewBitmap(com.hendo.hendomusic.R.id.notification_artwork, it) }
            }
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setDeleteIntent(actionFactory.createNotificationDismissalIntent(session))
            // Playback notification remains pinned until the explicit close action is used.
            // This also prevents Android's generic clear-all from dropping an active session.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()
        return MediaNotification(NOTIFICATION_ID, notification)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle): Boolean = false

    private companion object {
        const val CHANNEL_ID = "hendo_playback"
        const val NOTIFICATION_ID = 1001
    }

    private fun formatNotificationTime(positionMs: Long): String {
        val seconds = (positionMs.coerceAtLeast(0L) / 1_000L)
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
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
