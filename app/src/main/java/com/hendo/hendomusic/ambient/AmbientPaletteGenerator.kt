package com.hendo.hendomusic.ambient

import kotlin.math.max

object AmbientPaletteConfig {
    const val SAMPLE_SIZE = 48
    const val MAX_CACHE_ENTRIES = 48
    const val MIN_ALPHA = 128
    const val CHROMATIC_SATURATION = .16f
    const val WHITE_BACKGROUND_VALUE = .76f
    const val DARK_BACKGROUND_VALUE = .28f
    const val MIN_CHROMATIC_FRACTION = .16f
    const val MIN_CHROMATIC_FRACTION_ON_DARK = .08f
    const val MIN_CLUSTER_FRACTION = .035f
    const val TONAL_DOMINANCE = .72f
    const val DISTINCT_COLOR_DISTANCE = .145f
}

/** Pure, deterministic color quantization and palette generation. */
object AmbientPaletteGenerator {
    private data class Accumulator(var count: Int = 0, var red: Long = 0, var green: Long = 0, var blue: Long = 0) {
        fun add(color: Int) {
            count++
            red += (color ushr 16) and 0xFF
            green += (color ushr 8) and 0xFF
            blue += color and 0xFF
        }
        fun color(): Int = AmbientColorMath.argb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private data class Cluster(val color: Int, val hsv: AmbientHsv, val count: Int)

    fun generate(pixels: IntArray?): AmbientPalette {
        if (pixels == null || pixels.isEmpty()) return AmbientPalette.Neutral
        val buckets = linkedMapOf<Int, Accumulator>()
        var visible = 0
        var chromatic = 0
        var neutralValueTotal = 0f
        var neutralCount = 0
        pixels.forEach { color ->
            if ((color ushr 24) < AmbientPaletteConfig.MIN_ALPHA) return@forEach
            visible++
            val hsv = AmbientColorMath.hsv(color)
            if (hsv.saturation < AmbientPaletteConfig.CHROMATIC_SATURATION) {
                neutralValueTotal += hsv.value
                neutralCount++
                return@forEach
            }
            chromatic++
            val hueBin = (hsv.hue / 15f).toInt().coerceIn(0, 23)
            val saturationBin = (hsv.saturation * 4f).toInt().coerceIn(0, 3)
            val valueBin = (hsv.value * 4f).toInt().coerceIn(0, 3)
            val key = hueBin * 100 + saturationBin * 10 + valueBin
            buckets.getOrPut(key, ::Accumulator).add(color)
        }
        if (visible == 0 || chromatic == 0) return AmbientPalette.Neutral

        val neutralValue = if (neutralCount == 0) .5f else neutralValueTotal / neutralCount
        val requiredFraction = if (neutralValue < AmbientPaletteConfig.DARK_BACKGROUND_VALUE) {
            AmbientPaletteConfig.MIN_CHROMATIC_FRACTION_ON_DARK
        } else AmbientPaletteConfig.MIN_CHROMATIC_FRACTION
        if (chromatic.toFloat() / visible < requiredFraction) return AmbientPalette.Neutral

        val minimumPopulation = max(1, (visible * AmbientPaletteConfig.MIN_CLUSTER_FRACTION).toInt())
        val clusters = buckets.values.mapNotNull { bucket ->
            if (bucket.count < minimumPopulation) null
            else bucket.color().let { Cluster(it, AmbientColorMath.hsv(it), bucket.count) }
        }.sortedByDescending { cluster ->
            cluster.count * (.72f + cluster.hsv.saturation * .20f + cluster.hsv.value * .08f)
        }
        if (clusters.isEmpty()) return AmbientPalette.Neutral

        val selected = mutableListOf<Cluster>()
        clusters.forEach { candidate ->
            if (selected.size < 3 && selected.none {
                    AmbientColorMath.perceptualDistance(it.hsv, candidate.hsv) < AmbientPaletteConfig.DISTINCT_COLOR_DISTANCE
                }) selected += candidate
        }
        val primary = selected.first()
        val primaryShare = primary.count.toFloat() / chromatic
        val effectivelyTonal = selected.size == 1 || primaryShare >= AmbientPaletteConfig.TONAL_DOMINANCE ||
            selected.all { AmbientColorMath.hueDistance(primary.hsv.hue, it.hsv.hue) < 24f }
        return if (effectivelyTonal) tonal(primary.hsv) else multicolor(selected)
    }

    private fun tonal(source: AmbientHsv): AmbientPalette {
        val hue = source.hue
        val baseSaturation = source.saturation.coerceIn(.28f, .72f)
        val baseValue = source.value.coerceIn(.42f, .76f)
        return AmbientPalette(
            listOf(
                AmbientColorMath.fromHsv(hue, (baseSaturation * .68f).coerceAtLeast(.20f), (baseValue + .22f).coerceAtMost(.92f)),
                AmbientColorMath.fromHsv(hue, baseSaturation, baseValue),
                AmbientColorMath.fromHsv(hue, (baseSaturation * .92f).coerceAtLeast(.25f), (baseValue * .52f).coerceAtLeast(.18f)),
            ),
            AmbientPaletteKind.TONAL,
        )
    }

    private fun multicolor(selected: List<Cluster>): AmbientPalette {
        val colors = selected.take(3).map { cluster ->
            AmbientColorMath.fromHsv(
                cluster.hsv.hue,
                cluster.hsv.saturation.coerceIn(.28f, .68f),
                cluster.hsv.value.coerceIn(.40f, .74f),
            )
        }.toMutableList()
        if (colors.size == 2) {
            val primary = AmbientColorMath.hsv(colors.first())
            colors += AmbientColorMath.fromHsv(primary.hue, primary.saturation, (primary.value * .55f).coerceAtLeast(.18f))
        }
        return if (colors.size == 3) AmbientPalette(colors, AmbientPaletteKind.MULTICOLOR) else tonal(selected.first().hsv)
    }
}
