package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LlmrpaContent(component: LlmrpaComponent) {
    val instruction by component.currentInstruction.collectAsState()
    val availableTabs by component.availableTabs.collectAsState()
    val selectedTab by component.selectedTab.collectAsState()
    val isGenerating by component.isGenerating.collectAsState()
    val history by component.executionHistory.collectAsState()
    val showSettings by component.showSettings.collectAsState()
    val errorMessage by component.errorMessage.collectAsState()
    val handoffPath by component.handoffPath.collectAsState()

    BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                item {
                    HeaderSection(
                        onSettingsClick = { component.toggleSettings() }
                    )
                }

                // Settings Section (collapsible)
                if (showSettings) {
                    item {
                        SettingsSection(component)
                    }
                }

                // LLM Configuration Status
                item {
                    LLMConfigStatusCard(component)
                }

                // Browser Tab Selection (like bundled plugin)
                item {
                    TabSelectionSection(
                        availableTabs = availableTabs,
                        selectedTab = selectedTab,
                        onTabSelected = { component.selectTab(it) },
                        enabled = !isGenerating
                    )
                }

                // Instruction Input
                item {
                    InstructionInputSection(
                        instruction = instruction,
                        onInstructionChange = { component.updateInstruction(it) },
                        onGenerate = { component.generateActions() },
                        isGenerating = isGenerating,
                        hasSelectedTab = selectedTab != null,
                        onQuickExample = { component.applyQuickExample(it) }
                    )
                }

                // Error Message
                errorMessage?.let { error ->
                    item {
                        ErrorCard(error) { component.clearError() }
                    }
                }

                // Where the plan went. Without this the panel gave no sign that anything left
                // the plugin: the button says Execute, and the actions land in a directory the
                // user was never told about. Not gated on errorMessage: the flow is cleared at
                // the start of every generation, so it is never stale, and an unrelated error
                // (provider settings, say) must not hide a valid plan.
                handoffPath?.let { path ->
                    item {
                        HandoffCard(path)
                    }
                }

                // Execution History
                if (history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Generated Actions",
                                style = MaterialTheme.typography.h6,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { component.clearHistory() }) {
                                Text("Clear", style = MaterialTheme.typography.caption)
                            }
                        }
                    }

                    items(history.reversed()) { execution ->
                        ExecutionHistoryCard(execution)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(onSettingsClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = "LLM RPA",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "LLM RPA",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Natural language automation powered by AI",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TabSelectionSection(
    availableTabs: List<ActiveTabData>,
    selectedTab: ActiveTabData?,
    onTabSelected: (ActiveTabData) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Browser Tab",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled && availableTabs.isNotEmpty()) {
                            expanded = true
                        }
                        .border(
                            1.dp,
                            if (selectedTab != null)
                                MaterialTheme.colors.primary.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colors.surface
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selectedTab != null)
                                MaterialTheme.colors.primary
                            else
                                MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedTab?.title ?: if (availableTabs.isEmpty())
                                    "No browser tabs available"
                                else
                                    "Select a browser tab...",
                                style = MaterialTheme.typography.body1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selectedTab?.url != null) {
                                Text(
                                    text = selectedTab.url!!,
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = "Dropdown",
                            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded && availableTabs.isNotEmpty(),
                    onDismissRequest = { expanded = false }
                ) {
                    availableTabs.forEach { tab ->
                        DropdownMenuItem(
                            onClick = {
                                onTabSelected(tab)
                                expanded = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        tab.title,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    tab.url?.let { url ->
                                        Text(
                                            url,
                                            style = MaterialTheme.typography.caption,
                                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(component: LlmrpaComponent) {
    // Read on each composition rather than remembered: there is no change signal, so a
    // remembered snapshot would keep showing a provider the user has since changed or removed.
    val model = component.aiModel()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "AI Provider",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (model != null) {
                SettingRow("Provider", model.providerName)
                SettingRow("Model", model.modelId)
                // Max tokens and temperature are not shown any more: they are the user's
                // generation defaults, they live in Settings, AI Providers, and a second
                // copy here could only go stale or disagree.
            } else {
                Text(
                    "No AI provider is configured.",
                    style = MaterialTheme.typography.body2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Keys, endpoints and models are managed centrally in Settings → AI Providers, " +
                    "so every plugin shares one set of credentials. This panel holds none of " +
                    "its own.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )

            if (component.canOpenProviderSettings()) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { component.openProviderSettings() }) {
                    Text("Open AI Providers settings")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LLMConfigStatusCard(component: LlmrpaComponent) {
    // Re-read per composition, not remembered - see SettingsSection.
    val configured = component.aiAvailable()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp,
        backgroundColor = if (configured)
            BossThemeColors.SuccessColor.copy(alpha = 0.05f)
        else
            BossThemeColors.WarningColor.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (configured) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (configured) BossThemeColors.SuccessColor else BossThemeColors.WarningColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    component.aiModel()?.let { "AI Provider: ${it.providerName}" }
                        ?: "No AI provider configured",
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    component.aiModel()?.let { "Model: ${it.modelId}" }
                        ?: "Configure in Settings → AI Providers",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!configured && component.canOpenProviderSettings()) {
                TextButton(onClick = { component.openProviderSettings() }) {
                    Text("Configure", style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}

@Composable
private fun InstructionInputSection(
    instruction: String,
    onInstructionChange: (String) -> Unit,
    onGenerate: () -> Unit,
    isGenerating: Boolean,
    hasSelectedTab: Boolean,
    onQuickExample: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Natural Language Instruction",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "e.g., Click on the search button and type 'artificial intelligence'",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                },
                enabled = !isGenerating,
                minLines = 3,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                enabled = instruction.isNotBlank() && hasSelectedTab && !isGenerating,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary
                )
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating...")
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Execute",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Execute Instruction")
                }
            }

            // Quick action examples
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Quick Examples:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickChip("Fill form") { onQuickExample("Fill out the contact form with test data") }
                QuickChip("Extract data") { onQuickExample("Extract all product prices from this page") }
                QuickChip("Navigate") { onQuickExample("Navigate to the login page and sign in") }
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colors.primary.copy(alpha = 0.1f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.primary
        )
    }
}

/**
 * Where the generated plan was saved, and what to do with it next.
 *
 * The plan is written for the RPA Engine to load, which is a different panel - so without this
 * the panel showed a list of actions and no indication that anything runnable exists, or where.
 */
@Composable
private fun HandoffCard(path: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossThemeColors.SuccessColor.copy(alpha = 0.1f),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = BossThemeColors.SuccessColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Saved for the RPA Engine - open it to load and run this plan",
                    style = MaterialTheme.typography.body2,
                    color = BossThemeColors.SuccessColor
                )
                // Selectable and ellipsised: the entire point of this line is telling the user
                // where the file is, and a long path with maxLines alone hard-clips mid-name.
                SelectionContainer {
                    Text(
                        path,
                        style = MaterialTheme.typography.caption,
                        color = BossThemeColors.SuccessColor.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossThemeColors.ErrorColor.copy(alpha = 0.1f),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.body2,
                color = BossThemeColors.ErrorColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Dismiss", tint = BossThemeColors.ErrorColor)
            }
        }
    }
}

@Composable
private fun ExecutionHistoryCard(execution: LLMExecutionState) {
    val statusColor = when (execution.status) {
        LLMExecutionStatus.GENERATING -> BossThemeColors.WarningColor
        LLMExecutionStatus.READY -> BossThemeColors.SuccessColor
        LLMExecutionStatus.COMPLETED -> BossThemeColors.SuccessColor
        LLMExecutionStatus.ERROR -> BossThemeColors.ErrorColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp,
        backgroundColor = statusColor.copy(alpha = 0.05f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        execution.instruction,
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (execution.status) {
                                LLMExecutionStatus.GENERATING -> Icons.Default.Autorenew
                                LLMExecutionStatus.READY -> Icons.Default.CheckCircle
                                LLMExecutionStatus.COMPLETED -> Icons.Default.CheckCircle
                                LLMExecutionStatus.ERROR -> Icons.Default.Error
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = statusColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            execution.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.caption,
                            color = statusColor
                        )
                    }
                }

                Text(
                    formatTimestamp(execution.timestamp),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }

            // Show error if any
            execution.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = BossThemeColors.ErrorColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.caption,
                        color = BossThemeColors.ErrorColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Show generated actions
            if (execution.generatedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Generated ${execution.generatedActions.size} actions:",
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.primary
                )

                execution.generatedActions.forEachIndexed { index, action ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.surface,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "${index + 1}. ${action.type.uppercase()}: ${action.name}",
                                style = MaterialTheme.typography.caption,
                                fontWeight = FontWeight.Medium
                            )
                            if (action.selector.value != null) {
                                Text(
                                    "Selector: ${action.selector.type} = ${action.selector.value}",
                                    style = MaterialTheme.typography.caption,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            if (!action.value.isNullOrEmpty()) {
                                Text(
                                    "Value: ${action.value}",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // Note about execution
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = BossThemeColors.AccentColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.AccentColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Actions ready for manual execution or integration",
                            style = MaterialTheme.typography.caption,
                            color = BossThemeColors.AccentColor
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60000 -> "just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}
