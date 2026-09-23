package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * MCP tools contributed by the LLM RPA plugin.
 *
 * `llmrpa_execute` runs a task headless and needs no panel. `llmrpa_run` (draft steps) and
 * `llmrpa_status` act on the most recently opened panel, as they always have, and report that
 * when none is open.
 */
internal class LlmrpaMcpToolProvider(
    override val providerId: String,
    private val component: () -> LlmrpaComponent?,
    private val headless: HeadlessRunner,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "llmrpa_execute",
            description =
                "Do a browser task from a plain-language instruction, one step at a time, on an open tab. " +
                    "A model (Jev by default, or any configured chat model) picks each step and RPA Engine performs it. " +
                    "Stops instead of guessing when the model is unsure or the next action looks irreversible, and returns " +
                    "the steps taken. Put any text to type in quotes in the instruction. Each step is a paid model call.",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{""" +
                """"instruction":{"type":"string","description":"The task, e.g. Search for 'wireless keyboard' and open the first result"},""" +
                """"tab_id":{"type":"string","description":"Browser tab to act on; defaults to the first open browser tab"},""" +
                """"max_steps":{"type":"integer","minimum":1,"maximum":50,"default":12},""" +
                """"model":{"type":"string","description":"Model id, e.g. typesafe/jev-1.13 or a chat model id; defaults to Jev"}""" +
                """},"required":["instruction"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val root = runCatching { Json.parseToJsonElement(args.raw) as JsonObject }.getOrNull()
                    ?: return@McpToolHandler McpToolResult("Arguments must be a JSON object", isError = true)
                val instruction = (root["instruction"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@McpToolHandler McpToolResult("Missing required argument: instruction", isError = true)
                val maxSteps = ((root["max_steps"] as? JsonPrimitive)?.intOrNull ?: RunLimits().maxSteps).coerceIn(1, 50)
                headless.execute(
                    instruction,
                    (root["tab_id"] as? JsonPrimitive)?.contentOrNull,
                    maxSteps,
                    (root["model"] as? JsonPrimitive)?.contentOrNull,
                ).fold(
                    onSuccess = { McpToolResult(transcript(it).toString(), isError = it.status == RunStatus.FAILED) },
                    onFailure = { McpToolResult(it.message ?: "Could not run the task", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "llmrpa_status",
            description =
                "Report the LLM RPA panel's state: the live run (status, steps, summary) and the last drafted plan.",
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                // The last history entry, not just errorMessage. errorMessage only carries a
                // *save* failure, so a generation that produced nothing runnable reported
                // "error=none" - an agent polling this could not tell it had failed at all.
                val last = c.executionHistory.value.lastOrNull()
                val run = c.run.value
                McpToolResult(
                    "run=${run?.status?.name ?: "none"} steps=${run?.steps?.size ?: 0} " +
                        "summary=${run?.summary ?: "none"}\n" +
                        "draft: generating=${c.isGenerating.value} history=${c.executionHistory.value.size} " +
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
            description =
                "Draft steps: ask the selected chat model to write an RPA plan for an instruction and save it for RPA Engine. " +
                    "Does not act on the page; use llmrpa_execute to do the task.",
            inputSchema = """{"type":"object","properties":{"instruction":{"type":"string","description":"What to automate, in natural language."}},"required":["instruction"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val c = component() ?: return@McpToolHandler notOpen()
                val instruction = args.string("instruction")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: instruction", isError = true)
                c.updateInstruction(instruction)
                // Relay the refusal. This reported "Generating..." unconditionally, so an agent
                // then polled llmrpa_status and read the *previous* run's result.
                val refusal = c.generateActions()
                if (refusal == null) {
                    McpToolResult("Drafting RPA steps for: $instruction")
                } else {
                    McpToolResult(refusal, isError = true)
                }
            },
        ),
    )

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the LLM RPA panel first (no active instance).", isError = true)

    companion object {
        internal fun transcript(state: RunState) = buildJsonObject {
            put("status", state.status.name.lowercase())
            put("summary", state.summary)
            put("model", state.modelLabel)
            put("model_calls", state.calls)
            // Chat models report tokens, not money, through the gateway; leave cost out rather than say $0.
            if (state.costUsd > 0) put("cost_usd", state.costUsd)
            put("steps", buildJsonArray {
                state.steps.forEach { s ->
                    add(buildJsonObject {
                        put("step", s.index)
                        put("action", s.description)
                        put("confidence", s.confidence)
                        put("result", s.outcome.name.lowercase())
                        s.detail?.let { put("error", it) }
                    })
                }
            })
        }
    }
}
