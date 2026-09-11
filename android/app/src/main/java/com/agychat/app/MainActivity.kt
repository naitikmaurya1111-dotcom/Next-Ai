package com.agychat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agychat.app.domain.PluginManager
import com.agychat.app.ui.chat.ChatScreen
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.history.HistoryScreen
import com.agychat.app.ui.settings.SettingsScreen
import com.agychat.app.ui.theme.AGYChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object AppRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pluginManager: PluginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AGYChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AGYChatNavHost(pluginManager = pluginManager)
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
        startDestination = AppRoutes.CHAT
    ) {
        composable(AppRoutes.CHAT) {
            ChatScreen(
                viewModel = sharedChatViewModel,
                pluginManager = pluginManager,
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) }
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
                chatViewModel = sharedChatViewModel
            )
        }
    }
}
