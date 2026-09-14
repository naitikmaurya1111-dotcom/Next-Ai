package com.agychat.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Signature Cosmic Color Palette (Gemini + Antigravity)
val GeminiCyan = Color(0xFF00F0FF)
val GeminiBlue = Color(0xFF2563EB)
val GeminiViolet = Color(0xFFA855F7)
val GeminiCoral = Color(0xFFFF5E7E)
val GeminiAmber = Color(0xFFFFB020)
val GeminiSpaceVoid = Color(0xFF05070F)

/**
 * Gemini-Inspired Antigravity Vector Logo for Next AI.
 *
 * Distinctive design:
 * - Iconic 4-point celestial star with fluid concave Bézier curves and sweep gradient
 * - 3D orbital gravitational ring inclined at -26° that loops in front and behind
 * - Intertwined secondary Twin Sparkle (the Gemini "Twin" star) orbiting at upper-right
 * - Central radiant starlight singularity core
 * - Pure hardware-accelerated Compose Canvas code (120 FPS, 0 ms latency, resolution-independent).
 */
@Composable
fun NextAiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    showBackground: Boolean = true,
    showText: Boolean = false,
    primaryColor: Color = GeminiCyan,
    accentColor: Color = GeminiViolet,
    coralColor: Color = GeminiCoral,
    sparkColor: Color = Color.White,
    backgroundColor: Color = GeminiSpaceVoid
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val scale = w / 120f
        val cx = w / 2f
        val cy = h / 2f

        // 1. COSMIC MIDNIGHT BACKGROUND DISC
        if (showBackground) {
            val bgRadius = 56f * scale
            drawCircle(
                color = backgroundColor,
                radius = bgRadius,
                center = Offset(cx, cy)
            )
            // Ambient deep blue-violet bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GeminiBlue.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = 42f * scale
                ),
                radius = 42f * scale,
                center = Offset(cx, cy)
            )
        }

        val ringRx = 48f * scale
        val ringRy = 17f * scale
        val ringCy = cy

        // 2. 3D ORBITAL GRAVITATIONAL RING (Rear half passing behind star)
        rotate(degrees = -26f, pivot = Offset(cx, ringCy)) {
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.5f), accentColor.copy(alpha = 0.5f)),
                    start = Offset(cx - ringRx, ringCy),
                    end = Offset(cx + ringRx, ringCy)
                ),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - ringRx, ringCy - ringRy),
                size = Size(ringRx * 2f, ringRy * 2f),
                style = Stroke(width = 2.4f * scale, cap = StrokeCap.Round)
            )
        }

        // 3. HERO 4-POINT GEMINI CELESTIAL STAR
        val sr = 35f * scale
        val sw = 14f * scale
        val starPath = Path().apply {
            moveTo(cx, cy - sr)
            cubicTo(cx, cy - sw, cx + sw, cy, cx + sr, cy)
            cubicTo(cx + sw, cy, cx, cy + sw, cx, cy + sr)
            cubicTo(cx, cy + sw, cx - sw, cy, cx - sr, cy)
            cubicTo(cx - sw, cy, cx, cy - sw, cx, cy - sr)
            close()
        }

        // Vibrant multi-stop sweep gradient (Cyan -> Violet -> Coral -> Royal Blue -> Cyan)
        drawPath(
            path = starPath,
            brush = Brush.sweepGradient(
                colors = listOf(
                    primaryColor,
                    accentColor,
                    coralColor,
                    GeminiBlue,
                    primaryColor
                ),
                center = Offset(cx, cy)
            )
        )

        // 4. CORE SINGULARITY STARLIGHT DIAMOND
        val cr = 8.5f * scale
        val cw = 3.4f * scale
        val corePath = Path().apply {
            moveTo(cx, cy - cr)
            cubicTo(cx, cy - cw, cx + cw, cy, cx + cr, cy)
            cubicTo(cx + cw, cy, cx, cy + cw, cx, cy + cr)
            cubicTo(cx, cy + cw, cx - cw, cy, cx - cr, cy)
            cubicTo(cx - cw, cy, cx, cy - cw, cx, cy - cr)
            close()
        }
        drawPath(path = corePath, color = sparkColor)

        // 5. 3D ORBITAL GRAVITATIONAL RING (Front half sweeping across foreground)
        rotate(degrees = -26f, pivot = Offset(cx, ringCy)) {
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(coralColor, accentColor, primaryColor),
                    start = Offset(cx + ringRx, ringCy),
                    end = Offset(cx - ringRx, ringCy)
                ),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - ringRx, ringCy - ringRy),
                size = Size(ringRx * 2f, ringRy * 2f),
                style = Stroke(width = 3.0f * scale, cap = StrokeCap.Round)
            )
        }

        // Orbital Beacon Node
        val rotRad = Math.toRadians(-26.0)
        val cosRot = Math.cos(rotRad).toFloat()
        val sinRot = Math.sin(rotRad).toFloat()
        val aRad = Math.toRadians(36.0)
        val elX = (ringRx * Math.cos(aRad)).toFloat()
        val elY = (ringRy * Math.sin(aRad)).toFloat()
        val bX = elX * cosRot - elY * sinRot + cx
        val bY = elX * sinRot + elY * cosRot + ringCy

        drawCircle(color = sparkColor, radius = 3.4f * scale, center = Offset(bX, bY))

        // 6. SECONDARY TWIN SPARK (The Gemini Twin Star at upper right)
        val tcx = cx + 27f * scale
        val tcy = cy - 27f * scale
        val tr = 9.5f * scale
        val tw = 3.8f * scale
        val twinPath = Path().apply {
            moveTo(tcx, tcy - tr)
            cubicTo(tcx, tcy - tw, tcx + tw, tcy, tcx + tr, tcy)
            cubicTo(tcx + tw, tcy, tcx, tcy + tw, tcx, tcy + tr)
            cubicTo(tcx, tcy + tw, tcx - tw, tcy, tcx - tr, tcy)
            cubicTo(tcx - tw, tcy, tcx, tcy - tw, tcx, tcy - tr)
            close()
        }

        // Ambient cyan starlight glow behind twin
        drawCircle(color = primaryColor.copy(alpha = 0.25f), radius = tr * 1.4f, center = Offset(tcx, tcy))
        drawPath(path = twinPath, color = sparkColor)
    }
}
