package com.agychat.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agychat.app.ui.theme.ClaudeTerracotta

/**
 * Pure code vector logo component for Next AI.
 * Renders smoothly at any density or dimension without any bitmap image assets.
 */
@Composable
fun NextAiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    primaryColor: Color = ClaudeTerracotta,
    sparkColor: Color = Color(0xFFFFD7A8),
    backgroundColor: Color = Color(0xFF0D0D12),
    showBackground: Boolean = true
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val scale = w / 108f

        if (showBackground) {
            // Background dark circle
            drawCircle(
                color = backgroundColor,
                radius = w / 2f,
                center = Offset(w / 2f, h / 2f)
            )
            // Ambient center glow
            drawCircle(
                color = primaryColor.copy(alpha = 0.14f),
                radius = 36f * scale,
                center = Offset(54f * scale, 54f * scale)
            )
        }

        // Stylized 'N' Monogram
        val strokeW = 9f * scale
        val pathN = Path().apply {
            moveTo(36f * scale, 72f * scale)
            lineTo(36f * scale, 36f * scale)
            lineTo(72f * scale, 72f * scale)
            lineTo(72f * scale, 36f * scale)
        }
        drawPath(
            path = pathN,
            color = primaryColor,
            style = Stroke(
                width = strokeW,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Radiant 4-point AI Sparkle
        val sparkPath = Path().apply {
            val cx = 79f * scale
            val cy = 27f * scale
            val r = 8.5f * scale
            val c = 3.0f * scale
            moveTo(cx, cy - r)
            cubicTo(cx, cy - c, cx + c, cy, cx + r, cy)
            cubicTo(cx + c, cy, cx, cy + c, cx, cy + r)
            cubicTo(cx, cy + c, cx - c, cy, cx - r, cy)
            cubicTo(cx - c, cy, cx, cy - c, cx, cy - r)
            close()
        }
        drawPath(
            path = sparkPath,
            color = sparkColor
        )
    }
}
