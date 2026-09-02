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
import com.hendo.hendomusic.network.GenieLyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.hendo.hendomusic.network.LrcLibLyricsProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Requests are batched so launch remains responsive, then the background worker continues
 * through the library. User/embedded data is always guarded again in DAO writes.
 */
class AutoMetadataEnricher(
    private val dao: AppDao,
    private val artwork: ArtworkProvider = ArtworkProvider(),
    private val lyrics: LrcLibLyricsProvider = LrcLibLyricsProvider(),
    private val genie: GenieLyricsProvider = GenieLyricsProvider(),
) {
    private val inFlightTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val retryAfterMs = ConcurrentHashMap<String, Long>()
    private val retryDelayMs = 5 * 60_000L

    suspend fun enrichMissing(limit: Int = 12): Int {
        val now = System.currentTimeMillis()
        val candidates = (dao.artworkEnrichmentCandidates(limit) + dao.lyricEnrichmentCandidates(limit))
            .distinctBy { it.id }
            .filter { (retryAfterMs[it.id] ?: 0L) <= now }
            .take(limit)
        // A second launcher (for example, app start plus a scan) may observe the
        // same candidate while the first one is in flight. Count only work this
        // batch actually acquired; otherwise its loop would spin on that item.
        return candidates.count { enrichTrack(it) }
    }

    /** Continue bounded batches until there are no eligible missing metadata records left. */
    suspend fun enrichAllMissing(batchSize: Int = 12) {
        while (enrichMissing(batchSize) > 0) {
            // Avoid monopolising the app's IO and network resources while a library is large.
            delay(250)
        }
    }
    suspend fun enrichTrack(track: TrackEntity): Boolean {
        val now = System.currentTimeMillis()
        if (!inFlightTrackIds.add(track.id) || (retryAfterMs[track.id] ?: 0L) > now) return false
        // A valid remote miss is still a completed attempt.  Without a cooldown a
        // large library immediately re-selects the same unresolved rows forever.
        // This is in-memory only, so a later app launch can retry a newly indexed
        // provider result without changing the persisted metadata priority rules.
        retryAfterMs[track.id] = now + retryDelayMs
        try {
            if (track.customArtworkUri == null && track.albumArtUri.isNullOrBlank() && track.autoArtworkUri == null) enrichArtwork(track)
            val existingLyrics = dao.lyrics(track.id)
            when {
                existingLyrics == null -> enrichLyrics(track)
                existingLyrics.source.startsWith("AUTO_") && existingLyrics.plainText.isBlank() -> {
                    // A prior failed provider result used to leave an empty AUTO row behind.
                    // That row blocked every future enrichment attempt while user-authored
                    // lyrics must remain immutable to background work.
                    Log.d("AutoLyrics", "clearing empty auto row track=${track.id} source=${existingLyrics.source}")
                    dao.deleteLyrics(track.id)
                    enrichLyrics(track)
                }
                else -> Log.d("AutoLyrics", "skip existing lyrics track=${track.id} source=${existingLyrics.source}")
            }
            return true
        } catch (_: Exception) {
            retryAfterMs[track.id] = now + retryDelayMs
            return true
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
        val candidates = try {
            lyrics.search(track.title, track.artist)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w("AutoLyrics", "LRCLIB request failed track=${track.id}", error)
            emptyList()
        }
        Log.d("AutoLyrics", "candidates track=${track.id} count=${candidates.size}")
        val candidate = candidates
            .firstOrNull {
                it.plainText.isNotBlank() && MetadataConfidence.lyricsHigh(
                    track.title, track.artist, track.durationMs,
                    it.trackTitle, it.trackArtist, it.durationMs,
                )
            }
        candidates.filter { it.plainText.isNotBlank() }.forEach { result ->
            val accepted = MetadataConfidence.lyricsHigh(track.title, track.artist, track.durationMs, result.trackTitle, result.trackArtist, result.durationMs)
            Log.d("AutoLyrics", "candidate track=${track.id} title=${result.trackTitle} artist=${result.trackArtist} duration=${result.durationMs} accepted=$accepted")
        }
        if (candidate == null) {
            Log.d("AutoLyrics", "LRCLIB miss; Genie search start track=${track.id}")
            val genieResult = try {
                genie.search(track.title, track.artist, track.album)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w("AutoLyrics", "Genie request failed track=${track.id}", error)
                null
            } ?: run {
                Log.d("AutoLyrics", "Genie miss track=${track.id}")
                return
            }
            val saved = dao.insertAutoLyricsIfMissing(
                LyricsEntity(trackId = track.id, source = LyricsSource.AUTO_GENIE.name, plainText = genieResult.plainText),
                genieResult.syncedLines.mapIndexed { index, line -> LyricLineEntity(lyricsId = 0, lineIndex = index, startTimeMs = line.startTimeMs, text = line.text) },
            )
            Log.d("AutoLyrics", "Genie save track=${track.id} songId=${genieResult.songId} lines=${genieResult.syncedLines.size} saved=$saved")
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
