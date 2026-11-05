package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// ---- Activity: every tool call, live ----

enum class ActivityKind(val label: String) { TOOL("Tool"), JEV("Jev"), LLM("LLM") }

/** One recorded call. Arguments are summarised and never carry image data. */
data class ActivityEntry(
    val n: Int,
    val atMs: Long,
    val kind: ActivityKind,
    val name: String,
    val args: String,
    val result: String,
    val durationMs: Long,
    val confidence: Double? = null,
    val costUsd: Double? = null,
    val tokens: Int? = null,
    val failed: Boolean = false,
    /** A screenshot on disk, when the call made one. */
    val image: String? = null,
)

/** The calls of one request, in order, as a flow the panel shows live. Capped so a long run stays small. */
class ActivityLog(private val cap: Int = 600) {
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries

    fun add(e: ActivityEntry) = _entries.update { (it + e.copy(n = it.size + 1)).takeLast(cap) }

    val jevCalls: Int get() = _entries.value.count { it.kind == ActivityKind.JEV }
    val llmCalls: Int get() = _entries.value.count { it.kind == ActivityKind.LLM }
    val toolCalls: Int get() = _entries.value.count { it.kind != ActivityKind.LLM }
    val jevCost: Double get() = _entries.value.filter { it.kind == ActivityKind.JEV }.sumOf { it.costUsd ?: 0.0 }
    val llmTokens: Int get() = _entries.value.filter { it.kind == ActivityKind.LLM }.sumOf { it.tokens ?: 0 }
}

/**
 * A [ToolInvoker] that records each call it forwards. Typed text in [secrets] is masked in what is
 * recorded; image data never is.
 */
internal class RecordingTools(
    private val inner: ToolInvoker,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolInvoker {
    val secrets = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun has(toolName: String) = inner.has(toolName)
    override fun inputSchema(toolName: String) = inner.inputSchema(toolName)

    override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
        val at = clock()
        val started = System.nanoTime()
        val reply = try {
            inner.invoke(toolName, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            record(toolName, arguments, null, at, started, e.message ?: e::class.simpleName)
            throw e
        }
        record(toolName, arguments, reply, at, started, null)
        return reply
    }

    private fun record(tool: String, args: JsonElement, reply: ToolReply?, at: Long, started: Long, thrown: String?) {
        runCatching {
            val ms = (System.nanoTime() - started) / 1_000_000
            val jev = tool == ToolNames.JEV_DECIDE
            var confidence: Double? = null
            var cost: Double? = null
            var image: String? = null
            var result = thrown ?: reply?.let { if (it.isError) it.errorMessage else it.text }.orEmpty()
            if (reply != null && !reply.isError) {
                if (jev) {
                    runCatching { Cu.parseDecision(reply.json ?: error("no json")) }.onSuccess { d ->
                        confidence = d.confidence
                        cost = d.costUsd.takeIf { it > 0 }
                        result = buildString {
                            append(d.op.name).append(" p=").append("%.2f".format(d.confidence))
                            d.box?.takeIf { d.op == VisionOp.CLICK || d.op == VisionOp.TYPE_TEXT }?.let {
                                append(" target ").append(Cu.percent(it.centreX)).append("%,").append(Cu.percent(it.centreY)).append('%')
                            }
                            if (d.op == VisionOp.SCROLL_DOWN || d.op == VisionOp.SCROLL_UP) d.scroll?.let { append(' ').append(it) }
                        }
                    }
                } else if (tool == ToolNames.STEP) {
                    val j = reply.json
                    val shot = j?.let(Cu::parseShot)
                    image = shot?.path
                    val ok = (j?.get("ok") as? JsonPrimitive)?.booleanOrNull
                    result = when {
                        shot != null -> "${shot.width}x${shot.height}px (viewport ${shot.cssWidth}x${shot.cssHeight})"
                        ok == false -> "failed: " + ((j["error"] as? JsonPrimitive)?.contentOrNull ?: "no reason")
                        else -> "ok"
                    }
                }
            }
            log.add(ActivityEntry(
                n = 0, atMs = at, kind = if (jev) ActivityKind.JEV else ActivityKind.TOOL, name = tool,
                args = mask(summarise(tool, args)), result = mask(result.take(220)), durationMs = ms,
                confidence = confidence, costUsd = cost,
                failed = thrown != null || reply?.isError == true || result.startsWith("failed:"), image = image,
            ))
        }
    }

    private fun mask(s: String) = Secrets.mask(s, secrets)

    companion object {
        /** A call's arguments in one short line: image data becomes a size, long strings are cut. */
        internal fun summarise(tool: String, args: JsonElement): String {
            val o = args as? JsonObject ?: return args.toString().take(200)
            if (tool == ToolNames.STEP) {
                val a = o["action"] as? JsonObject
                val type = (a?.get("type") as? JsonPrimitive)?.content.orEmpty()
                val value = (a?.get("value") as? JsonPrimitive)?.content?.take(80)
                return listOfNotNull(type, value, (o["tab_id"] as? JsonPrimitive)?.content?.let { "tab $it" }).joinToString(" ")
            }
            if (tool == ToolNames.JEV_DECIDE) {
                val model = (o["model"] as? JsonPrimitive)?.content.orEmpty()
                val qs = (o["questions"] as? JsonObject)?.keys.orEmpty().joinToString(",")
                val assets = (o["assets"] as? JsonObject)?.values?.joinToString(",") { ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.content?.substringAfterLast('/') ?: "inline" }
                return "$model questions=$qs${assets?.let { " image=$it" }.orEmpty()}"
            }
            return redact(o).toString().take(200)
        }

        private fun redact(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k == "data_base64" && v is JsonPrimitive) JsonPrimitive("<${v.content.length} base64 chars>") else redact(v) })
            is JsonArray -> JsonArray(e.map(::redact))
            is JsonPrimitive -> if (e.isString && e.content.length > 120) JsonPrimitive(e.content.take(120) + "…") else e
            else -> e
        }
    }
}

