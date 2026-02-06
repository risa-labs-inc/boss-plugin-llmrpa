package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
                        SettingsSection()
                    }
                }

                // LLM Configuration Status
                item {
                    LLMConfigStatusCard()
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
private fun SettingsSection() {
    var selectedProvider by remember { mutableStateOf(LLMSettings.selectedProvider) }
    var apiKey by remember { mutableStateOf(LLMSettings.getApiKey(selectedProvider) ?: "") }
    var selectedModel by remember { mutableStateOf(LLMSettings.selectedModelId) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "LLM Configuration",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Provider Selection
            Text(
                "Provider",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LLMProvider.entries.forEach { provider ->
                    ProviderChip(
                        text = provider.displayName,
                        selected = selectedProvider == provider,
                        onClick = {
                            selectedProvider = provider
                            LLMSettings.setProvider(provider)
                            apiKey = LLMSettings.getApiKey(provider) ?: ""
                            selectedModel = LLMSettings.selectedModelId
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // API Key Input
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    LLMSettings.setApiKey(selectedProvider, it)
                },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )

            // Model Selection (if not custom)
            if (selectedProvider != LLMProvider.CUSTOM) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Model",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )

                val models = LLMModels.getModelsForProvider(selectedProvider)
                var expanded by remember { mutableStateOf(false) }

                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            LLMModels.findModelById(selectedModel)?.name ?: selectedModel,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, "Select model")
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(onClick = {
                                selectedModel = model.id
                                LLMSettings.setModel(model.id)
                                expanded = false
                            }) {
                                Text(model.name)
                            }
                        }
                    }
                }
            }

            // Custom endpoint (if custom provider)
            if (selectedProvider == LLMProvider.CUSTOM) {
                Spacer(modifier = Modifier.height(8.dp))
                var endpoint by remember { mutableStateOf(LLMSettings.getCustomEndpoint()) }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        LLMSettings.setCustomEndpoint(it)
                    },
                    label = { Text("Custom Endpoint URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.2f) else MaterialTheme.colors.surface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
        )
    }
}

@Composable
private fun LLMConfigStatusCard() {
    val hasApiKey = LLMSettings.hasValidApiKey()
    val provider = LLMSettings.selectedProvider
    val modelInfo = LLMModels.findModelById(LLMSettings.selectedModelId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp,
        backgroundColor = if (hasApiKey)
            Color(0xFF4CAF50).copy(alpha = 0.05f)
        else
            Color(0xFFFF9800).copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (hasApiKey) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (hasApiKey) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasApiKey) "LLM Provider: ${provider.displayName}" else "No API key configured",
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium
                )
                if (hasApiKey && modelInfo != null) {
                    Text(
                        "Model: ${modelInfo.name}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                } else if (!hasApiKey) {
                    Text(
                        "Configure in Settings > LLM Providers",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
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

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = Color(0xFFFF5252).copy(alpha = 0.1f),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.body2,
                color = Color(0xFFFF5252),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Dismiss", tint = Color(0xFFFF5252))
            }
        }
    }
}

@Composable
private fun ExecutionHistoryCard(execution: LLMExecutionState) {
    val statusColor = when (execution.status) {
        LLMExecutionStatus.GENERATING -> Color(0xFFFF9800)
        LLMExecutionStatus.READY -> Color(0xFF4CAF50)
        LLMExecutionStatus.COMPLETED -> Color(0xFF4CAF50)
        LLMExecutionStatus.ERROR -> Color(0xFFFF5252)
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
                    color = Color(0xFFFF5252).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.caption,
                        color = Color(0xFFFF5252),
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
                    color = Color(0xFF2196F3).copy(alpha = 0.1f),
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
                            tint = Color(0xFF2196F3)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Actions ready for manual execution or integration",
                            style = MaterialTheme.typography.caption,
                            color = Color(0xFF2196F3)
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
