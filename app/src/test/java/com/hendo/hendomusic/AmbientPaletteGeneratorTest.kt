package com.hendo.hendomusic

import com.hendo.hendomusic.ambient.AmbientColorMath
import com.hendo.hendomusic.ambient.AmbientPaletteGenerator
import com.hendo.hendomusic.ambient.AmbientPaletteKind
import com.hendo.hendomusic.ambient.AmbientPaletteRepository
import com.hendo.hendomusic.ambient.ArtworkPixelLoader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPaletteGeneratorTest {
    @Test fun noArtworkUsesNeutralGray() {
        val palette = AmbientPaletteGenerator.generate(null)
        assertEquals(AmbientPaletteKind.NEUTRAL, palette.kind)
        assertTrue(palette.colors.all { AmbientColorMath.hsv(it).saturation < .12f })
    }

    @Test fun nearlySolidRedCreatesRedTones() {
        val palette = AmbientPaletteGenerator.generate(pixels(900 to 0xFFD62435.toInt(), 100 to 0xFFB91F2E.toInt()))
        assertEquals(AmbientPaletteKind.TONAL, palette.kind)
        assertTrue(palette.colors.all { AmbientColorMath.hsv(it).hue.let { hue -> hue < 18f || hue > 342f } })
        assertTrue(AmbientColorMath.hsv(palette.colors[0]).value > AmbientColorMath.hsv(palette.colors[2]).value)
    }

    @Test fun nearlySolidBlueCreatesBlueTones() {
        val palette = AmbientPaletteGenerator.generate(pixels(850 to 0xFF2457D6.toInt(), 150 to 0xFF173EAE.toInt()))
        assertEquals(AmbientPaletteKind.TONAL, palette.kind)
        assertTrue(palette.colors.all { AmbientColorMath.hsv(it).hue in 205f..235f })
    }

    @Test fun bluePurplePinkKeepsDistinctMajorColors() {
        val palette = AmbientPaletteGenerator.generate(
            pixels(600 to 0xFF183B94.toInt(), 250 to 0xFF7138A8.toInt(), 100 to 0xFFC74787.toInt(), 50 to 0xFFF7F7F7.toInt()),
        )
        assertEquals(AmbientPaletteKind.MULTICOLOR, palette.kind)
        val hues = palette.colors.map { AmbientColorMath.hsv(it).hue }
        assertTrue(hues.maxOf { first -> hues.maxOf { second -> AmbientColorMath.hueDistance(first, second) } } > 55f)
    }

    @Test fun smallRedLogoOnWhiteDoesNotTurnWholePaletteRed() {
        val palette = AmbientPaletteGenerator.generate(pixels(900 to 0xFFF5F5F5.toInt(), 100 to 0xFFE32736.toInt()))
        assertEquals(AmbientPaletteKind.NEUTRAL, palette.kind)
    }

    @Test fun colorRemainsVisibleOnBlackBackground() {
        val palette = AmbientPaletteGenerator.generate(pixels(700 to 0xFF050505.toInt(), 300 to 0xFF2368D8.toInt()))
        assertEquals(AmbientPaletteKind.TONAL, palette.kind)
        assertTrue(palette.colors.any { AmbientColorMath.hsv(it).value > .4f })
    }

    @Test fun repeatedArtworkHitsPaletteCache() = runTest {
        var loads = 0
        val loader = ArtworkPixelLoader {
            loads++
            pixels(100 to 0xFF7A35BA.toInt())
        }
        val repository = AmbientPaletteRepository(loader)
        val first = repository.paletteFor("content://album/7")
        val second = repository.paletteFor("content://album/7")
        assertSame(first, second)
        assertEquals(1, loads)
    }

    private fun pixels(vararg entries: Pair<Int, Int>): IntArray = buildList {
        entries.forEach { (count, color) -> repeat(count) { add(color) } }
    }.toIntArray()
}
