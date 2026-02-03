package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.Serializable

/**
 * LLM Provider options
 */
enum class LLMProvider(val displayName: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    TOGETHER("Together AI"),
    CUSTOM("Custom API")
}

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

/**
 * LLM Model information
 */
data class LLMModel(
    val id: String,
    val name: String,
    val provider: LLMProvider,
    val contextWindow: Int = 4096
)

/**
 * Available LLM models
 */
object LLMModels {
    val anthropicModels = listOf(
        LLMModel("claude-opus-4-20250514", "Claude Opus 4", LLMProvider.ANTHROPIC, 200000),
        LLMModel("claude-sonnet-4-20250514", "Claude Sonnet 4", LLMProvider.ANTHROPIC, 200000),
        LLMModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", LLMProvider.ANTHROPIC, 200000),
        LLMModel("claude-3-5-sonnet-20240620", "Claude 3.5 Sonnet", LLMProvider.ANTHROPIC, 200000),
        LLMModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", LLMProvider.ANTHROPIC, 200000)
    )

    val openaiModels = listOf(
        LLMModel("gpt-4o", "GPT-4o", LLMProvider.OPENAI, 128000),
        LLMModel("gpt-4o-mini", "GPT-4o Mini", LLMProvider.OPENAI, 128000),
        LLMModel("gpt-4-turbo", "GPT-4 Turbo", LLMProvider.OPENAI, 128000),
        LLMModel("gpt-3.5-turbo", "GPT-3.5 Turbo", LLMProvider.OPENAI, 16000)
    )

    val togetherModels = listOf(
        LLMModel("meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo", "Llama 3.1 405B", LLMProvider.TOGETHER, 128000),
        LLMModel("meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo", "Llama 3.1 70B", LLMProvider.TOGETHER, 128000),
        LLMModel("mistralai/Mixtral-8x22B-Instruct-v0.1", "Mixtral 8x22B", LLMProvider.TOGETHER, 65536)
    )

    fun getModelsForProvider(provider: LLMProvider): List<LLMModel> = when (provider) {
        LLMProvider.ANTHROPIC -> anthropicModels
        LLMProvider.OPENAI -> openaiModels
        LLMProvider.TOGETHER -> togetherModels
        LLMProvider.CUSTOM -> emptyList()
    }

    fun findModelById(id: String): LLMModel? {
        return (anthropicModels + openaiModels + togetherModels).find { it.id == id }
    }
}
