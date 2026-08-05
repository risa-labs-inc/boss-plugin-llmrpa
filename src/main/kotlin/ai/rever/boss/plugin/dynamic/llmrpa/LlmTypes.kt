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
