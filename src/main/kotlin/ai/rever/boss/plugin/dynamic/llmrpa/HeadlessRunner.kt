package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Tabs with a run on them, shared by the panel and `llmrpa_execute` across the plugin, so two
 * runs never interleave steps on one tab.
 */
class TabLocks {
    enum class Owner(val busy: String) {
        PANEL("The LLM RPA panel is running a task on this tab. If it is waiting for an answer, answer it or press Stop there."),
        HEADLESS("An llmrpa_execute run is already acting on this tab. Wait for it to finish."),
    }

    private val busy = ConcurrentHashMap<String, Owner>()

    /** Takes [tabId] for [owner]; null when taken, else why not. */
    fun tryAcquire(tabId: String, owner: Owner): String? = busy.putIfAbsent(tabId, owner)?.busy
    fun release(tabId: String) { busy.remove(tabId) }
}

/**
 * Runs a task for an MCP caller, with no panel. Nobody is there to answer a question, so the
 * runner stops wherever the panel would have asked: when the model is unsure or the next action
 * looks irreversible. The caller gets the transcript and can decide what to do.
 */
class HeadlessRunner(
    private val tools: ToolInvoker,
    private val gateway: () -> AiGatewayAPI?,
    private val llmProvider: () -> LlmProvider?,
    private val tabs: () -> List<ActiveTabData>,
    /** The tab the user is looking at, or null when the host cannot say. */
    private val activeTabId: () -> String?,
    // No default: a forgotten one would give headless runs their own locks, apart from the panel's.
    private val locks: TabLocks,
    private val timeLimitMs: Long = TIME_LIMIT_MS,
) {
    suspend fun execute(instruction: String, tabId: String?, maxSteps: Int, model: String?): Result<RunState> = try {
        executeUnguarded(instruction, tabId, maxSteps, model)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("The run failed: ${e.message ?: e::class.simpleName}", e))
    }

    private suspend fun executeUnguarded(instruction: String, tabId: String?, maxSteps: Int, model: String?): Result<RunState> {
        if (!tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP)) {
            return Result.failure(IllegalStateException("RPA Engine with rpa_observe and rpa_step is not installed"))
        }
        val browserTabs = tabs().filter { it.url != null }
        if (browserTabs.isEmpty()) return Result.failure(IllegalStateException("No browser tab is open"))
        // Never an arbitrary tab: it acts in the user's logged-in session, so the focused one or a named one.
        val wanted = tabId ?: runCatching { activeTabId() }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("No tab is focused. Pass tab_id, one of: ${describe(browserTabs)}"))
        val tab = browserTabs.firstOrNull { it.tabId == wanted }
            ?: return Result.failure(IllegalArgumentException("No browser tab with id $wanted. Open browser tabs: ${describe(browserTabs)}"))
        // On IO: catalog calls into other plugins can read settings or secrets.
        val models = withContext(Dispatchers.IO) { ModelDirectory(tools, llmProvider, gateway).load() }.flatMap { it.models }
        val option = when (model) {
            // Jev, else the model selected in Settings: never just whichever provider the catalog lists first.
            null -> models.firstOrNull { it.kind == ModelOption.Kind.DECISION } ?: activeChat(models)
            else -> models.firstOrNull { it.key == model } ?: models.filter { it.modelId == model }.let { same ->
                if (same.size > 1) {
                    return Result.failure(IllegalArgumentException("'$model' is offered by more than one provider. Pass one of: ${same.joinToString { it.key }}"))
                }
                same.singleOrNull()
            }
        } ?: return Result.failure(
            IllegalArgumentException(
                if (model == null) "No model is available. Install Jev, pick a model in Settings → AI Providers, or pass model."
                else "Unknown model '$model'. Available: ${models.take(25).joinToString { it.key }}${if (models.size > 25) ", …" else ""}",
            ),
        )
        if (option.kind == ModelOption.Kind.DECISION && !tools.has(ToolNames.JEV_DECIDE)) {
            return Result.failure(IllegalStateException("Jev is not installed or not loaded (no jev_decide tool). Install it from Toolbox, or pass a chat model."))
        }
        val decider = if (option.kind == ModelOption.Kind.DECISION) JevDecider(tools, option) else ChatDecider(gateway, option)
        locks.tryAcquire(tab.tabId, TabLocks.Owner.HEADLESS)?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            val runner = TaskRunner(tools, decider, tab.tabId, instruction, RunLimits(maxSteps = maxSteps)) { Answer.Stop }
            // Bounded, so a caller that times out does not leave a run going on the user's tab.
            Result.success(withTimeoutOrNull(timeLimitMs) { runner.run() } ?: runner.also { it.timedOut(timeLimitMs) }.state.value)
        } finally {
            locks.release(tab.tabId)
        }
    }

    private fun activeChat(models: List<ModelOption>): ModelOption? {
        val active = runCatching { gateway()?.activeModel() }.getOrNull() ?: return null
        return models.firstOrNull { it.kind == ModelOption.Kind.CHAT && it.providerId == active.providerId && it.modelId == active.modelId }
            ?: ModelOption(ModelOption.Kind.CHAT, active.providerId, active.providerName, active.modelId)
    }

    companion object {
        const val TIME_LIMIT_MS = 10 * 60_000L

        private fun describe(tabs: List<ActiveTabData>): String =
            tabs.take(20).joinToString { "${it.tabId} ('${it.title.take(40)}', ${host(it.url)})" } + if (tabs.size > 20) ", …" else ""

        /**
         * The browser tab selected in the focused pane, or null. A panel id is unique only within a
         * workspace, so more than one match means the host cannot tell which, and that is null too.
         */
        fun activeTab(tabs: List<ActiveTabData>, activePanelId: String?, selectedTabId: (workspaceId: String, panelId: String) -> String?): String? {
            val panel = activePanelId ?: return null
            return tabs.filter { it.url != null && it.panelId == panel && selectedTabId(it.workspaceId, it.panelId) == it.tabId }
                .singleOrNull()?.tabId
        }
    }
}
