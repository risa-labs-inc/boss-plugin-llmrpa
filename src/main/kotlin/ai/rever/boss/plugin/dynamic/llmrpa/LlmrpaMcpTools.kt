package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools contributed by the LLM RPA plugin: submit a natural-language
 * instruction for the LLM to turn into RPA actions, and read status.
 *
 * Actions live on the per-panel [LlmrpaComponent], so these tools operate on the
 * most recently opened LLM RPA panel (via [component]); if none is open they
 * report that. Registered in [LlmrpaDynamicPlugin.register]; removed
 * automatically on disable/unload.
 */
internal class LlmrpaMcpToolProvider(
    override val providerId: String,
    private val component: () -> LlmrpaComponent?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "llmrpa_status",
            description =
                "Report LLM RPA status: whether it is generating, the last generation's outcome " +
                    "and where its plan was written, the current instruction and history size.",
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                // The last history entry, not just errorMessage. errorMessage only carries a
                // *save* failure, so a generation that produced nothing runnable reported
                // "error=none" - an agent polling this could not tell it had failed at all.
                val last = c.executionHistory.value.lastOrNull()
                McpToolResult(
                    "generating=${c.isGenerating.value} history=${c.executionHistory.value.size} " +
                        "last=${last?.status?.name ?: "none"} " +
                        "actions=${last?.generatedActions?.size ?: 0}\n" +
                        "plan=${c.handoffPath.value ?: "not written"}\n" +
                        "message=${last?.message ?: "none"}\n" +
                        "error=${last?.error ?: c.errorMessage.value ?: "none"}\n" +
                        "instruction=${c.currentInstruction.value}"
                )
            },
        ),
        McpToolDefinition(
            name = "llmrpa_run",
            description = "Set a natural-language instruction and ask the LLM to generate RPA actions for it.",
            inputSchema = """{"type":"object","properties":{"instruction":{"type":"string","description":"What to automate, in natural language."}},"required":["instruction"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val c = component() ?: return@McpToolHandler notOpen()
                val instruction = args.string("instruction")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: instruction", isError = true)
                c.updateInstruction(instruction)
                c.generateActions()
                McpToolResult("Generating RPA actions for: $instruction")
            },
        ),
    )

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the LLM RPA panel first (no active instance).", isError = true)
}
