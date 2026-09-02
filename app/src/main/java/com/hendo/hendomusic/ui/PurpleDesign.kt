package com.hendo.hendomusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

val ElectricPurple = Color(0xFF9B4DFF)
val NeonViolet = Color(0xFFC45CFF)
val PurpleWhite = Color(0xFFF8F3FF)

@Composable
fun PurpleAtmosphere(content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(if (dark) listOf(Color(0xFF020106), Color(0xFF080313), Color(0xFF030107)) else listOf(Color(0xFFFDF9FF), Color(0xFFF1E8FF), Color(0xFFFFFFFF)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(if (dark) Color(0xA86D16FF) else Color(0x707C3AED), Color(0x383D0AA8), Color.Transparent),
                    center = Offset(size.width * .08f, size.height * .68f),
                    radius = size.width * .82f,
                ),
                radius = size.width * .82f,
                center = Offset(size.width * .08f, size.height * .68f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x704D19D9), Color(0x24280A77), Color.Transparent),
                    center = Offset(size.width * .94f, size.height * .25f),
                    radius = size.width * .55f,
                ),
                radius = size.width * .55f,
                center = Offset(size.width * .94f, size.height * .25f),
            )
        }
        content()
    }
}

/** A header-owned divider: it always follows the title in both portrait and landscape. */
@Composable
fun HeaderGradientDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 7.dp)
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x809B4DFF), Color.Transparent))),
    )
}

@Composable fun Modifier.purpleGlass(radius: Int = 20): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return this.background(if (dark) Color(0xB2100A1D) else Color(0xDFFFFFFF), RoundedCornerShape(radius.dp))
        .border(1.dp, Brush.linearGradient(listOf(Color(0x709B4DFF), if (dark) Color(0x22FFFFFF) else Color(0x44FFFFFF), Color(0x507B2BE2))), RoundedCornerShape(radius.dp))
}
