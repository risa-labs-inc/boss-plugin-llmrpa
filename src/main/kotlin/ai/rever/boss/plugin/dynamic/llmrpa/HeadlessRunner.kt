package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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

    /** Whether any run holds a tab. */
    fun anyBusy(): Boolean = busy.isNotEmpty()
}

/**
 * Finished runs from the panel and `llmrpa_execute`, newest first, so `llmrpa_export` reaches
 * either. In memory only.
 */
class RunHistory(private val keep: Int = 20) {
    private val runs = AtomicReference<List<RunState>>(emptyList())

    fun add(run: RunState) { runs.updateAndGet { (listOf(run) + it).take(keep) } }
    fun recent(): List<RunState> = runs.get()
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
    /** The tab the user is looking at among these (drivable) tabs, or null when the host cannot say. */
    private val activeTabId: (List<ActiveTabData>) -> String?,
    // No defaults: a forgotten one would look like every tab being drivable, or none being openable.
    /** Whether the host can drive a tab now (only the space on screen resolves). */
    private val drivable: (tabId: String) -> Boolean,
    /** The host's createBrowserTab, in the active space; null when it made no tab. */
    private val openTab: suspend (url: String, title: String) -> String?,
    // No default: a forgotten one would give headless runs their own locks, apart from the panel's.
    private val locks: TabLocks,
    // No default, for the same reason: a forgotten one would hide headless runs from llmrpa_export.
    private val runs: RunHistory,
    private val timeLimitMs: Long = TIME_LIMIT_MS,
    private val limits: RunLimits = RunLimits(),
) {
    /** [newTab] or a [startUrl] opens a page in a new tab first; otherwise the focused or named tab. */
    suspend fun execute(
        instruction: String,
        tabId: String?,
        maxSteps: Int,
        model: String?,
        newTab: Boolean = false,
        startUrl: String? = null,
    ): Result<RunState> = try {
        executeUnguarded(instruction, tabId, maxSteps, model, newTab || startUrl != null, startUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("The run failed: ${e.message ?: e::class.simpleName}", e))
    }

    private suspend fun executeUnguarded(
        instruction: String,
        tabId: String?,
        maxSteps: Int,
        model: String?,
        newTab: Boolean,
        startUrl: String?,
    ): Result<RunState> {
        if (!tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP)) {
            return Result.failure(IllegalStateException("RPA Engine with rpa_observe and rpa_step is not installed"))
        }
        if (newTab && tabId != null) return Result.failure(IllegalArgumentException("Pass tab_id or new_tab/start_url, not both"))
        val start = startUrl?.let {
            StartPages.usable(it, httpsOnly = false)
                ?: return Result.failure(IllegalArgumentException("start_url must be an http(s) address with a host and no credentials"))
        }
        val tab = if (newTab) null else {
            val browserTabs = tabs().filter { it.url != null }
            if (browserTabs.isEmpty()) return Result.failure(IllegalStateException("No browser tab is open. Pass new_tab: true to open one."))
            // Not while a run holds a tab: the host resolves browsers through one static "selected tab",
            // so a probe racing a run's rpa_observe could hand that run the wrong tab. Unknown then
            // reads as drivable, and a NO_BROWSER at the first look still says why. Re-checked
            // before each tab, since a run can start mid-probe; on IO, one host call per tab.
            suspend fun probe(list: List<ActiveTabData>) = withContext(Dispatchers.IO) {
                list.associate { it.tabId to (locks.anyBusy() || runCatching { drivable(it.tabId) }.getOrDefault(true)) }
            }
            // A named tab probes only itself; the listing in an error probes the rest.
            var canDrive = if (tabId != null) probe(browserTabs.filter { it.tabId == tabId }) else probe(browserTabs)
            suspend fun listing(): String {
                if (canDrive.size < browserTabs.size) canDrive = canDrive + probe(browserTabs.filter { it.tabId !in canDrive })
                return describe(browserTabs, canDrive)
            }
            // Never an arbitrary tab: it acts in the user's logged-in session, so the focused one or a named one.
            // Drivable tabs are the space on screen, which also makes a panel id unambiguous.
            val wanted = tabId ?: runCatching { activeTabId(browserTabs.filter { canDrive[it.tabId] == true }) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("No drivable tab is focused. Pass tab_id, one of: ${listing()}; or new_tab: true"))
            val found = browserTabs.firstOrNull { it.tabId == wanted }
                ?: return Result.failure(IllegalArgumentException("No browser tab with id $wanted. Open browser tabs: ${listing()}"))
            if (canDrive[found.tabId] != true) {
                return Result.failure(IllegalStateException("Tab $wanted: $NO_BROWSER_HINT Pass new_tab: true, or one of: ${listing()}"))
            }
            found
        }
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
        val opener = if (tab == null) NewTab(openTab, claim = { locks.tryAcquire(it, TabLocks.Owner.HEADLESS) }, startUrl = start) else null
        // Built before the lock, so a constructor that throws cannot leave the tab held.
        val runner = TaskRunner(tools, decider, tab?.tabId, instruction, limits.copy(maxSteps = maxSteps), newTab = opener) { Answer.Stop }
        tab?.let { t -> locks.tryAcquire(t.tabId, TabLocks.Owner.HEADLESS)?.let { return Result.failure(IllegalStateException(it)) } }
        return try {
            // Bounded, so a caller that times out does not leave a run going on the user's tab.
            Result.success(withTimeoutOrNull(timeLimitMs) { runner.run() } ?: runner.also { it.timedOut(timeLimitMs) }.state.value)
        } finally {
            runner.state.value.tabId?.let(locks::release)
            runs.add(runner.state.value)
        }
    }

    private fun activeChat(models: List<ModelOption>): ModelOption? {
        val active = runCatching { gateway()?.activeModel() }.getOrNull() ?: return null
        return models.firstOrNull { it.kind == ModelOption.Kind.CHAT && it.providerId == active.providerId && it.modelId == active.modelId }
            ?: ModelOption(ModelOption.Kind.CHAT, active.providerId, active.providerName, active.modelId)
    }

    companion object {
        const val TIME_LIMIT_MS = 10 * 60_000L

        /** Drivable tabs first; the rest are marked, since the host cannot drive them from here. */
        private fun describe(tabs: List<ActiveTabData>, drivable: Map<String, Boolean>): String {
            val sorted = tabs.sortedByDescending { drivable[it.tabId] == true }
            return sorted.take(20).joinToString { t ->
                "${t.tabId} ('${t.title.take(40)}', ${host(t.url)}" +
                    (if (drivable[t.tabId] == true) "" else ", not drivable: ${StartPages.awayReason(t, tabs, drivable)}") + ")"
            } + if (tabs.size > 20) ", …" else ""
        }

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
