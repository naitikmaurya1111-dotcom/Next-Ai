package com.agychat.app.ui.personalization

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.data.local.MemoryEntity
import com.agychat.app.domain.model.MemoryCategory
import com.agychat.app.domain.model.Personalization
import com.agychat.app.ui.chat.ChatViewModel

// ─── Top-level Route Composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val personalization by viewModel.personalization.collectAsState()
    val memories by viewModel.memories.collectAsState(initial = emptyList())
    val memoriesCount by viewModel.enabledMemoriesCount.collectAsState(initial = 0)

    var currentTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🧠 Memory", "🎨 Personalize")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Memory & Personalization",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        )
                        if (personalization.isEnabled || personalization.memoryEnabled) {
                            val activeCount = buildList {
                                if (personalization.memoryEnabled) add("$memoriesCount memories")
                                if (personalization.isEnabled) add("persona active")
                            }.joinToString(" · ")
                            Text(
                                activeCount,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = currentTab) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = currentTab == i,
                        onClick = { currentTab = i },
                        text = { Text(title, fontWeight = if (currentTab == i) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            when (currentTab) {
                0 -> MemoryTab(viewModel = viewModel, memories = memories, personalization = personalization)
                1 -> PersonalizeTab(viewModel = viewModel, personalization = personalization)
            }
        }
    }
}

// ─── Memory Tab ─────────────────────────────────────────────────────────────

