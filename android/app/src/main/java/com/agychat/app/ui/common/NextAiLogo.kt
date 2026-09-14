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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Signature Google Antigravity Color Palette
val AntigravityCyan = Color(0xFF00F5FF)
val AntigravitySky = Color(0xFF38BDF8)
val AntigravityBlue = Color(0xFF2563EB)
val AntigravityIndigo = Color(0xFF6366F1)
val AntigravityViolet = Color(0xFFD946EF)
val AntigravityRose = Color(0xFFF43F5E)
val AntigravityCoral = Color(0xFFFF6B8B)
val AntigravitySpaceVoid = Color(0xFF080A16)
val AntigravityDeepDisc = Color(0xFF0B0E1E)

/**
 * Pure code vector logo component for Next AI.
 *
 * Employs Google Antigravity cosmic aesthetic:
 * - Dual 3D gravitational orbital torus rings with perspective depth (drawn front and rear)
 * - Zero-g suspended monogram ("N" with floating diagonal and integrated "AI")
 * - Integrated aerospace vector typography ("NEXT · AI")
 * - 4-point radiant diamond starlight sparkle and celestial satellite beacons
 * - Renders at 120 FPS natively without any external bitmap images or network calls.
 */
@Composable
fun NextAiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    showBackground: Boolean = true,
    showText: Boolean = size >= 36.dp,
    primaryColor: Color = AntigravityCyan,
    accentColor: Color = AntigravityViolet,
    coralColor: Color = AntigravityRose,
    sparkColor: Color = Color.White,
    backgroundColor: Color = AntigravitySpaceVoid
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val scale = w / 120f
        val cx = w / 2f
        val cy = h / 2f

        // 1. COSMIC BACKGROUND & ATMOSPHERIC HALO
        if (showBackground) {
            val bgRadius = 56f * scale

            // Ambient outer glow halo
            drawCircle(
                color = primaryColor.copy(alpha = 0.08f),
                radius = bgRadius + 3f * scale,
                center = Offset(cx, cy)
            )

            // Deep space disc
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AntigravityDeepDisc,
                        backgroundColor
                    ),
                    center = Offset(cx, cy * 0.95f),
                    radius = bgRadius
                ),
                radius = bgRadius,
                center = Offset(cx, cy)
            )

            // Iridescent quantum rim border
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.85f),
                        AntigravityIndigo.copy(alpha = 0.65f),
                        accentColor.copy(alpha = 0.85f),
                        coralColor.copy(alpha = 0.75f),
                        primaryColor.copy(alpha = 0.85f)
                    ),
                    center = Offset(cx, cy)
                ),
                radius = bgRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.8f * scale)
            )

            // Inner ambient nebula bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AntigravityIndigo.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy * 0.9f),
                    radius = 42f * scale
                ),
                radius = 42f * scale,
                center = Offset(cx, cy * 0.9f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = Offset(cx - 15f * scale, cy * 0.75f),
                    radius = 30f * scale
                ),
                radius = 30f * scale,
                center = Offset(cx - 15f * scale, cy * 0.75f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coralColor.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = Offset(cx + 16f * scale, cy * 1.05f),
                    radius = 32f * scale
                ),
                radius = 32f * scale,
                center = Offset(cx + 16f * scale, cy * 1.05f)
            )
        }

        // Layout offsets based on whether bottom "NEXT · AI" text is shown
        val heroOffsetY = if (showText) 0f else 6f * scale
        val heroScale = if (showText) 1.0f else 1.15f
        val ringCy = (cy - 7f * scale) + (if (showText) 0f else 4f * scale)
        val ringRx = 50f * scale * heroScale
        val ringRy = 19f * scale * heroScale

        // 2. REAR ORBITAL GRAVITATIONAL RING (Drawn behind the central floating glyphs)
        rotate(degrees = -26f, pivot = Offset(cx, ringCy)) {
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.45f),
                        accentColor.copy(alpha = 0.45f)
                    ),
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

        // 3. CENTRAL LEVITATING HERO MONOGRAM ("N" + "AI")
        // Designed with zero-gravity floating geometry and anti-gravity air gaps
        val nLeftX = (cx - 25f * scale * heroScale)
        val nRightX = (cx + 4f * scale * heroScale)
        val topY = (27f * scale * heroScale) + heroOffsetY
        val bottomY = (67f * scale * heroScale) + heroOffsetY
        val stemW = 8.4f * scale * heroScale

        // 'N' Left Stem: Quantum Cyan to Antigravity Blue
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(primaryColor, AntigravityBlue),
                start = Offset(nLeftX, topY),
                end = Offset(nLeftX, bottomY)
            ),
            start = Offset(nLeftX, topY),
            end = Offset(nLeftX, bottomY),
            strokeWidth = stemW,
            cap = StrokeCap.Round
        )

        // 'N' Floating Diagonal Blade: Suspended with anti-gravity air gaps at both ends
        val diagStartX = nLeftX + 5f * scale * heroScale
        val diagStartY = topY + 6f * scale * heroScale
        val diagEndX = nRightX - 5f * scale * heroScale
        val diagEndY = bottomY - 6f * scale * heroScale
        val diagW = 8.0f * scale * heroScale

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(AntigravityBlue, AntigravityIndigo, accentColor),
                start = Offset(diagStartX, diagStartY),
                end = Offset(diagEndX, diagEndY)
            ),
            start = Offset(diagStartX, diagStartY),
            end = Offset(diagEndX, diagEndY),
            strokeWidth = diagW,
            cap = StrokeCap.Round
        )

        // 'N' Right Stem: Radiant Violet to Neon Rose
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(accentColor, coralColor),
                start = Offset(nRightX, topY),
                end = Offset(nRightX, bottomY)
            ),
            start = Offset(nRightX, topY),
            end = Offset(nRightX, bottomY),
            strokeWidth = stemW,
            cap = StrokeCap.Round
        )

        // 'AI' Futuristic Sculpted Letterforms
        val aLeftX = cx + 13f * scale * heroScale
        val aApexX = cx + 19.5f * scale * heroScale
        val aRightX = cx + 25.5f * scale * heroScale
        val aTopY = (34f * scale * heroScale) + heroOffsetY
        val aiStemW = 6.4f * scale * heroScale

        // 'A' Left leg
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(coralColor, AntigravityCoral),
                start = Offset(aLeftX, bottomY),
                end = Offset(aApexX, aTopY)
            ),
            start = Offset(aLeftX, bottomY),
            end = Offset(aApexX, aTopY),
            strokeWidth = aiStemW,
            cap = StrokeCap.Round
        )

        // 'A' Right leg
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(AntigravityCoral, Color(0xFFFFB020)),
                start = Offset(aApexX, aTopY),
                end = Offset(aRightX, bottomY)
            ),
            start = Offset(aApexX, aTopY),
            end = Offset(aRightX, bottomY),
            strokeWidth = aiStemW,
            cap = StrokeCap.Round
        )

        // 'A' Floating Crossbar (suspended in zero-g)
        val aBarY = (55f * scale * heroScale) + heroOffsetY
        drawLine(
            color = Color(0xFFFFD4C8),
            start = Offset(cx + 15.5f * scale * heroScale, aBarY),
            end = Offset(cx + 23.5f * scale * heroScale, aBarY),
            strokeWidth = 4.4f * scale * heroScale,
            cap = StrokeCap.Round
        )

        // 'I' Upright Stem
        val iX = cx + 33.5f * scale * heroScale
        val iTopY = (45f * scale * heroScale) + heroOffsetY
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(primaryColor, AntigravitySky),
                start = Offset(iX, iTopY),
                end = Offset(iX, bottomY)
            ),
            start = Offset(iX, iTopY),
            end = Offset(iX, bottomY),
            strokeWidth = aiStemW,
            cap = StrokeCap.Round
        )

        // Anti-Gravity Singularity Orb (Dot of 'I' floating in levitation)
        val orbCenter = Offset(iX, (33f * scale * heroScale) + heroOffsetY)
        val orbRadius = 3.6f * scale * heroScale
        // Singularity starlight halo
        drawCircle(
            color = primaryColor.copy(alpha = 0.35f),
            radius = orbRadius * 1.8f,
            center = orbCenter
        )
        // Core pearl
        drawCircle(
            color = sparkColor,
            radius = orbRadius,
            center = orbCenter
        )

        // 4. FRONT ORBITAL GRAVITATIONAL RING (Sweeps across the foreground!)
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
                style = Stroke(width = 3.2f * scale, cap = StrokeCap.Round)
            )
        }

        // Orbital Satellite Particle Beacons
        val angleRad1 = Math.toRadians(38.0)
        val rotRad = Math.toRadians(-26.0)
        val cosRot = Math.cos(rotRad).toFloat()
        val sinRot = Math.sin(rotRad).toFloat()

        val elX1 = (ringRx * Math.cos(angleRad1)).toFloat()
        val elY1 = (ringRy * Math.sin(angleRad1)).toFloat()
        val node1X = elX1 * cosRot - elY1 * sinRot + cx
        val node1Y = elX1 * sinRot + elY1 * cosRot + ringCy

        // Beacon 1 (Starlight white with cyan halo)
        drawCircle(
            color = primaryColor.copy(alpha = 0.4f),
            radius = 5.2f * scale,
            center = Offset(node1X, node1Y)
        )
        drawCircle(
            color = Color.White,
            radius = 3.4f * scale,
            center = Offset(node1X, node1Y)
        )

        // Beacon 2 (Cyan orbital node)
        val angleRad2 = Math.toRadians(142.0)
        val elX2 = (ringRx * Math.cos(angleRad2)).toFloat()
        val elY2 = (ringRy * Math.sin(angleRad2)).toFloat()
        val node2X = elX2 * cosRot - elY2 * sinRot + cx
        val node2Y = elX2 * sinRot + elY2 * cosRot + ringCy

        drawCircle(
            color = primaryColor,
            radius = 2.8f * scale,
            center = Offset(node2X, node2Y)
        )

        // 5. RADIANT 4-POINT STARLIGHT DIAMOND SPARKLE (Upper Right Lagrange Point)
        val sparkCenterX = cx + 39f * scale
        val sparkCenterY = cy - 41f * scale
        val sparkRadius = 10f * scale
        val sparkCurve = 3.2f * scale

        val sparkPath = Path().apply {
            moveTo(sparkCenterX, sparkCenterY - sparkRadius)
            cubicTo(
                sparkCenterX, sparkCenterY - sparkCurve,
                sparkCenterX + sparkCurve, sparkCenterY,
                sparkCenterX + sparkRadius, sparkCenterY
            )
            cubicTo(
                sparkCenterX + sparkCurve, sparkCenterY,
                sparkCenterX, sparkCenterY + sparkCurve,
                sparkCenterX, sparkCenterY + sparkRadius
            )
            cubicTo(
                sparkCenterX, sparkCenterY + sparkCurve,
                sparkCenterX - sparkCurve, sparkCenterY,
                sparkCenterX - sparkRadius, sparkCenterY
            )
            cubicTo(
                sparkCenterX - sparkCurve, sparkCenterY,
                sparkCenterX, sparkCenterY - sparkCurve,
                sparkCenterX, sparkCenterY - sparkRadius
            )
            close()
        }

        // Spark ambient glow
        drawCircle(
            color = primaryColor.copy(alpha = 0.25f),
            radius = sparkRadius * 1.3f,
            center = Offset(sparkCenterX, sparkCenterY)
        )
        drawPath(
            path = sparkPath,
            color = sparkColor
        )

        // 6. PRECISION VECTOR TYPOGRAPHY ("N E X T · A I")
        if (showText) {
            val textY = 86f * scale
            val textH = 10.5f * scale
            val textW = 2.1f * scale
            val textColPrimary = Color(0xFFF1F5F9)
            val textColAccent = Color(0xFFFFC0D4)

            // N
            drawLine(
                color = textColPrimary,
                start = Offset(29f * scale, textY),
                end = Offset(29f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(29f * scale, textY),
                end = Offset(36f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(36f * scale, textY),
                end = Offset(36f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )

            // E
            drawLine(
                color = textColPrimary,
                start = Offset(41f * scale, textY),
                end = Offset(41f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(41f * scale, textY),
                end = Offset(47f * scale, textY),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(41f * scale, textY + textH * 0.5f),
                end = Offset(46f * scale, textY + textH * 0.5f),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(41f * scale, textY + textH),
                end = Offset(47f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )

            // X
            drawLine(
                color = textColPrimary,
                start = Offset(51.5f * scale, textY),
                end = Offset(58f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(58f * scale, textY),
                end = Offset(51.5f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )

            // T
            drawLine(
                color = textColPrimary,
                start = Offset(62.5f * scale, textY),
                end = Offset(69f * scale, textY),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColPrimary,
                start = Offset(65.7f * scale, textY),
                end = Offset(65.7f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )

            // Singularity Dot
            drawCircle(
                color = primaryColor,
                radius = 1.5f * scale,
                center = Offset(73f * scale, textY + textH * 0.5f)
            )

            // A
            drawLine(
                color = textColAccent,
                start = Offset(77f * scale, textY + textH),
                end = Offset(81.5f * scale, textY),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColAccent,
                start = Offset(81.5f * scale, textY),
                end = Offset(86f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
            drawLine(
                color = textColAccent,
                start = Offset(79f * scale, textY + textH * 0.65f),
                end = Offset(84f * scale, textY + textH * 0.65f),
                strokeWidth = textW, cap = StrokeCap.Round
            )

            // I
            drawLine(
                color = textColAccent,
                start = Offset(91f * scale, textY),
                end = Offset(91f * scale, textY + textH),
                strokeWidth = textW, cap = StrokeCap.Round
            )
        }
    }
}
