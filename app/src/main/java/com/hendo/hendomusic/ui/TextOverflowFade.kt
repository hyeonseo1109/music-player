package com.hendo.hendomusic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps a single-line title readable while softly hiding only the overflowing end. */
fun Modifier.fadeAtEnd(width: Dp = 22.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeWidth = width.toPx().coerceAtMost(size.width)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = (size.width - fadeWidth).coerceAtLeast(0f),
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
