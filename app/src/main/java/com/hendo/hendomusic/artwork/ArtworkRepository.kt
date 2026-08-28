package com.hendo.hendomusic.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hendo.hendomusic.network.ArtworkProvider
import com.hendo.hendomusic.network.ArtworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.min

sealed interface ArtworkSearchState {
    data object Idle : ArtworkSearchState
    data object Loading : ArtworkSearchState
    data class Success(val results: List<ArtworkResult>) : ArtworkSearchState
    data class Error(val message: String) : ArtworkSearchState
}

class ArtworkRepository(private val context: Context, private val provider: ArtworkProvider = ArtworkProvider()) {
    suspend fun search(query: String): List<ArtworkResult> = provider.searchQuery(query.trim())

    suspend fun cropToAppStorage(
        source: Uri,
        trackId: String,
        zoom: Float,
        offsetXFraction: Float,
        offsetYFraction: Float,
    ): Uri = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(source, 2048)
        try {
            val cropSize = (min(bitmap.width, bitmap.height) / zoom.coerceIn(1f, 3f)).toInt().coerceAtLeast(1)
            val maxX = (bitmap.width - cropSize).coerceAtLeast(0)
            val maxY = (bitmap.height - cropSize).coerceAtLeast(0)
            val left = ((maxX / 2f) - offsetXFraction.coerceIn(-1f, 1f) * maxX / 2f).toInt().coerceIn(0, maxX)
            val top = ((maxY / 2f) - offsetYFraction.coerceIn(-1f, 1f) * maxY / 2f).toInt().coerceIn(0, maxY)
            val cropped = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize)
            val output = if (cropSize > 1200) Bitmap.createScaledBitmap(cropped, 1200, 1200, true) else cropped
            val folder = File(context.filesDir, "artwork").apply { mkdirs() }
            val file = File(folder, "${trackId.replace(':', '_')}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (output !== cropped) output.recycle()
            cropped.recycle()
            Uri.fromFile(file)
        } finally { bitmap.recycle() }
    }

    suspend fun downloadForCrop(url: String): Uri = withContext(Dispatchers.IO) {
        val file = File.createTempFile("artwork_", ".jpg", context.cacheDir)
        URL(url.replace("http://", "https://")).openConnection().apply { connectTimeout = 10_000; readTimeout = 15_000 }
            .getInputStream().use { input -> file.outputStream().use(input::copyTo) }
        Uri.fromFile(file)
    }

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("이미지를 열 수 없습니다")
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("지원하지 않는 이미지 형식입니다")
    }
}
