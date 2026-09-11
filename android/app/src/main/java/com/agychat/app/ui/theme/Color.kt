package com.agychat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Claude Web Warm Aesthetic Palette ──────────────────────────────────────
val ClaudeTerracotta       = Color(0xFFD97757) // Claude signature warm terracotta coral
val ClaudeTerracottaLight  = Color(0xFFE88A6D)
val ClaudeTerracottaDark   = Color(0xFFBD5E3F)

// Dark Theme Surfaces (Warm rich charcoal, matching Claude Web dark mode)
val DarkBg                 = Color(0xFF1E1E1C) // Claude Web dark background
val DarkSurface            = Color(0xFF262624) // Soft warm dark card
val DarkSurfaceElevated    = Color(0xFF2F2E2A) // Floating input & user bubble
val DarkBorder             = Color(0xFF3D3C37) // Hairline warm border
val DarkTextPrimary        = Color(0xFFEDECE8) // Warm off-white
val DarkTextSecondary      = Color(0xFFA8A69E) // Muted warm gray

// Light Theme Surfaces (Warm ivory & parchment, matching Claude Web light mode)
val LightBg                = Color(0xFFFAF9F5) // Claude Web iconic warm parchment
val LightSurface           = Color(0xFFFFFFFF) // Crisp white card/floating input
val LightSurfaceElevated   = Color(0xFFF0EEE6) // User bubble & subtle elevated card
val LightBorder            = Color(0xFFE5E2D9) // Delicate warm border
val LightTextPrimary       = Color(0xFF1F1E1B) // Deep charcoal coffee
val LightTextSecondary     = Color(0xFF6F6D66) // Soft slate text

// Semantic Accents
val ThinkingPurpleBgDark   = Color(0xFF211D2B)
val ThinkingPurpleBorderDark= Color(0xFF3E3557)
val ThinkingPurpleTextDark = Color(0xFFC7B8F2)
val ThinkingPurpleBgLight  = Color(0xFFF7F5FC)
val ThinkingPurpleBorderLight= Color(0xFFE2DCF7)
val ThinkingPurpleTextLight= Color(0xFF5B45A8)

val CodeBlockBg            = Color(0xFF161618)
val CodeBlockHeader        = Color(0xFF202024)
val CodeBlockBorder        = Color(0xFF2D2D33)

// ── Material 3 Schemes ────────────────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF3B231B),
    onPrimaryContainer  = Color(0xFFFFDBCF),
    secondary           = ClaudeTerracottaLight,
    onSecondary         = Color(0xFF451909),
    tertiary            = Color(0xFF7CB342),
    onTertiary          = Color.White,
    background          = DarkBg,
    onBackground        = DarkTextPrimary,
    surface             = DarkSurface,
    onSurface           = DarkTextPrimary,
    surfaceVariant      = DarkSurfaceElevated,
    onSurfaceVariant    = DarkTextSecondary,
    outline             = DarkBorder,
    outlineVariant      = Color(0xFF2E2D29),
    error               = Color(0xFFEF5350),
    onError             = Color.White
)

val LightColorScheme = lightColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFFBECE5),
    onPrimaryContainer  = Color(0xFF3D1F16),
    secondary           = ClaudeTerracottaDark,
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
    outlineVariant      = Color(0xFFEDE9E0),
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
