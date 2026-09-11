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
    val badgeColor: Long, // ARGB Hex
    val isDirectAction: Boolean = false
)

@Singleton
class PluginManager @Inject constructor() {

    val plugins = listOf(
        // === Antigravity CLI Controls ===
        PluginItem(
            name = "model",
            title = "Switch Model",
            description = "Switch active AI model: Gemini 3.8 Flash, Claude Sonnet 4.6, Claude Opus, GPT-OSS",
            icon = Icons.Default.Tune,
            prefix = "/model",
            tag = "CLI",
            examplePrompt = "/model",
            badgeColor = 0xFF1976D2, // Blue
            isDirectAction = true
        ),
        PluginItem(
            name = "effort",
            title = "Reasoning Effort",
            description = "Adjust thinking budget: High, Medium, Low (Gemini & Open models)",
            icon = Icons.Default.ElectricBolt,
            prefix = "/effort",
            tag = "CLI",
            examplePrompt = "/effort",
            badgeColor = 0xFFD4704B, // Terracotta
            isDirectAction = true
        ),
        PluginItem(
            name = "skills",
            title = "Active Skills",
            description = "View all loaded skills, integrations, and workflow extensions in Antigravity",
            icon = Icons.Default.Extension,
            prefix = "/skills",
            tag = "CLI",
            examplePrompt = "/skills",
            badgeColor = 0xFF0097A7
        ),
        PluginItem(
            name = "agents",
            title = "Custom Agents",
            description = "List available custom agents, subagents, and roles",
            icon = Icons.Default.AccountCircle,
            prefix = "/agents",
            tag = "CLI",
            examplePrompt = "/agents",
            badgeColor = 0xFF5E35B1
        ),
        PluginItem(
            name = "usage",
            title = "Quota & Usage",
            description = "Inspect token quota, token usage, and daily limits in Antigravity",
            icon = Icons.Default.BarChart,
            prefix = "/usage",
            tag = "CLI",
            examplePrompt = "/usage",
            badgeColor = 0xFF388E3C
        ),
        PluginItem(
            name = "credits",
            title = "G1 Compute Credits",
            description = "Check remaining G1 credits and compute allocation",
            icon = Icons.Default.MonetizationOn,
            prefix = "/credits",
            tag = "CLI",
            examplePrompt = "/credits",
            badgeColor = 0xFFF57F17
        ),
        PluginItem(
            name = "permissions",
            title = "Tool Permissions",
            description = "Manage auto-approve rules and security policies for bash and edits",
            icon = Icons.Default.Security,
            prefix = "/permissions",
            tag = "CLI",
            examplePrompt = "/permissions",
            badgeColor = 0xFF0288D1
        ),
        PluginItem(
            name = "changelog",
            title = "CLI Changelog",
            description = "Show Antigravity release notes, new features, and version updates",
            icon = Icons.Default.History,
            prefix = "/changelog",
            tag = "CLI",
            examplePrompt = "/changelog",
            badgeColor = 0xFF6D4C41
        ),
        PluginItem(
            name = "clear",
            title = "Clear Chat",
            description = "Clear current conversation messages and start a fresh turn",
            icon = Icons.Default.DeleteOutline,
            prefix = "/clear",
            tag = "ACTION",
            examplePrompt = "/clear",
            badgeColor = 0xFFD32F2F,
            isDirectAction = true
        ),
        PluginItem(
            name = "help",
            title = "Command Directory",
            description = "Show full list of CLI commands, capabilities, and keybindings",
            icon = Icons.Default.HelpOutline,
            prefix = "/help",
            tag = "CLI",
            examplePrompt = "/help",
            badgeColor = 0xFF455A64
        ),

        // === Autonomous Agent Skills ===
        PluginItem(
            name = "boost",
            title = "Deep Thinking",
            description = "Multi-perspective deep reasoning, architecture review, and rigorous verification.",
            icon = Icons.Default.AutoAwesome,
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

    fun filterCommands(query: String): List<PluginItem> {
        val cleanQuery = query.removePrefix("/").trim().lowercase()
        if (cleanQuery.isEmpty()) return plugins
        return plugins.filter {
            it.name.lowercase().contains(cleanQuery) ||
            it.title.lowercase().contains(cleanQuery) ||
            it.tag.lowercase().contains(cleanQuery) ||
            it.description.lowercase().contains(cleanQuery)
        }
    }
}
