package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.Serializable

/**
 * LLM Action instruction
 */
@Serializable
data class LLMAction(
    val instruction: String,
    val actionType: String = "default",
    val meta: Map<String, String>? = null
)

/**
 * LLM RPA Request
 */
@Serializable
data class LLMRpaRequest(
    val actions: List<LLMAction>,
    val sourceUrl: String,
    val configuration: List<RpaActionConfig>? = null
)

/**
 * LLM RPA Response
 */
@Serializable
data class LLMRpaResponse(
    // Stays REQUIRED, deliberately: it is what makes the candidate loop in `decodeFirstObject`
    // discriminate. Defaulted, a braced aside like `{"foo":1}` would decode cleanly under
    // ignoreUnknownKeys and be returned as the first "successful" candidate - an empty plan.
    val configuration: List<RpaActionConfig>,
    // Defaulted for the same reason SelectorInfo's fields are: a reply of
    // `{"configuration":[...ten good actions...],"message":"..."}` with no status key threw
    // MissingFieldException and the whole plan was discarded. runnablePlan() is already built to
    // tolerate a status it does not recognise.
    val status: String = "success",
    val message: String? = null
)

/**
 * The actions this response can actually be run as, or null when it cannot.
 *
 * Both halves matter and both were once missing. A parse failure reports `status = "error"` while
 * still carrying whatever it managed to salvage, and callers that checked only for a non-empty
 * list treated that as a plan: the panel showed READY and the plan was written to disk as a
 * runnable configuration. One predicate, so the panel state, the error text and what reaches disk
 * cannot disagree.
 */
internal fun LLMRpaResponse.runnablePlan(): List<RpaActionConfig>? =
    // Inverted deliberately: anything that is not a known *failure* and carries actions is
    // runnable. Requiring exactly "success" meant "ok", "completed" or "done" - all plausible from
    // a model told to write "success" - discarded a perfect plan. Status drift is this plugin's
    // premise, so the failure statuses are the closed set, not the success one.
    configuration.takeIf { status !in NON_RUNNABLE_STATUSES && it.isNotEmpty() }

/**
 * Statuses that mean "there is no plan here".
 *
 * Two of these the plugin sets itself; the rest are what a model says when it could not do what was
 * asked. Without them a reply of `"status":"failed"` with a partial or apologetic action list was
 * runnable - written to disk under the user's instruction, with the card saying ready to run.
 */
private val NON_RUNNABLE_STATUSES =
    setOf("error", LlmApiClient.STATUS_EXAMPLE, "failed", "failure", "unable", "invalid", "refused")

/**
 * RPA Action Configuration
 */
@Serializable
data class RpaActionConfig(
    val name: String = "",
    val action_type: String = "default",
    // `type` stays required: it is the verb, and an action without one is genuinely not an action.
    val type: String,
    // Defaulted for the same reason as SelectorInfo's fields - an action with no selector at all
    // (navigate, wait, submit) is normal output, and nothing in the prompt makes a model emit a
    // key it has nothing to put in.
    val selector: SelectorInfo = SelectorInfo(),
    val value: String? = null,
    val meta: Map<String, String>? = null
)

/**
 * Selector information for RPA actions
 */
@Serializable
data class SelectorInfo(
    // Defaults on every field, because kotlinx treats a nullable field with no default as
    // REQUIRED. `"selector":{"type":"none"}` - a plausible thing for a model to emit for navigate
    // or wait - threw MissingFieldException, so a reply carrying a perfectly good plan was
    // reported as a parse error and discarded. Same failure class as the greedy-brace bug.
    val type: String = "none",
    val value: String? = null,
    val isUnique: Boolean = true
)

/**
 * Execution state for LLM RPA
 */
data class LLMExecutionState(
    val instruction: String,
    val status: LLMExecutionStatus,
    val generatedActions: List<RpaActionConfig> = emptyList(),
    val error: String? = null,
    /** The model's own explanation of what the plan does. Was passed around and then discarded. */
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Execution status
 */
enum class LLMExecutionStatus {
    GENERATING,
    READY,
    COMPLETED,
    ERROR
}
