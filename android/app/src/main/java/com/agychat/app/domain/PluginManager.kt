package com.agychat.app.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.agychat.app.domain.model.SlashCommand
import javax.inject.Inject
import javax.inject.Singleton

data class PluginItem(
    val name: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val prefix: String,
    val tag: String,
    val examplePrompt: String,
    val badgeColor: Long // ARGB Hex
)

@Singleton
class PluginManager @Inject constructor() {

    val plugins = listOf(
        PluginItem(
            name = "boost",
            title = "Deep Thinking",
            description = "Multi-perspective deep reasoning, architecture review, and rigorous verification.",
            icon = Icons.Default.ElectricBolt,
            prefix = "/boost",
            tag = "REASONING",
            examplePrompt = "/boost review my architecture and find edge cases",
            badgeColor = 0xFFD4704B // Claude Terracotta
        ),
        PluginItem(
            name = "goal",
            title = "Autonomous Goal",
            description = "Runs continuously in loop until the objective is fully solved without stopping.",
            icon = Icons.Default.RocketLaunch,
            prefix = "/goal",
            tag = "AUTONOMOUS",
            examplePrompt = "/goal write complete unit tests and ensure all pass",
            badgeColor = 0xFF2E7D32 // Emerald Green
        ),
        PluginItem(
            name = "plan",
            title = "Step-by-Step Plan",
            description = "Generates a structured, phased implementation roadmap before executing.",
            icon = Icons.Default.Assignment,
            prefix = "/plan",
            tag = "PLANNING",
            examplePrompt = "/plan design a realtime notification system",
            badgeColor = 0xFF1976D2 // Deep Blue
        ),
        PluginItem(
            name = "browser",
            title = "Web Automation",
            description = "Navigates live web pages, reads docs, extracts data, and searches.",
            icon = Icons.Default.Language,
            prefix = "/browser",
            tag = "RESEARCH",
            examplePrompt = "/browser search latest android compose best practices",
            badgeColor = 0xFF0097A7 // Cyan
        ),
        PluginItem(
            name = "schedule",
            title = "Timer & Cron",
            description = "Sets one-shot reminders or scheduled recurring tasks in background.",
            icon = Icons.Default.Schedule,
            prefix = "/schedule",
            tag = "AUTOMATION",
            examplePrompt = "/schedule run health check every 10 minutes",
            badgeColor = 0xFF7B1FA2 // Purple
        ),
        PluginItem(
            name = "learn",
            title = "Persist Memory",
            description = "Permanently saves project rules, conventions, and habits to agent brain.",
            icon = Icons.Default.Psychology,
            prefix = "/learn",
            tag = "MEMORY",
            examplePrompt = "/learn always use MVVM and Compose in this project",
            badgeColor = 0xFFC2185B // Pink
        ),
        PluginItem(
            name = "grill-me",
            title = "Interview Me",
            description = "Asks you hard clarifying questions to stress-test your design and assumptions.",
            icon = Icons.Default.QuestionAnswer,
            prefix = "/grill-me",
            tag = "INTERVIEW",
            examplePrompt = "/grill-me on my system architecture proposal",
            badgeColor = 0xFFE65100 // Deep Orange
        ),
        PluginItem(
            name = "teamwork-preview",
            title = "Multi-Agent Team",
            description = "Orchestrates parallel specialized agents collaborating on complex workflows.",
            icon = Icons.Default.Groups,
            prefix = "/teamwork-preview",
            tag = "COLLAB",
            examplePrompt = "/teamwork-preview build frontend and backend in parallel",
            badgeColor = 0xFF455A64 // Slate
        )
    )

    val commands: List<SlashCommand> = plugins.map {
        SlashCommand(
            name = it.name,
            description = it.description,
            prefix = it.prefix,
            tag = it.tag,
            example = it.examplePrompt
        )
    }
}
