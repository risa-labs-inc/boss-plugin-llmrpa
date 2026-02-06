package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * LLM RPA panel component (Dynamic Plugin)
 *
 * Provides AI-powered RPA automation with LLM integration.
 * Uses ActiveTabsProvider to list available browser tabs for targeting.
 */
class LlmrpaComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val activeTabsProvider: ActiveTabsProvider?
) : PanelComponentWithUI, ComponentContext by ctx {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val apiClient = LlmApiClient()

    // State
    private val _executionHistory = MutableStateFlow<List<LLMExecutionState>>(emptyList())
    val executionHistory: StateFlow<List<LLMExecutionState>> = _executionHistory

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _currentInstruction = MutableStateFlow("")
    val currentInstruction: StateFlow<String> = _currentInstruction

    // Browser tab state (like bundled plugin)
    private val _availableTabs = MutableStateFlow<List<ActiveTabData>>(emptyList())
    val availableTabs: StateFlow<List<ActiveTabData>> = _availableTabs

    private val _selectedTab = MutableStateFlow<ActiveTabData?>(null)
    val selectedTab: StateFlow<ActiveTabData?> = _selectedTab

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // Settings state
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    init {
        // Load settings on init
        LLMSettings.loadSettings()

        // Collect active tabs from provider
        activeTabsProvider?.let { provider ->
            scope.launch {
                provider.activeTabs.collectLatest { tabs ->
                    // Filter to only browser tabs (those with URLs)
                    val browserTabs = tabs.filter { it.url != null }
                    _availableTabs.value = browserTabs

                    // If selected tab is no longer available, clear selection
                    val currentSelected = _selectedTab.value
                    if (currentSelected != null && browserTabs.none { it.tabId == currentSelected.tabId }) {
                        _selectedTab.value = null
                    }

                    // Auto-select first tab if no tab is selected and tabs are available
                    if (_selectedTab.value == null && browserTabs.isNotEmpty()) {
                        _selectedTab.value = browserTabs.first()
                    }
                }
            }
        }

        lifecycle.doOnDestroy {
            scope.cancel()
            apiClient.dispose()
        }
    }

    @Composable
    override fun Content() {
        LlmrpaContent(this)
    }

    fun updateInstruction(instruction: String) {
        _currentInstruction.value = instruction
    }

    /**
     * Select a browser tab for RPA targeting.
     */
    fun selectTab(tab: ActiveTabData) {
        _selectedTab.value = tab
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Generate RPA actions from natural language instruction
     */
    fun generateActions() {
        val instruction = _currentInstruction.value
        if (instruction.isBlank()) {
            _errorMessage.value = "Please enter an instruction"
            return
        }

        _isGenerating.value = true
        _errorMessage.value = null

        val executionState = LLMExecutionState(
            instruction = instruction,
            status = LLMExecutionStatus.GENERATING
        )
        _executionHistory.value = _executionHistory.value + executionState
        val historyIndex = _executionHistory.value.size - 1

        scope.launch {
            try {
                // Use selected tab's URL or fallback
                val sourceUrl = _selectedTab.value?.url ?: "https://example.com"

                val request = LLMRpaRequest(
                    actions = listOf(LLMAction(instruction)),
                    sourceUrl = sourceUrl
                )

                val response = apiClient.callLLMApi(request)

                if (response.status == "success" || response.status == "error") {
                    updateExecutionStatus(
                        historyIndex,
                        if (response.configuration.isNotEmpty()) LLMExecutionStatus.READY else LLMExecutionStatus.ERROR,
                        generatedActions = response.configuration,
                        error = if (response.configuration.isEmpty()) response.message else null,
                        message = response.message
                    )

                    if (response.configuration.isNotEmpty()) {
                        _currentInstruction.value = ""
                    }
                } else {
                    updateExecutionStatus(
                        historyIndex,
                        LLMExecutionStatus.ERROR,
                        error = response.message ?: "Unknown error"
                    )
                }
            } catch (e: Exception) {
                updateExecutionStatus(
                    historyIndex,
                    LLMExecutionStatus.ERROR,
                    error = e.message ?: "Unknown error occurred"
                )
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun updateExecutionStatus(
        index: Int,
        status: LLMExecutionStatus,
        generatedActions: List<RpaActionConfig> = emptyList(),
        error: String? = null,
        message: String? = null
    ) {
        val history = _executionHistory.value.toMutableList()
        if (index < history.size) {
            history[index] = history[index].copy(
                status = status,
                generatedActions = if (generatedActions.isNotEmpty()) generatedActions else history[index].generatedActions,
                error = error ?: history[index].error
            )
            _executionHistory.value = history
        }
    }

    fun clearHistory() {
        _executionHistory.value = emptyList()
    }

    fun applyQuickExample(example: String) {
        _currentInstruction.value = example
    }
}
