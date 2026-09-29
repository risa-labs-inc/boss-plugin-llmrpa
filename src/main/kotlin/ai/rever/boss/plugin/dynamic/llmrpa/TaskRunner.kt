package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    val valueWritten: Boolean = false,
    val chosenBy: ChosenBy = ChosenBy.MODEL,
) {
    enum class Outcome { RUNNING, OK, FAILED }
    enum class ChosenBy { MODEL, USER }
}

/** What the runner needs from the person before it can continue. */
sealed interface PendingQuestion {
    /** The model was unsure; [options] are its best picks with their confidence. */
    data class Choose(val reason: String, val options: List<Pair<Candidate, Double>>) : PendingQuestion

    /** The chosen action looks irreversible. [risk] is null when it could not be assessed. */
    data class Confirm(val action: Candidate, val risk: Double?) : PendingQuestion
}

sealed interface Answer {
    data class Pick(val candidate: Candidate) : Answer
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
)

data class RunLimits(
    val maxSteps: Int = 12,
    /** Below this confidence the runner asks instead of acting. */
    val askBelow: Double = 0.6,
    /** At or above this risk the runner asks for confirmation. */
    val confirmAbove: Double = 0.5,
    val maxConsecutiveFailures: Int = 2,
)

/**
 * Observe → decide → act, one step at a time, on one tab. RPA Engine does every browser action
 * (`rpa_observe`, `rpa_step`); [decider] picks each step. Questions for the person go through
 * [ask], which the panel answers with buttons and a headless caller answers with [Answer.Stop].
 */
