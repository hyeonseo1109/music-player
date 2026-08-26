package com.luminara.player.lyrics

import java.util.Locale

data class SyncedLyricLine(val id: String, val startTimeMs: Long, val text: String)

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
    fun activeIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int =
        lines.indexOfLast { it.startTimeMs <= positionMs }
    fun offset(lines: List<SyncedLyricLine>, deltaMs: Long) = lines.map { it.copy(startTimeMs = (it.startTimeMs + deltaMs).coerceAtLeast(0)) }
}

interface LyricsProvider {
    suspend fun search(title: String, artist: String): List<LyricsSearchResult>
}
data class LyricsSearchResult(val id: String, val preview: String, val source: String, val synced: Boolean, val votes: Int, val updatedAt: String)
