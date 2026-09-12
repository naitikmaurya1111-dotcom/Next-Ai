package com.agychat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Modern ChatGPT Clean Aesthetics & Warm Accents ──────────────────────────
val ClaudeTerracotta       = Color(0xFFD97757) // Signature warm terracotta coral
val ClaudeTerracottaLight  = Color(0xFFE88A6D)
val ClaudeTerracottaDark   = Color(0xFFBD5E3F)

// ChatGPT Signature Semantic Accents
val ChatGptEmerald         = Color(0xFF10A37F) // Iconic ChatGPT emerald green
val ChatGptBlue            = Color(0xFF3B82F6) // Web search & research blue
val ChatGptPurple          = Color(0xFF8B5CF6) // Deep reasoning & intelligence purple
val ChatGptAmber           = Color(0xFFF59E0B) // Active tool & warning amber

// Dark Theme Surfaces (Modern ChatGPT 2025/2026 dark mode)
val DarkBg                 = Color(0xFF171717) // ChatGPT pure dark canvas
val DarkSurface            = Color(0xFF212121) // ChatGPT card & drawer surface
val DarkSurfaceElevated    = Color(0xFF2F2F2F) // ChatGPT user message bubble & floating card
val DarkBorder             = Color(0xFF383838) // Hairline border
val DarkTextPrimary        = Color(0xFFECECEC) // Crisp white high-legibility text
val DarkTextSecondary      = Color(0xFFB4B4B4) // Muted silver slate

// Light Theme Surfaces (Modern ChatGPT 2025/2026 light mode)
val LightBg                = Color(0xFFFFFFFF) // Pure clean white canvas
val LightSurface           = Color(0xFFFFFFFF) // Clean surface
val LightSurfaceElevated   = Color(0xFFF4F4F4) // ChatGPT soft gray user bubble & card
val LightBorder            = Color(0xFFE5E5E5) // Clean minimal border
val LightTextPrimary       = Color(0xFF0D0D0D) // Deep high-contrast dark text
val LightTextSecondary     = Color(0xFF666666) // Refined secondary text

// Semantic Accents
val ThinkingPurpleBgDark   = Color(0xFF1F1B2B)
val ThinkingPurpleBorderDark= Color(0xFF372E50)
val ThinkingPurpleTextDark = Color(0xFFD3C5F8)
val ThinkingPurpleBgLight  = Color(0xFFF7F5FC)
val ThinkingPurpleBorderLight= Color(0xFFE5E0F8)
val ThinkingPurpleTextLight= Color(0xFF5B45A8)

val CodeBlockBg            = Color(0xFF0D0D0D)
val CodeBlockHeader        = Color(0xFF1C1C1F)
val CodeBlockBorder        = Color(0xFF2E2E33)

// ── Material 3 Schemes ────────────────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF3B231B),
    onPrimaryContainer  = Color(0xFFFFDBCF),
    secondary           = ChatGptEmerald,
    onSecondary         = Color.White,
    tertiary            = ChatGptBlue,
    onTertiary          = Color.White,
    background          = DarkBg,
    onBackground        = DarkTextPrimary,
    surface             = DarkSurface,
    onSurface           = DarkTextPrimary,
    surfaceVariant      = DarkSurfaceElevated,
    onSurfaceVariant    = DarkTextSecondary,
    outline             = DarkBorder,
    outlineVariant      = Color(0xFF303030),
    error               = Color(0xFFEF5350),
    onError             = Color.White
)

val LightColorScheme = lightColorScheme(
    primary             = ClaudeTerracotta,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFFBECE5),
    onPrimaryContainer  = Color(0xFF3D1F16),
    secondary           = ChatGptEmerald,
    onSecondary         = Color.White,
    tertiary            = ChatGptBlue,
    onTertiary          = Color.White,
    background          = LightBg,
    onBackground        = LightTextPrimary,
    surface             = LightSurface,
    onSurface           = LightTextPrimary,
    surfaceVariant      = LightSurfaceElevated,
    onSurfaceVariant    = LightTextSecondary,
    outline             = LightBorder,
    outlineVariant      = Color(0xFFECECEC),
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
