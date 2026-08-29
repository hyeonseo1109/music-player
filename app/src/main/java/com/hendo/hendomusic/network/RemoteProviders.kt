package com.hendo.hendomusic.network

import com.hendo.hendomusic.BuildConfig
import android.util.Log
import com.hendo.hendomusic.lyrics.LyricsProvider
import com.hendo.hendomusic.lyrics.LyricsSearchResult
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Query

data class LrcLibResult(
    val id: Long,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Double?,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)
interface LrcLibApi {
    @Headers("User-Agent: HendoMusic/1.0 (Android)")
    @GET("api/search") suspend fun search(@Query("track_name") title: String, @Query("artist_name") artist: String): Response<List<LrcLibResult>>
}

class LrcLibLyricsProvider : LyricsProvider {
    private val api = Retrofit.Builder().baseUrl("https://lrclib.net/").addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(LrcLibApi::class.java)
    override suspend fun search(title: String, artist: String): List<LyricsSearchResult> {
        val response = api.search(title, artist)
        Log.d("LrcLib", "search title=$title artist=$artist http=${response.code()}")
        if (!response.isSuccessful) throw HttpException(response)
        return response.body().orEmpty().map {
        val full = (it.plainLyrics ?: it.syncedLyrics?.let { lrc -> com.hendo.hendomusic.lyrics.LrcCodec.parse(lrc).joinToString("\n") { line -> line.text } }).orEmpty()
        LyricsSearchResult(
            id = it.id.toString(), preview = full.take(180), source = "LRCLIB",
            synced = !it.syncedLyrics.isNullOrBlank(), votes = 0, updatedAt = "",
            plainText = full, syncedText = it.syncedLyrics, trackTitle = it.trackName,
            trackArtist = it.artistName, album = it.albumName, durationMs = it.duration?.times(1_000)?.toLong(),
        )
        }
    }
}

data class ArtworkResponse(@Json(name = "results") val results: List<ArtworkResult>)
data class ArtworkResult(val trackName: String?, val artistName: String?, val collectionName: String?, val artworkUrl100: String?) {
    val largeUrl: String? get() = artworkUrl100?.replace("100x100", "1200x1200")
}
interface ArtworkApi { @GET("search") suspend fun search(@Query("term") term: String, @Query("entity") entity: String = "song", @Query("limit") limit: Int = 30): ArtworkResponse }
class ArtworkProvider {
    private val api = Retrofit.Builder().baseUrl("https://itunes.apple.com/").addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(ArtworkApi::class.java)
    suspend fun search(title: String, artist: String, album: String): List<ArtworkResult> {
        val query = when {
            artist.isNotBlank() && album.isNotBlank() -> "$artist $album"
            artist.isNotBlank() && title.isNotBlank() -> "$artist $title"
            else -> title.ifBlank { album }
        }
        return api.search(query).results
    }
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
    private val api by lazy { Retrofit.Builder().baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/").addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(SupabaseLyricsApi::class.java) }
    override suspend fun search(title: String, artist: String): List<LyricsSearchResult> {
        if (!configured) return emptyList()
        val fingerprint = "eq.${normalize(title)}|${normalize(artist)}"
        return api.search(BuildConfig.SUPABASE_ANON_KEY, "Bearer ${BuildConfig.SUPABASE_ANON_KEY}", fingerprint = fingerprint).map {
            LyricsSearchResult(it.id, it.plainText.take(180), "Luminara · ${it.source ?: "공유"}", it.isSynced, it.voteCount, it.updatedAt, it.plainText, null)
        }
    }
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
}

private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
