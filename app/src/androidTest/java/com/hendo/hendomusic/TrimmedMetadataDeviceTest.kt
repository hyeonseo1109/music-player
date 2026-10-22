package com.hendo.hendomusic

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hendo.hendomusic.data.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrimmedMetadataDeviceTest {
    @get:Rule
    val mediaPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    @Test
    fun appOwnedTrimmedM4aCanPersistAndReadMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%_edited_%.m4a"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                cursor.getLong(0),
            ) else null
        }
        assumeNotNull(source)

        val qaName = "HenMusic_metadata_QA_${System.currentTimeMillis()}.m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, qaName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/HendoMusic/QA/")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val qaUri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openInputStream(requireNotNull(source))!!.use { input ->
                resolver.openOutputStream(qaUri, "w")!!.use(input::copyTo)
            }
            resolver.update(qaUri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val mediaId = ContentUris.parseId(qaUri)
            val owner = resolver.query(
                qaUri,
                arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            assertEquals(context.packageName, owner)

            val track = TrackEntity(
                id = "qa:$mediaId",
                mediaStoreId = mediaId,
                uri = qaUri.toString(),
                relativePath = "Music/HendoMusic/QA/",
                fileName = qaName,
                title = "before",
                artist = "before",
                album = "before",
                albumArtist = null,
                durationMs = 1,
                dateAdded = System.currentTimeMillis(),
                dateModified = System.currentTimeMillis(),
                albumArtUri = null,
            )
            val repository = (context.applicationContext as LuminaraApplication).container.musicRepository
            repository.updateMetadata(track, "QA edited title", "QA edited artist", "QA edited album", null)

            val actual = resolver.query(
                qaUri,
                arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                check(cursor.moveToFirst())
                Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
            assertEquals(Triple("QA edited title", "QA edited artist", "QA edited album"), actual)
        } finally {
            resolver.delete(qaUri, null, null)
        }
    }
}
