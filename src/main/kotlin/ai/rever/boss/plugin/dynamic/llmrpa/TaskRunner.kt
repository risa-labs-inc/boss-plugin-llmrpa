package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicReference

enum class RunStatus { RUNNING, WAITING, DONE, STOPPED, FAILED }

data class StepRecord(
    val index: Int,
    val description: String,
    val confidence: Double,
    val alternatives: List<Pair<String, Double>> = emptyList(),
    val outcome: Outcome = Outcome.RUNNING,
    val detail: String? = null,
    /** Where a typed value came from; null for steps that type nothing. */
    val valueSource: ValueSource? = null,
    val chosenBy: ChosenBy = ChosenBy.MODEL,
) {
    enum class Outcome { RUNNING, OK, FAILED }
    enum class ChosenBy { MODEL, USER }
    enum class ValueSource(val label: String) {
        QUOTED("quoted in your instruction"),
        PHRASE("words from your instruction"),
        MODEL("text written by the model"),
        USER("text you picked"),
    }

    val valueWritten: Boolean get() = valueSource == ValueSource.MODEL
}

/** What the runner needs from the person before it can continue. */
sealed interface PendingQuestion {
    /** The model was unsure; [options] are its best picks with their confidence. */
    data class Choose(val reason: String, val options: List<Pair<Candidate, Double>>) : PendingQuestion

    /** The chosen action looks irreversible. [risk] is null when it could not be assessed. */
    data class Confirm(val action: Candidate, val risk: Double?) : PendingQuestion

    /** A type step has no text; [options] are the instruction's quoted values and words. */
    data class ChooseText(val reason: String, val field: String, val options: List<String>) : PendingQuestion
}

sealed interface Answer {
    data class Pick(val candidate: Candidate) : Answer
    data class Text(val value: String) : Answer
    data object Proceed : Answer
    data object Stop : Answer
}

data class RunState(
    val instruction: String,
    val modelLabel: String,
    val maxSteps: Int,
    val status: RunStatus = RunStatus.RUNNING,
    val steps: List<StepRecord> = emptyList(),
    val question: PendingQuestion? = null,
    /** The last question asked, kept after the run ends so a headless caller can see where it stopped. */
    val lastQuestion: PendingQuestion? = null,
    val summary: String? = null,
    val calls: Int = 0,
    val costUsd: Double = 0.0,
    val startedAt: Long,
    /** The tab the run acts on; null until a new tab is open. */
    val tabId: String? = null,
    /** Set when the run opened its own tab. */
    val opened: OpenedPage? = null,
)

data class RunLimits(
    val maxSteps: Int = 12,
    /** Below this confidence the runner asks instead of acting. */
    val askBelow: Double = 0.6,
    /** At or above this risk the runner asks for confirmation. */
    val confirmAbove: Double = 0.5,
    /** Below this, the done check says the page does not show the task complete. */
    val doneAbove: Double = 0.5,
    val maxConsecutiveFailures: Int = 2,
    /** Settle times after a step, so the next look sees the rebuilt page; tests set them to zero. */
    val navSettleMs: Long = 900,
    val stepSettleMs: Long = 250,
    /** Waits before each look at a new tab, until the page reads; about 13 s in all. */
    val openWaitsMs: List<Long> = listOf(300, 500, 800, 1_200, 2_000, 2_000, 3_000, 3_000),
)

/**
 * Observe → decide → act, one step at a time, on one tab. RPA Engine does every browser action
 * (`rpa_observe`, `rpa_step`); [decider] picks each step. Questions for the person go through
 * [ask], which the panel answers with buttons and a headless caller answers with [Answer.Stop].
 * With no [tabId], the run first opens a page in a tab of its own through [newTab].
 */
