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
    val configuration: List<RpaActionConfig>,
    val status: String,
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
fun LLMRpaResponse.runnablePlan(): List<RpaActionConfig>? =
    configuration.takeIf { status == "success" && it.isNotEmpty() }
/**
 * RPA Action Configuration
 */
@Serializable
data class RpaActionConfig(
    val name: String,
    val action_type: String = "default",
    val type: String,
    val selector: SelectorInfo,
    val value: String? = null,
    val meta: Map<String, String>? = null
)

/**
 * Selector information for RPA actions
 */
@Serializable
data class SelectorInfo(
    val type: String,
    val value: String?,
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
