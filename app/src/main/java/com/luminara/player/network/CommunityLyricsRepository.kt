package com.luminara.player.network

import com.luminara.player.BuildConfig
import com.luminara.player.data.TrackEntity
import com.luminara.player.lyrics.LrcCodec
import com.luminara.player.lyrics.SyncedLyricLine
import com.squareup.moshi.Json
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class AnonymousSession(@Json(name = "access_token") val accessToken: String, val user: AnonymousUser)
data class AnonymousUser(val id: String)
data class TrackRow(val id: String)
data class LyricsRow(val id: String)
data class TrackUpload(
    @Json(name = "track_fingerprint") val fingerprint: String,
    @Json(name = "normalized_title") val title: String,
    @Json(name = "normalized_artist") val artist: String,
    val album: String,
    @Json(name = "duration_ms") val durationMs: Long,
)
data class LyricsUpload(
    @Json(name = "track_id") val trackId: String,
    @Json(name = "track_fingerprint") val fingerprint: String,
    @Json(name = "author_id") val authorId: String,
    @Json(name = "plain_text") val plainText: String,
    val source: String = "community",
    @Json(name = "is_synced") val synced: Boolean,
)
data class LineUpload(@Json(name = "lyrics_id") val lyricsId: String, @Json(name = "line_index") val index: Int, @Json(name = "start_time_ms") val time: Long, val text: String)
data class VoteUpload(@Json(name = "lyrics_id") val lyricsId: String, @Json(name = "user_id") val userId: String, val value: Int = 1)
data class ReportUpload(@Json(name = "lyrics_id") val lyricsId: String, @Json(name = "reporter_id") val userId: String, val reason: String)

interface CommunityApi {
    @POST("auth/v1/signup") suspend fun anonymous(@Header("apikey") key: String, @Body body: Map<String, String> = emptyMap()): AnonymousSession
    @POST("rest/v1/tracks") suspend fun upsertTrack(
        @Header("apikey") key: String, @Header("Authorization") auth: String,
        @Header("Prefer") prefer: String = "resolution=ignore-duplicates,return=representation",
        @Query("on_conflict") conflict: String = "track_fingerprint", @Body body: TrackUpload,
    ): List<TrackRow>
    @GET("rest/v1/tracks") suspend fun findTrack(
        @Header("apikey") key: String, @Header("Authorization") auth: String,
        @Query("select") select: String = "id", @Query("track_fingerprint") fingerprint: String,
        @Query("limit") limit: Int = 1,
    ): List<TrackRow>
    @POST("rest/v1/lyrics") suspend fun insertLyrics(
        @Header("apikey") key: String, @Header("Authorization") auth: String,
        @Header("Prefer") prefer: String = "return=representation", @Body body: LyricsUpload,
    ): List<LyricsRow>
    @POST("rest/v1/lyric_lines") suspend fun insertLines(@Header("apikey") key: String, @Header("Authorization") auth: String, @Body body: List<LineUpload>)
    @POST("rest/v1/lyrics_votes") suspend fun vote(
        @Header("apikey") key: String, @Header("Authorization") auth: String,
        @Header("Prefer") prefer: String = "resolution=ignore-duplicates", @Query("on_conflict") conflict: String = "lyrics_id,user_id", @Body body: VoteUpload,
    )
    @POST("rest/v1/lyrics_reports") suspend fun report(@Header("apikey") key: String, @Header("Authorization") auth: String, @Body body: ReportUpload)
    @GET("rest/v1/lyric_lines") suspend fun lines(
        @Header("apikey") key: String, @Header("Authorization") auth: String,
        @Query("select") select: String = "line_index,start_time_ms,text", @Query("lyrics_id") lyricsId: String,
        @Query("order") order: String = "line_index.asc",
    ): List<RemoteLine>
}
data class RemoteLine(@Json(name = "line_index") val index: Int, @Json(name = "start_time_ms") val time: Long, val text: String)

sealed interface CommunityActionState {
    data object Idle : CommunityActionState
    data object Loading : CommunityActionState
    data class Success(val message: String) : CommunityActionState
    data class Error(val message: String) : CommunityActionState
}

class CommunityLyricsRepository {
    val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private val api by lazy { Retrofit.Builder().baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/").addConverterFactory(MoshiConverterFactory.create()).build().create(CommunityApi::class.java) }
    private var session: AnonymousSession? = null

    private suspend fun session(): AnonymousSession {
        check(configured) { "Supabase 환경 변수가 설정되지 않았습니다." }
        return session ?: api.anonymous(BuildConfig.SUPABASE_ANON_KEY).also { session = it }
    }
    private fun auth(session: AnonymousSession) = "Bearer ${session.accessToken}"

    suspend fun share(track: TrackEntity, plainText: String, lines: List<SyncedLyricLine>): String {
        val user = session()
        val normalizedTitle = normalize(track.title)
        val normalizedArtist = normalize(track.artist)
        val fingerprint = "$normalizedTitle|$normalizedArtist"
        val remoteTrack = api.upsertTrack(BuildConfig.SUPABASE_ANON_KEY, auth(user), body = TrackUpload(fingerprint, normalizedTitle, normalizedArtist, track.album, track.durationMs)).firstOrNull()
            ?: api.findTrack(BuildConfig.SUPABASE_ANON_KEY, auth(user), fingerprint = "eq.$fingerprint").first()
        val lyric = api.insertLyrics(BuildConfig.SUPABASE_ANON_KEY, auth(user), body = LyricsUpload(remoteTrack.id, fingerprint, user.user.id, plainText, synced = lines.isNotEmpty())).first()
        if (lines.isNotEmpty()) api.insertLines(BuildConfig.SUPABASE_ANON_KEY, auth(user), body = lines.mapIndexed { index, line -> LineUpload(lyric.id, index, line.startTimeMs, line.text) })
        return lyric.id
    }

    suspend fun vote(lyricsId: String) {
        val user = session()
        api.vote(BuildConfig.SUPABASE_ANON_KEY, auth(user), body = VoteUpload(lyricsId, user.user.id))
    }

    suspend fun report(lyricsId: String, reason: String) {
        val user = session()
        api.report(BuildConfig.SUPABASE_ANON_KEY, auth(user), body = ReportUpload(lyricsId, user.user.id, reason))
    }

    suspend fun syncedText(lyricsId: String): String? {
        if (!configured) return null
        val remote = api.lines(BuildConfig.SUPABASE_ANON_KEY, "Bearer ${BuildConfig.SUPABASE_ANON_KEY}", lyricsId = "eq.$lyricsId")
        return remote.takeIf { it.isNotEmpty() }?.map { SyncedLyricLine("$lyricsId:${it.index}", it.time, it.text) }?.let(LrcCodec::encode)
    }

    companion object {
        fun normalize(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
    }
}
