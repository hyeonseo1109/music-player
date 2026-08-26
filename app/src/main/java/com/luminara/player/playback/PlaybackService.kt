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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.luminara.player.LuminaraApplication
import com.luminara.player.data.PlaybackQueueEntity
import com.luminara.player.data.PlaybackSessionEntity
import kotlinx.coroutines.*
import java.util.UUID

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao by lazy { (application as LuminaraApplication).container.database.dao() }
    private val handler = Handler(Looper.getMainLooper())
    private val periodicSave = object : Runnable {
        override fun run() { scope.launch { persist() }; handler.postDelayed(this, 5_000) }
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
        session = MediaSession.Builder(this, player).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )) scope.launch { persist() }
            }
        })
        scope.launch { restore() }
        handler.post(periodicSave)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val savedQueue = dao.queue()
        val savedSession = dao.session()
        if (savedQueue.isEmpty()) return@withContext
        val tracks = dao.tracks(savedQueue.map { it.trackId }).associateBy { it.id }
        val valid = savedQueue.mapNotNull { item -> tracks[item.trackId]?.toMediaItem(item.queueItemId) }
        if (valid.isEmpty()) return@withContext
        val wantedId = savedSession?.currentTrackId
        val index = valid.indexOfFirst { it.mediaMetadata.extras?.getString(KEY_TRACK_ID) == wantedId }.coerceAtLeast(0)
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

    override fun onTaskRemoved(rootIntent: Intent?) { scope.launch { persist() }; super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        handler.removeCallbacks(periodicSave)
        runBlocking { persist() }
        session.release(); player.release(); scope.cancel(); super.onDestroy()
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_PLAY_NEXT = "play_next"
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
