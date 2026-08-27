package com.luminara.player.network

import com.luminara.player.BuildConfig
import com.luminara.player.lyrics.LyricsProvider
import com.luminara.player.lyrics.LyricsSearchResult
import com.squareup.moshi.Json
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class LrcLibResult(
    val id: Long,
    val trackName: String?,
    val artistName: String?,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)
interface LrcLibApi {
    @GET("api/search") suspend fun search(@Query("track_name") title: String, @Query("artist_name") artist: String): List<LrcLibResult>
}

class LrcLibLyricsProvider : LyricsProvider {
    private val api = Retrofit.Builder().baseUrl("https://lrclib.net/").addConverterFactory(MoshiConverterFactory.create()).build().create(LrcLibApi::class.java)
    override suspend fun search(title: String, artist: String): List<LyricsSearchResult> = api.search(title, artist).map {
        val full = (it.plainLyrics ?: it.syncedLyrics?.let { lrc -> com.luminara.player.lyrics.LrcCodec.parse(lrc).joinToString("\n") { line -> line.text } }).orEmpty()
        LyricsSearchResult(it.id.toString(), full.take(180), "LRCLIB", !it.syncedLyrics.isNullOrBlank(), 0, "", full, it.syncedLyrics)
    }
}

data class ArtworkResponse(@Json(name = "results") val results: List<ArtworkResult>)
data class ArtworkResult(val trackName: String?, val artistName: String?, val collectionName: String?, val artworkUrl100: String?) {
    val largeUrl: String? get() = artworkUrl100?.replace("100x100", "1200x1200")
}
interface ArtworkApi { @GET("search") suspend fun search(@Query("term") term: String, @Query("entity") entity: String = "song", @Query("limit") limit: Int = 30): ArtworkResponse }
class ArtworkProvider {
    private val api = Retrofit.Builder().baseUrl("https://itunes.apple.com/").addConverterFactory(MoshiConverterFactory.create()).build().create(ArtworkApi::class.java)
    suspend fun search(title: String, artist: String, album: String) = api.search(listOf(artist, title, album).filter { it.isNotBlank() }.joinToString(" ")).results
    suspend fun searchQuery(query: String) = api.search(query).results
}

data class SharedLyricsDto(
    val id: String,
    @Json(name = "plain_text") val plainText: String,
    val source: String?,
    @Json(name = "is_synced") val isSynced: Boolean,
    @Json(name = "vote_count") val voteCount: Int,
    @Json(name = "updated_at") val updatedAt: String,
)
interface SupabaseLyricsApi {
    @GET("rest/v1/lyrics")
    suspend fun search(
        @Header("apikey") key: String,
        @Header("Authorization") authorization: String,
        @Query("select") select: String = "id,plain_text,source,is_synced,vote_count,updated_at",
        @Query("track_fingerprint") fingerprint: String,
    ): List<SharedLyricsDto>
}
class SupabaseLyricsProvider : LyricsProvider {
    private val configured get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private val api by lazy { Retrofit.Builder().baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/").addConverterFactory(MoshiConverterFactory.create()).build().create(SupabaseLyricsApi::class.java) }
    override suspend fun search(title: String, artist: String): List<LyricsSearchResult> {
        if (!configured) return emptyList()
        val fingerprint = "eq.${normalize(title)}|${normalize(artist)}"
        return api.search(BuildConfig.SUPABASE_ANON_KEY, "Bearer ${BuildConfig.SUPABASE_ANON_KEY}", fingerprint = fingerprint).map {
            LyricsSearchResult(it.id, it.plainText.take(180), "Luminara · ${it.source ?: "공유"}", it.isSynced, it.voteCount, it.updatedAt, it.plainText, null)
        }
    }
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
}
