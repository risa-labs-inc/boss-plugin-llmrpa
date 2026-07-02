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
            description = "Report LLM RPA status (generating?, current instruction, history size, last error).",
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                McpToolResult(
                    "generating=${c.isGenerating.value} history=${c.executionHistory.value.size} " +
                        "error=${c.errorMessage.value ?: "none"}\ninstruction=${c.currentInstruction.value}"
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