class TaskRunner(
    private val tools: ToolInvoker,
    private val decider: StepDecider,
    tabId: String?,
    private val instruction: String,
    private val limits: RunLimits = RunLimits(),
    clock: () -> Long = System::currentTimeMillis,
    private val newTab: NewTab? = null,
    private val ask: suspend (PendingQuestion) -> Answer,
) {
    init { require(tabId != null || newTab != null) { "A run needs a tab or a way to open one" } }

    private var tabId: String? = tabId
    private val _state = MutableStateFlow(RunState(instruction, decider.option.label, limits.maxSteps, startedAt = clock(), tabId = tabId))
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val values = Candidates.values(instruction)

    /**
     * Never throws except on cancellation: anything a decider or tool raises across the plugin
     * boundary ends the run as FAILED with the reason, not as "Stopped by you".
     */
    suspend fun run(): RunState = try {
        if (tabId == null) openStartPage()?.let { (opened, page) -> loop(listOf(opened.description), page) } ?: state.value
        else loop(emptyList(), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        fail(e)
    }

    /** Ends a live run as FAILED because of [e]. */
    fun fail(e: Throwable): RunState {
        if (_state.value.status == RunStatus.RUNNING || _state.value.status == RunStatus.WAITING) {
            finish(RunStatus.FAILED, "The run failed at step ${_state.value.steps.size + 1}: ${e.message ?: e::class.simpleName}")
        }
        return state.value
    }

    /**
     * Picks a start page, opens it in a new tab and waits until it reads. Only this open may use
     * the chosen address: later "Go to" steps still come from the instruction alone. A tab that
     * opened but cannot be used is left open on purpose, so the person can see what it shows.
     */
    private suspend fun openStartPage(): Pair<OpenedPage, PageSnapshot>? {
        val nt = newTab ?: return stop(RunStatus.FAILED, "No tab to run on")
        val choice = nt.startUrl?.let { StartPages.Choice(OpenedPage(it, StartSource.CALLER), 0, 0.0) }
            ?: try {
                StartPages.choose(instruction, decider)
            } catch (e: StartPages.NoStartPage) {
                return stop(RunStatus.STOPPED, e.message.orEmpty())
            }
        _state.update { it.copy(calls = it.calls + choice.calls, costUsd = it.costUsd + choice.costUsd) }
        val url = choice.page.url
        // Not cancellable: a Stop landing mid-create would lose the id of a tab that exists.
        var refusal: String? = null
        val id = withContext(NonCancellable) {
            nt.open(url, StartPages.TAB_TITLE)?.also { id ->
                refusal = nt.claim(id)
                if (refusal == null) {
                    tabId = id
                    _state.update { it.copy(tabId = id, opened = choice.page) }
                }
            }
        } ?: return stop(RunStatus.FAILED, "Could not open a new tab for $url: BOSS did not create one. Open the page yourself and pick its tab.")
        refusal?.let { return stop(RunStatus.FAILED, "Opened $id, but: $it") }
        // A Stop that arrived mid-create ends the run here, with the tab recorded for release.
        currentCoroutineContext().ensureActive()
        val page = awaitPage(id).getOrElse {
            return stop(RunStatus.FAILED, "Opened $url, but the page could not be read: ${it.message}. Check the new tab and run again on it.")
        }
        return choice.page to page
    }

    private fun stop(status: RunStatus, summary: String): Nothing? { finish(status, summary); return null }

    /**
     * Bounded retries while the tab registers and loads; an error is retried too (a script can
     * fail mid-navigation) and the last one reported. A page counts once it has an address and
     * something to act on, then gets the navigation settle time and a fresh look, since pages
     * rebuild widgets on load. A page that stays empty is used as it is when the budget runs out.
     */
    private suspend fun awaitPage(id: String): Result<PageSnapshot> {
        var last = "it did not load after ${limits.openWaitsMs.sum() / 1000} s"
        var empty: PageSnapshot? = null
        var waited = 0L
        for (wait in limits.openWaitsMs) {
            delay(wait)
            waited += wait
            val page = read(id)
            if (page == null) continue
            lastReadError = null
            if (page.url.isBlank() || page.url == "about:blank") { last = "it is still blank"; continue }
            // A page with nothing to act on (plain text, a PDF) is taken once it reads the same twice
            // past half the budget: an app shows a spinner under its final title for a while first.
            if (page.elements.isEmpty()) {
                if (waited * 2 >= limits.openWaitsMs.sum() && empty?.url == page.url && empty.title == page.title) return Result.success(page)
                empty = page
                continue
            }
            delay(limits.navSettleMs)
            return Result.success(read(id)?.takeIf { it.url.isNotBlank() } ?: page)
        }
        // A NO_BROWSER last word beats an empty page seen earlier: the tab left the space on screen.
        if (lastWasNoBrowser) return Result.failure(IllegalStateException(lastReadError ?: last))
        return empty?.let { Result.success(it) } ?: Result.failure(IllegalStateException(lastReadError ?: last))
    }

    /**
     * One look at [id], or null; the latest failure is kept in [lastReadError]. NO_BROWSER reads as
     * still loading, but if it is the last word the tab most likely left the space on screen.
     */
    private suspend fun read(id: String): PageSnapshot? {
        val reply = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", id) })
        val json = reply.json
        lastWasNoBrowser = reply.errorCode == ToolNames.NO_BROWSER
        if (reply.isError || json == null) {
            lastReadError = when (reply.errorCode) {
                ToolNames.NO_BROWSER -> NO_BROWSER_HINT.removeSuffix(".")
                // Not yet registered: keep whatever earlier look said.
                TAB_NOT_FOUND -> lastReadError
                else -> reply.errorMessage
            }
            return null
        }
        return PageSnapshot.parse(json)
    }

    private var lastReadError: String? = null
    private var lastWasNoBrowser = false

    /** [first] is a page already read for this step, so the first look is not repeated. */
    private suspend fun loop(seed: List<String>, first: PageSnapshot?): RunState {
        var pending = first
        val history = seed.toMutableList()
        var failures = 0
        var doneRejected = false
        // The field last typed into, and whether Enter there could post what was typed. Kept until
        // the page navigates, since focus stays in the field across a harmless click.
        var typedInto: PageElement? = null
        var typedCommits = false
        // Counted in actions taken, so a rejected done check does not use up the budget.
        while (_state.value.steps.size < limits.maxSteps) {
            val stepNo = _state.value.steps.size + 1
            val page = pending?.also { pending = null } ?: observe() ?: return state.value
            // Where the last step landed, so the model can tell a search results page from the article.
            if (history.isNotEmpty() && !history.last().contains(" → now on ")) {
                history[history.lastIndex] = "${history.last()} → now on '${page.title.take(80)}'"
            }
            val candidates = Candidates.build(page, instruction, decider.writesText)
            val phrases = Candidates.phrases(instruction, listOf(page.url))
            val ctx = StepContext(instruction, page, candidates, values, history.toList(), phrases)

            val decision = decider.decide(ctx).getOrElse {
                return finish(RunStatus.FAILED, "${decider.option.providerName} could not decide the next step: ${it.message}")
            }
            _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + decision.costUsd) }

            var chosen = candidates.firstOrNull { it.key == decision.key }
                ?: return finish(RunStatus.FAILED, "The model picked an action that is not on the page")
            var chosenBy = StepRecord.ChosenBy.MODEL
            val ranked = listOf(chosen to decision.confidence) +
                decision.alternatives.mapNotNull { (k, p) -> candidates.firstOrNull { it.key == k }?.let { it to p } }
            val actionable = ranked.filter { it.first.kind != Candidate.Kind.STUCK }

            // Unsure (including unsure it is done), or stuck: ask rather than guess on a live page.
            if (chosen.kind == Candidate.Kind.STUCK || decision.confidence < limits.askBelow) {
                if (actionable.isEmpty()) return finish(RunStatus.STOPPED, "No action on this page moves the task forward")
                val reason = if (chosen.kind == Candidate.Kind.STUCK) "The model found no clear next step"
                else "The model is only ${pct(decision.confidence)} sure"
                when (val a = waitFor(PendingQuestion.Choose(reason, actionable.take(3)))) {
                    is Answer.Pick -> { chosen = a.candidate; chosenBy = StepRecord.ChosenBy.USER }
                    else -> return finish(RunStatus.STOPPED, "Stopped at step $stepNo: $reason, so it asked which action to take")
                }
            }

            if (chosen.kind == Candidate.Kind.DONE) {
                val n = _state.value.steps.size
                val steps = "$n ${if (n == 1) "step" else "steps"}"
                // The person said so; the model does not get to overrule them.
                if (chosenBy == StepRecord.ChosenBy.USER) return finish(RunStatus.DONE, "Done in $steps. You said the task is complete.")
                // A model can claim success on the wrong page. Check once, with the page named.
                val check = decider.verifyDone(ctx)
                val verified = check.getOrNull()?.also { (_, cost) ->
                    _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + cost) }
                }?.first
                    // Fails closed like the risk check: an unchecked done is not reported as done.
                    ?: return finish(RunStatus.STOPPED, "Stopped: could not confirm the task is complete (${check.exceptionOrNull()?.message}). Check the page.")
                if (verified >= limits.doneAbove) return finish(RunStatus.DONE, "Done in $steps. ${pct(verified)} sure the task is complete.")
                if (doneRejected) {
                    return finish(RunStatus.STOPPED, "Stopped: the model says the task is done, but the page ('${page.title}') does not look like it.")
                }
                doneRejected = true
                history += "Checked whether the task is complete: not yet (the page is '${page.title.take(80)}')"
                continue
            }

            // Typing needs text; a decision model only ever types what the instruction gave.
            var value = chosen.action?.value
            // The model's value only describes the model's own pick.
            val modelValue = decision.value.takeIf { chosenBy == StepRecord.ChosenBy.MODEL }
            var source: StepRecord.ValueSource? = null
            if (chosen.needsValue) {
                val field = chosen.element?.label?.take(60) ?: "the field"
                value = modelValue ?: values.singleOrNull()
                // A private field only ever takes a quoted value; words are for the other fields.
                if (value == null && chosen.element?.sensitive != true) {
                    val (text, by) = pickText(ctx, field, (values + phrases).distinctBy { it.lowercase() }, stepNo) ?: return state.value
                    value = text
                    source = by
                }
                if (value == null) return finish(
                    RunStatus.STOPPED,
                    if (values.isEmpty()) "Needs text to type into '$field'. Put it in quotes in the instruction."
                    else "Not sure which text from the instruction goes into '$field'. Name the field next to each quoted value.",
                )
                source = source ?: when {
                    value in values -> StepRecord.ValueSource.QUOTED
                    // A writer's text stays its own, even when it matches the instruction's words.
                    !decider.writesText && phrases.any { it.equals(value, ignoreCase = true) } -> StepRecord.ValueSource.PHRASE
                    else -> StepRecord.ValueSource.MODEL
                }
            }
            // Only quoted text counts: a private field never takes words the runner picked out.
            val fromInstruction = value != null && value in values
            if (chosen.needsValue && chosen.element?.sensitive == true && !fromInstruction) {
                return finish(RunStatus.STOPPED, "Stopped before typing into the private field '${chosen.element.label?.take(60)}': only text from your instruction goes there.")
            }
            // A private field's text never reaches the timeline, the history the model sees, or the transcript.
            val shown = if (chosen.element?.sensitive == true) "••••••" else "'${value?.take(60)}'"
            val description = if (chosen.needsValue) "Type $shown into '${chosen.element?.label?.take(60)}'" else chosen.description

            if (chosen.canCommit) {
                // Enter is judged by the field it lands in, which "Press Enter" alone does not name.
                val field = typedInto?.takeIf { chosen.kind == Candidate.Kind.KEY }
                val target = field?.let { chosen.copy(description = "Press Enter in '${it.label?.take(60)}'", element = it) } ?: chosen
                // Fails closed: an unassessed risk asks (and stops a headless run). The model's flag
                // describes its own pick, never an alternative the person chose.
                val assessed = decision.risk.takeIf { chosenBy == StepRecord.ChosenBy.MODEL }
                    ?: decider.risk(ctx, target).getOrNull()?.also { (_, cost) ->
                        _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + cost) }
                    }?.first
                // The label check can only raise a model's answer: a chat model grades its own pick
                // from page text, so a misleading page could otherwise talk it past a Delete. Enter
                // after text the model wrote (or instruction words outside a search box) could post it.
                val label = if (Candidates.soundsCommitting(target) || (field != null && typedCommits)) 1.0 else 0.0
                val risk = when {
                    assessed != null -> maxOf(assessed, label)
                    label > 0 -> label
                    // The person picked it from the list, and nothing on it says it commits.
                    chosenBy == StepRecord.ChosenBy.USER -> 0.0
                    else -> null
                }
                if ((risk == null || risk >= limits.confirmAbove) && waitFor(PendingQuestion.Confirm(target, risk)) != Answer.Proceed) {
                    return finish(RunStatus.STOPPED, "Stopped before \"${target.description}\"")
                }
            }
            val action = chosen.action ?: return finish(RunStatus.FAILED, "'${chosen.description}' has nothing to perform")

            // The model's runners-up describe its pick, not one the person made.
            val runnersUp = if (chosenBy == StepRecord.ChosenBy.USER) emptyList() else decision.alternatives.mapNotNull { (k, p) ->
                candidates.firstOrNull { it.key == k }?.let { it.description to p }
            }
            val record = StepRecord(stepNo, description, decision.confidence, runnersUp, valueSource = source, chosenBy = chosenBy)
            _state.update { it.copy(steps = it.steps + record) }

            val (ok, error, navigated, noBrowser) = act(action.copy(value = value), allowSensitive = chosen.element?.sensitive == true && fromInstruction)
            if (chosen.needsValue && ok) {
                typedInto = chosen.element
                typedCommits = when (source) {
                    StepRecord.ValueSource.MODEL -> true
                    // The person's own words, but the field was the model's pick: only a search box is exempt.
                    StepRecord.ValueSource.PHRASE -> chosen.element?.let(Candidates::isSearchField) != true
                    else -> false
                }
            } else if (navigated) {
                typedInto = null
                typedCommits = false
            }
            _state.update { s ->
                s.copy(steps = s.steps.map {
                    if (it.index == stepNo) it.copy(outcome = if (ok) StepRecord.Outcome.OK else StepRecord.Outcome.FAILED, detail = error) else it
                })
            }
            if (noBrowser) return finish(RunStatus.FAILED, "Stopped at step $stepNo: $NO_BROWSER_HINT")
            // Let the page finish rendering before the next look: scripts often rebuild widgets on load.
            delay(if (navigated) limits.navSettleMs else limits.stepSettleMs)
            if (ok) {
                failures = 0
                history += if (error != null) "$description ($error)" else description
            } else if (++failures >= limits.maxConsecutiveFailures) {
                return finish(RunStatus.FAILED, "Stopped at step $stepNo after $failures failed tries: $error. Nothing else was clicked.")
            } else {
                history += "$description (failed: $error)"
            }
        }
        return finish(RunStatus.STOPPED, "Reached the ${limits.maxSteps}-step limit before the task was complete")
    }

    /**
     * Text for a type step that came without one: the decider picks from [options] if it can and is
     * sure, else the person does. Null once the run has finished (stopped, or nothing to offer).
     */
    private suspend fun pickText(ctx: StepContext, field: String, options: List<String>, stepNo: Int): Pair<String, StepRecord.ValueSource>? {
        if (options.isEmpty()) return stop(RunStatus.STOPPED, "Needs text to type into '$field'. Put it in quotes in the instruction.")
        fun sourceOf(v: String) = if (v in values) StepRecord.ValueSource.QUOTED else StepRecord.ValueSource.PHRASE
        val asked = decider.chooseText(ctx, field, options)
        var reason = "The model gave no text for '$field'"
        if (asked != null) {
            _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + (asked.getOrNull()?.costUsd ?: 0.0)) }
            val choice = asked.getOrNull()
            val pick = choice?.index?.let(options::getOrNull)
            if (pick != null && choice.confidence >= limits.askBelow) return pick to sourceOf(pick)
            reason = when {
                choice == null -> "${decider.option.providerName} could not pick the text (${asked.exceptionOrNull()?.message})"
                pick == null -> "${decider.option.providerName} found none of these fit '$field'"
                else -> "${decider.option.providerName} is only ${pct(choice.confidence)} sure of '${pick.take(60)}'"
            }
        }
        return when (val a = waitFor(PendingQuestion.ChooseText(reason, field, options))) {
            is Answer.Text -> a.value.takeIf { it in options }?.let { it to StepRecord.ValueSource.USER }
                ?: stop(RunStatus.STOPPED, "Stopped at step $stepNo: that text is not one of the options")
            else -> stop(
                RunStatus.STOPPED,
                "Stopped at step $stepNo: Not sure which text from the instruction goes into '$field' ($reason). " +
                    "It could be ${options.take(8).joinToString(", ") { "'${it.take(60)}'" }}. Put the text to type in quotes.",
            )
        }
    }

    private suspend fun observe(): PageSnapshot? {
        val reply = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", checkNotNull(tabId) { "No tab to observe" }) })
        if (reply.isError || reply.json == null) {
            val why = if (reply.errorCode == ToolNames.NO_BROWSER) NO_BROWSER_HINT else reply.errorMessage
            finish(RunStatus.FAILED, "Could not read the page: $why")
            return null
        }
        return PageSnapshot.parse(reply.json!!)
    }

    /** What one `rpa_step` did. [detail] is the error on failure, or a note such as the saved file. */
    private data class StepResult(val ok: Boolean, val detail: String?, val navigated: Boolean, val noBrowser: Boolean = false)

    private suspend fun act(action: StepAction, allowSensitive: Boolean): StepResult {
        val reply = tools.invoke(ToolNames.STEP, buildJsonObject {
            put("tab_id", checkNotNull(tabId) { "No tab to act on" })
            // RPA Engine refuses typing into a sensitive field without this; older engines ignore it.
            if (allowSensitive) put("allow_sensitive", true)
            putJsonObject("action") {
                put("type", action.type)
                action.selector?.let { s -> putJsonObject("selector") { put("type", s.type); s.value?.let { put("value", it) } } }
                action.value?.let { put("value", it) }
            }
        })
        if (reply.isError) {
            val noBrowser = reply.errorCode == ToolNames.NO_BROWSER
            return StepResult(false, if (noBrowser) NO_BROWSER_HINT else reply.errorMessage, false, noBrowser)
        }
        val ok = (reply.json?.get("ok") as? JsonPrimitive)?.booleanOrNull ?: false
        val navigated = (reply.json?.get("navigated") as? JsonPrimitive)?.booleanOrNull ?: false
        val error = (reply.json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
        // A download names the file it saved; the timeline shows it as the step's detail.
        val saved = ((reply.json?.get("download") as? JsonObject)?.get("file") as? JsonPrimitive)?.content
        return StepResult(ok, if (ok) saved?.let { "Saved $it" } else error, navigated)
    }

    private suspend fun waitFor(q: PendingQuestion): Answer {
        // Start asking before the question is shown, so an answer the moment it appears has a
        // place to land: ask runs up to its first suspension, then the question is published.
        val answer = coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { ask(q) }
            _state.update { it.copy(status = RunStatus.WAITING, question = q, lastQuestion = q) }
            pending.await()
        }
        _state.update { it.copy(status = RunStatus.RUNNING, question = null) }
        return answer
    }

    private fun finish(status: RunStatus, summary: String): RunState {
        _state.update { it.copy(status = status, summary = summary, question = null) }
        return state.value
    }

    /** Ends a live run that ran past [limitMs]. */
    fun timedOut(limitMs: Long) {
        if (_state.value.status == RunStatus.RUNNING || _state.value.status == RunStatus.WAITING) {
            interrupt("Interrupted by the time limit; it may or may not have happened")
            finish(RunStatus.STOPPED, "Stopped at step ${_state.value.steps.size.coerceAtLeast(1)}: the run reached its ${limitMs / 60_000}-minute limit")
        }
    }

    /** Called when the coroutine is cancelled by Stop. */
    fun markStopped() {
        if (_state.value.status == RunStatus.RUNNING || _state.value.status == RunStatus.WAITING) {
            interrupt("Stopped while running; it may or may not have happened")
            finish(RunStatus.STOPPED, "Stopped by you at step ${_state.value.steps.size.coerceAtLeast(1)}")
        }
    }

    /** A step cut off mid-action is not left reading as still running. */
    private fun interrupt(detail: String) = _state.update { s ->
        s.copy(steps = s.steps.map { if (it.outcome == StepRecord.Outcome.RUNNING) it.copy(outcome = StepRecord.Outcome.FAILED, detail = detail) else it })
    }

    companion object {
        /**
         * RPA Engine's code before a new tab is registered; retried silently. A cross-repo contract,
         * read from rpaengine `TabActions.kt` (`TabErrorCodes`, 1.3): a rename degrades to reporting
         * the raw message, never to a wrong result.
         */
        internal const val TAB_NOT_FOUND = "TAB_NOT_FOUND"

        fun pct(p: Double): String = "${(p * 100).toInt()}%"
    }
}

/** The panel's [TaskRunner] ask: suspends until a button press answers it. One per panel. */
internal class PanelAsker {
    // Atomic: the run asks off the UI thread and the panel answers on it.
    private val pending = AtomicReference<CompletableDeferred<Answer>?>(null)
    suspend fun ask(q: PendingQuestion): Answer =
        CompletableDeferred<Answer>().also { pending.getAndSet(it)?.complete(Answer.Stop) }.await()
    fun answer(a: Answer) { pending.getAndSet(null)?.complete(a) }
}
