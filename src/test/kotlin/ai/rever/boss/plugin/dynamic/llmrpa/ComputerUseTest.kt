package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiAgentResult
import ai.rever.boss.plugin.api.AiBudget
import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.AiReply
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ComputerUseTest {
    private fun reply(op: String, p: Double = 0.9, box: String? = """{"top":0.2,"bottom":0.3,"left":0.4,"right":0.6}""", scroll: String = "medium", cost: Double = 0.01) =
        """{"response":{"answers":{"operation":{"type":"choice","choice":"$op","probabilities":{"$op":$p},"confidence":$p},""" +
            """"target":{"type":"box","box":${box ?: "null"},"confidence":0.8},"scroll":{"type":"choice","choice":"$scroll"}},"usage":{"cost":$cost}}}"""

    // ---- parsing and mapping ----

    @Test
    fun `a decision maps the box centre to css pixels`() {
        val d = Cu.parseDecision(json(reply("CLICK")))
        assertEquals(VisionOp.CLICK, d.op)
        assertEquals(0.9, d.confidence)
        assertEquals(0.01, d.costUsd)
        assertEquals("medium", d.scroll)
        // centre (0.5, 0.25) on a 1120 x 780 viewport
        assertEquals(560 to 195, Cu.clickPoint(d.box, 1120, 780))
        assertEquals(50, Cu.percent(d.box!!.centreX))
        assertEquals(25, Cu.percent(d.box.centreY))
    }

    @Test
    fun `a box outside the screenshot or inverted is no click point`() {
        assertNull(Cu.clickPoint(Box(0.2, 0.3, 0.6, 0.4), 1000, 800))
        assertNull(Cu.clickPoint(Box(0.2, 1.2, 0.4, 0.6), 1000, 800))
        assertNull(Cu.clickPoint(null, 1000, 800))
        // The far edge stays inside the viewport.
        assertEquals(999 to 799, Cu.clickPoint(Box(0.99999, 1.0, 0.99999, 1.0), 1000, 800).let { it })
    }

    @Test
    fun `an unknown operation or a missing answer is an error not a guess`() {
        assertTrue(runCatching { Cu.parseDecision(json(reply("DANCE"))) }.isFailure)
        assertTrue(runCatching { Cu.parseDecision(json("""{"response":{"answers":{}}}""")) }.isFailure)
    }

    @Test
    fun `the request carries the screenshot by path, a choice and a box question`() {
        val steps = listOf(UseStep(1, VisionOp.CLICK, xPct = 10, yPct = 20, confidence = 0.9, ms = 5, changed = true))
        val args = Cu.decideArgs("typesafe/jev-1.14", "find x", "https://a.example/", "A", steps.map(Cu::recentAction), "/tmp/s.jpg")
        assertEquals(Cu.SHOT_REF, args["state"]!!.jsonObject["screenshot"]!!.jsonPrimitive.content)
        assertEquals("/tmp/s.jpg", args["assets"]!!.jsonObject[Cu.SHOT_REF]!!.jsonObject["path"]!!.jsonPrimitive.content)
        val q = args["questions"]!!.jsonObject
        assertEquals("choice", q["operation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Cu.OPERATIONS.keys.toList(), q["operation"]!!.jsonObject["criteria"]!!.jsonObject.keys.toList())
        assertEquals("box", q["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("criteria" in q["target"]!!.jsonObject)
        assertEquals(listOf("small", "medium", "large"), q["scroll"]!!.jsonObject["criteria"]!!.jsonObject.keys.toList())
        assertEquals(1, args["state"]!!.jsonObject["recent_actions"]!!.jsonArray.size)
    }

    @Test
    fun `scroll amounts are fractions of the viewport height and up is negative`() {
        assertEquals(195, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "small", 780))
        assertEquals(390, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "medium", 780))
        assertEquals(-702, Cu.scrollDelta(VisionOp.SCROLL_UP, "large", 780))
        assertEquals(390, Cu.scrollDelta(VisionOp.SCROLL_DOWN, null, 780))
    }

    @Test
    fun `only jev 1_14 or later is a vision model`() {
        assertTrue(Cu.supportsVision("typesafe/jev-1.14"))
        assertTrue(Cu.supportsVision("typesafe/jev-2"))
        assertFalse(Cu.supportsVision("typesafe/jev-1.13"))
        assertFalse(Cu.supportsVision("openai/gpt-5"))
        assertNotNull(Cu.visionProblem("typesafe/jev-1.13", null))
        assertNull(Cu.visionProblem("typesafe/jev-1.14", null))
        assertNotNull(Cu.visionProblem("typesafe/jev-1.14", """{"properties":{"model":{}}}"""))
        assertNull(Cu.visionProblem("typesafe/jev-1.14", """{"properties":{"assets":{}}}"""))
    }

    // ---- stop rules ----

    private fun step(op: VisionOp, changed: Boolean?) = UseStep(1, op, confidence = 0.9, ms = 1, changed = changed)

    @Test
    fun `three actions that changed nothing stall the run, a leading wait or unknown does not`() {
        assertTrue(Cu.stalled(List(3) { step(VisionOp.CLICK, false) }))
        assertFalse(Cu.stalled(listOf(step(VisionOp.CLICK, false), step(VisionOp.CLICK, false))))
        assertFalse(Cu.stalled(listOf(step(VisionOp.CLICK, false), step(VisionOp.CLICK, true), step(VisionOp.CLICK, false))))
        assertFalse(Cu.stalled(listOf(step(VisionOp.WAIT, false), step(VisionOp.CLICK, false), step(VisionOp.CLICK, false))))
        assertFalse(Cu.stalled(listOf(step(VisionOp.CLICK, false), step(VisionOp.CLICK, false), step(VisionOp.CLICK, null))))
    }

    @Test
    fun `confidence stats are per operation`() {
        val stats = Cu.confidenceStats(listOf(
            UseStep(1, VisionOp.CLICK, confidence = 0.5, ms = 1), UseStep(2, VisionOp.CLICK, confidence = 0.9, ms = 1),
            UseStep(3, VisionOp.CLICK, confidence = 0.7, ms = 1), UseStep(4, VisionOp.SCROLL_DOWN, confidence = 0.8, ms = 1),
        )).associateBy { it.op }
        assertEquals(3, stats.getValue(VisionOp.CLICK).n)
        assertEquals(0.5, stats.getValue(VisionOp.CLICK).min)
        assertEquals(0.7, stats.getValue(VisionOp.CLICK).median)
        assertEquals(0.8, stats.getValue(VisionOp.SCROLL_DOWN).median)
    }

    // ---- plan, report, browser_use arguments ----

    @Test
    fun `a plan keeps the first pages, the goal and the planned text, and falls back to the task`() {
        val p = Cu.parsePlan("""Sure: {"open":[{"url":"https://www.google.com/search?q=x"},"https://b.example"],"jev_goal":"List 10 places","answer_needed":true,"type_values":["Palo Alto"]}""", "task")
        assertEquals(listOf("https://www.google.com/search?q=x", "https://b.example"), p.open)
        assertEquals("List 10 places", p.goal)
        assertTrue(p.answerNeeded)
        assertEquals(listOf("Palo Alto"), p.typeValues)
        val bare = Cu.parsePlan("""{"open":[]}""", "do it")
        assertEquals("do it", bare.goal)
        assertTrue(runCatching { Cu.parsePlan("no json", "t") }.isFailure)
    }

    @Test
    fun `a report needs a summary and an answer may be null`() {
        assertEquals("ok" to "A", Cu.parseReport("""{"summary":"ok","answer":"A"}"""))
        assertEquals("ok" to null, Cu.parseReport("""{"summary":"ok","answer":null}"""))
        assertTrue(runCatching { Cu.parseReport("""{"answer":"A"}""") }.isFailure)
    }

    @Test
    fun `browser_use arguments are validated and clamped`() {
        val a = BrowserUseArgs.parse("""{"task":"find x"}""").getOrThrow()
        assertEquals("vision", a.mode); assertEquals("Jev", a.displayName); assertEquals(25, a.maxSteps); assertEquals(180, a.timeoutS); assertTrue(a.includeText); assertFalse(a.newTab)
        val b = BrowserUseArgs.parse("""{"task":"t","max_steps":500,"timeout_s":1,"display_name":"Max","new_tab":true,"mode":"dom","tab_id":"t9"}""").getOrThrow()
        assertEquals(60, b.maxSteps); assertEquals(10, b.timeoutS); assertEquals("Max", b.displayName); assertTrue(b.newTab); assertEquals("dom", b.mode); assertEquals("t9", b.tabId)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","bogus":1}""").exceptionOrNull()!!.message!!.contains("bogus"))
        assertTrue(BrowserUseArgs.parse("""{}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","mode":"x"}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","new_tab":"yes"}""").isFailure)
        assertTrue(BrowserUseArgs.parse("[1]").isFailure)
    }

    @Test
    fun `the browser_use schema is closed and lists every argument`() {
        val schema = json(LlmrpaMcpToolProvider.BROWSER_USE_SCHEMA)
        assertEquals(false, (schema["additionalProperties"] as JsonPrimitive).content.toBoolean())
        assertEquals(setOf("task", "url", "tab_id", "new_tab", "mode", "model", "planner_model", "display_name", "max_steps", "timeout_s", "include_text"),
            schema["properties"]!!.jsonObject.keys)
        assertEquals("task", schema["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    // ---- the whole flow, against fakes ----

    private class FakeBrowser(val dir: File, val script: List<String>, val textPick: String? = "Palo Alto") : ToolInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var jevCalls = 0
        var shots = 0
        /** The screenshot content; clicks change it unless [frozen]. */
        var frozen = false
        var focused = """{"tag":"input","editable":true,"label":"Search"}"""
        override fun has(toolName: String) = true
        override fun inputSchema(toolName: String) = if (toolName == ToolNames.JEV_DECIDE) """{"properties":{"model":{"enum":["typesafe/jev-1.14"]},"assets":{}}}""" else null

        override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
            val a = arguments.jsonObject
            calls += toolName to a
            return when (toolName) {
                ToolNames.OBSERVE -> ToolReply("""{"tab_id":"t1","url":"https://sample.com/","title":"S","elements":[]}""", false)
                ToolNames.STEP -> {
                    val action = a["action"]!!.jsonObject
                    when (action["type"]!!.jsonPrimitive.content) {
                        "screenshot" -> {
                            val f = File(dir, "shot-${if (frozen) 0 else shots++}.jpg").also { it.writeText("shot ${if (frozen) 0 else shots}") }
                            ToolReply("""{"ok":true,"screenshot":{"path":"${f.absolutePath}","width":560,"height":390,"css_width":1120,"css_height":780,"dpr":2}}""", false)
                        }
                        "focused" -> ToolReply("""{"ok":true,"focused":$focused}""", false)
                        "page_text" -> ToolReply("""{"ok":true,"text":"Best: A, B, C"}""", false)
                        else -> ToolReply("""{"ok":true}""", false)
                    }
                }
                ToolNames.JEV_DECIDE -> {
                    val q = a["questions"]!!.jsonObject
                    if ("text" in q) {
                        val criteria = q["text"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                        val key = criteria.entries.firstOrNull { it.value == textPick }?.key ?: JevDecider.NONE_OF_THESE
                        ToolReply("""{"response":{"answers":{"text":{"type":"choice","choice":"$key","probabilities":{"$key":0.95}}},"usage":{"cost":0.001}}}""", false)
                    } else {
                        val op = script[jevCalls.coerceAtMost(script.lastIndex)]
                        jevCalls++
                        val p = if (op.endsWith("@low")) 0.3 else 0.9
                        ToolReply(reply(op.removeSuffix("@low"), p), false)
                    }
                }
                else -> ToolReply("unknown", true)
            }
        }
        private fun reply(op: String, p: Double) =
            """{"response":{"answers":{"operation":{"type":"choice","choice":"$op","probabilities":{"$op":$p}},""" +
                """"target":{"type":"box","box":{"top":0.2,"bottom":0.3,"left":0.4,"right":0.6},"confidence":0.8},"scroll":{"type":"choice","choice":"small"}},"usage":{"cost":0.01}}}"""
        fun stepTypes() = calls.filter { it.first == ToolNames.STEP }.map { it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        fun stepArgs(type: String) = calls.filter { it.first == ToolNames.STEP && it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content == type }.map { it.second }
    }

    private class FakeTabs : UseTabs {
        val opened = mutableListOf<String>()
        val released = mutableListOf<String>()
        override suspend fun open(url: String): String? { opened += url; return "t1" }
        override fun claim(tabId: String): String? = null
        override fun release(tabId: String) { released += tabId }
    }

    private fun gateway(requests: MutableList<AiRequest>, planner: String, report: String) = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> {
            requests += request
            return Result.success(AiReply(if (request.system.startsWith("You plan")) planner else report))
        }
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = emptySet()
        override fun activeModel(): AiModelInfo? = AiModelInfo("OPENAI", "OpenAI", "gpt-5")
    }

    private val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Search Palo Alto restaurants and read the top ones","answer_needed":true,"type_values":["Palo Alto"]}"""
    private val reportJson = """{"summary":"Searched and read the list.","answer":"A, B, C"}"""

    private fun flow(browser: FakeBrowser, tabs: FakeTabs, requests: MutableList<AiRequest>, plan: String = this.plan) =
        ComputerUseFlow(browser, { gateway(requests, plan, reportJson) }, tabs, openWaitsMs = listOf(0), settleMs = 0, stepSettleMs = 0)

    private fun tmp() = java.nio.file.Files.createTempDirectory("cu").toFile().also { it.deleteOnExit() }

    @Test
    fun `plan, open, type, enter, done and a report with the answer`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK", "TYPE_TEXT", "PRESS_ENTER", "SCROLL_DOWN", "DONE"))
        val tabs = FakeTabs()
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, tabs, requests).runComputerUse(UseRequest("find top places", pointerLabel = "Max", maxSteps = 10))

        assertEquals(UseStatus.DONE, report.status)
        assertTrue(report.ok)
        assertEquals(listOf("https://sample.com/"), tabs.opened)
        assertEquals(listOf("t1"), tabs.released)
        assertEquals(listOf(VisionOp.CLICK, VisionOp.TYPE_TEXT, VisionOp.PRESS_ENTER, VisionOp.SCROLL_DOWN), report.steps.map { it.op })
        // click_at lands on the box centre of the 1120 x 780 viewport, with the pointer label
        val clicks = browser.stepArgs("click_at")
        assertEquals("560,195", clicks.first()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Max", clicks.first()["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("Palo Alto", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("195", browser.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Enter", browser.stepArgs("key").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        // The screenshot, and nothing with image data, is what the activity records.
        assertEquals("A, B, C", report.answer)
        assertEquals(6, report.jevCalls) // five operations and the choice of text for the field
        assertEquals(1, requests.count { it.system.startsWith("You plan") })
        assertEquals(1, requests.count { it.system.startsWith("You write") })
        assertEquals(2, report.llmCalls)
        assertNotNull(report.costUsd)
        assertEquals("Best: A, B, C", report.pageText)
        assertNotNull(report.screenshotPath)
        val j = report.toJson()
        assertEquals("done", j["status"]!!.jsonPrimitive.content)
        assertTrue(j["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4, j["steps"]!!.jsonArray.size)
        assertTrue("page_text" in j)
        assertFalse("page_text" in report.toJson(includeText = false))
    }

    @Test
    fun `three unchanged screenshots block the run`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK")).also { it.frozen = true }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(UseStatus.BLOCKED, report.status)
        assertEquals(3, report.steps.size)
        assertTrue(report.error!!.contains("changed nothing"))
    }

    @Test
    fun `the step budget ends the run as budget`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN"))
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 3))
        assertEquals(UseStatus.BUDGET, report.status)
        assertEquals(3, report.steps.size)
        assertFalse(report.ok)
    }

    @Test
    fun `blocked from jev is reported as blocked`() = runTest {
        val report = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.BLOCKED, report.status)
        assertTrue(report.steps.isEmpty())
    }

    @Test
    fun `a headless run proceeds when unsure and an interactive one asks first`() = runTest {
        val low = FakeBrowser(tmp(), listOf("SCROLL_DOWN@low", "DONE"))
        val headless = flow(low, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", confirm = null))
        assertEquals(UseStatus.DONE, headless.status)
        assertEquals(1, headless.steps.size)

        val asked = mutableListOf<Double>()
        val declined = flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN@low", "DONE")), FakeTabs(), mutableListOf())
            .runComputerUse(UseRequest("t", confirm = { _, p -> asked += p; false }))
        assertEquals(listOf(0.3), asked)
        assertEquals(UseStatus.BLOCKED, declined.status)
        assertTrue(declined.steps.isEmpty())
    }

    @Test
    fun `a click on something that is not a text field types nothing`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE")).also { it.focused = """{"tag":"div","editable":false,"label":""}""" }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(0, browser.stepArgs("type_text").size)
        assertTrue(report.steps.single().note!!.contains("did not land on a text field"))
    }

    @Test
    fun `a private field takes only given text and is masked in the record`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = null).also { it.focused = """{"tag":"input","editable":true,"label":"Password","type":"password"}""" }
        val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Sign in","answer_needed":false,"type_values":["hunter2"]}"""
        val report = flow(browser, FakeTabs(), mutableListOf(), plan).runComputerUse(UseRequest("t"))
        assertEquals("hunter2", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(Secrets.MASK, report.steps.single().text)
        assertNull(report.answer)
    }

    @Test
    fun `a model that cannot read screenshots is refused before anything runs`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("DONE"))
        val tabs = FakeTabs()
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", jevModel = "typesafe/jev-1.13"))
        assertEquals(UseStatus.ERROR, report.status)
        assertTrue(report.error!!.contains("jev-1.14"))
        assertTrue(tabs.opened.isEmpty())
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `an unreadable plan ends the run before a tab is opened`() = runTest {
        val tabs = FakeTabs()
        val report = flow(FakeBrowser(tmp(), listOf("DONE")), tabs, mutableListOf(), plan = "I cannot").runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.ERROR, report.status)
        assertTrue(report.error!!.startsWith("Could not plan"))
        assertTrue(tabs.opened.isEmpty())
    }

    @Test
    fun `a given url replaces the planned page and the activity log has no image data`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("DONE"))
        val tabs = FakeTabs()
        val run = UseRun("t")
        flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", url = "https://given.com/x"), run)
        assertEquals(listOf("https://given.com/x"), tabs.opened)
        val entries = run.log.entries.value
        assertTrue(entries.any { it.kind == ActivityKind.JEV && it.name == ToolNames.JEV_DECIDE })
        assertTrue(entries.any { it.kind == ActivityKind.LLM })
        assertTrue(entries.none { it.args.contains("base64") || it.args.length > 300 })
        assertEquals(entries.size, entries.map { it.n }.toSet().size)
        assertEquals(UsePhase.FINISHED, run.phase.value)
    }

    @Test
    fun `a tab that is given is claimed, navigated and not opened`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("DONE"))
        val tabs = FakeTabs()
        flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", tabId = "t7"))
        assertTrue(tabs.opened.isEmpty())
        assertEquals("t7", browser.stepArgs("navigate").single()["tab_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `screenshot args summarise without image data`() {
        val args = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x/shot.jpg")
        assertTrue(RecordingTools.summarise(ToolNames.JEV_DECIDE, args).contains("image=shot.jpg"))
        val inline = kotlinx.serialization.json.buildJsonObject { put("data_base64", "A".repeat(5000)) }
        assertFalse(RecordingTools.summarise("other", inline).contains("AAAA"))
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
