package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.LlmProvider

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
) {
    suspend fun execute(instruction: String, tabId: String?, maxSteps: Int, model: String?): Result<RunState> {
        if (!tools.has(ToolNames.OBSERVE) || !tools.has(ToolNames.STEP)) {
            return Result.failure(IllegalStateException("RPA Engine with rpa_observe and rpa_step is not installed"))
        }
        val browserTabs = tabs().filter { it.url != null }
        val tab = if (tabId != null) {
            browserTabs.firstOrNull { it.tabId == tabId } ?: return Result.failure(IllegalArgumentException("No browser tab with id $tabId"))
        } else {
            browserTabs.firstOrNull() ?: return Result.failure(IllegalStateException("No browser tab is open"))
        }
        val models = ModelDirectory(tools, llmProvider, gateway).load().flatMap { it.models }
        val option = when {
            model == null -> models.firstOrNull { it.kind == ModelOption.Kind.DECISION } ?: models.firstOrNull()
            else -> models.firstOrNull { it.modelId == model || it.key == model }
        } ?: return Result.failure(
            IllegalArgumentException(
                if (model == null) "No model is available. Install Jev or add a provider in Settings → AI Providers."
                else "Unknown model '$model'. Available: ${models.take(25).joinToString { it.modelId }}${if (models.size > 25) ", …" else ""}",
            ),
        )
        val decider = if (option.kind == ModelOption.Kind.DECISION) JevDecider(tools, option) else ChatDecider(gateway, option)
        val runner = TaskRunner(tools, decider, tab.tabId, instruction, RunLimits(maxSteps = maxSteps)) { Answer.Stop }
        return Result.success(runner.run())
    }
}
