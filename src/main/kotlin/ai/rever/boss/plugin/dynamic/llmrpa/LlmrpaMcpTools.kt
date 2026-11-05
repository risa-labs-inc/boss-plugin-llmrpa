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
import kotlinx.serialization.json.doubleOrNull
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
    private val runLog: RunLogStore,
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
            name = BROWSER_USE,
            description =
                "Do a task in the user's real, logged-in browser by looking at the page, like a person. In the default agent mode one vision chat model " +
                    "(agent_model; Claude Haiku by default) decides each step from a marked screenshot and the element list, and RPA Engine clicks, types and scrolls " +
                    "with a visible pointer; it asks (status needs_input, blocker.question and blocker.options) for logins, codes, choices and before paying. To answer, call again " +
                    "with the same tab_id and inputs {\"reply\":\"...\"} (or a new named input, or {\"continue\":\"yes\"}): the same run resumes with its history. Secret inputs are typed as {{name}} and never sent to the model. " +
                    "mode fast is the older Jev loop (a chat model plans the goal and page, Jev clicks from screenshots). " +
                    "The result reports what happened plus the final page text. Use it to research or operate a website. " +
                    "The user sees the run: the tab is opened or brought to the front in the space on screen and a labelled pointer moves over it. " +
                    "It opens a new tab at the planned page (or url), or acts on tab_id; only tabs in the space on screen can be driven. " +
                    "The run keeps its tab in front while it works: if the tab is hidden the screenshot brings it back first (a hidden tab's screen is stale), so the user should not switch away. " +
                    "It never waits for a person. When it is stuck (no progress, Jev blocked or unsure) it stops with status needs_input and a blocker, leaving the tab open: " +
                    "call it again with the same tab_id, task and inputs (named values such as phone or code) to continue where it stopped; when the page asks for a login, a code or payment (status needs_input, nothing is consulted) the person does it in the tab and you call again with inputs {\"continue\":\"yes\"} to resume without replanning; with on_stuck consult it asks the planner chat model what to do first. " +
                    "Information on the pages it passes is saved in collected[]. " +
                    "A long task does not block the call: after wait_s (default 45 s) it returns {ok:false, status:\"running\", run_id, tab_id, steps so far} and the run goes on in the background; poll browser_use_status with run_id until it returns the final report. " +
                    "Returns JSON {ok, status (done|needs_input|blocked|budget|error|running), run_id, summary (built from the steps, not a model's opinion), url, title, tab_id, page_text, collected[] (text saved from pages), pages_visited[], blocker {reason, question, url, title, tab_id, screenshot_path, step} when it stopped, answer (only with extract), steps, seconds, jev_calls, " +
                    "llm_calls, tool_calls, cost_usd, confidences, screenshot_path}; ok only means Jev reached its goal, so read the answer from page_text yourself.",
            inputSchema = BROWSER_USE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val a = BrowserUseArgs.parse(args.raw).getOrElse { return@McpToolHandler McpToolResult(it.message ?: "Invalid arguments", isError = true) }
                val result = if (a.mode == "dom") {
                    headless.browserUseDomWaiting(a).map { it.toString() }
                } else {
                    headless.browserUse(a).map { o ->
                        when (o) {
                            is HeadlessRunner.UseOutcome.Finished -> o.report.toJson(a.includeText).toString()
                            is HeadlessRunner.UseOutcome.Running -> UseReport.running(o.run).toString()
                        }
                    }
                }
                result.fold(
                    onSuccess = { McpToolResult(it) },
                    onFailure = { McpToolResult(it.message ?: "Could not run the task", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = BROWSER_USE_STATUS,
            description =
                "Read the log of browser_use and Jev-tab runs (they are kept in ~/.boss/cache/llmrpa/runs, the newest 50). A browser_use call that returned status running is read here: " +
                    "with its run_id you get {status:\"running\", steps so far} while it goes on, then the final report in browser_use's shape, plus the detailed log under \"record\" " +
                    "(task, plan, input names, status, every step with its tab, screenshot, consult, popup and dialog notes, the report, tool-call counts and cost). " +
                    "Without: the live run, if any, and a list of the last runs. Use it to see where a run stopped or went wrong. Private text is masked; no image data is stored, only screenshot paths.",
            inputSchema = BROWSER_USE_STATUS_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val root = runCatching { Json.parseToJsonElement(args.raw.ifBlank { "{}" }) as JsonObject }.getOrNull()
                    ?: return@McpToolHandler McpToolResult("Arguments must be a JSON object", isError = true)
                val unknown = root.keys - setOf("run_id", "limit", "include_activity")
                if (unknown.isNotEmpty()) return@McpToolHandler McpToolResult("Unknown argument ${unknown.sorted().joinToString()}. Allowed: run_id, limit, include_activity", isError = true)
                val limit = ((root["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 50)
                val withActivity = (root["include_activity"] as? JsonPrimitive)?.booleanOrNull ?: false
                val id = (root["run_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (id != null) {
                    val rec = runLog.read(id) ?: return@McpToolHandler McpToolResult(
                        "No run '$id'. Recent runs: ${runLog.list(10).joinToString { (it["run_id"] as? JsonPrimitive)?.content.orEmpty() }.ifEmpty { "none" }}", isError = true)
                    // A run still going: where it is. A finished one: the report exactly as browser_use returns it, with the log's detail under "record".
                    val liveRun = runLog.liveRuns().firstOrNull { it.id == id }
                    val report = (rec["report"] as? JsonObject)
                    val head = when {
                        liveRun != null -> UseReport.running(liveRun)
                        report != null -> report
                        else -> null
                    }
                    McpToolResult(if (head == null) statusRecord(rec, withActivity).toString() else buildJsonObject {
                        head.forEach { (k, v) -> put(k, v) }
                        put("record", statusRecord(JsonObject(rec.filterKeys { it != "report" }), withActivity))
                    }.toString())
                } else {
                    val live = runLog.liveRuns().lastOrNull()?.let { runLog.record(it) }
                    McpToolResult(buildJsonObject {
                        if (live != null) put("live", statusRecord(live, withActivity)) else put("live", kotlinx.serialization.json.JsonNull)
                        put("runs", buildJsonArray { runLog.list(limit).forEach { add(it) } })
                    }.toString())
                }
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
                        "jev_run=${runLog.liveRuns().lastOrNull()?.let { "${it.id} (running)" } ?: runLog.list(1).firstOrNull()?.let { r -> "${(r["run_id"] as? JsonPrimitive)?.content} (${(r["status"] as? JsonPrimitive)?.content})" } ?: "none"} (see browser_use_status)\n" +
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
        internal const val BROWSER_USE = "browser_use"
        internal const val BROWSER_USE_STATUS = "browser_use_status"

        internal const val BROWSER_USE_STATUS_SCHEMA = """{"type":"object","additionalProperties":false,"properties":{""" +
            """"run_id":{"type":"string","description":"The run_id browser_use returned; omit to see the live run and the latest runs"},""" +
            """"limit":{"type":"integer","minimum":1,"maximum":50,"default":5,"description":"How many recent runs to list"},""" +
            """"include_activity":{"type":"boolean","default":false,"description":"Add every tool call (rpa_step, jev_decide, gateway chat) with timing, result and cost"}}}"""

        /** A run's record with the activity list cut to its count unless [withActivity]. */
        internal fun statusRecord(rec: JsonObject, withActivity: Boolean): JsonObject = buildJsonObject {
            rec.forEach { (k, v) ->
                if (k == "activity" && !withActivity) put("activity_count", (v as? kotlinx.serialization.json.JsonArray)?.size ?: 0) else put(k, v)
            }
        }

        internal const val BROWSER_USE_SCHEMA = """{"type":"object","additionalProperties":false,"properties":{""" +
            """"task":{"type":"string","description":"What to do or find, in plain language, e.g. find the top 10 restaurants in Palo Alto"},""" +
            """"url":{"type":"string","description":"http(s) address to open in a new tab; replaces the page the planner would choose (the goal is still planned)"},""" +
            """"tab_id":{"type":"string","description":"Browser tab to act on (explicit; giving it skips planning). Without tab_id the run plans and opens a new tab (as with new_tab: true); the focused tab is never used"},""" +
            """"new_tab":{"type":"boolean","default":false,"description":"Open the planned page in a new tab. The default whenever tab_id is not given"},""" +
            """"mode":{"type":"string","enum":["agent","fast","dom"],"default":"agent","description":"agent: one vision chat model decides each step from a marked screenshot and the element list (general); fast: the Jev loop with milestones and page plans; dom: the text-list runner of llmrpa_execute"},""" +
            """"agent_model":{"type":"string","description":"agent mode: the chat model that decides each step; it must see images. Default: the newest Claude Haiku the AI Gateway lists, else the planner"},""" +
            """"model":{"type":"string","description":"Jev model; vision needs one that takes images and boxes (default typesafe/jev-1.14). In dom mode, any model id of llmrpa_execute"},""" +
            """"planner_model":{"type":"string","description":"Chat model id (from Settings → AI Providers) that plans the task and writes text for fields; defaults to the selected one"},""" +
            """"navigator_model":{"type":"string","description":"Fast chat model that plans each new page and advises (Claude Haiku via the AI Gateway by default; falls back to the planner when unavailable). It sees the marked screenshot when the gateway has vision"},""" +
            """"page_plan":{"type":"boolean","default":true,"description":"The navigator plans each new page once (up to 5 steps, which Jev then grounds one by one); also splits a multi-part goal when no plan was made"},""" +
            """"display_name":{"type":"string","default":"Jev","description":"Label on the on-page pointer (always shown)"},""" +
            """"on_stuck":{"type":"string","enum":["return","consult"],"default":"return","description":"When stuck: return stops with status needs_input and a blocker (the tab stays open; call again with the same tab_id, task and inputs to continue); consult asks the planner chat model what to do, up to max_consults times"},""" +
            """"consult_below":{"type":"number","minimum":0,"maximum":1,"default":0.5,"description":"Jev operation confidence below this counts as stuck"},""" +
            """"max_consults":{"type":"integer","minimum":0,"maximum":10,"default":5},""" +
            """"max_replans":{"type":"integer","minimum":0,"maximum":5,"default":2,"description":"How many times the planner may choose a new place to continue from when the page is a dead end for the next milestone"},""" +
            """"consult_model":{"type":"string","description":"Chat model that looks at the screenshot when Jev is stuck (a model that can see images). Default: the planner if it can, else the first available that can; with none, the run returns instead of consulting a model that cannot see"},""" +
            """"inputs":{"type":"object","additionalProperties":{"type":"string"},"description":"Named values for Jev to type when asked, e.g. {\"phone\":\"...\"}; names that sound private (password, code, otp, card) are only typed into private fields and masked in the record"},""" +
            """"extract":{"type":"boolean","default":false,"description":"One LLM call answers the task only from the collected and final page text and returns it as answer"},""" +
            """"plan":{"type":"boolean","description":"Ask the planner chat model for the goal and page first. Default: false when url or tab_id is given (the task is the goal), true otherwise"},""" +
            """"llm_typing":{"type":"boolean","default":true,"description":"Let the planner chat model write text for a field when nothing from the task fits (never for password, PIN or card fields)"},""" +
            """"max_steps":{"type":"integer","minimum":1,"maximum":60,"default":25},""" +
            """"timeout_s":{"type":"integer","minimum":10,"maximum":600,"default":180,"description":"The run's own time limit"},""" +
            """"wait_s":{"type":"integer","minimum":5,"maximum":600,"default":45,"description":"How long this call waits; a run still going then returns status running with its run_id (poll browser_use_status) and continues in the background"},""" +
            """"include_text":{"type":"boolean","default":true,"description":"Include the final page's text (up to 20000 characters) as page_text"}""" +
            """},"required":["task"]}"""

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

/** `browser_use` arguments, validated: an unknown key or a wrong type is refused, ranges are clamped. */
internal data class BrowserUseArgs(
    val task: String,
    val url: String?,
    val tabId: String?,
    val newTab: Boolean,
    val mode: String,
    val model: String?,
    val plannerModel: String?,
    val displayName: String,
    val llmTyping: Boolean,
    val onStuck: OnStuck,
    val consultBelow: Double,
    val maxConsults: Int,
    val consultModel: String?,
    val navigatorModel: String?,
    val agentModel: String?,
    val pagePlan: Boolean,
    val maxReplans: Int,
    val inputs: Map<String, String>,
    val extract: Boolean,
    /** Plan with the chat model; null = only when no url or tab_id was given. */
    val plan: Boolean?,
    val maxSteps: Int,
    val timeoutS: Int,
    /** How long the call waits for the run before returning status running. */
    val waitS: Int,
    val includeText: Boolean,
) {
    companion object {
        private val KEYS = setOf("task", "url", "tab_id", "new_tab", "mode", "model", "planner_model", "display_name", "llm_typing", "on_stuck", "consult_below", "max_consults", "consult_model", "navigator_model", "agent_model", "page_plan", "max_replans", "inputs", "extract", "plan", "max_steps", "timeout_s", "wait_s", "include_text")

        fun parse(raw: String): Result<BrowserUseArgs> = runCatching {
            val root = Json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject ?: error("Arguments must be a JSON object")
            val unknown = root.keys - KEYS
            require(unknown.isEmpty()) { "Unknown argument ${unknown.sorted().joinToString()}. Allowed: ${KEYS.sorted().joinToString()}" }
            fun str(k: String): String? {
                val v = root[k] ?: return null
                val p = v as? JsonPrimitive
                require(p != null && p.isString) { "'$k' must be a string" }
                return p.content.takeIf { it.isNotBlank() }
            }
            fun bool(k: String, default: Boolean): Boolean {
                val v = root[k] ?: return default
                return (v as? JsonPrimitive)?.booleanOrNull ?: error("'$k' must be a boolean")
            }
            fun int(k: String, default: Int, min: Int, max: Int): Int {
                val v = root[k] ?: return default
                return ((v as? JsonPrimitive)?.intOrNull ?: error("'$k' must be an integer")).coerceIn(min, max)
            }
            fun dbl(k: String, default: Double, min: Double, max: Double): Double {
                val v = root[k] ?: return default
                return ((v as? JsonPrimitive)?.doubleOrNull ?: error("'$k' must be a number")).coerceIn(min, max)
            }
            val stuck = str("on_stuck") ?: "return"
            require(stuck == "return" || stuck == "consult") { "'on_stuck' must be return or consult" }
            val inputs = (root["inputs"] as? JsonObject)?.let { o ->
                o.mapValues { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("inputs.$k must be a string") }
            } ?: if (root["inputs"] != null) error("'inputs' must be an object of strings") else emptyMap()
            val mode = (str("mode") ?: "agent").let { if (it == "vision") "fast" else it }
            require(mode == "agent" || mode == "fast" || mode == "dom") { "'mode' must be agent, fast or dom" }
            BrowserUseArgs(
                task = str("task") ?: error("Missing required argument: task"),
                url = str("url"), tabId = str("tab_id"), newTab = bool("new_tab", false), mode = mode,
                model = str("model"), plannerModel = str("planner_model"), displayName = str("display_name") ?: "Jev", llmTyping = bool("llm_typing", true),
                onStuck = if (stuck == "consult") OnStuck.CONSULT else OnStuck.RETURN, consultBelow = dbl("consult_below", 0.5, 0.0, 1.0),
                maxConsults = int("max_consults", 5, 0, 10), consultModel = str("consult_model"), navigatorModel = str("navigator_model"), agentModel = str("agent_model"), pagePlan = bool("page_plan", true), maxReplans = int("max_replans", 2, 0, 5), inputs = inputs, extract = bool("extract", false),
                plan = if (root["plan"] == null) null else bool("plan", false),
                maxSteps = int("max_steps", 25, 1, 60), timeoutS = int("timeout_s", 180, 10, 600), waitS = int("wait_s", 45, 5, 600), includeText = bool("include_text", true),
            )
        }
    }
}