@Composable
fun MemoryTab(
    viewModel: ChatViewModel,
    memories: List<MemoryEntity>,
    personalization: Personalization
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MemoryCategory.ALL) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }

    val filtered = remember(memories, searchQuery, selectedCategory) {
        memories.filter { m ->
            val matchesSearch = searchQuery.isBlank() || m.content.contains(searchQuery, ignoreCase = true)
            val matchesCat = selectedCategory == MemoryCategory.ALL || m.category.equals(selectedCategory, ignoreCase = true)
            matchesSearch && matchesCat
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Memory Toggle
        item {
            MasterToggleCard(
                title = "Memory",
                subtitle = if (personalization.memoryEnabled)
                    "${memories.size} memories saved · AI uses them across all chats"
                else
                    "Memory is OFF · AI starts fresh every conversation",
                isEnabled = personalization.memoryEnabled,
                icon = Icons.Outlined.Psychology,
                onToggle = { viewModel.savePersonalization(personalization.copy(memoryEnabled = it)) }
            )
        }

        // Auto-Memory Toggle
        item {
            AnimatedVisibility(personalization.memoryEnabled) {
                SettingToggleRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Auto-Learn from Conversations",
                    subtitle = "AI automatically extracts and saves facts, preferences, and project context as you chat",
                    checked = personalization.autoMemoryEnabled,
                    onCheckedChange = { viewModel.savePersonalization(personalization.copy(autoMemoryEnabled = it)) }
                )
            }
        }

        // Search + Add bar
        item {
            AnimatedVisibility(personalization.memoryEnabled && memories.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search memories…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    FilledTonalIconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add memory")
                    }
                }
            }
        }

        // Category Filter Chips
        item {
            AnimatedVisibility(personalization.memoryEnabled && memories.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MemoryCategory.ALL_CATEGORIES) { cat ->
                        val catCount = if (cat == MemoryCategory.ALL) memories.size
                        else memories.count { it.category.equals(cat, ignoreCase = true) }
                        if (catCount > 0 || cat == MemoryCategory.ALL) {
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = {
                                    Text("${MemoryCategory.getIconEmoji(cat)} ${MemoryCategory.getDisplayName(cat).substringAfter(" ")} ($catCount)")
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }
        }

        // Memory Items
        if (personalization.memoryEnabled) {
            if (filtered.isEmpty() && memories.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No memories match your search", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (memories.isEmpty()) {
                item {
                    EmptyMemoryState(onAdd = { showAddDialog = true })
                }
            } else {
                items(filtered, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onToggle = { viewModel.toggleMemory(memory.id, it) },
                        onEdit = { editingMemory = memory },
                        onDelete = { viewModel.deleteMemory(memory.id) },
                        onImportanceChange = { imp ->
                            viewModel.updateMemoryImportance(memory.id, imp)
                        }
                    )
                }
            }
        }

        // Clear All Button
        if (personalization.memoryEnabled && memories.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearAllDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    )
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear All Memories")
                }
            }
        }
    }

    // Dialogs
    if (showAddDialog) {
        AddEditMemoryDialog(
            existing = null,
            onSave = { content, category ->
                viewModel.addMemory(content, category)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editingMemory?.let { mem ->
        AddEditMemoryDialog(
            existing = mem,
            onSave = { content, category ->
                viewModel.editMemory(mem.id, content, category)
                editingMemory = null
            },
            onDismiss = { editingMemory = null }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Memories?") },
            text = { Text("This will permanently delete all ${memories.size} saved memories. The AI will no longer remember anything about you. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAllMemories(); showClearAllDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ─── Personalize Tab ─────────────────────────────────────────────────────────

@Composable
fun PersonalizeTab(
    viewModel: ChatViewModel,
    personalization: Personalization
) {
    var draft by remember(personalization) { mutableStateOf(personalization) }
    var hasChanges by remember { mutableStateOf(false) }

    fun update(new: Personalization) {
        draft = new
        hasChanges = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Personalization Toggle
        item {
            MasterToggleCard(
                title = "Personalization",
                subtitle = if (draft.isEnabled)
                    "AI adapts its behavior, tone, and depth to your profile"
                else
                    "Personalization OFF · AI uses generic defaults",
                isEnabled = draft.isEnabled,
                icon = Icons.Outlined.Tune,
                onToggle = { update(draft.copy(isEnabled = it)) }
            )
        }

        item {
            AnimatedVisibility(
                visible = draft.isEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // ── Section: Who You Are ──────────────────────────────
                    SectionHeader(icon = Icons.Outlined.Person, title = "Who You Are", subtitle = "Helps AI speak at the right level and context")

                    PersonaTextField(
                        label = "Your Name",
                        value = draft.name,
                        hint = "e.g. Naitik",
                        onValueChange = { update(draft.copy(name = it)) }
                    )
                    PersonaTextField(
                        label = "Occupation / Role",
                        value = draft.occupation,
                        hint = "e.g. Android Developer, Student, Researcher",
                        onValueChange = { update(draft.copy(occupation = it)) }
                    )
                    PersonaTextField(
                        label = "Skills & Expertise",
                        value = draft.expertise,
                        hint = "e.g. Kotlin, Compose, Python, Machine Learning",
                        onValueChange = { update(draft.copy(expertise = it)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PersonaTextField(
                            label = "Country",
                            value = draft.country,
                            hint = "India",
                            onValueChange = { update(draft.copy(country = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        PersonaTextField(
                            label = "Age",
                            value = draft.age,
                            hint = "22",
                            onValueChange = { update(draft.copy(age = it)) },
                            modifier = Modifier.weight(0.6f)
                        )
                    }

                    // ── Section: How AI Should Respond ──────────────────────
                    SectionHeader(icon = Icons.Outlined.Tune, title = "How AI Should Respond", subtitle = "These shape every single response")

                    ChipSelector(
                        label = "Response Length",
                        options = listOf("Concise", "Balanced", "Detailed", "Adaptive"),
                        selected = draft.responseLength,
                        onSelect = { update(draft.copy(responseLength = it)) }
                    )
                    ChipSelector(
                        label = "Tone Style",
                        options = listOf("Direct", "Formal", "Casual", "Socratic", "Empathetic"),
                        selected = draft.toneStyle,
                        descriptions = mapOf(
                            "Direct" to "No fluff, straight to the point",
                            "Formal" to "Professional, structured language",
                            "Casual" to "Friendly, conversational",
                            "Socratic" to "Asks questions, makes you think",
                            "Empathetic" to "Supportive, patient tone"
                        ),
                        onSelect = { update(draft.copy(toneStyle = it)) }
                    )
                    ChipSelector(
                        label = "Knowledge Depth",
                        options = listOf("Beginner", "Intermediate", "Expert", "Research"),
                        selected = draft.depthLevel,
                        descriptions = mapOf(
                            "Beginner" to "Simple explanations with analogies",
                            "Intermediate" to "Assumes basic knowledge",
                            "Expert" to "Deep technical depth, no hand-holding",
                            "Research" to "Cutting-edge, academic-level detail"
                        ),
                        onSelect = { update(draft.copy(depthLevel = it)) }
                    )
                    ChipSelector(
                        label = "Output Format",
                        options = listOf("Auto", "Always Markdown", "Plain Text"),
                        selected = draft.responseFormat,
                        onSelect = { update(draft.copy(responseFormat = it)) }
                    )
                    PersonaTextField(
                        label = "Primary Code Language",
                        value = draft.codeLanguage,
                        hint = "Kotlin",
                        onValueChange = { update(draft.copy(codeLanguage = it)) }
                    )

                    // ── Section: Behavior Flags ──────────────────────────────
                    SectionHeader(icon = Icons.Outlined.Settings, title = "AI Behavior Flags", subtitle = "Fine-grained control over how AI operates")

                    BehaviorToggle(
                        title = "Include Code Examples",
                        subtitle = "Proactively show code/concept examples in explanations",
                        checked = draft.enableExamples,
                        onCheckedChange = { update(draft.copy(enableExamples = it)) }
                    )
                    BehaviorToggle(
                        title = "Proactive Insights",
                        subtitle = "Volunteer useful related information you didn't ask for — catches blind spots",
                        checked = draft.enableProactiveInsights,
                        onCheckedChange = { update(draft.copy(enableProactiveInsights = it)) }
                    )
                    BehaviorToggle(
                        title = "Critical & Honest Feedback",
                        subtitle = "Give real assessments, flag flaws, push back when wrong — no sugarcoating",
                        checked = draft.enableCriticalFeedback,
                        onCheckedChange = { update(draft.copy(enableCriticalFeedback = it)) }
                    )
                    BehaviorToggle(
                        title = "Use Emoji in Responses",
                        subtitle = "Include emoji for visual structure and tone",
                        checked = draft.enableEmoji,
                        onCheckedChange = { update(draft.copy(enableEmoji = it)) }
                    )

                    // ── Section: Context & Constraints ──────────────────────
                    SectionHeader(icon = Icons.Outlined.Description, title = "Context & Constraints", subtitle = "Background info and topics to avoid")

                    PersonaTextField(
                        label = "Project / Work Context",
                        value = draft.customContext,
                        hint = "e.g. Building an AI Android app called Next AI. Backend is FastAPI + AGY on Colab. App uses Compose, Hilt, Room.",
                        onValueChange = { update(draft.copy(customContext = it)) },
                        maxLines = 5,
                        minLines = 3
                    )
                    PersonaTextField(
                        label = "Topics to Avoid",
                        value = draft.avoidTopics,
                        hint = "e.g. Politics, religion, unrelated small talk",
                        onValueChange = { update(draft.copy(avoidTopics = it)) }
                    )
                    PersonaTextField(
                        label = "Extra Instructions",
                        value = draft.extraInstructions,
                        hint = "e.g. Always end coding answers with a test case. Prefer performance over readability. Always explain trade-offs.",
                        onValueChange = { update(draft.copy(extraInstructions = it)) },
                        maxLines = 4,
                        minLines = 2
                    )

                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Save Button
        item {
            Button(
                onClick = {
                    viewModel.savePersonalization(draft)
                    hasChanges = false
                },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (hasChanges) "Save Personalization" else "Saved", fontWeight = FontWeight.SemiBold)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ─── Reusable UI Components ──────────────────────────────────────────────────

@Composable
fun MasterToggleCard(
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    icon: ImageVector,
    onToggle: (Boolean) -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(300),
        label = "toggleBg"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(4.dp))
}

@Composable
fun PersonaTextField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), fontSize = 13.sp) },
        singleLine = maxLines == 1,
        maxLines = maxLines,
        minLines = minLines,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    )
}

@Composable
fun ChipSelector(
    label: String,
    options: List<String>,
    selected: String,
    descriptions: Map<String, String> = emptyMap(),
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(option, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        descriptions[selected]?.let { desc ->
            Text(
                "→ $desc",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
fun BehaviorToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(width = 48.dp, height = 28.dp)
        )
    }
}

@Composable
fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable { onCheckedChange(!checked) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun MemoryCard(
    memory: MemoryEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onImportanceChange: (Int) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val catColor = remember(memory.category) {
        try { Color(android.graphics.Color.parseColor(MemoryCategory.getColor(memory.category))) }
        catch (_: Exception) { Color(0xFF90A4AE) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (memory.isEnabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Category badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(catColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${MemoryCategory.getIconEmoji(memory.category)} ${MemoryCategory.getDisplayName(memory.category).substringAfter(" ")}",
                        fontSize = 10.sp,
                        color = catColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Importance stars
                Row {
                    repeat(5) { i ->
                        val filled = i < (memory.importance / 2)
                        Icon(
                            if (filled) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp).clickable { onImportanceChange((i + 1) * 2) },
                            tint = if (filled) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Enabled toggle
                Switch(
                    checked = memory.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.size(width = 40.dp, height = 24.dp)
                )

                // More menu
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                memory.content,
                fontSize = 14.sp,
                color = if (memory.isEnabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            if (memory.accessCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Used ${memory.accessCount} times in context",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun EmptyMemoryState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Text("No memories yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "As you chat, the AI automatically saves your preferences, skills, and context here — just like ChatGPT Memory.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Memory Manually")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMemoryDialog(
    existing: MemoryEntity?,
    onSave: (content: String, category: String) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: MemoryCategory.GENERAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Memory" else "Edit Memory", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Memory content") },
                    placeholder = { Text("e.g. Prefers Kotlin over Java") },
                    maxLines = 5,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Text("Category", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(MemoryCategory.ALL_CATEGORIES.drop(1)) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = {
                                Text(
                                    "${MemoryCategory.getIconEmoji(cat)} ${MemoryCategory.getDisplayName(cat).substringAfter(" ")}",
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (content.isNotBlank()) onSave(content.trim(), category) },
                enabled = content.isNotBlank()
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
