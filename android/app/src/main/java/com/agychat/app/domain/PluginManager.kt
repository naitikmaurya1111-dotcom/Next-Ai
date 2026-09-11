package com.agychat.app.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.agychat.app.domain.model.SlashCommand
import javax.inject.Inject
import javax.inject.Singleton

data class PluginCommand(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val prefix: String,
    val color: Long // ARGB hex
)

@Singleton
class PluginManager @Inject constructor() {

    val pluginCommands = listOf(
        PluginCommand(
            name = "goal",
            description = "Run until done — agent keeps going until the goal is fully achieved",
            icon = Icons.Default.RocketLaunch,
            prefix = "/goal",
            color = 0xFF4CAF50
        ),
        PluginCommand(
            name = "plan",
            description = "Step-by-step planning before execution",
            icon = Icons.Default.List,
            prefix = "/plan",
            color = 0xFF2196F3
        ),
        PluginCommand(
            name = "boost",
            description = "Deep thinking — multiple perspectives and rigorous verification",
            icon = Icons.Default.ElectricBolt,
            prefix = "/boost",
            color = 0xFFFF9800
        ),
        PluginCommand(
            name = "schedule",
            description = "Set a timer or recurring cron job",
            icon = Icons.Default.Schedule,
            prefix = "/schedule",
            color = 0xFF9C27B0
        ),
        PluginCommand(
            name = "browser",
            description = "Browse the web and interact with web applications",
            icon = Icons.Default.Language,
            prefix = "/browser",
            color = 0xFF00BCD4
        ),
        PluginCommand(
            name = "learn",
            description = "Persist this behavior for future conversations",
            icon = Icons.Default.AutoFixHigh,
            prefix = "/learn",
            color = 0xFFE91E63
        ),
        PluginCommand(
            name = "grill-me",
            description = "Interactive interview to align on a plan",
            icon = Icons.Default.Forum,
            prefix = "/grill-me",
            color = 0xFFFF5722
        ),
        PluginCommand(
            name = "teamwork-preview",
            description = "Spawn a team of autonomous agents working together",
            icon = Icons.Default.Group,
            prefix = "/teamwork-preview",
            color = 0xFF607D8B
        )
    )

    // Legacy compat: SlashCommand list for PluginDrawer
    val commands = pluginCommands.map { p ->
        SlashCommand(
            name = p.name,
            description = p.description,
            icon = 0, // unused — PluginDrawer uses pluginCommands now
            prefix = p.prefix
        )
    }
}
