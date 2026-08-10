package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SettingsProvider
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
import kotlinx.coroutines.withContext

private const val AI_PROVIDERS_SETTINGS_SECTION = "LLM_PROVIDERS"

/**
 * LLM RPA panel component (Dynamic Plugin)
 *
 * Provides AI-powered RPA automation with LLM integration.
 * Uses ActiveTabsProvider to list available browser tabs for targeting.
 *
 * Holds no credentials and speaks no wire format: requests go through the shared AI Gateway
 * plugin, which resolves the active provider itself.
 */
class LlmrpaComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val aiGateway: () -> AiGatewayAPI?,
    /**
     * Explains why AI is unavailable and offers the fix; returns true when AI is usable.
     *
     * A lambda for the same reason [aiGateway] is one: it keeps `AiAvailability`, which
     * needs the whole `PluginContext`, out of this class and its call sites' tests.
     */
    private val promptAiFix: suspend (feature: String) -> Boolean = { false },
    private val settingsProvider: SettingsProvider? = null,
    private val windowId: String? = null
) : PanelComponentWithUI, ComponentContext by ctx {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val apiClient = LlmApiClient(aiGateway)

    /** Whether AI can be used right now. See [aiModel] for why this is never cached. */
    fun aiAvailable(): Boolean = aiModel() != null

    /**
     * The provider and model a request would use, for display. Null when AI is unavailable.
     *
     * Read fresh at every call rather than cached: there is no change signal, so a snapshot
     * keeps naming a provider the user has since changed or removed — and a null can equally
     * mean "the gateway or provider plugin has not finished loading yet", which only a later
     * read can tell apart from "nothing configured". The composables call it per composition
     * for the same reason.
     *
     * Guarded, because this crosses a plugin classloader boundary: a gateway built against a
     * different api revision raises `NoSuchMethodError` rather than returning null, and this
     * is read from composition where that would take the panel down.
     */
    fun aiModel(): AiModelInfo? = runCatching { aiGateway()?.activeModel() }.getOrNull()

    /**
     * Open Settings → AI Providers, where keys and models are configured.
     *
     * The section name is the host's `SettingsSection` enum entry, matched case-insensitively;
     * it is still `LLM_PROVIDERS` even though the section displays as "AI Providers", so
     * existing deep links keep resolving.
     */
    fun openProviderSettings() {
        val provider = settingsProvider ?: return
        val window = windowId ?: return
        runCatching { provider.openSettings(window, AI_PROVIDERS_SETTINGS_SECTION) }
            .onFailure { _errorMessage.value = "Open Settings → AI Providers to configure a key." }
    }

    /** Whether [openProviderSettings] can actually navigate, so the button can be hidden. */
    fun canOpenProviderSettings(): Boolean = settingsProvider != null && windowId != null

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

    /**
     * Where the last generated plan was written for the RPA Engine, or null.
     *
     * Surfaced so the panel can say the plan is ready to run rather than leaving the user to
     * guess whether anything left this plugin.
     */
    private val _handoffPath = MutableStateFlow<String?>(null)
    val handoffPath: StateFlow<String?> = _handoffPath

    // Settings state
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    init {
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
            // No apiClient.dispose(): it no longer owns an HTTP client. The transport
            // belongs to the AI Gateway plugin now, which the host unloads with its own
            // classloader.
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

        // Ask about AI before generating. Without this the panel quietly returned its
        // example response, which reads as a working feature producing a useless answer -
        // the user has no way to tell that from a model that did badly.
        if (!aiAvailable()) {
            scope.launch { if (promptAiFix("Generating RPA actions")) generateActions() }
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
                        if (response.status == "success" && response.configuration.isNotEmpty()) {
                            LLMExecutionStatus.READY
                        } else {
                            LLMExecutionStatus.ERROR
                        },
                        generatedActions = response.configuration,
                        error = if (response.status == "success" && response.configuration.isNotEmpty()) {
                            null
                        } else {
                            response.message
                        },
                        message = response.message
                    )

                    if (response.status == "success" && response.configuration.isNotEmpty()) {
                        // Gated on the status too, not only on there being actions: a failed parse
                        // reports "error" and anything it still carries is not a plan the user
                        // asked for, so it must never reach disk as a runnable configuration.
                        // Hand the plan to the RPA Engine, which loads configurations from disk.
                        // Without this the actions were generated and then had no consumer at
                        // all - the button said Execute and nothing could run what it produced.
                        // On Dispatchers.IO: scope is Main, and this is mkdirs + a file write.
                        val handoff =
                            withContext(Dispatchers.IO) {
                                RpaEngineHandoff.writeResult(instruction, response.configuration)
                            }
                        _handoffPath.value = handoff.getOrNull()?.absolutePath
                        _errorMessage.value =
                            handoff.exceptionOrNull()?.let { cause ->
                                // Name the reason. "Could not save them" with the cause only in
                                // the log left the user nothing to act on.
                                "Generated the actions but could not save them for the RPA " +
                                    "Engine: ${cause.message ?: cause::class.simpleName}"
                            }
                        // Only clear the field on success - otherwise the text they would retry
                        // with is gone.
                        if (handoff.isSuccess) {
                            _currentInstruction.value = ""
                        }
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
