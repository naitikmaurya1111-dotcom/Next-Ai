package com.agychat.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeState {
    var themeMode by mutableStateOf("system") // "system", "light", "dark"
    var dynamicColor by mutableStateOf(false)
    private var isInitialized = false

    fun init(context: android.content.Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences("next_ai_prefs", android.content.Context.MODE_PRIVATE)
        themeMode = prefs.getString("theme_mode", "system") ?: "system"
        dynamicColor = prefs.getBoolean("dynamic_color", false)
        isInitialized = true
    }

    fun setTheme(context: android.content.Context, mode: String) {
        themeMode = mode
        context.getSharedPreferences("next_ai_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode)
            .apply()
    }

    fun setDynamicColor(context: android.content.Context, enabled: Boolean) {
        dynamicColor = enabled
        context.getSharedPreferences("next_ai_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dynamic_color", enabled)
            .apply()
    }
}

@Composable
fun AGYChatTheme(
    darkTheme: Boolean = when (ThemeState.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = ThemeState.dynamicColor,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ThemeState.init(context)
    }

    val resolvedDark = when (ThemeState.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val resolvedDynamic = ThemeState.dynamicColor

    val colorScheme = when {
        resolvedDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (resolvedDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        resolvedDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !resolvedDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NextAITypography,
        content = content
    )
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
