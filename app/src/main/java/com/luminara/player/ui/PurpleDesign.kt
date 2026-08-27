package com.luminara.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

val ElectricPurple = Color(0xFF9B4DFF)
val NeonViolet = Color(0xFFC45CFF)
val PurpleWhite = Color(0xFFF8F3FF)

@Composable
fun PurpleAtmosphere(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF020106), Color(0xFF080313), Color(0xFF030107)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xA86D16FF), Color(0x383D0AA8), Color.Transparent),
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
            drawLine(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color(0x809B4DFF), Color.Transparent)),
                start = Offset(0f, size.height * .12f),
                end = Offset(size.width, size.height * .12f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        content()
    }
}

fun Modifier.purpleGlass(radius: Int = 20): Modifier = this
    .background(Color(0xB2100A1D), RoundedCornerShape(radius.dp))
    .border(1.dp, Brush.linearGradient(listOf(Color(0x709B4DFF), Color(0x22FFFFFF), Color(0x507B2BE2))), RoundedCornerShape(radius.dp))
