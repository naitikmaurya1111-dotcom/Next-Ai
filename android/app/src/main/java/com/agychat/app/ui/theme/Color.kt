package com.agychat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Claude Warm Palette ───────────────────────────────────────────────────
val ClaudeTerracotta    = Color(0xFFD4704B) // Claude signature warm terracotta
val ClaudeAmberLight    = Color(0xFFE5855E)
val ClaudeAmberDark     = Color(0xFFB85633)

// Dark Theme Surfaces
val DarkBg              = Color(0xFF141413) // Warm charcoal
val DarkSurface         = Color(0xFF1F1E1D) // Slightly lighter card
val DarkSurfaceElevated = Color(0xFF2B2A27) // Input & user bubble
val DarkBorder          = Color(0xFF33322E) // Refined subtle border
val DarkTextPrimary     = Color(0xFFEDECE6) // Cream off-white
val DarkTextSecondary   = Color(0xFF9E9C94) // Muted warm gray

// Light Theme Surfaces
val LightBg             = Color(0xFFFAF9F5) // Warm paper cream
val LightSurface        = Color(0xFFFFFFFF)
val LightSurfaceElevated= Color(0xFFF2F0E8) // User bubble & subtle card
val LightBorder         = Color(0xFFE5E3D8)
val LightTextPrimary    = Color(0xFF1C1B18)
val LightTextSecondary  = Color(0xFF737067)

// Semantic Accents
val ThinkingPurpleBg    = Color(0xFF1C182A)
val ThinkingPurpleBorder= Color(0xFF4C3D7A)
val ThinkingPurpleText  = Color(0xFFC0B4F2)
val CodeBlockDark       = Color(0xFF0F0F0F)

// ── Material 3 Schemes ────────────────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF3D2117),
    onPrimaryContainer  = Color(0xFFFFDBCF),
    secondary           = Color(0xFFE5855E),
    onSecondary         = Color(0xFF441B08),
    tertiary            = Color(0xFF7CB342),
    onTertiary          = Color.White,
    background          = DarkBg,
    onBackground        = DarkTextPrimary,
    surface             = DarkSurface,
    onSurface           = DarkTextPrimary,
    surfaceVariant      = DarkSurfaceElevated,
    onSurfaceVariant    = DarkTextSecondary,
    outline             = DarkBorder,
    error               = Color(0xFFEF5350),
    onError             = Color.White
)

val LightColorScheme = lightColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFFFDBCF),
    onPrimaryContainer  = Color(0xFF3D2117),
    secondary           = Color(0xFFB85633),
    onSecondary         = Color.White,
    tertiary            = Color(0xFF558B2F),
    onTertiary          = Color.White,
    background          = LightBg,
    onBackground        = LightTextPrimary,
    surface             = LightSurface,
    onSurface           = LightTextPrimary,
    surfaceVariant      = LightSurfaceElevated,
    onSurfaceVariant    = LightTextSecondary,
    outline             = LightBorder,
    error               = Color(0xFFD32F2F),
    onError             = Color.White
)

// ── Typography ────────────────────────────────────────────────────────────
val NextAITypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )
)
