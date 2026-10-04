package com.hendo.hendomusic.ambient

enum class AmbientPaletteKind { NEUTRAL, TONAL, MULTICOLOR }

/** Three small ARGB values are the only artwork-derived objects retained by Ambient Light. */
data class AmbientPalette(
    val colors: List<Int>,
    val kind: AmbientPaletteKind,
) {
    init { require(colors.size == 3) { "Ambient palettes must contain exactly three colors" } }

    companion object {
        val Neutral = AmbientPalette(
            colors = listOf(0xFFC8C8C8.toInt(), 0xFF7A7A7A.toInt(), 0xFF383838.toInt()),
            kind = AmbientPaletteKind.NEUTRAL,
        )
    }
}
