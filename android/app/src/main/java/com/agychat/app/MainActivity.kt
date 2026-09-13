package com.agychat.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agychat.app.domain.PluginManager
import com.agychat.app.ui.chat.ChatScreen
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.history.HistoryScreen
import com.agychat.app.ui.onboarding.OnboardingScreen
import com.agychat.app.ui.personalization.PersonalizationScreen
import com.agychat.app.ui.settings.SettingsScreen
import com.agychat.app.ui.theme.AGYChatTheme
import com.agychat.app.ui.theme.ClaudeTerracotta
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.Context
import androidx.compose.ui.platform.LocalContext

object AppRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val PERSONALIZATION = "personalization"
    const val ONBOARDING = "onboarding"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pluginManager: PluginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (t: Throwable) {
            Log.w("MainActivity", "enableEdgeToEdge threw exception, proceeding safely", t)
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MainActivity", "Uncaught exception in thread ${thread?.name ?: "unknown"}", throwable)
            try {
                val prefs = getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                val errName = throwable?.javaClass?.simpleName ?: "Crash"
                val errMsg = throwable?.localizedMessage ?: "Unexpected runtime condition"
                prefs.edit().putString("last_crash_error", "$errName: $errMsg").commit()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            AGYChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val prefs = remember { context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE) }
                    var crashMessage by remember { mutableStateOf(prefs.getString("last_crash_error", null)) }

                    if (crashMessage != null) {
                        CrashRecoveryScreen(
                            errorMessage = crashMessage ?: "An unexpected error occurred",
                            onDismiss = {
                                prefs.edit().remove("last_crash_error").apply()
                                crashMessage = null
                            },
                            onResetDefaults = {
                                prefs.edit()
                                    .remove("last_crash_error")
                                    .remove("last_active_conversation_id")
                                    .apply()
                                crashMessage = null
                            }
                        )
                    } else {
                        AGYChatNavHost(pluginManager = pluginManager)
                    }
                }
            }
        }
    }
}

@Composable
fun CrashRecoveryScreen(
    errorMessage: String,
    onDismiss: () -> Unit,
    onResetDefaults: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = ClaudeTerracotta.copy(alpha = 0.12f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Text(
                    text = "Application Restored",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Next AI recovered gracefully from a previous unexpected condition. Your chat history and memories remain safely stored in Room database.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetDefaults,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset State")
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                }
            }
        }
    }
}

@Composable
fun AGYChatNavHost(pluginManager: PluginManager) {
    val navController = rememberNavController()
    // Share a single ChatViewModel instance across all screens
    val sharedChatViewModel: ChatViewModel = hiltViewModel()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE) }
    val isOnboardingDone = remember { prefs.getBoolean("onboarding_completed", false) }
    val startDest = if (isOnboardingDone) AppRoutes.CHAT else AppRoutes.ONBOARDING

    NavHost(
        navController = navController,
        startDestination = startDest,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeIn(tween(300)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeOut(tween(300)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeIn(tween(300)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(300)) }
    ) {
        composable(AppRoutes.ONBOARDING) {
            OnboardingScreen(
                viewModel = sharedChatViewModel,
                onFinishOnboarding = {
                    navController.navigate(AppRoutes.CHAT) {
                        popUpTo(AppRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(AppRoutes.CHAT) {
            ChatScreen(
                viewModel = sharedChatViewModel,
                pluginManager = pluginManager,
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                onNavigateToPersonalization = { navController.navigate(AppRoutes.PERSONALIZATION) }
            )
        }
        composable(AppRoutes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                chatViewModel = sharedChatViewModel
            )
        }
        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                chatViewModel = sharedChatViewModel,
                onNavigateToPersonalization = { navController.navigate(AppRoutes.PERSONALIZATION) }
            )
        }
        composable(AppRoutes.PERSONALIZATION) {
            PersonalizationScreen(
                viewModel = sharedChatViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
