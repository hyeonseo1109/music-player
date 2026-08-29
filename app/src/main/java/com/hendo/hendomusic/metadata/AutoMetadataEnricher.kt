package com.hendo.hendomusic.metadata

import android.util.Log
import com.hendo.hendomusic.data.AppDao
import com.hendo.hendomusic.data.ArtworkSource
import com.hendo.hendomusic.data.LyricLineEntity
import com.hendo.hendomusic.data.LyricsEntity
import com.hendo.hendomusic.data.LyricsSource
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.network.ArtworkProvider
import com.hendo.hendomusic.network.LrcLibLyricsProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * A small bounded batch keeps scanning responsive and avoids making a network request for
 * every local song at once. Each later scan continues with the next missing items.
 */
class AutoMetadataEnricher(
    private val dao: AppDao,
    private val artwork: ArtworkProvider = ArtworkProvider(),
    private val lyrics: LrcLibLyricsProvider = LrcLibLyricsProvider(),
) {
    private val inFlightTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val retryAfterMs = ConcurrentHashMap<String, Long>()
    private val retryDelayMs = 5 * 60_000L

    suspend fun enrichMissing(limit: Int = 4) {
        (dao.artworkEnrichmentCandidates(limit) + dao.lyricEnrichmentCandidates(limit))
            .distinctBy { it.id }
            .take(limit)
            .forEach { enrichTrack(it) }
    }
    suspend fun enrichTrack(track: TrackEntity) {
        val now = System.currentTimeMillis()
        if (!inFlightTrackIds.add(track.id) || (retryAfterMs[track.id] ?: 0L) > now) return
        try {
            if (track.customArtworkUri == null && track.albumArtUri.isNullOrBlank() && track.autoArtworkUri == null) enrichArtwork(track)
            if (dao.lyrics(track.id) == null) enrichLyrics(track)
            retryAfterMs.remove(track.id)
        } catch (_: Exception) {
            retryAfterMs[track.id] = now + retryDelayMs
        } finally {
            inFlightTrackIds.remove(track.id)
        }
    }

    private suspend fun enrichArtwork(track: TrackEntity) {
        val candidate = artwork.search(track.title, track.artist, track.album)
            .firstOrNull { it.largeUrl != null && MetadataConfidence.artworkHigh(track.title, track.artist, track.album, it.trackName, it.artistName, it.collectionName) }
            ?: return
        // SQL eligibility predicate is the final guard against USER/EMBEDDED races.
        dao.applyAutoArtworkIfEligible(track.id, candidate.largeUrl!!, ArtworkSource.AUTO_ITUNES.name, System.currentTimeMillis())
    }

    private suspend fun enrichLyrics(track: TrackEntity) {
        Log.d("AutoLyrics", "start track=${track.id} title=${track.title} artist=${track.artist} durationMs=${track.durationMs}")
        val candidates = lyrics.search(track.title, track.artist)
        Log.d("AutoLyrics", "candidates track=${track.id} count=${candidates.size}")
        val candidate = candidates
            .firstOrNull {
                it.plainText.isNotBlank() && MetadataConfidence.lyricsHigh(
                    track.title, track.artist, track.durationMs,
                    it.trackTitle, it.trackArtist, it.durationMs,
                )
            }
            ?: run {
                Log.d("AutoLyrics", "no eligible candidate track=${track.id}")
                return
            }
        Log.d("AutoLyrics", "selected track=${track.id} title=${candidate.trackTitle} artist=${candidate.trackArtist} remoteDurationMs=${candidate.durationMs} durationDeltaMs=${candidate.durationMs?.let { kotlin.math.abs(track.durationMs - it) }}")
        val lines = candidate.syncedText?.let(LrcCodec::parse).orEmpty()
        val saved = dao.insertAutoLyricsIfMissing(
            LyricsEntity(trackId = track.id, source = LyricsSource.AUTO_LRCLIB.name, plainText = candidate.plainText),
            lines.mapIndexed { index, line -> LyricLineEntity(lyricsId = 0, lineIndex = index, startTimeMs = line.startTimeMs, text = line.text) },
        )
        Log.d("AutoLyrics", "save track=${track.id} source=${LyricsSource.AUTO_LRCLIB.name} saved=$saved lines=${lines.size}")
    }
}
