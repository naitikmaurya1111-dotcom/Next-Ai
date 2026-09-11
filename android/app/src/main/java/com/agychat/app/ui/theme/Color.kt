package com.agychat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Brand Colors ────────────────────────────────────────────────────────────
val AGYBlue        = Color(0xFF1A73E8)  // Google Blue
val AGYBlueDark    = Color(0xFF4A90D9)
val AGYSurface     = Color(0xFF1E1E2E)  // Dark background (Catppuccin Mocha)
val AGYSurfaceVar  = Color(0xFF2A2A3E)
val AGYOnSurface   = Color(0xFFCDD6F4)
val AGYPrimary     = Color(0xFF89B4FA)
val UserBubble     = Color(0xFF313244)
val AssistBubble   = Color(0xFF1E1E2E)

// ── Dark Color Scheme ───────────────────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary          = AGYPrimary,
    onPrimary        = Color(0xFF00204A),
    primaryContainer = Color(0xFF1A3557),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary        = Color(0xFFCBA6F7),   // Mauve
    onSecondary      = Color(0xFF390050),
    secondaryContainer = Color(0xFF51006A),
    onSecondaryContainer = Color(0xFFF6D0FF),
    tertiary         = Color(0xFF94E2D5),   // Teal
    onTertiary       = Color(0xFF003731),
    background       = Color(0xFF11111B),   // Darkest
    onBackground     = Color(0xFFCDD6F4),
    surface          = Color(0xFF1E1E2E),
    onSurface        = Color(0xFFCDD6F4),
    surfaceVariant   = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    outline          = Color(0xFF585B70),
    error            = Color(0xFFF38BA8),
    onError          = Color(0xFF690029),
)

// ── Light Color Scheme ──────────────────────────────────────────────────────
val LightColorScheme = lightColorScheme(
    primary          = AGYBlue,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary        = Color(0xFF7C4DFF),
    onSecondary      = Color.White,
    secondaryContainer = Color(0xFFEDE7F6),
    onSecondaryContainer = Color(0xFF21005D),
    tertiary         = Color(0xFF00897B),
    onTertiary       = Color.White,
    background       = Color(0xFFFAFAFF),
    onBackground     = Color(0xFF1A1C2A),
    surface          = Color(0xFFFFFFFF),
    onSurface        = Color(0xFF1A1C2A),
    surfaceVariant   = Color(0xFFEEF0FB),
    onSurfaceVariant = Color(0xFF45474E),
    outline          = Color(0xFFB0B3C0),
    error            = Color(0xFFBA1A1A),
    onError          = Color.White,
)

// ── Typography ─────────────────────────────────────────────────────────────
val NextAITypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