// ---- The chat model ----

/** The planner and reporter: one chat model reached through the AI Gateway, never acting on a page. */
internal class UseLlm(
    private val gateway: () -> AiGatewayAPI?,
    /** Null = the model selected in Settings. */
    private val option: ModelOption?,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val label: String get() = option?.let { "${it.providerName} ${it.modelId}" }
        ?: runCatching { gateway()?.activeModel()?.let { "${it.providerName} ${it.modelId}" } }.getOrNull() ?: "active model"

    /** Whether the gateway can take a screenshot with the question. */
    fun sees(): Boolean = runCatching { gateway()?.capabilities()?.contains(AiGatewayAPI.CAPABILITY_VISION) == true }.getOrDefault(false)

    suspend fun ask(purpose: String, system: String, user: String, image: ByteArray? = null): Result<String> {
        val at = clock()
        val started = System.nanoTime()
        fun record(result: String, failed: Boolean, tokens: Int? = null) = log.add(ActivityEntry(
            n = 0, atMs = at, kind = ActivityKind.LLM, name = "gateway chat: $purpose", args = "$label, ${user.length} chars${if (image != null) ", screenshot" else ""}",
            result = result.take(220), durationMs = (System.nanoTime() - started) / 1_000_000, tokens = tokens, failed = failed,
        ))
        val api = runCatching { gateway() }.getOrNull()
            ?: return Result.failure<String>(IllegalStateException("The AI Gateway plugin is not available")).also { record("no gateway", true) }
        option?.let { ChatDecider.routingProblem(api, it) }?.let { return Result.failure<String>(IllegalStateException(it)).also { _ -> record(it, true) } }
        val request = AiRequest(
            system = system, messages = listOf(userMessage(user, image)), temperature = 0f, maxTokens = 3_000, timeoutMs = 90_000,
            extras = option?.let { ChatDecider.routingExtras(it) }.orEmpty(),
        )
        return try {
            val r = api.complete(request)
            val reply = r.getOrElse { e -> record(e.message ?: e::class.simpleName.orEmpty(), true); return Result.failure(e) }
            record(reply.text.replace(Regex("\\s+"), " "), false, reply.usage?.totalTokens)
            Result.success(reply.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            record(e.message ?: e::class.simpleName.orEmpty(), true)
            Result.failure(e)
        }
    }
}


/** The user turn, with [image] attached when there is one; a gateway build that cannot take it gets the text alone. */
private fun userMessage(text: String, image: ByteArray?): AiMessage =
    if (image == null) AiMessage.user(text)
    else runCatching { AiMessage(AiMessage.ROLE_USER, text, listOf(ai.rever.boss.plugin.api.AiImage(image, "image/jpeg"))) }.getOrElse { AiMessage.user(text) }

// ---- The request and its live view ----

internal enum class OnStuck { RETURN, CONSULT }

/**
 * One computer-use request. With [tabId] the run acts on that tab where it is (no navigation unless [url] is
 * given); without it, it opens a new one. A [url] replaces the planner's choice of page (the goal is still planned).
 * With [resume] the run continues the one in the [UseRun] it is given: its plan, steps and collected pages.
 */
internal data class UseRequest(
    val task: String,
    val url: String? = null,
    val tabId: String? = null,
    val jevModel: String = Cu.VISION_DEFAULT_MODEL,
    /** The planner, typist and consultant; null = the active chat model. */
    val llm: ModelOption? = null,
    val pointerLabel: String = "Jev",
    /** May the planner chat model write text for a field when no text from the request fits? Never for a private field. */
    val llmTyping: Boolean = true,
    /** What to do when the run is stuck: stop and say so, or ask the chat model what to do. */
    val onStuck: OnStuck = OnStuck.RETURN,
    /** Jev's operation confidence below this counts as stuck. */
    val consultBelow: Double = 0.5,
    val maxConsults: Int = 5,
    /** Named values the caller gives (a phone number, a code); names that sound private are only typed into private fields. */
    val inputs: Map<String, String> = emptyMap(),
    /** One LLM call that answers the task from the collected text. */
    val extract: Boolean = false,
    val resume: Boolean = false,
    /** Direction from the person, such as the reply to a question; Jev sees it as advice. */
    val advice: List<String> = emptyList(),
    val maxSteps: Int = 25,
    val timeoutMs: Long = 180_000,
    /** Asked before an action Jev is unsure of; null never asks (a headless caller must not block on a person). */
    val confirm: (suspend (String, Double) -> Boolean)? = null,
)

/** What a run needs from tabs: opening a claimed one, claiming an open one, giving one back. */
internal interface UseTabs {
    /** Opens [url] in a new tab and claims it; null when none was made. */
    suspend fun open(url: String): String?
    /** Claims [tabId]; null when taken, else why not. */
    fun claim(tabId: String): String?
    fun release(tabId: String)
    /** Brings [tabId] to the front in the space on screen, so the person sees the run. */
    suspend fun reveal(tabId: String) {}
}

internal class UseQuestion(val reason: String, val answer: CompletableDeferred<Boolean> = CompletableDeferred())

enum class UsePhase(val label: String) {
    IDLE("Ready"), PLANNING("Planning"), OPENING("Opening the page"), RUNNING("Jev is working"), REPORTING("Writing the report"), FINISHED("Finished")
}

/** The live state of one request: what the Jev tab shows while the flow runs and after. A resumed run keeps it. */
internal class UseRun(val task: String, val startedAt: Long = System.currentTimeMillis()) {
    val log = ActivityLog()
    val phase = MutableStateFlow(UsePhase.PLANNING)
    val plan = MutableStateFlow<UsePlan?>(null)
    val steps = MutableStateFlow<List<UseStep>>(emptyList())
    val question = MutableStateFlow<UseQuestion?>(null)
    val report = MutableStateFlow<UseReport?>(null)
    val pageUrl = MutableStateFlow<String?>(null)
    val collected = MutableStateFlow<List<CollectedItem>>(emptyList())

    internal val store = CollectedStore()
    internal val visited = mutableListOf<PageVisit>()
    internal val snapshotted = mutableSetOf<String>()
    internal val advice = mutableListOf<String>()
    internal var tabId: String? = null
    internal var priorMs = 0L
    /** Named values the person gave in replies, offered to Jev's text choice on a continued run. */
    internal val inputs = mutableMapOf<String, String>()
}

private data class Outcome(val status: UseStatus, val error: String? = null, val question: String? = null)

/**
 * Plan, open, loop (with stuck handling and collecting), report, as one function with no UI in it:
 * `runComputerUse(request)` returns the [UseReport]. The panel's Jev tab, `browser_use` and any other caller
 * share it; [run] carries the live view and the activity log.
 */
internal class ComputerUseFlow(
    private val rawTools: ToolInvoker,
    private val gateway: () -> AiGatewayAPI?,
    private val tabs: UseTabs,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Waits before each look at a new page; tests pass zeros. */
    private val openWaitsMs: List<Long> = listOf(300, 500, 800, 1_200, 2_000, 2_000, 3_000, 3_000),
    private val settleMs: Long = 900,
    private val stepSettleMs: Long = 250,
    private val hash: (String) -> String = ::fileHash,
) {
    suspend fun runComputerUse(req: UseRequest, run: UseRun = UseRun(req.task, clock())): UseReport {
        val tools = RecordingTools(rawTools, run.log, clock)
        val llm = UseLlm(gateway, req.llm, run.log, clock)
        val state = FlowState(segmentStart = clock())
        // A name that sounds private keeps its value out of every record.
        req.inputs.filterKeys(Cu::secretName).values.filter { it.isNotBlank() }.forEach { tools.secrets.add(it) }
        run.report.value = null
        var outcome = Outcome(UseStatus.ERROR, "The run did not start")
        try {
            outcome = withTimeoutOrNull(req.timeoutMs) { execute(req, run, tools, llm, state) }
                ?: Outcome(UseStatus.BUDGET, "Reached the ${req.timeoutMs / 1000}-second time limit")
        } catch (e: CancellationException) {
            run.phase.value = UsePhase.FINISHED
            state.tab?.let(tabs::release)
            throw e
        } catch (e: Throwable) {
            outcome = Outcome(UseStatus.ERROR, e.message ?: e::class.simpleName)
        }
        try {
            run.question.value?.answer?.complete(false)
            run.question.value = null
            return finish(req, run, tools, llm, state, outcome)
        } finally {
            state.tab?.let(tabs::release)
            run.phase.value = UsePhase.FINISHED
        }
    }

    private class FlowState(val segmentStart: Long) {
        var tab: String? = null
        var lastShot: String? = null
    }

    private suspend fun execute(req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, st: FlowState): Outcome {
        for (t in listOf(ToolNames.OBSERVE, ToolNames.STEP, ToolNames.JEV_DECIDE)) {
            if (!rawTools.has(t)) return Outcome(UseStatus.ERROR, if (t == ToolNames.JEV_DECIDE) "Jev is not installed or not loaded (no jev_decide tool)" else "RPA Engine with $t is not installed")
        }
        Cu.visionProblem(req.jevModel, rawTools.inputSchema(ToolNames.JEV_DECIDE))?.let { return Outcome(UseStatus.ERROR, it) }

        // 1. PLAN (a resumed run keeps its plan)
        val plan = run.plan.value.takeIf { req.resume } ?: run {
            run.phase.value = UsePhase.PLANNING
            val planText = llm.ask("plan", Cu.PLAN_SYSTEM, buildString {
                append("Request: ").append(req.task.take(2_000))
                req.url?.let { append("\nThe page ").append(it).append(" will be opened for Jev; plan only the goal.") }
                if (req.tabId != null && req.url == null) append("\nA page is already open in the browser and Jev will continue on it; \"open\" should be empty.")
                if (req.inputs.isNotEmpty()) append("\nThe caller gave inputs named: ").append(req.inputs.keys.joinToString(", ")).append(" (values are private; do not ask for them).")
            }).getOrElse { return Outcome(UseStatus.ERROR, "Could not plan: ${it.message}") }
            runCatching { Cu.parsePlan(planText, req.task) }.getOrElse { return Outcome(UseStatus.ERROR, "Could not plan: ${it.message}") }
                .also { run.plan.value = it }
        }

        // 2. OPEN
        run.phase.value = UsePhase.OPENING
        val target = (req.url ?: plan.open.firstNotNullOfOrNull { StartPages.usable(it, httpsOnly = true) })
            ?.let { StartPages.usable(it, httpsOnly = false) }
        if (req.tabId != null) {
            tabs.claim(req.tabId)?.let { return Outcome(UseStatus.ERROR, it) }
            st.tab = req.tabId
            // On a tab the caller named, stay where it is unless a url says otherwise: a continued run must not start over.
            req.url?.let { StartPages.usable(it, httpsOnly = false) }?.let { u ->
                val r = tools.invoke(ToolNames.STEP, Cu.step(req.tabId, "navigate", u))
                if (r.isError) return Outcome(UseStatus.ERROR, noBrowser(r))
            }
        } else {
            if (target == null) return Outcome(UseStatus.ERROR, "The planner chose no page to open, and no tab was given")
            val id = tabs.open(target) ?: return Outcome(UseStatus.ERROR, "Could not open a new tab for $target")
            st.tab = id
        }
        val tab = checkNotNull(st.tab)
        run.tabId = tab
        runCatching { tabs.reveal(tab) }
        awaitPage(tools, tab)?.let { return Outcome(UseStatus.ERROR, it) }

        // 3. LOOP
        run.phase.value = UsePhase.RUNNING
        return loop(req, run, tools, llm, st, tab, plan)
    }

    private fun noBrowser(r: ToolReply) = if (r.errorCode == ToolNames.NO_BROWSER) NO_BROWSER_HINT else r.errorMessage

    /** Null once the page reads; else why not. */
    private suspend fun awaitPage(tools: RecordingTools, tab: String): String? {
        var last = "it did not load"
        for (w in openWaitsMs) {
            delay(w)
            val r = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) })
            if (r.isError) { last = noBrowser(r); continue }
            val url = (r.json?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (url.isBlank() || url == "about:blank") { last = "it is still blank"; continue }
            delay(settleMs)
            return null
        }
        return "The page did not open: $last"
    }

    private suspend fun observe(tools: RecordingTools, tab: String): Pair<String, String> {
        val r = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) })
        val j = r.json
        return ((j?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()) to ((j?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    /** A page's address without its fragment: one snapshot per page, not per anchor. */
    private fun pageKey(url: String) = url.substringBefore('#')

    private suspend fun loop(req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, st: FlowState, tab: String, plan: UsePlan): Outcome {
        val steps = run.steps.value.toMutableList()
        fun publish() { run.steps.value = steps.toList() }
        // Stuck signals look only at steps since the last consult or reply.
        var mark = steps.size
        var consults = 0
        var prevHash: String? = null
        var failures = 0
        run.advice += req.advice
        while (true) {
            val shotReply = tools.invoke(ToolNames.STEP, Cu.step(tab, "screenshot"))
            if (shotReply.isError) return Outcome(UseStatus.ERROR, noBrowser(shotReply))
            val shot = shotReply.json?.let(Cu::parseShot)
                ?: return Outcome(UseStatus.ERROR, "The screenshot failed: ${shotReply.json?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "RPA Engine returned no image (does it support the screenshot action?)"}")
            st.lastShot = shot.path
            val h = runCatching { hash(shot.path) }.getOrDefault(shot.path + steps.size)
            if (prevHash != null && steps.isNotEmpty()) { steps[steps.lastIndex] = steps.last().copy(changed = h != prevHash); publish() }
            prevHash = h
            if (steps.size >= req.maxSteps) return Outcome(UseStatus.BUDGET, "Stopped at ${req.maxSteps} steps")

            val (url, title) = observe(tools, tab)
            run.pageUrl.value = url
            if (run.visited.lastOrNull()?.url != url) run.visited += PageVisit(url, title, steps.size)
            val now = Seen(url, shot.scrollY, h)

            // Stuck before asking Jev again: the page is where it was.
            var stuck: String? = Cu.noProgress(steps.drop(mark), now)
            var d: VisionDecision? = null
            var t0 = System.nanoTime()
            if (stuck == null) {
                t0 = System.nanoTime()
                val reply = tools.invoke(ToolNames.JEV_DECIDE, Cu.decideArgs(req.jevModel, plan.goal, url, title, steps.map(Cu::recentAction), shot.path, run.advice.toList()))
                if (reply.isError) return Outcome(UseStatus.ERROR, "Jev could not decide: ${reply.errorMessage}")
                d = runCatching { Cu.parseDecision(reply.json ?: error("Jev returned no JSON")) }
                    .getOrElse { return Outcome(UseStatus.ERROR, "Jev could not decide: ${it.message}") }
                stuck = when {
                    d.op == VisionOp.BLOCKED -> "Jev found no operation that makes progress"
                    d.confidence < req.consultBelow -> "Jev is only ${TaskRunner.pct(d.confidence)} sure what to do next"
                    else -> null
                }
            }
            if (stuck != null) {
                if (req.onStuck == OnStuck.CONSULT && consults < req.maxConsults) {
                    consults++
                    when (val r = consult(req, run, tools, llm, tab, plan, shot, url, title, steps, stuck)) {
                        is ConsultResult.Stepped -> {
                            steps += r.step.copy(index = steps.size + 1, before = now)
                            publish()
                            mark = steps.size
                            prevHash = null
                            delay(stepSettleMs)
                            continue
                        }
                        is ConsultResult.Stop -> return r.outcome
                    }
                }
                val tail = if (req.onStuck == OnStuck.CONSULT) " (already consulted ${req.maxConsults} times)" else ""
                return Outcome(UseStatus.NEEDS_INPUT, stuck + tail,
                    "Jev is stuck: $stuck. Tell it what to do next, or give it the details it needs (for example a phone number or a code).")
            }
            val dec = checkNotNull(d)
            if (dec.op == VisionOp.DONE) return Outcome(UseStatus.DONE)

            // Unsure: ask the person when one is there; a headless run never waits for anyone.
            val ask = req.confirm
            if (ask != null && dec.confidence < Cu.ASK_BELOW) {
                val q = UseQuestion("Jev is only ${TaskRunner.pct(dec.confidence)} sure it should ${dec.op.name.lowercase().replace('_', ' ')}")
                run.question.value = q
                val go = try { ask(q.reason, dec.confidence) } finally { run.question.value = null }
                if (!go) return Outcome(UseStatus.BLOCKED, "Stopped: ${q.reason}")
            }

            // The page about to be left, saved once per address.
            if (dec.op == VisionOp.CLICK || dec.op == VisionOp.TYPE_TEXT || dec.op == VisionOp.PRESS_ENTER) {
                snapshot(tools, run, tab, url, title, steps.size + 1, shot.path, auto = true)
            }
            val step = act(req, tools, llm, run, tab, plan, dec, shot, url, title, steps)
            steps += step.copy(index = steps.size + 1, ms = (System.nanoTime() - t0) / 1_000_000, before = now)
            publish()
            if (step.note == NO_BROWSER_NOTE) return Outcome(UseStatus.ERROR, NO_BROWSER_HINT)
            if (step.failed) { if (++failures >= 2) return Outcome(UseStatus.ERROR, "Two actions failed in a row: ${step.note}") } else failures = 0
            delay(if (dec.op == VisionOp.WAIT) 500 else stepSettleMs)
        }
    }

    /** Saves the page text of [url] once per address; [auto] marks the runner's own snapshots. */
    private suspend fun snapshot(tools: RecordingTools, run: UseRun, tab: String, url: String, title: String, step: Int, shotPath: String?, auto: Boolean): Boolean {
        val key = pageKey(url)
        if (auto && key in run.snapshotted) return false
        run.snapshotted += key
        val text = runCatching { readPageText(tools, tab) }.getOrNull().orEmpty()
        val added = run.store.add(CollectedItem(step, url, title, text, shotPath, auto))
        run.collected.value = run.store.list
        return added
    }

    // ---- consulting the chat model ----

    private sealed interface ConsultResult {
        data class Stepped(val step: UseStep) : ConsultResult
        data class Stop(val outcome: Outcome) : ConsultResult
    }

    /**
     * Asks the planner chat model what to do about [reason]: with the screenshot when the gateway takes images, and
     * the page's elements as RPA Engine lists them. Its action runs through `rpa_step` with the element's selector.
     */
    private suspend fun consult(
        req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, tab: String, plan: UsePlan, shot: Shot,
        url: String, title: String, steps: List<UseStep>, reason: String,
    ): ConsultResult {
        val t0 = System.nanoTime()
        val page = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) }).json?.let(PageSnapshot::parse)
        val elements = page?.elements.orEmpty().take(MAX_CONSULT_ELEMENTS)
        val image = if (llm.sees()) runCatching { File(shot.path).readBytes() }.getOrNull() else null
        val user = buildString {
            appendLine("Request: ${req.task.take(2_000)}")
            appendLine("Goal given to Jev: ${plan.goal}")
            appendLine("Why Jev is stuck: $reason")
            appendLine("Page: ${ChatDecider.quote(title)} (${ChatDecider.quote(url, 200)})")
            appendLine("Recent steps: ${steps.takeLast(8).joinToString("; ") { it.description }.ifEmpty { "none" }}")
            if (run.advice.isNotEmpty()) appendLine("Advice so far: ${run.advice.takeLast(3).joinToString("; ") { ChatDecider.quote(it, 200) }}")
            if (req.inputs.isNotEmpty()) appendLine("Inputs the person gave (use \"{{name}}\" as a value): " +
                req.inputs.entries.joinToString(", ") { (k, v) -> if (Cu.secretName(k)) "$k (private)" else "$k=${ChatDecider.quote(v, 60)}" })
            appendLine("Interactive elements (id | role | label):")
            elements.forEach { e ->
                appendLine("${e.id} | ${e.role} | ${ChatDecider.quote(e.label.orEmpty(), 80)}${if (e.sensitive) " | private field" else ""}${if (e.options.isNotEmpty()) " | options: ${e.options.take(8).joinToString(", ")}" else ""}")
            }
            if (elements.isEmpty()) appendLine("(none listed)")
        }
        val text = llm.ask("consult", Cu.CONSULT_SYSTEM, user, image).getOrElse {
            return ConsultResult.Stop(Outcome(UseStatus.NEEDS_INPUT, "$reason; consulting ${llm.label} failed: ${it.message}",
                "Jev is stuck: $reason. Tell it what to do next."))
        }
        val reply = runCatching { Cu.parseConsult(text) }.getOrElse {
            return ConsultResult.Stop(Outcome(UseStatus.NEEDS_INPUT, "$reason; ${it.message}", "Jev is stuck: $reason. Tell it what to do next."))
        }
        val who = "Consulted ${llm.label}"
        fun step(note: String, failed: Boolean = false) = ConsultResult.Stepped(UseStep(0, VisionOp.CONSULT, confidence = 1.0, ms = (System.nanoTime() - t0) / 1_000_000, failed = failed, consult = "$who: $note"))
        return when (reply) {
            is Cu.Consult.Hint -> { run.advice += reply.text; step("hint '${reply.text.take(120)}'") }
            is Cu.Consult.NeedInput -> ConsultResult.Stop(Outcome(UseStatus.NEEDS_INPUT, reason, reply.question))
            is Cu.Consult.Done -> ConsultResult.Stop(Outcome(UseStatus.DONE, "Consulted ${llm.label}: ${reply.reason}"))
            is Cu.Consult.GiveUp -> ConsultResult.Stop(Outcome(UseStatus.BLOCKED, "Consulted ${llm.label}: ${reply.reason}"))
            is Cu.Consult.Act -> {
                val (what, failed) = runConsultAction(req, tools, tab, shot, page, reply)
                step(what, failed)
            }
        }
    }

    /** Runs a consulted action through `rpa_step`, with the element's selector and the pointer. Returns what happened. */
    private suspend fun runConsultAction(req: UseRequest, tools: RecordingTools, tab: String, shot: Shot, page: PageSnapshot?, a: Cu.Consult.Act): Pair<String, Boolean> {
        val pointer = req.pointerLabel.takeIf { it.isNotBlank() }
        fun failedReply(r: ToolReply): String? =
            if (r.isError) noBrowser(r) else if ((r.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false) (r.json?.get("error") as? JsonPrimitive)?.contentOrNull ?: "the action failed" else null
        when (a.type) {
            "navigate" -> {
                val u = StartPages.usable(Cu.fillInputs(a.value, req.inputs), httpsOnly = true) ?: return "navigate refused (not an https address)" to true
                return (failedReply(tools.invoke(ToolNames.STEP, Cu.step(tab, "navigate", u, pointer)))?.let { "navigate failed: $it" } ?: "navigate to ${host(u)}") to false
            }
            "scroll" -> {
                val dy = a.value?.trim()?.toIntOrNull() ?: Cu.scrollDelta(if (a.value?.trim().equals("up", true)) VisionOp.SCROLL_UP else VisionOp.SCROLL_DOWN, "medium", shot.cssHeight)
                return (failedReply(tools.invoke(ToolNames.STEP, Cu.step(tab, "scroll_by", dy.toString(), pointer)))?.let { "scroll failed: $it" } ?: "scroll ${if (dy < 0) "up" else "down"}") to false
            }
            "keypress" -> {
                val key = a.value?.trim().orEmpty().ifEmpty { "Enter" }
                return (failedReply(tools.invoke(ToolNames.STEP, Cu.step(tab, "key", key, pointer)))?.let { "key failed: $it" } ?: "press $key") to false
            }
        }
        val el = page?.elements?.firstOrNull { it.id == a.element } ?: return "element '${a.element}' is not on the page" to true
        val label = "'${el.label?.take(40) ?: el.role}'"
        val fromInput = a.value?.let { Regex("""\{\{.*}}""").containsMatchIn(it) } == true
        var value = Cu.fillInputs(a.value, req.inputs)
        if (a.value != null && value == null) return "an input named in the value was not given" to true
        if (a.type == "input" || a.type == "select") {
            // A private field only takes what the person gave; text a model wrote never goes there. Model-written text needs typing allowed.
            if (a.type == "input" && el.sensitive && !fromInput) return "refused: only a value the person gave goes into $label" to true
            if (a.type == "input" && !fromInput && !req.llmTyping) return "refused: typing by the model is off" to true
            if (el.sensitive) value?.let { tools.secrets.add(it) }
        }
        val reply = tools.invoke(ToolNames.STEP, buildJsonObject {
            put("tab_id", tab)
            if (el.sensitive && fromInput) put("allow_sensitive", true)
            putJsonObject("action") {
                put("type", a.type)
                putJsonObject("selector") { put("type", el.selector.type); el.selector.value?.let { put("value", it) } }
                value?.let { put("value", it) }
            }
            pointer?.let { l -> putJsonObject("pointer") { put("label", l) } }
        })
        val shownValue = if (el.sensitive) Secrets.MASK else value?.take(60)
        val what = when (a.type) { "input" -> "type '$shownValue' into $label"; "select" -> "select '$shownValue' in $label"; else -> "click $label" }
        return (failedReply(reply)?.let { "$what failed: $it" } ?: what) to false
    }

    private suspend fun act(
        req: UseRequest, tools: RecordingTools, llm: UseLlm, run: UseRun, tab: String, plan: UsePlan,
        d: VisionDecision, shot: Shot, url: String, title: String, history: List<UseStep>,
    ): UseStep {
        val pointer = req.pointerLabel.takeIf { it.isNotBlank() }
        fun base(note: String? = null, failed: Boolean = false) = UseStep(0, d.op, confidence = d.confidence, ms = 0, note = note, failed = failed)
        suspend fun send(type: String, value: String? = null, withPointer: Boolean = true): ToolReply =
            tools.invoke(ToolNames.STEP, Cu.step(tab, type, value, pointer.takeIf { withPointer }))
        fun failure(r: ToolReply): String? {
            if (r.isError) return noBrowser(r).let { if (r.errorCode == ToolNames.NO_BROWSER) NO_BROWSER_NOTE else it }
            return if ((r.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false) (r.json?.get("error") as? JsonPrimitive)?.contentOrNull ?: "the action failed" else null
        }
        when (d.op) {
            VisionOp.CLICK, VisionOp.TYPE_TEXT -> {
                val (x, y) = Cu.clickPoint(d.box, shot.cssWidth, shot.cssHeight)
                    ?: return base("Jev gave no usable target box", failed = true)
                val where = base().copy(x = x, y = y, xPct = Cu.percent(d.box!!.centreX), yPct = Cu.percent(d.box.centreY))
                val click = send("click_at", "$x,$y")
                failure(click)?.let { return where.copy(note = it, failed = true) }
                if (d.op == VisionOp.CLICK) return where
                // TYPE_TEXT: what did the click land on, and which of the goal's texts goes there?
                val fr = send("focused", withPointer = false)
                failure(fr)?.let { return where.copy(note = it, failed = true) }
                val field = fr.json?.let(Cu::parseFocused)
                if (field == null || !field.editable) return where.copy(note = "the box did not land on a text field; nothing typed")
                val sensitive = Cu.sensitiveField(field)
                val options = Cu.textOptions(plan.goal, plan.typeValues, url, sensitive, req.inputs)
                // A private field never gets text a model wrote.
                val mayAsk = req.llmTyping && !sensitive
                if (options.isEmpty() && !mayAsk) return where.copy(note = "no text from the goal fits '${field.label.take(40)}'")
                val picked = pickText(tools, llm, req, plan, field, options, url, title, history, mayAsk)
                    ?: return where.copy(note = "no text found for '${field.label.take(40)}'")
                if (sensitive) tools.secrets.add(picked.first)
                val typed = send("type_text", picked.first)
                failure(typed)?.let { return where.copy(note = it, failed = true) }
                return where.copy(text = if (sensitive) Secrets.MASK else picked.first, textSource = if (picked.second) WRITTEN_BY_MODEL else null)
            }
            VisionOp.PRESS_ENTER -> return send("key", "Enter").let { r -> failure(r)?.let { base(it, true) } ?: base() }
            VisionOp.SCROLL_DOWN, VisionOp.SCROLL_UP -> {
                val dy = Cu.scrollDelta(d.op, d.scroll, shot.cssHeight)
                return send("scroll_by", dy.toString()).let { r ->
                    failure(r)?.let { base(it, true) } ?: base("amount ${d.scroll ?: "medium"}")
                }
            }
            VisionOp.COLLECT -> {
                val added = snapshot(tools, run, tab, url, title, history.size + 1, shot.path, auto = false)
                return base(if (added) "saved" else "already saved")
            }
            VisionOp.WAIT -> return base()
            else -> return base("not an action", true)
        }
    }

    /**
     * The text for a field and whether the writing model wrote it. The only option when it is given or planned,
     * else Jev's choice (taken at [Cu.ASK_BELOW] or above); the planner chat model writes it when Jev asks for
     * that or when there is nothing to choose from, if [mayAsk].
     */
    private suspend fun pickText(
        tools: RecordingTools, llm: UseLlm, req: UseRequest, plan: UsePlan, field: Focused, options: List<String>,
        url: String, title: String, history: List<UseStep>, mayAsk: Boolean,
    ): Pair<String, Boolean>? {
        val given = (plan.typeValues + req.inputs.values + Candidates.values(plan.goal)).map { it.trim() }
        options.singleOrNull()?.takeIf { it in given }?.let { return it to false }
        if (options.isNotEmpty()) {
            val jev = JevDecider(tools, ModelOption(ModelOption.Kind.DECISION, ModelDirectory.JEV_PROVIDER, "Jev", req.jevModel))
            val ctx = StepContext(plan.goal, PageSnapshot(url, title, emptyList(), false), emptyList(), given, history.map { it.description }, options)
            val choice = jev.chooseText(ctx, field.label.ifBlank { "the field" }.take(60), options, askLlm = mayAsk)?.getOrNull() ?: return null
            if (!(choice.askLlm && mayAsk)) return choice.index?.let(options::getOrNull)?.takeIf { choice.confidence >= Cu.ASK_BELOW }?.let { it to false }
        }
        return writeText(llm, req, plan, field, url, title, history)?.let { it to true }
    }

    /** One planner-model call for a field's text; null when it fails or says nothing. */
    private suspend fun writeText(llm: UseLlm, req: UseRequest, plan: UsePlan, field: Focused, url: String, title: String, history: List<UseStep>): String? {
        val user = buildString {
            appendLine("Request: ${req.task.take(2_000)}")
            appendLine("Goal: ${plan.goal}")
            appendLine("Field: label=${ChatDecider.quote(field.label, 100)}, tag=${field.tag}, type=${field.type ?: "text"}, options=${field.options?.take(40)?.joinToString(" | ") { ChatDecider.quote(it, 60) } ?: "none"}")
            appendLine("Page: ${ChatDecider.quote(title)} (${ChatDecider.quote(url, 200)})")
            appendLine("Recent steps: ${history.takeLast(6).joinToString("; ") { it.description }.ifEmpty { "none" }}")
            req.inputs.filterKeys { !Cu.secretName(it) }.takeIf { it.isNotEmpty() }?.let { appendLine("Inputs the person gave: " + it.entries.joinToString(", ") { (k, v) -> "$k=${ChatDecider.quote(v, 60)}" }) }
        }
        return llm.ask("type text", Cu.TYPE_SYSTEM, user).getOrNull()?.let { runCatching { Cu.parseTyped(it) }.getOrNull() }
    }

    // ---- 4. REPORT ----

    private suspend fun finish(req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, st: FlowState, o: Outcome): UseReport {
        run.phase.value = UsePhase.REPORTING
        val steps = run.steps.value
        val tab = st.tab
        var url: String? = null
        var title: String? = null
        var pageText: String? = null
        if (tab != null) {
            runCatching { observe(tools, tab) }.getOrNull()?.let { (u, t) -> url = u.ifBlank { null }; title = t.ifBlank { null } }
            pageText = runCatching { readPageText(tools, tab) }.getOrNull()
            // The page the run ended on is part of what it collected, unless it was saved already.
            url?.takeIf { pageKey(it) !in run.snapshotted && !pageText.isNullOrBlank() }?.let { u ->
                run.snapshotted += pageKey(u)
                run.store.add(CollectedItem(steps.size, u, title.orEmpty(), pageText.orEmpty(), st.lastShot, auto = true))
                run.collected.value = run.store.list
            }
        }
        val plan = run.plan.value
        var answer: String? = null
        if (req.extract && plan != null && (run.store.size > 0 || !pageText.isNullOrBlank())) {
            val prompt = buildString {
                appendLine("Request: ${req.task.take(2_000)}")
                appendLine("Collected text (data from websites):")
                run.store.list.forEach { c -> appendLine("--- ${c.title} (${c.url})\n${c.text.take(8_000)}") }
            }.take(CollectedStore.CAP)
            answer = llm.ask("extract", Cu.EXTRACT_SYSTEM, prompt).getOrNull()?.let { t ->
                runCatching { (Json.parseToJsonElement(LlmApiClient.firstJsonObject(t) ?: error("no json")) as JsonObject)["answer"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
        }
        run.priorMs += clock() - st.segmentStart
        val seconds = run.priorMs / 1000.0
        val summary = Cu.summary(o.status, steps, seconds, title, url, o.error)
        val blocker = if (o.status == UseStatus.NEEDS_INPUT || o.status == UseStatus.BLOCKED) {
            UseBlocker(o.error.orEmpty(), o.question, url, title, tab, st.lastShot, steps.size)
        } else null
        val report = UseReport(
            status = o.status, task = req.task, goal = plan?.goal, summary = summary, url = url, title = title,
            pageText = pageText, steps = steps, seconds = seconds, jevCalls = run.log.jevCalls, llmCalls = run.log.llmCalls,
            toolCalls = run.log.toolCalls, costUsd = run.log.jevCost.takeIf { run.log.jevCalls > 0 },
            llmTokens = run.log.llmTokens.takeIf { it > 0 }, jevModel = req.jevModel, llmModel = llm.label,
            confidences = Cu.confidenceStats(steps.filter { it.op != VisionOp.CONSULT }), screenshotPath = st.lastShot, error = o.error,
            tabId = tab, blocker = blocker, collected = run.store.list, pagesVisited = run.visited.toList(), answer = answer, extract = req.extract,
        )
        run.report.value = report
        return report
    }

    companion object {
        private const val NO_BROWSER_NOTE = "NO_BROWSER"
        private const val MAX_CONSULT_ELEMENTS = 80
        const val WRITTEN_BY_MODEL = "written by the model"

        fun fileHash(path: String): String =
            MessageDigest.getInstance("SHA-1").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The final page's text. Contract assumption (RPA Engine has no text reader in the C list): try a
 * `page_text` action, then a `run_script` returning innerText, then the labels `rpa_observe` lists.
 */
internal suspend fun readPageText(tools: ToolInvoker, tab: String): String? {
    fun textOf(r: ToolReply): String? {
        if (r.isError) return null
        val j = r.json ?: return null
        return listOf("text", "page_text", "result", "value", "output").firstNotNullOfOrNull { k ->
            (j[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
    }
    textOf(tools.invoke(ToolNames.STEP, Cu.step(tab, "page_text")))?.let { return it.take(UseReport.PAGE_TEXT_MAX) }
    val obs = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) })
    val page = obs.json?.let(PageSnapshot::parse) ?: return null
    return (listOf(page.title) + page.elements.mapNotNull { it.label }).joinToString("\n").take(UseReport.PAGE_TEXT_MAX)
}

/** [UseTabs] over the plugin's shared [TabLocks]: the panel and headless callers never drive one tab twice. */
internal class LockedTabs(
    private val locks: TabLocks,
    private val owner: TabLocks.Owner,
    /** The host's createBrowserTab, in the active space. */
    private val create: suspend (url: String, title: String) -> String?,
    private val show: suspend (tabId: String) -> Unit = {},
) : UseTabs {
    override suspend fun open(url: String): String? {
        val id = create(url, StartPages.TAB_TITLE) ?: return null
        locks.tryAcquire(id, owner)?.let { throw IllegalStateException("Opened $id, but: $it") }
        return id
    }
    override fun claim(tabId: String): String? = locks.tryAcquire(tabId, owner)
    override fun release(tabId: String) = locks.release(tabId)
    override suspend fun reveal(tabId: String) = show(tabId)
}

/**
 * Brings [tabId] to the front of its pane in the space on screen: the host lists a new tab a moment
 * after it creates it, so the lookup retries briefly. Never throws; a tab that cannot be found stays where it is.
 */
internal suspend fun revealTab(provider: ai.rever.boss.plugin.api.ActiveTabsProvider?, tabId: String, waitsMs: List<Long> = listOf(0, 100, 200, 400, 800)) {
    val p = provider ?: return
    for (w in waitsMs) {
        if (w > 0) delay(w)
        val tab = runCatching { p.activeTabs.value.firstOrNull { it.tabId == tabId } }.getOrNull() ?: continue
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { p.selectTab(tabId, tab.panelId) } }
        return
    }
}
