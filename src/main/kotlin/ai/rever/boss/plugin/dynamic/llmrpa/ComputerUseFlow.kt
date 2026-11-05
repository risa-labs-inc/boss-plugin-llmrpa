package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import kotlin.math.roundToInt

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
    /** Text to mask in everything recorded; the run keeps the same list for its run log. */
    val secrets: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList(),
) : ToolInvoker {
    /** The browser dialog each tab has pending, as the last `rpa_step` reply on it said (RPA Engine puts `dialog` in every result while one waits). */
    private val dialogs = java.util.concurrent.ConcurrentHashMap<String, Cu.DialogInfo>()
    fun dialogFor(tabId: String): Cu.DialogInfo? = dialogs[tabId]

    /** Dialogs RPA Engine answered by itself (`dialog_auto` in a result), as step notes waiting for the next step. */
    private val autoNotes = java.util.concurrent.ConcurrentLinkedQueue<String>()
    fun drainAutoNotes(): List<String> = generateSequence { autoNotes.poll() }.toList()
    fun addNote(note: String) { autoNotes += note }

    /** A popup window (a pseudo tab id `<tab>#popup-<n>`) that opened or closed, as `popups` in a result said. */
    data class PopupEvent(val id: String, val url: String, val title: String, val open: Boolean)
    private val popupSeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val popupEvents = java.util.concurrent.ConcurrentLinkedQueue<PopupEvent>()
    fun drainPopupEvents(): List<PopupEvent> = generateSequence { popupEvents.poll() }.toList()

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
        if (tool == ToolNames.STEP && reply != null && !reply.isError) {
            val tab = ((args as? JsonObject)?.get("tab_id") as? JsonPrimitive)?.content
            // A look's result nests its fields under `look`; popups and dialog may sit at either level.
            val body = Marks.body(reply.json)
            if (tab != null) Cu.parseDialog(body)?.let { dialogs[tab] = it } ?: dialogs.remove(tab)
            (body?.get("popups") as? JsonArray)?.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                fun f(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val id = f("id") ?: return@forEach
                val open = (o["open"] as? JsonPrimitive)?.booleanOrNull ?: true
                val was = popupSeen.put(id, open)
                // Listed open again and again is one opening; a close is told once, and only for a window seen open.
                if ((open && was != true) || (!open && was == true)) popupEvents += PopupEvent(id, f("url").orEmpty(), f("title").orEmpty(), open)
            }
            (body?.get("dialog_auto") as? JsonArray)?.forEach { e ->
                val o = e as? JsonObject
                fun f(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
                autoNotes += if (o != null) "Dialog '${f("message").orEmpty().replace(Regex("\\s+"), " ").take(60)}' (${f("type") ?: "dialog"}) was answered automatically: ${f("answer") ?: f("action") ?: "handled"}"
                else "A dialog was answered automatically: ${(e as? JsonPrimitive)?.contentOrNull.orEmpty().take(60)}"
            }
        }
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
                    val look = Marks.parseLook(Marks.body(j))
                    val shot = look?.shot ?: j?.let(Cu::parseShot)
                    image = shot?.path
                    val ok = (j?.get("ok") as? JsonPrimitive)?.booleanOrNull
                    result = when {
                        look != null -> "look ${shot!!.width}x${shot.height}px (viewport ${shot.cssWidth}x${shot.cssHeight}), ${look.marks.size} elements, ${look.located.count { it.value.found }}/${look.located.size} phrases found"
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
                val value = (a?.get("value") as? JsonPrimitive)?.content?.take(80)?.takeIf { type != "look" }
                val element = (a?.get("element") as? JsonPrimitive)?.content?.let { "element $it" }
                return listOfNotNull(type, value, element, (o["tab_id"] as? JsonPrimitive)?.content?.let { "tab $it" }).joinToString(" ")
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
    val label: String get() = option?.let { modelLabel(it.providerName, it.modelId) }
        ?: runCatching { gateway()?.activeModel()?.let { modelLabel(it.providerName, it.modelId) } }.getOrNull() ?: "active model"

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


/** "Provider model", without saying the same name twice ("Claude Code CLI" is both the provider and the model). */
internal fun modelLabel(provider: String, model: String): String =
    if (provider.isBlank() || model.contains(provider, ignoreCase = true) || provider.equals(model, ignoreCase = true)) model.ifBlank { provider } else "$provider $model"

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
    val pointerLabel: String = Cu.DEFAULT_POINTER_LABEL,
    /** May the planner chat model write text for a field when no text from the request fits? Never for a private field. */
    val llmTyping: Boolean = true,
    /** What to do when the run is stuck: stop and say so, or ask the chat model what to do. */
    val onStuck: OnStuck = OnStuck.RETURN,
    /** Jev's operation confidence below this counts as stuck. */
    val consultBelow: Double = 0.5,
    val maxConsults: Int = 5,
    /** The model that looks at the screenshot when Jev is stuck; null = the planner if it can see, else the first model that can. No model that can see: no consult. */
    val consultModel: ModelOption? = null,
    /** How many times the planner may be asked for a new place to continue from when the page is a dead end. */
    val maxReplans: Int = 2,
    /** Named values the caller gives (a phone number, a code); names that sound private are only typed into private fields. */
    val inputs: Map<String, String> = emptyMap(),
    /** One LLM call that answers the task from the collected text. */
    val extract: Boolean = false,
    val resume: Boolean = false,
    /** Ask the planner chat model for the goal and page. False uses the task as the goal (a caller that gave the page does not need a plan). */
    val planWithLlm: Boolean = true,
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
    /** The drivable browser tabs in the space on screen, id to title; empty when the host cannot say (no popup following then). */
    suspend fun snapshot(): Map<String, String> = emptyMap()
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
    /** Page states (address and content hash) whose text was saved while passing. */
    internal val contentSeen = mutableSetOf<String>()
    /** How often the tab was brought back to the front. */
    internal var revealed = 0
    /** Milestones reached, and how many replans were spent. */
    internal val doneMilestones = mutableListOf<String>()
    internal var replans = 0
    /** Element labels a planner's hint named that the page has: looked for like the goal's phrases, so locate and snap help find them. */
    internal val extraTargets = mutableListOf<String>()
    val milestonesDone = MutableStateFlow(0)
    internal val advice = mutableListOf<String>()
    internal var tabId: String? = null
    internal var priorMs = 0L
    val id: String = RunLogStore.newId()
    internal val secrets = java.util.concurrent.CopyOnWriteArrayList<String>()
    internal var inputNames: List<String> = emptyList()
    internal var jevModel: String = ""
    internal var llmModel: String = ""
    internal var mode: String = "vision"
    /** The model consulted when stuck, once chosen. */
    internal var consultModel: String? = null
    internal var endedAt: Long? = null
    internal var segmentStart: Long = startedAt
    /** The tabs the run drove, in order. */
    internal val tabsUsed = mutableListOf<TabUse>()
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
    private val hash: (String) -> String = ::fileHash,
    private val runLog: RunLogStore? = null,
    /** The second look for a new tab after an action: a plain link's tab appears within this; a later one is caught before the next look. */
    private val popupRecheckMs: Long = 150,
) {
    suspend fun runComputerUse(request: UseRequest, run: UseRun = UseRun(request.task, clock())): UseReport {
        // "continue" among the inputs says the person did the asked thing in the tab themselves: carry on from the page as it is, with no plan.
        val continuing = request.inputs.keys.any { it.equals("continue", ignoreCase = true) }
        val req = if (!continuing) request else request.copy(
            inputs = request.inputs.filterKeys { !it.equals("continue", ignoreCase = true) }, planWithLlm = false,
            advice = request.advice + "The person did what the page asked in this tab themselves (logged in, entered a code): continue from the page as it is now",
        )
        val tools = RecordingTools(rawTools, run.log, clock, run.secrets)
        val llm = UseLlm(gateway, req.llm, run.log, clock)
        val state = FlowState(segmentStart = clock())
        run.segmentStart = state.segmentStart
        run.endedAt = null
        run.inputNames = (run.inputNames + req.inputs.keys).distinct()
        run.jevModel = req.jevModel
        run.llmModel = llm.label
        // A name that sounds private keeps its value out of every record.
        req.inputs.filterKeys(Cu::secretName).values.filter { it.isNotBlank() }.forEach { tools.secrets.add(it) }
        run.report.value = null
        runLog?.begin(run)
        var outcome = Outcome(UseStatus.ERROR, "The run did not start")
        try {
            outcome = withTimeoutOrNull(req.timeoutMs) { execute(req, run, tools, llm, state) }
                ?: Outcome(UseStatus.BUDGET, "Reached the ${req.timeoutMs / 1000}-second time limit")
        } catch (e: CancellationException) {
            run.phase.value = UsePhase.FINISHED
            run.endedAt = clock()
            withContext(NonCancellable) { endComputerUse(tools, state); runLog?.end(run) }
            throw e
        } catch (e: Throwable) {
            outcome = Outcome(UseStatus.ERROR, e.message ?: e::class.simpleName)
        }
        try {
            run.question.value?.answer?.complete(false)
            run.question.value = null
            return finish(req, run, tools, llm, state, outcome)
        } finally {
            withContext(NonCancellable) { endComputerUse(tools, state) }
            run.phase.value = UsePhase.FINISHED
        }
    }

    /** Tells RPA Engine the run is over on every tab it touched (it puts the host's dialog handlers back), then gives the tabs back. */
    private suspend fun endComputerUse(tools: RecordingTools, st: FlowState) {
        st.touched.filter { !isPopupId(it) }.forEach { id -> runCatching { tools.invoke(ToolNames.STEP, Cu.step(id, "cu_end")) } }
        st.owned.filter { !isPopupId(it) }.forEach(tabs::release)
    }

    private class FlowState(val segmentStart: Long) {
        /** The tab being driven now. */
        var tab: String? = null
        var lastShot: String? = null
        /** Tabs this run opened, was given or followed into: the only ones it may act on, and releases. */
        val owned = LinkedHashSet<String>()
        /** Tabs that saw a computer-use action, each told `cu_end` at the end. */
        val touched = LinkedHashSet<String>()
        /** Tabs to go back to when a popup closes. */
        val stack = ArrayDeque<String>()
        /** The drivable tabs as last seen (id to title). */
        var known: Map<String, String> = emptyMap()
        /** False once `locate_text` proves unsupported, so older engines are not asked every step. */
        var locateWorks = true
        /** False once the engine says it has no `look` verb: the run then uses screenshot, locate_text and page_text, as before. */
        var lookWorks = true
        /** The model consulted when stuck (resolved once), and whether there is none that can see. */
        var consultLlm: UseLlm? = null
        var consultResolved = false
        /** The last screenshot that was taken, for a dialog decision (the engine refuses new ones while a dialog waits). */
        var lastShotObj: Shot? = null
        /** locate_text answers by page state and phrase. */
        val located = HashMap<String, Cu.Located>()
    }

    private suspend fun execute(req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, st: FlowState): Outcome {
        for (t in listOf(ToolNames.OBSERVE, ToolNames.STEP, ToolNames.JEV_DECIDE)) {
            if (!rawTools.has(t)) return Outcome(UseStatus.ERROR, if (t == ToolNames.JEV_DECIDE) "Jev is not installed or not loaded (no jev_decide tool)" else "RPA Engine with $t is not installed")
        }
        Cu.visionProblem(req.jevModel, rawTools.inputSchema(ToolNames.JEV_DECIDE))?.let { return Outcome(UseStatus.ERROR, it) }

        // 1. PLAN (a resumed run keeps its plan)
        val plan = run.plan.value.takeIf { req.resume } ?: run planning@{
            // The caller gave the page: the task is the goal and no model is asked.
            if (!req.planWithLlm) return@planning UsePlan(emptyList(), req.task, emptyList()).also { run.plan.value = it; runLog?.update(run) }
            run.phase.value = UsePhase.PLANNING
            val planText = llm.ask("plan", Cu.PLAN_SYSTEM, buildString {
                append("Request: ").append(req.task.take(2_000))
                req.url?.let { append("\nThe page ").append(it).append(" will be opened for Jev; plan only the goal.") }
                if (req.tabId != null && req.url == null) append("\nA page is already open in the browser and Jev will continue on it; \"open\" should be empty.")
                if (req.inputs.isNotEmpty()) append("\nThe caller gave inputs named: ").append(req.inputs.keys.joinToString(", ")).append(" (values are private; do not ask for them).")
            }).getOrElse { return Outcome(UseStatus.ERROR, "Could not plan: ${it.message}") }
            runCatching { Cu.parsePlan(planText, req.task) }.getOrElse { return Outcome(UseStatus.ERROR, "Could not plan: ${it.message}") }
                .also { run.plan.value = it; runLog?.update(run) }
        }

        // 2. OPEN
        run.phase.value = UsePhase.OPENING
        val target = (req.url ?: plan.open.firstNotNullOfOrNull { StartPages.usable(it, httpsOnly = true) })
            ?.let { StartPages.usable(it, httpsOnly = false) }
        if (req.tabId != null) {
            tabs.claim(req.tabId)?.let { return Outcome(UseStatus.ERROR, it) }
            st.tab = req.tabId
            st.owned += req.tabId
            st.touched += req.tabId
            if (run.tabsUsed.none { it.tabId == req.tabId }) run.tabsUsed += TabUse(req.tabId, "", "given")
            // On a tab the caller named, stay where it is unless a url says otherwise: a continued run must not start over.
            req.url?.let { StartPages.usable(it, httpsOnly = false) }?.let { u ->
                val r = tools.invoke(ToolNames.STEP, Cu.step(req.tabId, "navigate", u))
                if (r.isError) return Outcome(UseStatus.ERROR, noBrowser(r))
            }
        } else {
            if (target == null) return Outcome(UseStatus.ERROR, if (req.planWithLlm) "The planner chose no page to open, and no tab was given" else "No page to open: give url or tab_id, or let the planner choose one")
            val id = tabs.open(target) ?: return Outcome(UseStatus.ERROR, "Could not open a new tab for $target")
            st.tab = id
            st.owned += id
            st.touched += id
            run.tabsUsed += TabUse(id, "", "opened")
        }
        val tab = checkNotNull(st.tab)
        run.tabId = tab
        st.known = runCatching { tabs.snapshot() }.getOrDefault(emptyMap())
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

    private suspend fun loop(req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, st: FlowState, tab0: String, plan0: UsePlan): Outcome {
        var tab = tab0
        var plan = plan0
        val steps = run.steps.value.toMutableList()
        fun publish() { run.steps.value = steps.toList(); runLog?.update(run) }
        // Stuck signals look only at steps since the last consult, reply or change of tab.
        var mark = steps.size
        var consults = 0
        var prevHash: String? = null
        var prevContent: String? = null
        // The page's text as of the last time it was read, to tell what an action changed.
        var lastText: String? = null
        // The page text the latest look carried (asked for only after an action, when the content is likely to have changed).
        var lookText: String? = null
        // A tab may still open a moment after an action: the next pass checks once more.
        var lateCheck = false
        var doneRejected = 0
        var failures = 0
        run.advice += req.advice
        while (true) {
            // The tab being driven closed itself (a popup that finished): back to the one it came from.
            followTabs(tools, run, st, tab, st.known, allowNew = lateCheck, wait = false)?.let { (back, note) ->
                tab = back; st.tab = back; run.tabId = back; prevHash = null
                if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().let { it.copy(popup = listOfNotNull(it.popup, note).joinToString("; ")) }
                mark = steps.size; publish()
            }
            lateCheck = false
            // A popup window that closed since the last call: back to where it came from.
            followPopups(run, st, tools, tab)?.let { (t, note) ->
                tab = t; st.tab = t; run.tabId = t; prevHash = null; prevContent = null
                if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().let { it.copy(popup = listOfNotNull(it.popup, note).joinToString("; ")) }
                mark = steps.size; publish()
            }
            // One `look` per decision: the screenshot, where the goal's names are, the viewport's elements and (after an action) the page text. The engine
            // brings a hidden tab to the front and waits before capturing; popup windows are not tabs and are left out. An engine without `look` is used
            // as before: screenshot, then locate_text and page_text where needed.
            val milestoneNow = plan.milestones.getOrNull(run.doneMilestones.size)
            val lookPhrases = (run.extraTargets + Cu.targetPhrasesFor(milestoneNow, plan.goal, run.pageUrl.value.orEmpty(), plan.entities)).distinct().take(Cu.MAX_TARGETS + 1)
            val wantText = lookText == null || steps.lastOrNull()?.op.let { it == VisionOp.CLICK || it == VisionOp.TYPE_TEXT || it == VisionOp.PRESS_ENTER }
            var viaLook = false
            suspend fun takeShot(): ToolReply {
                if (st.lookWorks) {
                    val r = tools.invoke(ToolNames.STEP, Cu.lookStep(tab, lookPhrases, wantText, ensureVisible = !isPopupId(tab)))
                    if (!Cu.lookUnsupported(r)) { viaLook = true; return r }
                    // An older engine: screenshot, locate_text and page_text from here on (the failed call is in the activity).
                    st.lookWorks = false
                }
                viaLook = false
                return tools.invoke(ToolNames.STEP, Cu.step(tab, "screenshot", ensureVisible = !isPopupId(tab)))
            }
            fun bodyOf(r: ToolReply): JsonObject? = if (viaLook) Marks.body(r.json) else r.json
            var shotReply = takeShot()
            var shotBody = bodyOf(shotReply)
            var flags = Cu.screenFlags(shotBody)
            if (flags.first == true) { run.revealed++; tools.addNote("brought the tab back to the front") }
            // Not visible: the image is stale, so Jev is not asked about it. One more try.
            if (flags.second == false) {
                shotReply = takeShot()
                shotBody = bodyOf(shotReply)
                flags = Cu.screenFlags(shotBody)
                if (flags.first == true) { run.revealed++; tools.addNote("brought the tab back to the front") }
            }
            val hidden = flags.second == false
            // A call on a popup window that fails means it is gone.
            if (isPopupId(tab) && (shotReply.isError || ((shotBody?.get("ok") as? JsonPrimitive)?.booleanOrNull == false && tools.dialogFor(tab) == null))) {
                val back = popBackFromPopup(run, st, tab)
                if (back != null) {
                    if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().let { it.copy(popup = listOfNotNull(it.popup, back.second).joinToString("; ")) }
                    tab = back.first; st.tab = tab; run.tabId = tab; prevHash = null; prevContent = null; mark = steps.size; publish()
                    continue
                }
            }
            if (shotReply.isError) return Outcome(UseStatus.ERROR, noBrowser(shotReply))
            val fresh = if (hidden) null else shotBody?.let(Cu::parseShot)
            val look = if (viaLook && fresh != null) Marks.parseLook(shotBody) else null
            look?.pageText?.let { lookText = it }
            // While a dialog waits, RPA Engine refuses every verb (the screenshot too) and attaches the dialog: the answer comes
            // next, judged on the last screenshot taken.
            val waiting = tools.dialogFor(tab) != null
            val shot = fresh ?: if (waiting || hidden) (st.lastShotObj ?: Shot("", 0, 0, 1120, 780, 1.0)) else
                return Outcome(UseStatus.ERROR, "The screenshot failed: ${shotBody?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "RPA Engine returned no image (does it support the screenshot action?)"}")
            val h: String
            if (fresh != null) {
                st.lastShot = shot.path
                st.lastShotObj = shot
                h = runCatching { hash(shot.path) }.getOrDefault(shot.path + steps.size)
                if (prevHash != null && steps.isNotEmpty()) { steps[steps.lastIndex] = steps.last().let { it.copy(changed = h != prevHash, contentChanged = if (shot.contentHash != null && prevContent != null) shot.contentHash != prevContent else null, scrolledTo = if (it.op == VisionOp.SCROLL_DOWN || it.op == VisionOp.SCROLL_UP) shot.position else null) }; publish() }
                prevHash = h
            } else h = prevHash ?: ""
            if (steps.size >= req.maxSteps) return Outcome(UseStatus.BUDGET, "Stopped at ${req.maxSteps} steps")

            // A browser dialog is open over the page: the next decision is its answer. The page cannot be read while it waits.
            val dlg = tools.dialogFor(tab)
            // The screenshot result carries the address and title (observe fails on popup windows): observe only when it does not.
            val (url, title) = when {
                dlg != null || hidden -> run.pageUrl.value.orEmpty() to ""
                fresh?.url != null -> fresh.url to fresh.title.orEmpty()
                else -> observe(tools, tab)
            }
            // The dialog drawn on the last screenshot: what Jev and a consulted model get to look at.
            val shotView = when {
                hidden -> shot.copy(path = "") // a stale image is shown to no one
                dlg != null && shot.path.isNotEmpty() -> renderDialogShot(run, shot, dlg)
                else -> shot
            }
            if (dlg == null) {
                run.pageUrl.value = url
                if (run.visited.lastOrNull()?.url != url) run.visited += PageVisit(url, title, steps.size)
            }
            val now = Seen(url, shot.scrollY, h, shot.contentHash)

            // The content changed after an action (a scroll that loaded more, a click that opened a panel): save what is on the page, so the
            // collected text covers everything the run passed, even when Jev never picks COLLECT.
            if (dlg == null && !hidden && fresh != null && shot.contentHash != null) {
                if (steps.isNotEmpty() && prevContent != null && shot.contentHash != prevContent) {
                    val text = look?.pageText ?: runCatching { readPageText(tools, tab) }.getOrNull().orEmpty()
                    // What the last step did to the page, in a line Jev can remember it by.
                    Cu.changeSummary(lastText, text)?.let { r -> steps[steps.lastIndex] = steps.last().let { it.copy(result = it.result ?: r) }; publish() }
                    if (text.isNotBlank()) lastText = text
                    if (run.contentSeen.add("${pageKey(url)}|${shot.contentHash}") && run.store.add(CollectedItem(steps.size, url, title, text, shot.path, auto = true))) run.collected.value = run.store.list
                }
                prevContent = shot.contentHash
            }

            // The plan's stop_when: a login, code or payment the page puts up ends the run for the person, before another call is made.
            if (plan.stopWhen != null && dlg == null && !hidden && Cu.wallKind(shot.modal, look?.pageText) != null) {
                userWall(tools, tab, url, title, explicit = false, shotModal = shot.modal)?.let { return it }
            }

            // Stuck before asking Jev again: the page is where it was.
            var stuck: String? = if (hidden) Cu.HIDDEN_REASON else if (dlg != null) null else Cu.noProgress(steps.drop(mark), now)
            var d: VisionDecision? = null
            var dd: DialogDecision? = null
            var targets: List<Cu.Target> = emptyList()
            var t0 = System.nanoTime()
            if (stuck == null) {
                t0 = System.nanoTime()
                if (dlg != null) {
                    dd = dialogDecision(req, tools, plan, dlg, shotView, url, title, steps, run).getOrElse { return Outcome(UseStatus.ERROR, "Jev could not decide: ${it.message}") }
                    // Unsure by its probability, or by the confidence the answer itself reports (a split 0.4 / 0.6 with confidence 0.2 is a guess).
                    if (dd.viaJev && (dd.confidence < req.consultBelow || (dd.choiceConfidence ?: 1.0) < Cu.DIALOG_MIN_CONFIDENCE)) stuck = "Jev is only ${TaskRunner.pct(minOf(dd.confidence, dd.choiceConfidence ?: 1.0))} sure how to answer the ${dlg.type} dialog"
                } else {
                    targets = Cu.pruneTargets(
                        if (look != null) targetsFromLook(look, lookPhrases, steps) else locateTargets(tools, st, tab, lookPhrases, url, shot, h, steps),
                        steps, isPopupId(tab))
                    // The elements Jev may pick by number, drawn on a copy of the screenshot (the one image it sees).
                    val marks = if (look != null) Marks.visible(look.marks) else emptyList()
                    val marked = if (marks.isNotEmpty()) renderMarks(run, shot, marks) else null
                    val reply = tools.invoke(ToolNames.JEV_DECIDE, Cu.decideArgs(req.jevModel, plan.goal, url, title, Cu.recentActions(steps), marked ?: shot.path, run.advice.toList(), shot.position, targets, run.store.info(), req.pointerLabel, plan.milestones, run.doneMilestones.size, marks, plan.stopWhen))
                    if (reply.isError) return Outcome(UseStatus.ERROR, "Jev could not decide: ${reply.errorMessage}")
                    d = runCatching { Marks.reconcile(Cu.parseDecision(reply.json ?: error("Jev returned no JSON")), marks, shot.cssWidth, shot.cssHeight) }
                        .getOrElse { return Outcome(UseStatus.ERROR, "Jev could not decide: ${it.message}") }
                    stuck = when {
                        d.op == VisionOp.BLOCKED -> "Jev found no operation that makes progress"
                        d.confidence < req.consultBelow -> "Jev is only ${TaskRunner.pct(d.confidence)} sure what to do next"
                        else -> null
                    }
                }
            }
            // The current milestone is true on this screen (Jev's own answer to the milestone question, or DONE): credit it and move on to the next, and
            // act on this same decision unless it is DONE (nothing to act on). No look is taken just for the milestone. A milestone is credited only for
            // progress this run made ([Cu.mayCredit]): a fact already true before it (a cart left over from an earlier session) is not.
            if (d != null && d.op != VisionOp.NEEDS_INPUT && ((d.milestoneP ?: 0.0) >= Cu.MILESTONE_NOUL || (d.op == VisionOp.DONE && stuck == null))) {
                val reached = plan.milestones.getOrNull(run.doneMilestones.size)
                // Done is never said over an open login or payment dialog: that is the person's to deal with.
                if (reached == null) return (if (shot.modal != null) userWall(tools, tab, url, title, explicit = false, shotModal = shot.modal) else null) ?: Outcome(UseStatus.DONE)
                val sinceLast = steps.drop(steps.indexOfLast { it.op == VisionOp.MILESTONE } + 1)
                if (Cu.mayCredit(reached, run.doneMilestones.isEmpty(), sinceLast)) {
                    val k = run.doneMilestones.size + 1
                    run.doneMilestones += reached
                    run.milestonesDone.value = run.doneMilestones.size
                    doneRejected = 0
                    val last = k >= plan.milestones.size
                    if (last && plan.stopWhen == null) {
                        // A "stop at the login or payment step" milestone from a planner that wrote it there ends as the person's when the page does ask; else it is done.
                        if (Cu.isStopMilestone(reached) || shot.modal != null) userWall(tools, tab, url, title, explicit = false, shotModal = shot.modal)?.let { return it }
                        return Outcome(UseStatus.DONE)
                    }
                    steps += UseStep(steps.size + 1, VisionOp.MILESTONE, confidence = d.milestoneP ?: d.confidence, ms = (System.nanoTime() - t0) / 1_000_000, before = now, tabId = tab,
                        note = "milestone $k done: $reached")
                    publish()
                    mark = steps.size
                    // With a stop_when the run goes on toward it after the last milestone; the page asking for a login or payment ends it.
                    if (last && shot.modal != null) userWall(tools, tab, url, title, explicit = false, shotModal = shot.modal)?.let { return it }
                    if (d.op == VisionOp.DONE) { prevHash = null; continue }
                } else if (d.op == VisionOp.DONE) {
                    // DONE for a state that was already there, not reached by this run: not accepted. The page is read again after a nudge.
                    if (++doneRejected >= 3) return Outcome(UseStatus.BLOCKED, "Jev said DONE three times for '$reached', but nothing this run did made it true (it may be left over from earlier)")
                    val note = "DONE was not accepted: '$reached' needs an action in this run (it may be left over from earlier)"
                    run.advice += "Do the step for '$reached' yourself: what is on screen was there before this run started"
                    steps += UseStep(steps.size + 1, VisionOp.REPLAN, confidence = 1.0, ms = 0, before = now, tabId = tab, note = note)
                    publish()
                    prevHash = null
                    continue
                }
            }
            // The page asks for something only the person can give (a login, a code, payment): stop for them in either mode, before any consult.
            if (d != null && (d.op == VisionOp.NEEDS_INPUT || d.op == VisionOp.BLOCKED)) {
                userWall(tools, tab, url, title, explicit = d.op == VisionOp.NEEDS_INPUT, shotModal = shot.modal)?.let { return it }
            }
            // A dead end (the page does not have what the next milestone needs): ask the planner for a better place to continue from.
            // Never undo progress: a page that is already the right site is not a dead end, and a menu that loads as it scrolls has not been scrolled yet.
            val rightSite = Cu.pageMatches(url, title, Cu.distinctiveWords(plan.goal, run.doneMilestones))
            // Stuck on the right site: the page is the right one but Jev is not finding its way (the Menu tab shows photos, the Order Online tab has the
            // buttons). The planner, even without vision, reads the page text and elements and says what to do there; that is advice, never a move.
            if (stuck != null && dlg == null && stuck != Cu.HIDDEN_REASON && run.replans < req.maxReplans && rightSite) {
                run.replans++
                val hint = advise(req, run, tools, llm, tab, plan, plan.milestones.getOrNull(run.doneMilestones.size), url, title, steps, stuck)
                if (hint != null) {
                    run.advice += hint
                    steps += UseStep(steps.size + 1, VisionOp.REPLAN, confidence = 1.0, ms = 0, before = now, tabId = tab, note = "advised: ${hint.take(120)}")
                    publish()
                    mark = steps.size
                    prevHash = null
                    continue
                }
            }
            val scrolledHere = steps.any { (it.op == VisionOp.SCROLL_DOWN || it.op == VisionOp.SCROLL_UP) && it.before?.url?.let(::pageKey) == pageKey(url) }
            if (stuck != null && dlg == null && stuck != Cu.HIDDEN_REASON && run.replans < req.maxReplans && !rightSite && scrolledHere) {
                val milestone = plan.milestones.getOrNull(run.doneMilestones.size)
                if (isDeadEnd(tools, st, tab, plan, milestone, url, shot, h, steps, if (look != null) targets else null)) {
                    run.replans++
                    when (val next = replan(req, run, tools, llm, tab, plan, milestone, url, title, steps, stuck)) {
                        is ReplanResult.Moved -> {
                            plan = next.plan
                            steps += UseStep(steps.size + 1, VisionOp.REPLAN, confidence = 1.0, ms = 0, before = now, tabId = tab, note = "replanned: ${next.url}")
                            publish()
                            mark = steps.size
                            prevHash = null
                            prevContent = null
                            lastText = null
                            awaitPage(tools, tab)
                            continue
                        }
                        is ReplanResult.Stayed -> {
                            run.advice += next.hint
                            steps += UseStep(steps.size + 1, VisionOp.REPLAN, confidence = 1.0, ms = 0, before = now, tabId = tab, note = "replan: stay on this page, ${next.hint.take(100)}")
                            publish()
                            mark = steps.size
                            prevHash = null
                            continue
                        }
                        null -> Unit
                    }
                }
            }
            if (stuck != null) {
                var tail = if (req.onStuck == OnStuck.CONSULT && stuck != Cu.HIDDEN_REASON && consults >= req.maxConsults) " (already consulted ${req.maxConsults} times)" else ""
                // A hidden tab has no screen to look at, and a model that cannot see would be guessing: neither is consulted.
                if (req.onStuck == OnStuck.CONSULT && consults < req.maxConsults && stuck != Cu.HIDDEN_REASON) {
                    val consultLlm = consultLlmFor(req, run, st)
                    if (consultLlm == null) {
                        tail = " (no vision-capable model to consult)"
                    } else {
                        consults++
                        when (val r = consult(req, run, tools, consultLlm, tab, plan, shotView, url, title, steps, stuck)) {
                            is ConsultResult.Stepped -> {
                                steps += r.step.copy(index = steps.size + 1, before = now, tabId = tab)
                                publish()
                                mark = steps.size
                                prevHash = null
                                continue
                            }
                            is ConsultResult.Stop -> return r.outcome
                        }
                    }
                }
                return Outcome(UseStatus.NEEDS_INPUT, stuck + tail,
                    "Jev is stuck: $stuck. Tell it what to do next, or give it the details it needs (for example a phone number or a code).")
            }

            if (dlg != null) {
                val step = answerDialog(req, tools, llm, tab, plan, dlg, checkNotNull(dd), url, title, steps)
                steps += step.copy(index = steps.size + 1, ms = (System.nanoTime() - t0) / 1_000_000, before = now, tabId = tab, screenshotPath = shot.path.ifEmpty { null }, note = withAuto(step.note, tools))
                publish()
                if (step.note == NO_BROWSER_NOTE) return Outcome(UseStatus.ERROR, NO_BROWSER_HINT)
                continue
            }

            val dec = checkNotNull(d)
            if (dec.op == VisionOp.DONE) return (if (shot.modal != null) userWall(tools, tab, url, title, explicit = false, shotModal = shot.modal) else null) ?: Outcome(UseStatus.DONE)

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
                snapshot(tools, run, tab, url, title, steps.size + 1, shot.path, auto = true, pageText = look?.pageText)
            }
            if (lastText == null && shot.contentHash != null && (dec.op == VisionOp.CLICK || dec.op == VisionOp.TYPE_TEXT || dec.op == VisionOp.PRESS_ENTER)) {
                lastText = lookText ?: runCatching { readPageText(tools, tab) }.getOrNull()
            }
            val tabBefore = runCatching { tabs.snapshot() }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) st.known = it }
            val actedOn = tab
            val step = act(req, tools, llm, run, tab, plan, dec, shot, url, title, steps, targets)
            // A click or Enter may open a popup, or close the one being driven; look for a moment.
            val change = followTabs(tools, run, st, tab, tabBefore, allowNew = true, wait = dec.op == VisionOp.CLICK || dec.op == VisionOp.PRESS_ENTER)
                ?: followPopups(run, st, tools, tab)
            steps += step.copy(index = steps.size + 1, ms = (System.nanoTime() - t0) / 1_000_000, before = now, tabId = actedOn, screenshotPath = shot.path, box = dec.box, popup = change?.second, note = withAuto(step.note, tools))
            lateCheck = change == null && (dec.op == VisionOp.CLICK || dec.op == VisionOp.PRESS_ENTER || dec.op == VisionOp.TYPE_TEXT)
            if (change != null) { tab = change.first; st.tab = tab; run.tabId = tab; prevHash = null; prevContent = null; lookText = null; mark = steps.size }
            publish()
            if (step.note == NO_BROWSER_NOTE) return Outcome(UseStatus.ERROR, NO_BROWSER_HINT)
            if (step.failed) { if (++failures >= 2) return Outcome(UseStatus.ERROR, "Two actions failed in a row: ${step.note}") } else failures = 0
            // The engine's own quiet wait follows every action; only a deliberate WAIT sleeps here.
            if (dec.op == VisionOp.WAIT) delay(500)
        }
    }

    // ---- popup windows ----

    /**
     * Compares the drivable tabs with [before] after an action. A tab that appeared (and is not someone else's run) becomes
     * the one driven: the old one is kept to come back to. If [current] is gone, the run goes back to the tab it came from.
     * Returns the tab to drive now and a note, or null when nothing changed. Only tabs this run opened, was given or
     * followed into are ever acted on; the lock is taken for a popup, so a tab another run holds is left alone.
     */
    private suspend fun followTabs(tools: RecordingTools, run: UseRun, st: FlowState, current: String, before: Map<String, String>, allowNew: Boolean, wait: Boolean): Pair<String, String>? {
        if (before.isEmpty()) return null
        var rechecked = false
        while (true) {
            val now = runCatching { tabs.snapshot() }.getOrDefault(emptyMap())
            if (now.isNotEmpty()) {
                st.known = now
                if (allowNew) {
                    for (id in now.keys.filter { it !in before && it !in st.owned }) {
                        if (tabs.claim(id) != null) continue
                        st.owned += id; st.touched += id; st.stack.addLast(current)
                        run.tabsUsed += TabUse(id, now[id].orEmpty(), "popup")
                        runCatching { tabs.reveal(id) }
                        awaitPage(tools, id)
                        return id to "Switched to popup '${now[id].orEmpty().take(60)}'"
                    }
                }
                if (current !in now && !isPopupId(current)) {
                    var back: String? = null
                    while (back == null) { val c = st.stack.removeLastOrNull() ?: break; if (c in now) back = c }
                    if (back != null) {
                        tabs.release(current); st.owned -= current
                        runCatching { tabs.reveal(back) }
                        return back to "Popup closed, back on '${now[back].orEmpty().take(60)}'"
                    }
                }
            }
            // Right after the action, and once more shortly after: a plain link's tab shows up within that. A later one is caught before the next look.
            if (!wait || rechecked) return null
            rechecked = true
            delay(popupRecheckMs)
        }
    }

    // ---- popup windows (pseudo tabs) ----

    /**
     * Popup windows that BOSS opens as separate windows are not tabs; RPA Engine gives them ids `<tab>#popup-<n>` and lists them in
     * `popups` on every result. One opened from a tab this run owns becomes the one driven (no lock: it is not a BOSS tab); when it
     * reports closed, the run goes back. Returns the tab to drive now and a note, or null when nothing changed.
     */
    private fun followPopups(run: UseRun, st: FlowState, tools: RecordingTools, current: String): Pair<String, String>? {
        var cur = current
        var note: String? = null
        for (e in tools.drainPopupEvents()) {
            val parent = e.id.substringBefore("#popup-")
            if (parent !in st.owned) continue
            if (e.open) {
                if (e.id in st.owned) continue
                st.owned += e.id
                st.stack.addLast(cur)
                run.tabsUsed += TabUse(e.id, e.title, "popup_window")
                cur = e.id
                note = "Switched to popup window '${e.title.take(60)}'"
            } else if (e.id == cur) {
                popBackFromPopup(run, st, cur)?.let { (back, text) -> cur = back; note = text }
            }
        }
        return if (cur != current) cur to checkNotNull(note) else null
    }

    private fun popBackFromPopup(run: UseRun, st: FlowState, popup: String): Pair<String, String>? {
        val back = st.stack.removeLastOrNull() ?: return null
        st.owned -= popup
        val title = st.known[back].orEmpty().ifBlank { run.tabsUsed.firstOrNull { it.tabId == back }?.title.orEmpty() }
        return back to "Popup window closed, back on ${if (title.isBlank()) "the page before" else "'${title.take(60)}'"}"
    }

    // ---- browser dialogs ----

    private data class DialogDecision(
        val ok: Boolean, val confidence: Double, val viaJev: Boolean,
        /** The text Jev chose to enter in a prompt, or that it wants the writing model to write it. */
        val text: String? = null, val wantsLlm: Boolean = false,
        /** The confidence the answer itself reported, apart from its probability. */
        val choiceConfidence: Double? = null,
    )

    /** Draws the pending dialog on the last screenshot so Jev can see it; the file is noted in the activity. Falls back to the plain screenshot. */
    private fun renderDialogShot(run: UseRun, shot: Shot, dlg: Cu.DialogInfo): Shot {
        val path = DialogImage.render(shot.path, dlg, shot.width, shot.height)
        run.log.add(ActivityEntry(n = 0, atMs = clock(), kind = ActivityKind.TOOL, name = "render dialog", args = "${dlg.type} '${dlg.message.replace(Regex("\\s+"), " ").take(40)}' over ${shot.path.substringAfterLast('/')}",
            result = path ?: "could not draw the dialog; the plain screenshot is used", durationMs = 0, failed = path == null, image = path))
        return if (path != null) shot.copy(path = path) else shot
    }

    /** alert: OK. beforeunload: OK unless the goal says to stay. confirm and prompt: Jev chooses OK or CANCEL from the screenshot. */
    private suspend fun dialogDecision(req: UseRequest, tools: RecordingTools, plan: UsePlan, dlg: Cu.DialogInfo, shot: Shot, url: String, title: String, steps: List<UseStep>, run: UseRun): Result<DialogDecision> {
        if (dlg.type == "alert" || (dlg.type == "beforeunload" && !Cu.goalSaysStay(plan.goal))) return Result.success(DialogDecision(true, 1.0, false))
        // Nothing to show Jev (the dialog came before any screenshot): the safe answer.
        if (shot.path.isEmpty()) return Result.success(DialogDecision(false, 1.0, false))
        // A prompt is answered with a concrete text: each candidate (inputs, quoted values, the goal's clause and phrases) is an option, and the writing model too.
        var texts = emptyList<String>()
        var askLlm = false
        if (dlg.type == "prompt") {
            val sensitive = Cu.sensitiveField(Focused("prompt", true, dlg.message.take(80), null, null))
            texts = Cu.textOptions(plan.goal, plan.typeValues, url, sensitive, req.inputs).take(8)
            askLlm = req.llmTyping && !sensitive
            if (sensitive) tools.secrets.addAll(texts)
        }
        val reply = tools.invoke(ToolNames.JEV_DECIDE, Cu.dialogArgs(req.jevModel, plan.goal, url, title, Cu.recentActions(steps), shot.path, dlg, run.advice.toList(), req.pointerLabel, texts, askLlm))
        if (reply.isError) return Result.failure(IllegalStateException(reply.errorMessage))
        return runCatching {
            Cu.parseDialogChoice(reply.json ?: error("Jev returned no JSON"), texts.size).let { c ->
                DialogDecision(c.ok, c.probability, true, text = c.textIndex?.let { texts[it] }, wantsLlm = c.askLlm, choiceConfidence = c.confidence)
            }
        }
    }

    /** Answers the pending dialog through `dialog_answer`; a prompt's text comes from the usual choice (inputs, goal words, the writing model). */
    private suspend fun answerDialog(
        req: UseRequest, tools: RecordingTools, llm: UseLlm, tab: String, plan: UsePlan,
        dlg: Cu.DialogInfo, dd: DialogDecision, url: String, title: String, history: List<UseStep>,
    ): UseStep {
        val shownMsg = dlg.message.replace(Regex("\\s+"), " ").take(60)
        var value = if (dd.ok) "ok" else "cancel"
        var shownAnswer = if (dd.ok) "OK" else "CANCEL"
        var text: String? = null
        var source: String? = null
        var note: String? = null
        if (dd.ok && dlg.type == "prompt") {
            val field = Focused("prompt", true, dlg.message.take(80), null, null)
            val sensitive = Cu.sensitiveField(field)
            val options = Cu.textOptions(plan.goal, plan.typeValues, url, sensitive, req.inputs)
            val mayAsk = req.llmTyping && !sensitive
            // Jev already picked the text in the dialog question; else the usual choice.
            val picked = dd.text?.let { it to false }
                ?: (if (dd.wantsLlm) writeText(llm, req, plan, field, url, title, history, free = true)?.let { it to true } else null)
                ?: (if (options.isEmpty() && !mayAsk) null else pickText(tools, llm, req, plan, field, options, url, title, history, mayAsk))
            if (picked == null) { value = "cancel"; shownAnswer = "CANCEL"; note = "no text for the prompt, so it was cancelled" }
            else {
                if (sensitive) tools.secrets.add(picked.first)
                value = "ok:" + picked.first
                text = if (sensitive) Secrets.MASK else picked.first
                shownAnswer = "OK '${text.take(60)}'"
                if (picked.second) source = WRITTEN_BY_MODEL
            }
        }
        val reply = tools.invoke(ToolNames.STEP, Cu.step(tab, "dialog_answer", value))
        val failed = reply.isError || (reply.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false
        val err = if (reply.isError) (if (reply.errorCode == ToolNames.NO_BROWSER) NO_BROWSER_NOTE else reply.errorMessage) else (reply.json?.get("error") as? JsonPrimitive)?.contentOrNull
        return UseStep(0, VisionOp.DIALOG, confidence = dd.confidence, ms = 0, text = text, textSource = source, failed = failed,
            note = if (failed) err else note, dialog = "Dialog '$shownMsg': $shownAnswer", dialogMessage = shownMsg, dialogAnswer = shownAnswer)
    }

    /** The targets a look located, in the order they were asked for; a phrase already acted on is left out. */
    private fun targetsFromLook(look: Look, phrases: List<String>, steps: List<UseStep>): List<Cu.Target> =
        phrases.filter { !Cu.actedOn(it, steps) }.mapNotNull { p ->
            (look.located[p] ?: look.located.entries.firstOrNull { it.key.trim().equals(p.trim(), ignoreCase = true) }?.value)?.let { Cu.Target(p, it) }
        }

    /** Draws the marks on a copy of the screenshot and notes the file in the activity; null (the plain screenshot is used) when it could not be drawn. */
    private fun renderMarks(run: UseRun, shot: Shot, marks: List<Mark>): String? {
        val path = MarkImage.render(shot, marks)
        run.log.add(ActivityEntry(n = 0, atMs = clock(), kind = ActivityKind.TOOL, name = "render marks", args = "${marks.size} marks over ${shot.path.substringAfterLast('/')}",
            result = path ?: "could not draw the marks; the plain screenshot is used", durationMs = 0, failed = path == null, image = path))
        return path
    }

    /**
     * Where the goal's things are on this page: up to [Cu.MAX_TARGETS] phrases from the goal, each located through `locate_text`.
     * Answers are cached by address, content hash (pixels when there is none) and scroll position, so a screen already asked about
     * costs nothing. An engine without `locate_text` is asked once and then left alone.
     */
    private suspend fun locateTargets(tools: RecordingTools, st: FlowState, tab: String, phrases: List<String>, url: String, shot: Shot, pixelHash: String, steps: List<UseStep>): List<Cu.Target> {
        if (!st.locateWorks) return emptyList()
        val out = mutableListOf<Cu.Target>()
        for (phrase in phrases) {
            // Already clicked, snapped to or typed: no need to look for it again.
            if (Cu.actedOn(phrase, steps)) continue
            val key = "$url|${shot.contentHash ?: pixelHash}|${shot.scrollY?.toInt()}|$phrase"
            val found = st.located[key] ?: run {
                val r = tools.invoke(ToolNames.STEP, Cu.step(tab, "locate_text", phrase))
                if (Cu.parseDialog(r.json) != null) return out
                val parsed = if (r.isError || (r.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false) null else Cu.parseLocated(r.json)
                if (parsed == null) { st.locateWorks = false; return out }
                parsed.also { st.located[key] = it }
            }
            out += Cu.Target(phrase, found)
        }
        return out
    }

    /** [note] with the dialogs the engine answered by itself since the last step. */
    private fun withAuto(note: String?, tools: RecordingTools): String? =
        (listOfNotNull(note) + tools.drainAutoNotes()).joinToString("; ").ifEmpty { null }

    /** Saves the page text of [url] once per address; [auto] marks the runner's own snapshots. */
    private suspend fun snapshot(tools: RecordingTools, run: UseRun, tab: String, url: String, title: String, step: Int, shotPath: String?, auto: Boolean, pageText: String? = null): Boolean {
        val key = pageKey(url)
        if (auto && key in run.snapshotted) return false
        run.snapshotted += key
        val text = pageText ?: runCatching { readPageText(tools, tab) }.getOrNull().orEmpty()
        val added = run.store.add(CollectedItem(step, url, title, text, shotPath, auto))
        run.collected.value = run.store.list
        return added
    }

    /**
     * Whether the page is a dead end for the current milestone: what it needs ([Cu.targetPhrasesFor]) is not on the page at all. Without
     * phrases to look for, or an engine that cannot locate them, there is no way to tell, and the run is not replanned.
     */
    private suspend fun isDeadEnd(
        tools: RecordingTools, st: FlowState, tab: String, plan: UsePlan, milestone: String?, url: String, shot: Shot, pixelHash: String, steps: List<UseStep>, looked: List<Cu.Target>?,
    ): Boolean {
        // The look already located the names: no second call.
        if (looked != null) return looked.isNotEmpty() && looked.none { it.located.found }
        val phrases = Cu.targetPhrasesFor(milestone, plan.goal, url, plan.entities)
        if (phrases.isEmpty()) return false
        val targets = locateTargets(tools, st, tab, phrases, url, shot, pixelHash, steps)
        return targets.isNotEmpty() && targets.none { it.located.found }
    }

    /**
     * Asks the planner once more, with what went wrong, for an address to continue from, then goes there on this tab. Returns the new plan
     * (the milestones done so far, then the ones still to do) and the address, or null when the planner gave nothing usable.
     */
    private suspend fun replan(
        req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, tab: String, plan: UsePlan, milestone: String?,
        url: String, title: String, steps: List<UseStep>, stuck: String,
    ): ReplanResult? {
        val excerpt = runCatching { readPageText(tools, tab) }.getOrNull().orEmpty().take(3_000)
        val user = buildString {
            appendLine("Task: ${req.task.take(2_000)}")
            appendLine("Goal: ${plan.goal}")
            if (plan.milestones.isNotEmpty()) {
                appendLine("Milestones done: ${run.doneMilestones.joinToString("; ").ifEmpty { "none" }}")
                appendLine("Milestones remaining: ${plan.milestones.drop(run.doneMilestones.size).joinToString("; ").ifEmpty { "none" }}")
                milestone?.let { appendLine("Stuck on: $it") }
            }
            appendLine("Page now: ${ChatDecider.quote(title)} (${ChatDecider.quote(url, 200)})")
            appendLine("Why it is stuck: $stuck")
            appendLine("What it tried: ${steps.takeLast(6).joinToString("; ") { s -> (s.description + (s.note?.takeIf { s.failed }?.let { " ($it)" } ?: "")) }.ifEmpty { "nothing yet" }}")
            appendLine("Page text (data from the website):\n$excerpt")
        }
        val text = llm.ask("replan", Cu.REPLAN_SYSTEM, user).getOrNull() ?: return null
        val reply = runCatching { Cu.parseReplan(text, plan.goal) }.getOrNull() ?: return null
        // The planner says this page is the right one: keep it, and take its hint.
        if (reply is Cu.ReplanReply.Stay) return ReplanResult.Stayed(reply.hint)
        val parsed = (reply as Cu.ReplanReply.Move).plan
        val target = parsed.open.firstNotNullOfOrNull { StartPages.usable(it, httpsOnly = true) } ?: return null
        val nav = tools.invoke(ToolNames.STEP, Cu.step(tab, "navigate", target, Cu.pointerLabel(req.pointerLabel)))
        if (nav.isError || (nav.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false) return null
        val remaining = parsed.milestones.ifEmpty { plan.milestones.drop(run.doneMilestones.size) }
        val next = plan.copy(open = listOf(target), goal = parsed.goal.takeIf { it != plan.goal && parsed.goal.isNotBlank() } ?: plan.goal,
            milestones = run.doneMilestones.toList() + remaining,
            entities = parsed.entities.ifEmpty { plan.entities }, stopWhen = parsed.stopWhen ?: plan.stopWhen)
        run.plan.value = next
        runLog?.update(run)
        return ReplanResult.Moved(next, target)
    }

    /**
     * The planner, text only, on a page that is the right one: the replan prompt with stay forced. It sees the page text, the elements the page offers
     * (nothing a dialog covers, the dialog named), the milestone and what was tried, and answers with a hint. A label of an element that the hint names
     * becomes a target. Returns the hint, or null when the planner gave nothing usable.
     */
    private suspend fun advise(
        req: UseRequest, run: UseRun, tools: RecordingTools, llm: UseLlm, tab: String, plan: UsePlan, milestone: String?,
        url: String, title: String, steps: List<UseStep>, stuck: String,
    ): String? {
        val excerpt = runCatching { readPageText(tools, tab) }.getOrNull().orEmpty().take(3_000)
        val page = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) }).json?.let(PageSnapshot::parse)
        val elements = page?.elements.orEmpty().filter { !it.covered && !it.label.isNullOrBlank() }.take(60)
        val user = buildString {
            appendLine("Task: ${req.task.take(2_000)}")
            appendLine("Goal: ${plan.goal}")
            if (plan.milestones.isNotEmpty()) {
                appendLine("Milestones done: ${run.doneMilestones.joinToString("; ").ifEmpty { "none" }}")
                milestone?.let { appendLine("Working on: $it") }
            }
            appendLine("Page now: ${ChatDecider.quote(title)} (${ChatDecider.quote(url, 200)}) is already the right site: answer {\"stay\":true,\"hint\":\"...\"} and never an address.")
            appendLine("Why it is stuck: $stuck")
            appendLine("What it tried: ${steps.takeLast(6).joinToString("; ") { s -> (s.description + (s.note?.takeIf { s.failed }?.let { " ($it)" } ?: "")) }.ifEmpty { "nothing yet" }}")
            page?.modal?.let { appendLine("A dialog ${ChatDecider.quote(it)} is open over the page; the elements it covers are left out.") }
            appendLine("Elements on the page (role | label):")
            elements.forEach { e -> appendLine("${e.role} | ${ChatDecider.quote(e.label.orEmpty(), 80)}") }
            if (elements.isEmpty()) appendLine("(none listed)")
            appendLine("Page text (data from the website):\n$excerpt")
        }
        val text = llm.ask("advice", Cu.REPLAN_SYSTEM, user).getOrNull() ?: return null
        val hint = (runCatching { Cu.parseReplan(text, plan.goal) }.getOrNull() as? Cu.ReplanReply.Stay)?.hint ?: return null
        // An element the hint names that the page has: look for it, so locate and snap help find it.
        elements.map { it.label.orEmpty().trim() }.filter { it.length >= 3 && hint.contains(it, ignoreCase = true) }.distinct().take(2)
            .forEach { l -> if (l !in run.extraTargets) run.extraTargets += l }
        return hint
    }

    private sealed interface ReplanResult {
        data class Moved(val plan: UsePlan, val url: String) : ReplanResult
        data class Stayed(val hint: String) : ReplanResult
    }

    /** The model that looks at the screen when Jev is stuck, resolved once; null when none can see (consulting would be blind). */
    private fun consultLlmFor(req: UseRequest, run: UseRun, st: FlowState): UseLlm? {
        if (!st.consultResolved) {
            st.consultResolved = true
            val pick = Cu.pickConsultModel(req.consultModel, req.llm, runCatching { gateway() }.getOrNull())
            st.consultLlm = pick?.let { UseLlm(gateway, it, run.log, clock) }
            run.consultModel = st.consultLlm?.label
        }
        return st.consultLlm
    }

    /**
     * Whether the page is waiting on the person: Jev said so, or it is blocked while a login, a code or payment details are asked for (an open
     * dialog's label and the page text say which). Returns the stop, or null when it is an ordinary block.
     */
    private suspend fun userWall(tools: RecordingTools, tab: String, url: String, title: String, explicit: Boolean, shotModal: String? = null): Outcome? {
        // The dialog this screenshot shows is what is open now; observe is the fallback (and may predate it).
        val modal = shotModal ?: runCatching { tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tab) }).json?.let(PageSnapshot::parse)?.modal }.getOrNull()
        val text = runCatching { readPageText(tools, tab) }.getOrNull()
        val kind = Cu.wallKind(modal, text)
        if (kind == null && !explicit) return null
        val what = kind ?: "provide something only you can"
        val site = host(url).ifBlank { title }
        return Outcome(UseStatus.NEEDS_INPUT, "The page asks for something only you can provide ($what)", Cu.needsInputQuestion(site, what))
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
        // What an open dialog covers cannot be reached from where it is, so it is not offered.
        val elements = page?.elements.orEmpty().filter { !it.covered }.take(MAX_CONSULT_ELEMENTS)
        val image = if (llm.sees()) runCatching { File(shot.path).readBytes() }.getOrNull() else null
        val user = buildString {
            appendLine("Request: ${req.task.take(2_000)}")
            appendLine("Goal given to Jev: ${plan.goal}")
            appendLine("Why Jev is stuck: $reason")
            appendLine(Cu.cursorNote(req.pointerLabel))
            appendLine("Page: ${ChatDecider.quote(title)} (${ChatDecider.quote(url, 200)})")
            appendLine("Recent steps: ${steps.takeLast(8).joinToString("; ") { it.description }.ifEmpty { "none" }}")
            if (run.advice.isNotEmpty()) appendLine("Advice so far: ${run.advice.takeLast(3).joinToString("; ") { ChatDecider.quote(it, 200) }}")
            if (req.inputs.isNotEmpty()) appendLine("Inputs the person gave (use \"{{name}}\" as a value): " +
                req.inputs.entries.joinToString(", ") { (k, v) -> if (Cu.secretName(k)) "$k (private)" else "$k=${ChatDecider.quote(v, 60)}" })
            page?.modal?.let { appendLine("A dialog ${ChatDecider.quote(it)} is open over the page; the elements it covers are left out.") }
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
        val pointer = Cu.pointerLabel(req.pointerLabel)
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
        // Never a password, a one-time code or a card: those are for the person.
        if (a.type == "input" && Cu.consultMayNotType(el.label, el.sensitive)) return "refused: $label is a password, code or card field, which only the person fills" to true
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
        d: VisionDecision, shot: Shot, url: String, title: String, history: List<UseStep>, targets: List<Cu.Target> = emptyList(),
    ): UseStep {
        val pointer = Cu.pointerLabel(req.pointerLabel)
        fun base(note: String? = null, failed: Boolean = false) = UseStep(0, d.op, confidence = d.confidence, ms = 0, note = note, failed = failed)
        suspend fun send(type: String, value: String? = null, withPointer: Boolean = true): ToolReply =
            tools.invoke(ToolNames.STEP, Cu.step(tab, type, value, pointer.takeIf { withPointer }))
        fun failure(r: ToolReply): String? {
            if (r.isError) return noBrowser(r).let { if (r.errorCode == ToolNames.NO_BROWSER) NO_BROWSER_NOTE else it }
            // A refusal with a dialog attached is the dialog's doing: it is answered next, not a failure of the action.
            if (Cu.parseDialog(r.json) != null) return null
            return if ((r.json?.get("ok") as? JsonPrimitive)?.booleanOrNull == false) (r.json?.get("error") as? JsonPrimitive)?.contentOrNull ?: "the action failed" else null
        }
        when (d.op) {
            VisionOp.CLICK, VisionOp.TYPE_TEXT -> {
                // A picked mark is clicked by id (the engine re-resolves it and refuses a disabled or covered one); its centre is the fallback point.
                // With no mark (NONE, no marks, an older engine) the box decides, as before.
                val mark = d.mark
                val (x, y) = if (mark != null) {
                    mark.centreX.roundToInt().coerceIn(0, (shot.cssWidth - 1).coerceAtLeast(0)) to mark.centreY.roundToInt().coerceIn(0, (shot.cssHeight - 1).coerceAtLeast(0))
                } else Cu.clickPoint(d.box, shot.cssWidth, shot.cssHeight) ?: return base("Jev gave no usable target box", failed = true)
                // Which of the goal's targets was under the click, so they are not reported as missing once acted on.
                val under = targets.filter { it.located.inView && it.located.rect?.contains(x.toDouble(), y.toDouble()) == true }.map { it.text }
                val where = base().copy(
                    x = x, y = y, xPct = Cu.percent(x / shot.cssWidth.toDouble().coerceAtLeast(1.0)), yPct = Cu.percent(y / shot.cssHeight.toDouble().coerceAtLeast(1.0)),
                    clickedTargets = under, target = mark?.short,
                )
                // The box goes along (without a mark) so a click that lands beside its target is moved onto it.
                val click = tools.invoke(ToolNames.STEP, Cu.step(tab, "click_at", "$x,$y", pointer, if (mark == null && d.box != null) Cu.snapBox(d.box, shot.cssWidth, shot.cssHeight) else null, element = mark?.id))
                failure(click)?.let { err ->
                    // A disabled or covered element is the page's answer, not a failure of ours: a note for the next decision, and no action.
                    Marks.refusal(err)?.let { return where.copy(note = it, refused = true) }
                    return where.copy(note = err, failed = true)
                }
                val snapped = Cu.parseSnapped(click.json)
                fun noted(extra: String? = null) = listOfNotNull(snapped?.note, if (d.boxDisagreed) "box disagreed" else null, extra).joinToString("; ").ifEmpty { null }
                val clicked = where.copy(snapped = snapped, note = noted())
                if (d.op == VisionOp.CLICK) return clicked
                // The click may have opened a dialog: nothing more can be read until it is answered.
                if (tools.dialogFor(tab) != null) return clicked.copy(note = noted("a dialog opened; it is answered next"))
                // TYPE_TEXT: what did the click land on, and which of the goal's texts goes there?
                val fr = send("focused", withPointer = false)
                failure(fr)?.let { return clicked.copy(note = noted(it), failed = true) }
                if (tools.dialogFor(tab) != null) return clicked.copy(note = noted("a dialog opened; it is answered next"))
                val field = fr.json?.let(Cu::parseFocused)
                // A native dropdown reports itself as not editable; RPA Engine's type_text answers it by type-ahead.
                val isSelect = field?.tag.equals("select", ignoreCase = true)
                if (field == null || (!field.editable && !isSelect)) return clicked.copy(note = noted("the box did not land on a text field; nothing typed"))
                val sensitive = Cu.sensitiveField(field)
                // A dropdown offers its own options: the text must be one of them.
                val listed = if (isSelect) field.options.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(40) else emptyList()
                val options = listed.ifEmpty { Cu.textOptions(plan.goal, plan.typeValues, url, sensitive, req.inputs) }
                // A private field never gets text a model wrote, and a dropdown's text is chosen, not written.
                val mayAsk = req.llmTyping && !sensitive && !isSelect
                if (options.isEmpty() && !mayAsk) return clicked.copy(note = noted("no text from the goal fits '${field.label.take(40)}'"))
                // A message box takes a sentence: the writing model writes it straight from the goal, not a phrase chosen from the goal.
                val free = Cu.freeTextField(field) && !sensitive && !isSelect
                val given = (plan.typeValues + req.inputs.values + Candidates.values(plan.goal)).map { it.trim() }.filter { it.isNotEmpty() }
                val picked = (if (free && req.llmTyping && given.size != 1) writeText(llm, req, plan, field, url, title, history, free = true)?.let { it to true } else null)
                    ?: pickText(tools, llm, req, plan, field, options, url, title, history, mayAsk)
                    ?: return clicked.copy(note = noted("no text found for '${field.label.take(40)}'"))
                if (sensitive) tools.secrets.add(picked.first)
                val typed = send("type_text", picked.first)
                failure(typed)?.let { return clicked.copy(note = noted(it), failed = true) }
                return clicked.copy(text = if (sensitive) Secrets.MASK else picked.first, textSource = if (picked.second) WRITTEN_BY_MODEL else null, field = "${field.label.trim().lowercase()}|${field.tag.lowercase()}")
            }
            VisionOp.PRESS_ENTER -> return send("key", "Enter").let { r -> failure(r)?.let { base(it, true) } ?: base() }
            VisionOp.SCROLL_DOWN, VisionOp.SCROLL_UP -> {
                // One target far off in the direction Jev chose: go straight there instead of in steps.
                val direct = Cu.scrollToTarget(targets, d.op, plan.milestones.getOrNull(run.doneMilestones.size))
                val dy = direct?.let { Cu.scrollDeltaScreens(it.second, shot.cssHeight) } ?: Cu.scrollDelta(d.op, d.scroll, shot.cssHeight)
                val amount = direct?.let { (t, move) -> "amount %.1f screens toward '%s' (it was %.1f screens away)".format(Math.abs(move), t.text.take(40), t.located.screens ?: 0.0) }
                    ?: "amount ${d.scroll ?: "medium"}"
                return send("scroll_by", dy.toString()).let { r ->
                    failure(r)?.let { base(it, true) } ?: base(amount)
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
    private suspend fun writeText(llm: UseLlm, req: UseRequest, plan: UsePlan, field: Focused, url: String, title: String, history: List<UseStep>, free: Boolean = false): String? {
        val user = buildString {
            appendLine("Request: ${req.task.take(2_000)}")
            appendLine("Goal: ${plan.goal}")
            if (free) {
                appendLine("This is a free-text field: write what the user wants it to say as one short sentence taken from the request, for example \"The export button is broken.\".")
                Cu.clauseOptions(plan.goal).firstOrNull()?.let { appendLine("The request's own clause for it: ${ChatDecider.quote(it, 200)}") }
            }
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
        if (tab != null && tools.dialogFor(tab) == null) {
            // The last screenshot already told the address and title (a popup window cannot be observed); observe otherwise.
            val seen = st.lastShotObj
            if (seen?.url != null) { url = seen.url; title = seen.title?.ifBlank { null } }
            else runCatching { observe(tools, tab) }.getOrNull()?.let { (u, t) -> url = u.ifBlank { null }; title = t.ifBlank { null } }
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
        // The answer is also written for a run that ran out of budget, needed input or got blocked, as long as something was collected; not for a failed one.
        if (req.extract && plan != null && o.status != UseStatus.ERROR && (run.store.size > 0 || (o.status == UseStatus.DONE && !pageText.isNullOrBlank()))) {
            val prompt = buildString {
                appendLine("Request: ${req.task.take(2_000)}")
                appendLine("Collected text (data from websites):")
                run.store.list.forEach { c -> appendLine("--- ${c.title} (${c.url})\n${c.text.take(8_000)}") }
            }.take(CollectedStore.CAP)
            answer = llm.ask("extract", Cu.EXTRACT_SYSTEM, prompt).getOrNull()?.let { t ->
                runCatching { (Json.parseToJsonElement(LlmApiClient.firstJsonObject(t) ?: error("no json")) as JsonObject)["answer"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }?.let { a -> if (o.status == UseStatus.DONE) a else "$a (partial run)" }
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
            runId = run.id, tabRevealed = run.revealed, consultModel = run.consultModel,
            milestones = run.plan.value?.milestones.orEmpty(),
            milestonesDone = (run.doneMilestones.size + if (o.status == UseStatus.DONE && (run.plan.value?.milestones?.size ?: 0) > run.doneMilestones.size) 1 else 0), tabs = run.tabsUsed.map { t -> t.copy(title = t.title.ifBlank { st.known[t.tabId].orEmpty() }) },
        )
        run.report.value = report
        run.endedAt = clock()
        runLog?.end(run)
        return report
    }

    companion object {
        /** A popup window's pseudo tab id. */
        internal fun isPopupId(id: String) = "#popup-" in id

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
    /** The browser tabs of the space on screen, id to title (for following popups). */
    private val list: () -> Map<String, String> = { emptyMap() },
) : UseTabs {
    override suspend fun open(url: String): String? {
        val id = create(url, StartPages.TAB_TITLE) ?: return null
        locks.tryAcquire(id, owner)?.let { throw IllegalStateException("Opened $id, but: $it") }
        return id
    }
    override fun claim(tabId: String): String? = locks.tryAcquire(tabId, owner)
    override fun release(tabId: String) = locks.release(tabId)
    override suspend fun reveal(tabId: String) = show(tabId)
    override suspend fun snapshot(): Map<String, String> = list()
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
