package com.hendo.hendomusic.ambient

import android.content.Context
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ArtworkPixelLoader {
    suspend fun load(artworkUri: String): IntArray?
}

class AlbumArtworkSampler(context: Context) : ArtworkPixelLoader {
    private val appContext = context.applicationContext
    private val imageLoader = ImageLoader.Builder(appContext).build()

    override suspend fun load(artworkUri: String): IntArray? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(appContext)
            .data(artworkUri)
            .size(AmbientPaletteConfig.SAMPLE_SIZE, AmbientPaletteConfig.SAMPLE_SIZE)
            .allowHardware(false)
            .build()
        val image = (imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage ?: return@withContext null
        val bitmap = image.bitmap.ensureSampleSize()
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (bitmap !== image.bitmap) bitmap.recycle()
        }
    }

    private fun Bitmap.ensureSampleSize(): Bitmap {
        if (width <= AmbientPaletteConfig.SAMPLE_SIZE && height <= AmbientPaletteConfig.SAMPLE_SIZE) return this
        return Bitmap.createScaledBitmap(this, AmbientPaletteConfig.SAMPLE_SIZE, AmbientPaletteConfig.SAMPLE_SIZE, true)
    }
}

class AmbientPaletteRepository(
    private val loader: ArtworkPixelLoader,
    private val cache: AmbientPaletteCache = AmbientPaletteCache(),
) {
    constructor(context: Context) : this(AlbumArtworkSampler(context))

    private val mutex = Mutex()

    suspend fun paletteFor(artworkUri: String?): AmbientPalette {
        if (artworkUri.isNullOrBlank()) return AmbientPalette.Neutral
        cache.get(artworkUri)?.let { return it }
        return mutex.withLock {
            cache.get(artworkUri)?.let { return@withLock it }
            val palette = AmbientPaletteGenerator.generate(loader.load(artworkUri))
            cache.put(artworkUri, palette)
            palette
        }
    }
}
