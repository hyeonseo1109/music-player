package com.hendo.hendomusic.ambient

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AmbientHsv(val hue: Float, val saturation: Float, val value: Float)

object AmbientColorMath {
    fun argb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int =
        (alpha.coerceIn(0, 255) shl 24) or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    fun hsv(color: Int): AmbientHsv {
        val red = ((color ushr 16) and 0xFF) / 255f
        val green = ((color ushr 8) and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        val hue = when {
            delta == 0f -> 0f
            maximum == red -> 60f * (((green - blue) / delta) % 6f)
            maximum == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return AmbientHsv(hue, if (maximum == 0f) 0f else delta / maximum, maximum)
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val chroma = v * s
        val x = chroma * (1f - abs((normalizedHue / 60f) % 2f - 1f))
        val (r1, g1, b1) = when (normalizedHue) {
            in 0f..<60f -> Triple(chroma, x, 0f)
            in 60f..<120f -> Triple(x, chroma, 0f)
            in 120f..<180f -> Triple(0f, chroma, x)
            in 180f..<240f -> Triple(0f, x, chroma)
            in 240f..<300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = v - chroma
        return argb(((r1 + match) * 255f).toInt(), ((g1 + match) * 255f).toInt(), ((b1 + match) * 255f).toInt())
    }

    fun hueDistance(first: Float, second: Float): Float = abs(first - second).let { min(it, 360f - it) }

    fun perceptualDistance(first: AmbientHsv, second: AmbientHsv): Float =
        hueDistance(first.hue, second.hue) / 180f * .72f +
            abs(first.saturation - second.saturation) * .16f +
            abs(first.value - second.value) * .12f
}
