package com.agychat.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agychat.app.R

// Signature Vibrant Flat Ribbon Color Palette
val GeminiCyan = Color(0xFF00E5FF)
val GeminiBlue = Color(0xFF2979FF)
val GeminiViolet = Color(0xFF9C27B0)
val GeminiCoral = Color(0xFFFF4081)
val GeminiAmber = Color(0xFFFFD600)
val GeminiSpaceVoid = Color(0xFFFFFFFF)

/**
 * Vibrant Flat Curved Ribbon Logo for Next AI.
 * Displays the high-resolution flat ribbon logo on a clean circular disc or transparent canvas.
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
    backgroundColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (showBackground) {
                    Modifier
                        .clip(CircleShape)
                        .background(backgroundColor)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_nextai_logo_transparent),
            contentDescription = "Next AI Logo",
            modifier = Modifier.fillMaxSize(if (showBackground) 0.85f else 1.0f),
            contentScale = ContentScale.Fit
        )
    }
}
