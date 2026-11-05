package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** Older runs keep their steps and call summaries, not the call text: export needs only steps. */
    fun add(run: RunState) {
        runs.updateAndGet { (listOf(run) + it).take(keep).mapIndexed { i, r -> ModelCall.trimmed(r, i) } }
    }
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
    // No default: a forgotten one would run `browser_use` in a tab the person cannot see.
    /** Brings a tab to the front in the space on screen. */
    private val reveal: suspend (tabId: String) -> Unit,
    // No default: a forgotten one would give headless runs their own locks, apart from the panel's.
    private val locks: TabLocks,
    // No default, for the same reason: a forgotten one would hide headless runs from llmrpa_export.
    private val runs: RunHistory,
    // No default, for the same reason: a forgotten one would leave Jev runs with no log to debug from.
    internal val runLog: RunLogStore,
    private val timeLimitMs: Long = TIME_LIMIT_MS,
    /** Where `browser_use` runs live: the plugin's own, so a caller that stops waiting (an MCP timeout, a cancelled call) never stops the run. */
    internal val runScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
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
        pointerLabel: String = Cu.DEFAULT_POINTER_LABEL,
        show: Boolean = false,
    ): Result<RunState> = try {
        executeUnguarded(instruction, tabId, maxSteps, model, newTab || startUrl != null, startUrl, pointerLabel, show)
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
        pointerLabel: String,
        show: Boolean,
    ): Result<RunState> {
        if (!tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP)) {
            return Result.failure(IllegalStateException("RPA Engine with rpa_observe and rpa_step is not installed"))
        }
        if (newTab && tabId != null) return Result.failure(IllegalArgumentException("Pass tab_id or new_tab/start_url, not both"))
        val start = startUrl?.let {
            StartPages.usable(it, httpsOnly = false)
                ?: return Result.failure(IllegalArgumentException("start_url must be an http(s) address with a host and no credentials"))
        }
        val tab = if (newTab) null else pickTab(tabId).getOrElse { return Result.failure(it) }
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
        val opener = if (tab == null) NewTab(if (show) ({ u, t -> openTab(u, t)?.also { id -> runCatching { reveal(id) } } }) else openTab, claim = { locks.tryAcquire(it, TabLocks.Owner.HEADLESS) }, startUrl = start) else null
        // Built before the lock, so a constructor that throws cannot leave the tab held.
        val runner = TaskRunner(tools, decider, tab?.tabId, instruction, limits.copy(maxSteps = maxSteps), newTab = opener, pointerLabel = Cu.pointerLabel(pointerLabel)) { Answer.Stop }
        tab?.let { t -> locks.tryAcquire(t.tabId, TabLocks.Owner.HEADLESS)?.let { return Result.failure(IllegalStateException(it)) } }
        if (show) tab?.let { t -> runCatching { reveal(t.tabId) } }
        return try {
            // Bounded, so a caller that times out does not leave a run going on the user's tab.
            Result.success(withTimeoutOrNull(timeLimitMs) { runner.run() } ?: runner.also { it.timedOut(timeLimitMs) }.state.value)
        } finally {
            runner.state.value.tabId?.let(locks::release)
            runs.add(runner.state.value)
        }
    }

    /**
     * `browser_use`: plan, open, Jev vision loop, report ([ComputerUseFlow]) on a tab of the caller's
     * choosing, never waiting for a person. [model] is the Jev model, [planner] a chat model id (default:
     * the one selected in Settings).
     */
    /** What `browser_use` hands back: the report, or the run still going after the wait. */
    internal sealed interface UseOutcome {
        data class Finished(val report: UseReport) : UseOutcome
        data class Running(val run: UseRun) : UseOutcome
    }

    /** Agent runs that stopped to ask, by the tab they left open: a call on that tab resumes them. */
    private val agentPaused = java.util.concurrent.ConcurrentHashMap<String, UseRun>()

    /** Waits up to `wait_s` for the run; if it is still going it is left running and its id is returned to poll. */
    internal suspend fun browserUse(a: BrowserUseArgs): Result<UseOutcome> = try {
        // Only an explicit tab_id acts on an open tab; with none, the run plans (or takes url) and opens a new tab. The focused tab is never used.
        browserUseUnguarded(a, a.newTab || a.tabId == null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("The run failed: ${e.message ?: e::class.simpleName}", e))
    }

    private suspend fun browserUseUnguarded(a: BrowserUseArgs, newTab: Boolean): Result<UseOutcome> {
        if (!tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP)) {
            return Result.failure(IllegalStateException("RPA Engine with rpa_observe and rpa_step is not installed"))
        }
        if (newTab && a.tabId != null) return Result.failure(IllegalArgumentException("Pass tab_id or new_tab/url, not both"))
        val page = a.url?.let { StartPages.usable(it, httpsOnly = false) ?: return Result.failure(IllegalArgumentException("url must be an http(s) address with a host and no credentials")) }
        val tab = if (newTab) null else pickTab(a.tabId).getOrElse { return Result.failure(it) }
        val chat = if (a.plannerModel == null && a.consultModel == null && a.navigatorModel == null && a.agentModel == null) emptyList()
            else withContext(Dispatchers.IO) { ModelDirectory(tools, llmProvider, gateway).load() }.flatMap { it.models }.filter { it.kind == ModelOption.Kind.CHAT }
        fun resolveChat(id: String, arg: String): Result<ModelOption> =
            chat.firstOrNull { it.key == id }?.let { Result.success(it) } ?: chat.filter { it.modelId == id }.let { same ->
                when {
                    same.size > 1 -> Result.failure(IllegalArgumentException("'$id' is offered by more than one provider. Pass one of: ${same.joinToString { it.key }}"))
                    same.isEmpty() -> Result.failure(IllegalArgumentException("Unknown $arg '$id'. Pass a chat model id configured in Settings → AI Providers."))
                    else -> Result.success(same.single())
                }
            }
        val llm = a.plannerModel?.let { resolveChat(it, "planner_model").getOrElse { e -> return Result.failure(e) } }
        val navigatorModel = a.navigatorModel?.let { resolveChat(it, "navigator_model").getOrElse { e -> return Result.failure(e) } }
        val consultModel = a.consultModel?.let { resolveChat(it, "consult_model").getOrElse { e -> return Result.failure(e) } }
        val lockedTabs = LockedTabs(locks, TabLocks.Owner.HEADLESS, openTab, reveal, list = { tabs().filter { it.url != null }.associate { it.tabId to it.title } })
        if (a.mode == "agent") {
            val agentModel = a.agentModel?.let { resolveChat(it, "agent_model").getOrElse { e -> return Result.failure(e) } }
            // The same tab as a run that stopped to ask: that run resumes, with the reply in its history.
            val paused = tab?.tabId?.let { agentPaused.remove(it) }
            val reply = a.inputs.entries.firstOrNull { it.key.equals("reply", true) || it.key.equals(Cu.CHOICE_INPUT, true) }?.value
                ?: if (a.inputs.keys.any { it.equals("continue", true) }) "continue: I did what was asked in the tab myself"
                else if (paused != null && a.inputs.isNotEmpty()) "I gave: ${a.inputs.keys.joinToString()}" else null
            val loop = AgentLoop(tools, gateway, lockedTabs, openWaitsMs = limits.openWaitsMs, settleMs = limits.navSettleMs, runLog = runLog)
            val request = AgentRequest(
                task = paused?.task ?: a.task, url = page, tabId = tab?.tabId, model = agentModel, planner = llm, jevModel = a.model ?: Cu.VISION_DEFAULT_MODEL,
                pointerLabel = Cu.pointerLabel(a.displayName), inputs = a.inputs.filterKeys { k -> listOf("reply", "continue", Cu.CHOICE_INPUT).none { k.equals(it, true) } },
                resume = paused != null, reply = reply, maxSteps = a.maxSteps, timeoutMs = a.timeoutS * 1000L,
            )
            val run = paused ?: UseRun(a.task)
            val job = runScope.async {
                loop.run(request, run).also { r ->
                    if ((r.status == UseStatus.NEEDS_INPUT || r.status == UseStatus.BLOCKED) && r.tabId != null) agentPaused[r.tabId] = run
                }
            }
            val report = withTimeoutOrNull(a.waitS * 1000L) { job.await() }
            return Result.success(if (report != null) UseOutcome.Finished(report) else UseOutcome.Running(run))
        }
        val flow = ComputerUseFlow(tools, gateway, LockedTabs(locks, TabLocks.Owner.HEADLESS, openTab, reveal, list = { tabs().filter { it.url != null }.associate { it.tabId to it.title } }),
            openWaitsMs = limits.openWaitsMs, settleMs = limits.navSettleMs, runLog = runLog)
        val request = UseRequest(
            task = a.task, url = page, tabId = tab?.tabId, jevModel = a.model ?: Cu.VISION_DEFAULT_MODEL, llm = llm,
            pointerLabel = Cu.pointerLabel(a.displayName), llmTyping = a.llmTyping,
            onStuck = a.onStuck, consultBelow = a.consultBelow, maxConsults = a.maxConsults, consultModel = consultModel, navigatorModel = navigatorModel, pagePlans = a.pagePlan, maxReplans = a.maxReplans, inputs = a.inputs, extract = a.extract,
            maxSteps = a.maxSteps, timeoutMs = a.timeoutS * 1000L, confirm = null,
            // A caller that names the page needs no plan; `plan` forces one either way.
            planWithLlm = a.plan ?: (page == null && tab == null),
        )
        val run = UseRun(a.task)
        // In the plugin's scope, not this call's: stopping to wait does not stop the run.
        val job = runScope.async { flow.runComputerUse(request, run) }
        val report = withTimeoutOrNull(a.waitS * 1000L) { job.await() }
        return Result.success(if (report != null) UseOutcome.Finished(report) else UseOutcome.Running(run))
    }

    /** `browser_use` in `dom` mode: the text-list runner of `llmrpa_execute`, returned in the same JSON shape. */
    /** As [browserUseDom], but only waits `wait_s`: a longer run goes on in the background and its record is read with `browser_use_status`. */
    internal suspend fun browserUseDomWaiting(a: BrowserUseArgs): Result<kotlinx.serialization.json.JsonObject> {
        val id = RunLogStore.newId()
        val job = runScope.async {
            browserUseDom(a.task, a.tabId, a.newTab, a.url, a.model, a.maxSteps.coerceAtMost(50), a.timeoutS * 1000L, a.includeText, a.displayName, runId = id)
        }
        return withTimeoutOrNull(a.waitS * 1000L) { job.await() } ?: Result.success(buildJsonObject {
            put("ok", false); put("status", "running"); put("mode", "dom"); put("run_id", id)
            put("summary", "Still running; poll browser_use_status with run_id")
        })
    }

    suspend fun browserUseDom(
        task: String, tabId: String?, newTab: Boolean, url: String?, model: String?, maxSteps: Int, timeoutMs: Long, includeText: Boolean,
        pointerLabel: String = Cu.DEFAULT_POINTER_LABEL, runId: String = RunLogStore.newId(),
    ): Result<kotlinx.serialization.json.JsonObject> {
        val started = System.currentTimeMillis()
        fun logRecord(status: String, extra: kotlinx.serialization.json.JsonObject? = null) = buildJsonObject {
            put("run_id", runId); put("mode", "dom"); put("task", task); put("status", status)
            put("started_at", java.time.Instant.ofEpochMilli(started).toString()); put("started_at_ms", started)
            put("seconds", (System.currentTimeMillis() - started) / 1000.0)
            extra?.forEach { (k, v) -> put(k, v) }
        }
        runLog.writeRaw(runId, logRecord("running"))
        var timedOut = false
        val state = withTimeoutOrNull(timeoutMs) {
            execute(task, tabId, maxSteps, model, newTab, url, pointerLabel, show = true)
        }?.getOrElse { runLog.writeRaw(runId, logRecord("error", buildJsonObject { put("error", it.message ?: "failed") })); return Result.failure(it) } ?: run { timedOut = true; runs.recent().firstOrNull() }
        if (state == null) return Result.failure(IllegalStateException("The run timed out before it started"))
        val status = when { timedOut -> "budget"; state.status == RunStatus.DONE -> "done"; state.status == RunStatus.FAILED -> "error"; else -> "blocked" }
        val text = state.tabId?.let { runCatching { readPageText(tools, it) }.getOrNull() }
        val obs = state.tabId?.let { runCatching { tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", it) }).json }.getOrNull() }
        val result = buildJsonObject {
            put("run_id", runId)
            put("ok", status == "done")
            put("status", status)
            put("mode", "dom")
            put("summary", state.summary ?: "")
            put("url", (obs?.get("url") as? JsonPrimitive)?.content ?: state.startUrl.orEmpty())
            put("title", (obs?.get("title") as? JsonPrimitive)?.content.orEmpty())
            if (includeText) put("page_text", text.orEmpty())
            put("steps", buildJsonArray {
                state.steps.forEach { s -> add(buildJsonObject {
                    put("op", s.description); put("confidence", s.confidence)
                    put("result", s.outcome.name.lowercase())
                }) }
            })
            put("seconds", (System.currentTimeMillis() - started) / 1000.0)
            put("jev_calls", state.modelCalls.count { it.tool == ToolNames.JEV_DECIDE })
            put("llm_calls", state.modelCalls.count { it.tool == ModelCall.GATEWAY })
            put("tool_calls", state.steps.size)
            if (state.costUsd > 0) put("cost_usd", state.costUsd)
        }
        runLog.writeRaw(runId, logRecord(status, buildJsonObject {
            put("steps_count", state.steps.size)
            put("steps", buildJsonArray { state.steps.forEach { st -> add(buildJsonObject { put("index", st.index); put("description", st.description); put("confidence", st.confidence); put("result", st.outcome.name.lowercase()); st.detail?.let { d -> put("note", d) } }) } })
            put("report", result)
            put("jev_calls", state.modelCalls.count { it.tool == ToolNames.JEV_DECIDE }); put("llm_calls", state.modelCalls.count { it.tool == ModelCall.GATEWAY })
        }))
        return Result.success(result)
    }

    /** The tab to act on: the named one, else the focused one, never an arbitrary one. */
    private suspend fun pickTab(tabId: String?): Result<ActiveTabData> {
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
        return Result.success(found)
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
