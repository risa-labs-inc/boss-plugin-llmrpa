package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.LlmProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SettingsProvider
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
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
    private val windowId: String? = null,
    /** Other plugins' MCP tools: RPA Engine acts and Jev decides. */
    internal val tools: ToolInvoker = RegistryToolInvoker { null },
    private val llmProvider: () -> LlmProvider? = { null },
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
            runJob?.cancel()
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
    /**
     * Start a generation, reporting why not when it does not start.
     *
     * Returned Unit and routed all three refusals into `_errorMessage`, so `llmrpa_run` answered
     * "Generating..." regardless and an agent then polled `llmrpa_status` and read the *previous*
     * run's result.
     */
    fun generateActions(): String? {
        // compareAndSet, not a check then a set: llmrpa_run also calls this, and the MCP handler
        // thread is not guaranteed to be the UI thread - two calls could both pass a plain check,
        // lose one history append, resolve the same index and race on _handoffPath.
        val instruction = _currentInstruction.value
        if (instruction.isBlank()) {
            _errorMessage.value = "Please enter an instruction"
            return "Please enter an instruction"
        }

        // Ask about AI before generating. Without this the panel quietly returned its
        // example response, which reads as a working feature producing a useless answer -
        // the user has no way to tell that from a model that did badly.
        if (!aiAvailable()) {
            scope.launch { if (promptAiFix("Generating RPA actions")) generateActions() }
            return "No AI provider is configured"
        }

        // Last, and with compareAndSet rather than a check then a set: llmrpa_run also calls this
        // and the MCP handler thread is not guaranteed to be the UI thread, so two calls could both
        // pass a plain check, lose one history append, resolve the same index and race on
        // _handoffPath. It is taken *after* the refusals above, because those return without
        // starting anything and must not leave the flag latched - and because the aiAvailable path
        // re-enters this function.
        if (!_isGenerating.compareAndSet(expect = false, update = true)) {
            return "A generation is already in progress"
        }

        _errorMessage.value = null
        // The previous plan is not this run's result. Without this, a generation that fails or
        // returns an unparseable reply leaves the card pointing at the *earlier* instruction's
        // file, so the user opens the engine and runs the wrong plan believing it is the new one.
        _handoffPath.value = null

        val executionState = LLMExecutionState(
            instruction = instruction,
            status = LLMExecutionStatus.GENERATING
        )
        // updateAndGet, so the append and the index that addresses it cannot disagree: reading
        // .value, concatenating, assigning back and then taking size - 1 is three separate steps.
        val historyIndex = _executionHistory.updateAndGet { it + executionState }.size - 1

        scope.launch {
            try {
                // Use selected tab's URL or fallback
                // No example.com fallback: Draft steps is disabled without a tab, and a plan
                // written for a made-up page is worse than none.
                val sourceUrl = _selectedTab.value?.url ?: error("Pick a tab first")

                val request = LLMRpaRequest(
                    actions = listOf(LLMAction(instruction)),
                    sourceUrl = sourceUrl
                )

                val response = apiClient.callLLMApi(request, _selectedModel.value)

                // The example response carries its own status so runnablePlan() excludes it; it
                // still has actions to show, so it belongs on this branch rather than the
                // error-only one below. Listed explicitly, so an unrecognised status keeps
                // falling through to the else rather than being treated as showable.
                // Everything with a status we recognise or actions to show goes here; the else
                // is for a response that is neither, which is nothing this plugin produces today.
                if (response.status in SHOWABLE_STATUSES || response.configuration.isNotEmpty()) {
                    val plan = response.runnablePlan()
                    updateExecutionStatus(
                        historyIndex,
                        if (plan != null) LLMExecutionStatus.READY else LLMExecutionStatus.ERROR,
                        generatedActions = response.configuration,
                        error = if (plan == null) response.message else null,
                        message = response.message
                    )

                    if (plan != null) {
                        // Gated on the status too, not only on there being actions: a failed parse
                        // reports "error" and anything it still carries is not a plan the user
                        // asked for, so it must never reach disk as a runnable configuration.
                        // Hand the plan to the RPA Engine, which loads configurations from disk.
                        // Without this the actions were generated and then had no consumer at
                        // all - the button said Execute and nothing could run what it produced.
                        // On Dispatchers.IO: scope is Main, and this is mkdirs + a file write.
                        val handoff =
                            withContext(Dispatchers.IO) {
                                RpaEngineHandoff.writeResult(instruction, plan)
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
        // Started: nothing to report.
        return null
    }

    private fun updateExecutionStatus(
        index: Int,
        status: LLMExecutionStatus,
        generatedActions: List<RpaActionConfig> = emptyList(),
        error: String? = null,
        message: String? = null
    ) {
        // update, not read-modify-write on .value: two interleaved callers could otherwise lose
        // an append and then both write the same index.
        _executionHistory.update { current ->
            if (index >= current.size) {
                current
            } else {
                current.toMutableList().also { history ->
                    history[index] = history[index].copy(
                        status = status,
                        generatedActions =
                            if (generatedActions.isNotEmpty()) {
                                generatedActions
                            } else {
                                history[index].generatedActions
                            },
                        error = error ?: history[index].error,
                        // Was accepted and dropped on the floor, so the model's explanation of
                        // what the plan does never reached the panel or llmrpa_status.
                        message = message ?: history[index].message,
                    )
                }
            }
        }
    }

    fun clearHistory() {
        _executionHistory.value = emptyList()
    }

    // ---- Models ----

    private val modelDirectory = ModelDirectory(tools, llmProvider, aiGateway)

    private val _modelGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val modelGroups: StateFlow<List<ModelGroup>> = _modelGroups

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)
    val selectedModel: StateFlow<ModelOption?> = _selectedModel

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels

    /** Reloads the model list; keeps the current pick when it still exists, else prefers Jev. */
    fun refreshModels() {
        if (!_loadingModels.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val groups = modelDirectory.load()
                // An empty answer is usually "not registered yet" (plugins load in any order), not
                // "nothing exists": keep what we had rather than clearing the user's pick.
                if (groups.isEmpty()) return@launch
                _modelGroups.value = groups
                val all = groups.flatMap { it.models }
                val current = _selectedModel.value
                if (current == null || all.none { it.key == current.key }) {
                    _selectedModel.value = all.firstOrNull { it.kind == ModelOption.Kind.DECISION }
                        ?: all.firstOrNull { active -> aiModel()?.let { it.providerId == active.providerId && it.modelId == active.modelId } == true }
                        ?: all.firstOrNull()
                }
            } finally {
                _loadingModels.value = false
            }
        }
    }

    fun selectModel(option: ModelOption) {
        _selectedModel.value = option
        recheck()
    }

    private val _readiness = MutableStateFlow<Blocker?>(Blocker.MODEL)

    /**
     * What stops Run, as observable state, so the chip, the notice and the Run button agree. A
     * plain call from composition went stale: nothing recomposes when Jev or RPA Engine register
     * after the panel opened, which is the normal order at BOSS startup and after a hot reload.
     */
    val readiness: StateFlow<Blocker?> = _readiness

    private var jevSeen = false

    /** Recomputes readiness, reloading models when the list is empty or Jev appeared or left. */
    fun recheck() {
        val jevNow = tools.has(ToolNames.JEV_DECIDE)
        if (_modelGroups.value.isEmpty() || jevNow != jevSeen) {
            jevSeen = jevNow
            refreshModels()
        }
        _readiness.value = blocker()
    }

    // ---- Running ----

    private val _maxSteps = MutableStateFlow(RunLimits().maxSteps)
    val maxSteps: StateFlow<Int> = _maxSteps
    fun setMaxSteps(n: Int) { _maxSteps.value = n.coerceIn(1, 50) }

    private val _run = MutableStateFlow<RunState?>(null)
    /** The current or most recent run, or null before the first. */
    val run: StateFlow<RunState?> = _run

    private val _pastRuns = MutableStateFlow<List<RunState>>(emptyList())
    /** Finished runs for this panel, newest first. In memory only; never written to disk. */
    val pastRuns: StateFlow<List<RunState>> = _pastRuns

    private var runJob: Job? = null
    private var runner: TaskRunner? = null
    private val asker = TaskRunner.Companion.Asker()

    val isRunning: Boolean get() = runJob?.isActive == true

    // After every property it touches: an init block runs in declaration order. The loop is cheap
    // (a registry read and, only when needed, a model reload) and dies with the panel's scope.
    init {
        refreshModels()
        scope.launch {
            while (true) {
                recheck()
                delay(READINESS_POLL_MS)
            }
        }
        scope.launch { _selectedModel.collect { _readiness.value = blocker() } }
        scope.launch { _selectedTab.collect { _readiness.value = blocker() } }
    }

    /** What stops Run from starting, in words the panel shows; null when ready. */
    fun blocker(): Blocker? = when {
        !tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP) -> Blocker.ENGINE
        _selectedModel.value == null -> Blocker.MODEL
        _selectedModel.value?.kind == ModelOption.Kind.DECISION && !tools.has(ToolNames.JEV_DECIDE) -> Blocker.JEV
        _selectedModel.value?.kind == ModelOption.Kind.CHAT && !aiAvailable() -> Blocker.AI
        _selectedTab.value == null -> Blocker.TAB
        else -> null
    }

    enum class Blocker(val short: String, val detail: String) {
        ENGINE("Update RPA Engine", "Running tasks needs RPA Engine 1.3 or newer, which can read and act on a tab. Update it from Toolbox."),
        JEV("Install Jev", "Jev picks each step. Install it from Toolbox, then add an OpenRouter key in Settings → AI Providers."),
        AI("Add an AI provider", "Add a provider and key in Settings → AI Providers, or pick Jev."),
        MODEL("Pick a model", "Install Jev from Toolbox or add an AI provider in Settings → AI Providers."),
        TAB("Open a page", "Open a web page in a browser tab to run a task on it."),
    }

    /** Starts a live run on the selected tab. Returns why it did not start, or null. */
    fun startRun(): String? {
        val instruction = _currentInstruction.value.trim()
        if (instruction.isEmpty()) return "Describe the task first"
        blocker()?.let { return it.detail }
        if (isRunning) return "A task is already running"
        val tab = _selectedTab.value!!
        val model = _selectedModel.value!!
        val decider = if (model.kind == ModelOption.Kind.DECISION) JevDecider(tools, model) else ChatDecider(aiGateway, model)
        val r = TaskRunner(tools, decider, tab.tabId, instruction, RunLimits(maxSteps = _maxSteps.value)) { asker.ask(it) }
        runner = r
        runJob = scope.launch {
            val mirror = launch { r.state.collect { _run.value = it } }
            try {
                r.run()
            } finally {
                r.markStopped()
                _run.value = r.state.value
                mirror.cancel()
                _pastRuns.update { (listOf(r.state.value) + it).take(10) }
            }
        }
        return null
    }

    /** Answers the question the run is waiting on. */
    fun answer(a: Answer) = asker.answer(a)

    fun stopRun() {
        asker.answer(Answer.Stop)
        runJob?.cancel()
    }

    /** Puts a finished run's instruction back in the box. */
    fun reuse(past: RunState) { _currentInstruction.value = past.instruction }

    fun applyQuickExample(example: String) {
        _currentInstruction.value = example
    }

    private companion object {
        const val READINESS_POLL_MS = 2_000L

        /** Statuses that carry something worth showing in the panel. */
        val SHOWABLE_STATUSES = setOf("success", "error", LlmApiClient.STATUS_EXAMPLE)
    }
}
