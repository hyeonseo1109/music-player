package com.hendo.hendomusic.lyrics

import java.util.Locale

data class SyncedLyricLine(val id: String, val startTimeMs: Long, val text: String)

fun canSaveSync(lines: List<String>, stamps: Map<Int, Long>): Boolean =
    lines.isNotEmpty() && stamps.keys.any { it in lines.indices }

/**
 * Builds a complete timeline once the user has recorded at least one timestamp.
 *
 * The editor can legitimately leave a hole when the line-group size is changed or the user
 * moves with Previous/Next. Keeping Save disabled in that state made a visually completed edit
 * impossible to finish. Missing entries inherit the closest preceding timestamp (or the first
 * recorded timestamp for leading entries), so no line is silently written at 00:00.
 */
fun buildSyncedLyrics(trackId: String, lines: List<String>, stamps: Map<Int, Long>): List<SyncedLyricLine>? {
    if (!canSaveSync(lines, stamps)) return null
    val valid = stamps.filterKeys { it in lines.indices }
    val firstStamp = valid.minBy { it.key }.value.coerceAtLeast(0L)
    var previousStamp = firstStamp
    return lines.mapIndexed { index, text ->
        previousStamp = valid[index]?.coerceAtLeast(0L) ?: previousStamp
        SyncedLyricLine("$trackId:$index", previousStamp, text)
    }
}

sealed interface LyricsSearchState {
    data object Idle : LyricsSearchState
    data object Loading : LyricsSearchState
    data class Success(val results: List<LyricsSearchResult>) : LyricsSearchState
    data class Error(val message: String) : LyricsSearchState
}

object LrcCodec {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    fun parse(input: String): List<SyncedLyricLine> = input.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val text = stamp.replace(line, "").trim()
        matches.asSequence().mapIndexed { index, match ->
            val (m, s, fraction) = match.destructured
            val ms = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0
            SyncedLyricLine("${m}_${s}_${ms}_$index", m.toLong() * 60_000 + s.toLong() * 1_000 + ms, text)
        }
    }.sortedBy { it.startTimeMs }.toList()

    fun encode(lines: List<SyncedLyricLine>): String = lines.sortedBy { it.startTimeMs }.joinToString("\n") {
        val min = it.startTimeMs / 60_000
        val sec = (it.startTimeMs % 60_000) / 1_000
        val cs = (it.startTimeMs % 1_000) / 10
        String.format(Locale.US, "[%02d:%02d.%02d]%s", min, sec, cs, it.text)
    }
    fun activeIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int {
        val activeStamp = lines.asSequence().map { it.startTimeMs }
            .filter { it <= positionMs }.maxOrNull() ?: return -1
        return lines.indexOfFirst { it.startTimeMs == activeStamp }
    }
    /** All lines sharing the active timestamp form one multilingual lyric block. */
    fun activeRange(lines: List<SyncedLyricLine>, positionMs: Long): IntRange? {
        val first = activeIndex(lines, positionMs)
        if (first < 0) return null
        val stamp = lines[first].startTimeMs
        val last = (first until lines.size).takeWhile { lines[it].startTimeMs == stamp }.lastOrNull() ?: first
        return first..last
    }
    fun offset(lines: List<SyncedLyricLine>, deltaMs: Long) = lines.map { it.copy(startTimeMs = (it.startTimeMs + deltaMs).coerceAtLeast(0)) }
}

interface LyricsProvider {
    suspend fun search(title: String, artist: String): List<LyricsSearchResult>
}
data class LyricsSearchResult(
    val id: String,
    val preview: String,
    val source: String,
    val synced: Boolean,
    val votes: Int,
    val updatedAt: String,
    val plainText: String = preview,
    val syncedText: String? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)
