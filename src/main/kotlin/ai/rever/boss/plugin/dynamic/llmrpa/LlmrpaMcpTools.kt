package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    private val runs: RunHistory,
    /** Where `llmrpa_export` writes; tests pass a temp directory. */
    private val exportDir: java.io.File = RpaEngineHandoff.defaultConfigDir,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "llmrpa_execute",
            description =
                "Do a browser task from a plain-language instruction, one step at a time, on an open tab or a new one. It acts in the user's " +
                    "real, logged-in browser session, as them, and can open any web address the instruction contains. " +
                    "By default it uses the focused tab. With new_tab: true it opens a new tab in the space on screen at the first " +
                    "address in the instruction, else one the model picks (https only), else a DuckDuckGo search for the instruction text " +
                    "with its quoted values, emails and addresses removed; " +
                    "start_url names that address instead and is opened as given, with no host filtering (as an address in the instruction is). " +
                    "Only tabs in the space on screen can be driven. " +
                    "A model (Jev by default, or any configured chat model) picks each step and RPA Engine performs it. " +
                    "Stops instead of guessing when the model is unsure or the next action looks irreversible, and returns " +
                    "the steps taken. Put any text to type in quotes in the instruction; with none quoted, words from the instruction " +
                    "(e.g. a search term) may be typed into a field that is not private. Each step is one to three paid model calls, " +
                    "and a run stops after 10 minutes.",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{""" +
                """"instruction":{"type":"string","description":"The task, e.g. Search for 'wireless keyboard' and open the first result"},""" +
                """"tab_id":{"type":"string","description":"Browser tab to act on; defaults to the focused tab; required when no tab is focused (the error lists the open ones and which can be driven)"},""" +
                """"new_tab":{"type":"boolean","default":false,"description":"Open the right page in a new tab first instead of using an open tab"},""" +
                """"start_url":{"type":"string","description":"http(s) address to open in a new tab first; implies new_tab"},""" +
                """"max_steps":{"type":"integer","minimum":1,"maximum":50,"default":12},""" +
                """"model":{"type":"string","description":"Model id, e.g. typesafe/jev-1.13 or a chat model id; defaults to Jev"},""" +
                """"include_calls":{"type":"boolean","default":false,"description":"Add every model call in full: request, response, parsed pick, latency, cost (private text masked)"},""" +
                """"verbose":{"type":"boolean","default":false,"description":"Same as include_calls"}""" +
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
                    (root["tab_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    maxSteps,
                    (root["model"] as? JsonPrimitive)?.contentOrNull,
                    newTab = (root["new_tab"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    startUrl = (root["start_url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                ).fold(
                    onSuccess = { McpToolResult(transcript(it, includeCalls(root)).toString(), isError = it.status == RunStatus.FAILED) },
                    onFailure = { McpToolResult(it.message ?: "Could not run the task", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "llmrpa_status",
            description =
                "Report the LLM RPA panel's state: the live run (status, steps, summary, model calls per step) and the last drafted plan. " +
                    "include_calls: true adds each model call in full.",
            inputSchema = """{"type":"object","properties":{""" +
                """"include_calls":{"type":"boolean","default":false,"description":"Add every model call of the run in full: request, response, parsed pick, latency, cost (private text masked)"},""" +
                """"verbose":{"type":"boolean","default":false,"description":"Same as include_calls"}}}""",
            handler = McpToolHandler { args ->
                val c = component() ?: return@McpToolHandler notOpen()
                // The last history entry, not just errorMessage. errorMessage only carries a
                // *save* failure, so a generation that produced nothing runnable reported
                // "error=none" - an agent polling this could not tell it had failed at all.
                val last = c.executionHistory.value.lastOrNull()
                val run = c.run.value
                McpToolResult(
                    "ready=${c.blocker()?.name ?: "yes"} model=${c.selectedModel.value?.key ?: "none"} " +
                        "models=${c.modelGroups.value.sumOf { it.models.size }} " +
                        "tools=${listOf(ToolNames.OBSERVE, ToolNames.STEP, ToolNames.JEV_DECIDE).filter { c.tools.has(it) }}\n" +
                    "run=${run?.status?.name ?: "none"} steps=${run?.steps?.size ?: 0} " +
                        "summary=${run?.summary ?: "none"}\n" +
                        "draft: generating=${c.isGenerating.value} history=${c.executionHistory.value.size} " +
                        "last=${last?.status?.name ?: "none"} " +
                        "actions=${last?.generatedActions?.size ?: 0}\n" +
                        "plan=${c.handoffPath.value ?: "not written"}\n" +
                        "message=${last?.message ?: "none"}\n" +
                        "error=${last?.error ?: c.errorMessage.value ?: "none"}\n" +
                        "instruction=${c.currentInstruction.value}" +
                        (run?.let { "\nrun_detail=" + transcript(it, includeCalls(parse(args.raw))) } ?: "")
                )
            },
        ),
        McpToolDefinition(
            name = "llmrpa_export",
            description =
                "Save a finished LLM RPA run as an RPA Engine configuration: a navigate to where it started, then each step that worked, " +
                    "in ~/.boss/config/rpaengine as a new file (never replacing one). Downloads are left out (RPA Engine plans cannot download), " +
                    "and a private field's text is never written: that step types nothing and the description says so. " +
                    "Addresses from the instruction are kept; a tab the run found open is written without its query and fragment. " +
                    "Works for a done run, or a stopped or failed one with at least one step that worked. Returns the path and the action count.",
            inputSchema = """{"type":"object","properties":{""" +
                """"run":{"type":"integer","minimum":1,"default":1,"description":"Which finished run, newest first: 1 is the last one"}}}""",
            readOnly = false,
            handler = McpToolHandler { args -> export(args) },
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

    private suspend fun export(args: McpToolArgs): McpToolResult {
        val root = parse(args.raw) ?: return McpToolResult("Arguments must be a JSON object", isError = true)
        val recent = runs.recent()
        if (recent.isEmpty()) return McpToolResult("No run has finished yet. Run a task first (llmrpa_execute or the panel).", isError = true)
        val n = (root["run"] as? JsonPrimitive)?.intOrNull ?: 1
        val run = recent.getOrNull(n - 1)
            ?: return McpToolResult("There is no run $n. Finished runs, newest first: ${listing(recent)}", isError = true)
        if (!RpaEngineHandoff.exportable(run)) {
            return McpToolResult("Run $n (${run.status.name.lowercase()}) has no step that worked to export. Finished runs: ${listing(recent)}", isError = true)
        }
        return withContext(Dispatchers.IO) { RpaEngineHandoff.exportRun(run, exportDir) }.fold(
            onSuccess = { e ->
                McpToolResult(buildJsonObject {
                    put("path", e.file.absolutePath)
                    put("name", e.name)
                    put("actions", e.actionCount)
                    if (e.notes.isNotEmpty()) put("notes", buildJsonArray { e.notes.forEach { add(JsonPrimitive(it)) } })
                    put("hint", "Load it in RPA Engine with rpa_load and this name, then rpa_run.")
                }.toString())
            },
            onFailure = { McpToolResult("Could not export: ${it.message ?: it::class.simpleName}", isError = true) },
        )
    }

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the LLM RPA panel first (no active instance).", isError = true)

    companion object {
        private fun parse(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) as JsonObject }.getOrNull()

        private fun includeCalls(root: JsonObject?): Boolean =
            listOf("include_calls", "verbose").any { (root?.get(it) as? JsonPrimitive)?.booleanOrNull == true }

        private fun listing(runs: List<RunState>): String = runs.take(10).withIndex().joinToString { (i, r) ->
            // Scrubbed like an export's name: a run that stopped early never learned which value was private.
            "${i + 1}: ${r.status.name.lowercase()} '${Candidates.scrub(r.instruction, Secrets.MASK).take(50)}' (${r.steps.count { it.outcome == StepRecord.Outcome.OK }} steps ok)"
        }

        /** A step's model calls in one line each: what was asked, the pick, how sure, how long. */
        private fun callSummary(c: ModelCall) = buildJsonObject {
            put("kind", c.kind.name.lowercase())
            c.pick?.let { put("pick", it.take(120)) }
            c.confidence?.let { put("confidence", it) }
            c.risk?.let { put("risk", it) }
            put("latency_ms", c.latencyMs)
            c.costUsd?.takeIf { it > 0 }?.let { put("cost_usd", it) }
            c.error?.let { put("error", it.take(200)) }
        }

        internal fun callDetail(c: ModelCall) = buildJsonObject {
            put("step", c.step)
            put("kind", c.kind.name.lowercase())
            put("tool", c.tool)
            put("model", c.model)
            if (c.questions.isNotEmpty()) put("questions", buildJsonArray {
                c.questions.forEach { q ->
                    add(buildJsonObject {
                        put("id", q.id)
                        put("question", q.text)
                        q.pick?.let { put("pick", it) }
                        put("options", buildJsonArray {
                            q.options.forEach { o -> add(buildJsonObject { put("key", o.key); put("label", o.label); o.probability?.let { put("p", it) } }) }
                        })
                    })
                }
            })
            // An older run's text is let go to save memory; saying so reads better than an empty string.
            if (c.request.dropped) put("text_dropped", ModelCall.TEXT_DROPPED) else {
                put("request", c.request.text)
                if (c.request.truncated) put("request_truncated", true)
                c.response?.let { put("response", it.text); if (it.truncated) put("response_truncated", true) }
            }
            c.pick?.let { put("pick", it) }
            c.confidence?.let { put("confidence", it) }
            c.risk?.let { put("risk", it) }
            put("latency_ms", c.latencyMs)
            c.costUsd?.let { put("cost_usd", it) }
            c.inputTokens?.let { put("input_tokens", it) }
            c.outputTokens?.let { put("output_tokens", it) }
            c.error?.let { put("error", it) }
        }

        internal fun transcript(state: RunState, includeCalls: Boolean = false) = buildJsonObject {
            put("status", state.status.name.lowercase())
            put("summary", state.summary)
            put("model", state.modelLabel)
            put("model_calls", state.calls)
            // Chat models report tokens, not money, through the gateway; leave cost out rather than say $0.
            if (state.costUsd > 0) put("cost_usd", state.costUsd)
            state.opened?.let { o ->
                put("opened", buildJsonObject {
                    state.tabId?.let { put("tab_id", it) }
                    put("url", o.url)
                    put("chosen_by", o.source.label)
                    o.note?.let { put("note", it) }
                })
            }
            when (val q = state.lastQuestion.takeIf { state.status == RunStatus.STOPPED }) {
                is PendingQuestion.Choose -> put("stopped_at_question", buildJsonObject {
                    put("reason", q.reason)
                    put("options", buildJsonArray {
                        q.options.forEach { (c, p) -> add(buildJsonObject { put("action", c.description); put("confidence", p) }) }
                    })
                    put("hint", "Rerun with a more specific instruction, or run it in the LLM RPA panel to choose.")
                })
                is PendingQuestion.Confirm -> put("stopped_at_question", buildJsonObject {
                    put("reason", if (q.risk == null) "Could not tell whether the next action can be undone" else "The next action looks irreversible")
                    put("action", q.action.description)
                    q.risk?.let { put("risk", it) }
                })
                is PendingQuestion.ChooseText -> put("stopped_at_question", buildJsonObject {
                    put("reason", q.reason)
                    put("field", q.field)
                    put("text_options", buildJsonArray { q.options.forEach { add(JsonPrimitive(if (Candidates.isKeywordSecret(state.instruction, it)) "••••••" else it)) } })
                    put("hint", "Put the text to type in quotes in the instruction, or run it in the LLM RPA panel to choose.")
                })
                null -> Unit
            }
            put("steps", buildJsonArray {
                state.steps.forEach { s ->
                    add(buildJsonObject {
                        put("step", s.index)
                        put("action", s.description)
                        put("confidence", s.confidence)
                        put("result", s.outcome.name.lowercase())
                        s.valueSource?.let { put("text_source", it.name.lowercase()) }
                        s.detail?.let { put(if (s.outcome == StepRecord.Outcome.FAILED) "error" else "detail", it) }
                        val calls = state.modelCalls.filter { it.step == s.index }
                        if (calls.isNotEmpty()) put("model_calls", buildJsonArray { calls.forEach { add(callSummary(it)) } })
                    })
                }
            })
            // Calls that led to no step: choosing the start page (step 0), and the last decision and done check.
            val stepless = state.modelCalls.filter { c -> state.steps.none { it.index == c.step } }
            if (stepless.isNotEmpty()) put("other_model_calls", buildJsonArray { stepless.forEach { c -> add(buildJsonObject { put("step", c.step); callSummary(c).forEach { (k, v) -> put(k, v) } }) } })
            if (includeCalls) put("calls", buildJsonArray { state.modelCalls.forEach { add(callDetail(it)) } })
        }
    }
}