class TaskRunner(
    private val tools: ToolInvoker,
    private val decider: StepDecider,
    private val tabId: String,
    private val instruction: String,
    private val limits: RunLimits = RunLimits(),
    clock: () -> Long = System::currentTimeMillis,
    private val ask: suspend (PendingQuestion) -> Answer,
) {
    private val _state = MutableStateFlow(RunState(instruction, decider.option.label, limits.maxSteps, startedAt = clock()))
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val values = Candidates.values(instruction)

    /**
     * Never throws except on cancellation: anything a decider or tool raises across the plugin
     * boundary ends the run as FAILED with the reason, not as "Stopped by you".
     */
    suspend fun run(): RunState = try {
        loop()
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

    private suspend fun loop(): RunState {
        val history = mutableListOf<String>()
        var failures = 0
        var doneRejected = false
        // Counted in actions taken, so a rejected done check does not use up the budget.
        while (_state.value.steps.size < limits.maxSteps) {
            val stepNo = _state.value.steps.size + 1
            val page = observe() ?: return state.value
            // Where the last step landed, so the model can tell a search results page from the article.
            if (history.isNotEmpty() && !history.last().contains(" → now on ")) {
                history[history.lastIndex] = "${history.last()} → now on '${page.title.take(80)}'"
            }
            val candidates = Candidates.build(page, instruction)
            val ctx = StepContext(instruction, page, candidates, values, history.toList())

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
                // A model can claim success on the wrong page. Check once, with the page named.
                val verified = decider.verifyDone(ctx).getOrNull()?.also { (_, cost) ->
                    _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + cost) }
                }?.first
                if (verified == null || verified >= limits.confirmAbove || doneRejected) {
                    val sure = verified ?: decision.confidence
                    val n = _state.value.steps.size
                    return finish(
                        if (verified != null && verified < limits.confirmAbove) RunStatus.STOPPED else RunStatus.DONE,
                        if (verified != null && verified < limits.confirmAbove) "Stopped: the model says the task is done, but the page ('${page.title}') does not look like it."
                        else "Done in $n ${if (n == 1) "step" else "steps"}. ${pct(sure)} sure the task is complete.",
                    )
                }
                doneRejected = true
                history += "Checked whether the task is complete: not yet (the page is '${page.title.take(80)}')"
                continue
            }

            // Typing needs text; a decision model only ever types what the instruction gave.
            var value = chosen.action?.value
            // The model's value only describes the model's own pick.
            val modelValue = decision.value.takeIf { chosenBy == StepRecord.ChosenBy.MODEL }
            if (chosen.needsValue) {
                value = modelValue ?: values.singleOrNull()
                    ?: return finish(RunStatus.STOPPED, "Needs text to type into ${chosen.element?.label ?: "the field"}. Put it in quotes in the instruction.")
            }
            val fromInstruction = value != null && value in values
            if (chosen.needsValue && chosen.element?.sensitive == true && !fromInstruction) {
                return finish(RunStatus.STOPPED, "Stopped before typing into the private field '${chosen.element?.label?.take(60)}': only text from your instruction goes there.")
            }
            val description = if (chosen.needsValue) "Type '${value!!.take(60)}' into '${chosen.element?.label?.take(60)}'" else chosen.description

            if (chosen.canCommit) {
                // Fails closed: an unassessed risk asks (and stops a headless run). The model's flag
                // describes its own pick, never an alternative the person chose.
                val assessed = decision.risk.takeIf { chosenBy == StepRecord.ChosenBy.MODEL }
                    ?: decider.risk(ctx, chosen).getOrNull()?.also { (_, cost) ->
                        _state.update { it.copy(calls = it.calls + 1, costUsd = it.costUsd + cost) }
                    }?.first
                val risk = assessed ?: if (Candidates.soundsCommitting(chosen)) 1.0 else null
                if ((risk == null || risk >= limits.confirmAbove) && waitFor(PendingQuestion.Confirm(chosen, risk)) != Answer.Proceed) {
                    return finish(RunStatus.STOPPED, "Stopped before \"${chosen.description}\"")
                }
            }

            val record = StepRecord(stepNo, description, decision.confidence, decision.alternatives.mapNotNull { (k, p) ->
                candidates.firstOrNull { it.key == k }?.let { it.description to p }
            }, valueWritten = chosen.needsValue && !fromInstruction, chosenBy = chosenBy)
            _state.update { it.copy(steps = it.steps + record) }

            val (ok, error, navigated) = act(chosen.action!!.copy(value = value), allowSensitive = chosen.element?.sensitive == true && fromInstruction)
            // Let the page finish rendering before the next look: scripts often rebuild widgets on load.
            delay(if (navigated) NAV_SETTLE_MS else STEP_SETTLE_MS)
            _state.update { s ->
                s.copy(steps = s.steps.map {
                    if (it.index == stepNo) it.copy(outcome = if (ok) StepRecord.Outcome.OK else StepRecord.Outcome.FAILED, detail = error) else it
                })
            }
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

    private suspend fun observe(): PageSnapshot? {
        val reply = tools.invoke(ToolNames.OBSERVE, buildJsonObject { put("tab_id", tabId) })
        if (reply.isError || reply.json == null) {
            finish(RunStatus.FAILED, "Could not read the page: ${reply.errorMessage}")
            return null
        }
        return PageSnapshot.parse(reply.json!!)
    }

    private suspend fun act(action: StepAction, allowSensitive: Boolean): Triple<Boolean, String?, Boolean> {
        val reply = tools.invoke(ToolNames.STEP, buildJsonObject {
            put("tab_id", tabId)
            // RPA Engine refuses typing into a sensitive field without this; older engines ignore it.
            if (allowSensitive) put("allow_sensitive", true)
            putJsonObject("action") {
                put("type", action.type)
                action.selector?.let { s -> putJsonObject("selector") { put("type", s.type); s.value?.let { put("value", it) } } }
                action.value?.let { put("value", it) }
            }
        })
        if (reply.isError) return Triple(false, reply.errorMessage, false)
        val ok = (reply.json?.get("ok") as? JsonPrimitive)?.booleanOrNull ?: false
        val navigated = (reply.json?.get("navigated") as? JsonPrimitive)?.booleanOrNull ?: false
        val error = (reply.json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
        // A download names the file it saved; the timeline shows it as the step's detail.
        val saved = ((reply.json?.get("download") as? JsonObject)?.get("file") as? JsonPrimitive)?.content
        return Triple(ok, if (ok) saved?.let { "Saved $it" } else error, navigated)
    }

    private suspend fun waitFor(q: PendingQuestion): Answer {
        _state.update { it.copy(status = RunStatus.WAITING, question = q, lastQuestion = q) }
        val answer = ask(q)
        _state.update { it.copy(status = RunStatus.RUNNING, question = null) }
        return answer
    }

    private fun finish(status: RunStatus, summary: String): RunState {
        _state.update { it.copy(status = status, summary = summary, question = null) }
        return state.value
    }

    /** Called when the coroutine is cancelled by Stop. */
    fun markStopped() {
        if (_state.value.status == RunStatus.RUNNING || _state.value.status == RunStatus.WAITING) {
            finish(RunStatus.STOPPED, "Stopped by you at step ${_state.value.steps.size.coerceAtLeast(1)}")
        }
    }

    companion object {
        /** Settle times after a step; tests set them to zero. */
        internal var NAV_SETTLE_MS = 900L
        internal var STEP_SETTLE_MS = 250L

        fun pct(p: Double): String = "${(p * 100).toInt()}%"

        /** An [ask] that the panel completes from a button press. */
        class Asker {
            // Atomic: ask and answer run on Main today, but answer is public and Stop may come from elsewhere.
            private val pending = AtomicReference<CompletableDeferred<Answer>?>(null)
            suspend fun ask(q: PendingQuestion): Answer = CompletableDeferred<Answer>().also { pending.set(it) }.await()
            fun answer(a: Answer) { pending.getAndSet(null)?.complete(a) }
        }
    }
}
