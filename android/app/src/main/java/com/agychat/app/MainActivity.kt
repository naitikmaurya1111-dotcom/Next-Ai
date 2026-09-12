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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agychat.app.domain.PluginManager
import com.agychat.app.ui.chat.ChatScreen
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.history.HistoryScreen
import com.agychat.app.ui.personalization.PersonalizationScreen
import com.agychat.app.ui.settings.SettingsScreen
import com.agychat.app.ui.theme.AGYChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object AppRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val PERSONALIZATION = "personalization"
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

        setContent {
            AGYChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var crashMessage by remember { mutableStateOf<String?>(null) }

                    if (crashMessage != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Text(
                                text = "Next AI Startup Error:\n\n$crashMessage",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        AGYChatNavHost(pluginManager = pluginManager)
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

    NavHost(
        navController = navController,
        startDestination = AppRoutes.CHAT,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeIn(tween(300)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeOut(tween(300)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeIn(tween(300)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(300)) }
    ) {
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
