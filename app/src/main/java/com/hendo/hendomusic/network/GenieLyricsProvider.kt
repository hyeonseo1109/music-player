package com.hendo.hendomusic.network

import android.util.Log
import com.hendo.hendomusic.metadata.MetadataConfidence
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.jsoup.Jsoup

data class GenieLyricLine(val startTimeMs: Long, val text: String)
data class GenieLyricsResult(val songId: String, val title: String, val artist: String, val album: String?, val plainText: String, val syncedLines: List<GenieLyricLine>)
internal data class GenieSongCandidate(val songId: String, val title: String, val artist: String, val album: String?)

class GenieLyricsProvider {
    suspend fun search(title: String, artist: String, album: String): GenieLyricsResult? = withContext(Dispatchers.IO) {
        val candidates = requestCandidates(title, artist)
        candidates.forEach { row ->
            Log.d("GenieLyrics", "auto candidate songId=${row.songId} title=${row.title} artist=${row.artist} accepted=${MetadataConfidence.genieLyricsHigh(title, artist, row.title, row.artist)}")
        }
        val candidate = candidates.firstOrNull {
            MetadataConfidence.genieLyricsHigh(title, artist, it.title, it.artist)
        } ?: return@withContext null
        lyricsFor(candidate)
    }

    /** Manual search is intentionally less strict: the user sees and chooses the candidate. */
    suspend fun searchManual(title: String, artist: String): List<GenieLyricsResult> = withContext(Dispatchers.IO) {
        val candidates = requestCandidates(title, artist)
        val requested = manualCandidates(candidates, title, artist)
        Log.d("GenieLyrics", "manual title=$title artist=$artist candidates=${candidates.size} requested=${requested.size}")
        requested.mapNotNull(::lyricsFor)
    }

    private fun requestCandidates(title: String, artist: String): List<GenieSongCandidate> {
        fun stripped(value: String) = value.replace(Regex("\\s*[\\(\\[（][^\\)\\]）]*[\\)\\]）]"), "").trim()
        // Search with the file tags first, then retry the common "title(alias)" form.
        // Local music tags frequently contain translations while Genie stores only the base name.
        val queries = listOf("$artist $title", "${stripped(artist)} ${stripped(title)}").distinct().filter { it.isNotBlank() }
        return queries.flatMap { query ->
            val searchUrl = "https://www.genie.co.kr/search/searchMain".toHttpUrl().newBuilder()
                .addQueryParameter("query", query).build()
            val doc = Jsoup.connect(searchUrl.toString())
                .userAgent(desktopUserAgent).timeout(12_000).get()
            parseCandidates(doc.outerHtml())
        }.distinctBy { it.songId }
    }

    private fun lyricsFor(candidate: GenieSongCandidate): GenieLyricsResult? {
        val lyricUrl = "https://dn.genie.co.kr/app/purchase/get_msl.asp".toHttpUrl().newBuilder()
            .addQueryParameter("songid", candidate.songId)
            .build()
        val lines = parseLines(Jsoup.connect(lyricUrl.toString())
            .userAgent(desktopUserAgent).timeout(12_000).ignoreContentType(true).execute().body())
        return lines.takeIf { it.isNotEmpty() }?.let {
            GenieLyricsResult(candidate.songId, candidate.title, candidate.artist, candidate.album, it.joinToString("\n") { line -> line.text }, it)
        }
    }

    companion object {
        private const val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        /** Restrict matching to Genie song rows so lyric snippets cannot be selected as songs. */
        internal fun parseCandidates(html: String): List<GenieSongCandidate> = Jsoup.parse(html)
            .select("div.search_song tr.list")
            .mapNotNull { row ->
                row.attr("songid").takeIf { it.isNotBlank() }?.let { songId ->
                    GenieSongCandidate(
                        songId = songId,
                        title = row.selectFirst("a.title")?.text().orEmpty(),
                        artist = row.selectFirst("a.artist")?.text().orEmpty(),
                        album = row.selectFirst("a.albumtitle")?.text(),
                    )
                }
            }

        /**
         * Manual search is deliberately permissive: local tags often contain translations,
         * aliases in parentheses, featured artists, or inconsistent spelling. The user sees the
         * result and chooses it, unlike background auto-enrichment which stays strict.
         */
        internal fun manualCandidates(candidates: List<GenieSongCandidate>, title: String, artist: String): List<GenieSongCandidate> {
            fun containsEither(left: String, right: String): Boolean {
                val a = MetadataConfidence.normalize(left)
                val b = MetadataConfidence.normalize(right)
                return a.length >= 2 && b.length >= 2 && (a.contains(b) || b.contains(a))
            }
            val matching = candidates.filter { candidate ->
                containsEither(candidate.title, title) || containsEither(candidate.artist, artist)
            }
            // The Genie query itself contains the original title and artist (including aliases
            // in parentheses). If normalized comparison loses those aliases, keep its top hits.
            return matching.ifEmpty { candidates }.take(5)
        }

        fun parseLines(response: String): List<GenieLyricLine> = runCatching {
            val start = response.indexOf('('); val end = response.lastIndexOf(')')
            if (start < 0 || end <= start) return emptyList()
            val reader = JsonReader.of(Buffer().writeUtf8(response.substring(start + 1, end)))
            buildList {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    val text = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else { reader.skipValue(); "" }
                    key.toLongOrNull()?.let { ms ->
                        text.replace("&nbsp;", " ").trim().takeIf { it.isNotBlank() }?.let { add(GenieLyricLine(ms, it)) }
                    }
                }
                reader.endObject()
            }.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }
}
