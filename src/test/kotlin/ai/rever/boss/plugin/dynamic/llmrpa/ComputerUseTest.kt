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
import kotlinx.serialization.json.buildJsonObject
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

    private fun seen(y: Double? = 0.0, hash: String = "h", url: String = "https://a.com/") = Seen(url, y, hash)
    private fun act(op: VisionOp, before: Seen, xPct: Int? = null, yPct: Int? = null, text: String? = null) =
        UseStep(1, op, xPct = xPct, yPct = yPct, text = text, confidence = 0.9, ms = 1, before = before)

    @Test
    fun `three actions that left the address and scroll position alone are no progress, even if pixels change`() {
        val now = seen(100.0, "video-frame-9")
        // The video hero: every frame differs, the scroll position does not.
        val steps = List(3) { act(VisionOp.SCROLL_DOWN, seen(102.0, "video-frame-$it")) }
        assertNotNull(Cu.noProgress(steps, now))
        // Within 4 px counts as the same place; 5 px is movement.
        assertNull(Cu.noProgress(List(3) { act(VisionOp.SCROLL_DOWN, seen(95.0, "f$it")) }, now))
        // Two actions are not enough, a different address is progress, typed text is progress, a leading wait is exempt.
        assertNull(Cu.noProgress(steps.take(2), now))
        assertNull(Cu.noProgress(steps.map { it.copy(before = it.before!!.copy(url = "https://b.com/")) }, now))
        assertNull(Cu.noProgress(listOf(steps[0], steps[1], steps[2].copy(op = VisionOp.TYPE_TEXT, text = "x")), now))
        assertNull(Cu.noProgress(listOf(steps[0].copy(op = VisionOp.WAIT), steps[1], steps[2]), now))
    }

    @Test
    fun `without scroll information the pixels decide`() {
        val now = seen(null, "same")
        assertNotNull(Cu.noProgress(List(3) { act(VisionOp.CLICK, seen(null, "same"), 50, 20 + it * 10) }, now))
        assertNull(Cu.noProgress(List(3) { act(VisionOp.CLICK, seen(null, "frame$it"), 50, 20 + it * 10) }, now))
    }

    @Test
    fun `scrolling down and up in turn with no new address is oscillation`() {
        val now = seen(300.0)
        val moved = listOf(0.0, 300.0, 0.0, 300.0).mapIndexed { i, y -> act(if (i % 2 == 0) VisionOp.SCROLL_DOWN else VisionOp.SCROLL_UP, seen(y, "h$i")) }
        assertTrue(Cu.noProgress(moved, now)!!.contains("down and up"))
        assertNull(Cu.noProgress(moved.take(3), now))
        // Two downs in a row is not oscillation.
        assertNull(Cu.noProgress(moved.mapIndexed { i, st -> if (i == 1) st.copy(op = VisionOp.SCROLL_DOWN) else st }, now))
        // A new address in between is progress.
        assertNull(Cu.noProgress(moved.mapIndexed { i, st -> if (i == 0) st.copy(before = st.before!!.copy(url = "https://z.com/")) else st }, now))
    }

    @Test
    fun `the same spot clicked three times with no new page is no progress`() {
        val now = seen(10.0)
        fun click(i: Int, x: Int, y: Int) = act(VisionOp.CLICK, seen(i * 50.0, "h$i"), x, y)
        assertNotNull(Cu.noProgress(listOf(click(0, 50, 25), click(1, 51, 24), click(2, 49, 26)), now))
        assertNull(Cu.noProgress(listOf(click(0, 50, 25), click(1, 60, 25), click(2, 50, 25)), now))
        assertNull(Cu.noProgress(listOf(click(0, 50, 25), click(1, 50, 25), click(2, 50, 25).copy(before = seen(100.0, "h2", "https://other.com/"))), now))
    }

    @Test
    fun `collecting and consulting are not actions on the page, but repeated collecting is a loop`() {
        val now = seen(0.0)
        val still = List(3) { act(VisionOp.CLICK, seen(0.0, "h")) }
        assertNull(Cu.noProgress(still.take(2) + act(VisionOp.COLLECT, seen(0.0, "h")), now))
        assertTrue(Cu.noProgress(List(3) { act(VisionOp.COLLECT, seen(0.0, "h")) }, now)!!.contains("collecting"))
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
        val p = Cu.parsePlan("""Sure: {"open":[{"url":"https://www.google.com/search?q=x"},"https://b.example"],"jev_goal":"List 10 places","type_values":["Palo Alto"]}""", "task")
        assertEquals(listOf("https://www.google.com/search?q=x", "https://b.example"), p.open)
        assertEquals("List 10 places", p.goal)
        assertEquals(listOf("Palo Alto"), p.typeValues)
        val bare = Cu.parsePlan("""{"open":[]}""", "do it")
        assertEquals("do it", bare.goal)
        assertTrue(runCatching { Cu.parsePlan("no json", "t") }.isFailure)
    }

    @Test
    fun `typed text is read from the first json object and must not be empty`() {
        assertEquals("Palo Alto", Cu.parseTyped("""Sure!\n```json\n{"text":" Palo Alto "}\n```\nHmm {wait}"""))
        assertTrue(runCatching { Cu.parseTyped("""{"text":""}""") }.isFailure)
        assertTrue(runCatching { Cu.parseTyped("no json") }.isFailure)
        assertEquals(Cu.TYPED_MAX, Cu.parseTyped("""{"text":"${"x".repeat(900)}"}""").length)
    }

    @Test
    fun `the summary is built from the steps and names the error when it did not finish`() {
        val steps = listOf(
            UseStep(1, VisionOp.CLICK, confidence = 0.9, ms = 1), UseStep(2, VisionOp.CLICK, confidence = 0.9, ms = 1), UseStep(3, VisionOp.CLICK, confidence = 0.9, ms = 1),
            UseStep(4, VisionOp.TYPE_TEXT, text = "Palo Alto", confidence = 0.9, ms = 1), UseStep(5, VisionOp.PRESS_ENTER, confidence = 0.9, ms = 1),
            UseStep(6, VisionOp.SCROLL_DOWN, confidence = 0.9, ms = 1), UseStep(7, VisionOp.SCROLL_UP, confidence = 0.9, ms = 1),
        )
        assertEquals(
            "Done in 7 steps (31.4 s) on 'Results' (shop.com). Clicked 3 times, typed 'Palo Alto', pressed Enter, scrolled 2 times.",
            Cu.summary(UseStatus.DONE, steps, 31.4, "Results", "https://www.shop.com/s?q=1", null),
        )
        val blocked = Cu.summary(UseStatus.BLOCKED, steps.take(1), 5.0, null, null, "3 actions in a row changed nothing on screen")
        assertEquals("Stopped: blocked after 1 step (5.0 s). 3 actions in a row changed nothing on screen. Clicked once.", blocked)
        assertTrue(Cu.summary(UseStatus.ERROR, emptyList(), 1.0, null, null, "boom").startsWith("Failed after 0 steps (1.0 s). boom."))
        assertTrue(Cu.summary(UseStatus.BUDGET, steps, 9.0, "T", "https://a.com/", "Stopped at 7 steps").startsWith("Stopped: out of budget after 7 steps"))
    }

    @Test
    fun `browser_use arguments are validated and clamped`() {
        val a = BrowserUseArgs.parse("""{"task":"find x"}""").getOrThrow()
        assertEquals("vision", a.mode); assertEquals("Jev", a.displayName); assertEquals(25, a.maxSteps); assertEquals(180, a.timeoutS); assertTrue(a.includeText); assertFalse(a.newTab); assertTrue(a.llmTyping)
        assertFalse(BrowserUseArgs.parse("""{"task":"t","llm_typing":false}""").getOrThrow().llmTyping)
        val b = BrowserUseArgs.parse("""{"task":"t","max_steps":500,"timeout_s":1,"display_name":"Max","new_tab":true,"mode":"dom","tab_id":"t9"}""").getOrThrow()
        assertEquals(60, b.maxSteps); assertEquals(10, b.timeoutS); assertEquals("Max", b.displayName); assertTrue(b.newTab); assertEquals("dom", b.mode); assertEquals("t9", b.tabId)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","bogus":1}""").exceptionOrNull()!!.message!!.contains("bogus"))
        assertTrue(BrowserUseArgs.parse("""{}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","mode":"x"}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","new_tab":"yes"}""").isFailure)
        assertTrue(BrowserUseArgs.parse("[1]").isFailure)
        // Stuck handling, inputs and extract.
        assertEquals(OnStuck.RETURN, a.onStuck); assertEquals(0.5, a.consultBelow); assertEquals(5, a.maxConsults); assertEquals(emptyMap(), a.inputs); assertFalse(a.extract)
        val c = BrowserUseArgs.parse("""{"task":"t","on_stuck":"consult","consult_below":7,"max_consults":99,"inputs":{"phone":"555"},"extract":true}""").getOrThrow()
        assertEquals(OnStuck.CONSULT, c.onStuck); assertEquals(1.0, c.consultBelow); assertEquals(10, c.maxConsults); assertEquals(mapOf("phone" to "555"), c.inputs); assertTrue(c.extract)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","on_stuck":"ask"}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","inputs":{"phone":5}}""").isFailure)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","inputs":"x"}""").isFailure)
    }

    @Test
    fun `the browser_use schema is closed and lists every argument`() {
        val schema = json(LlmrpaMcpToolProvider.BROWSER_USE_SCHEMA)
        assertEquals(false, (schema["additionalProperties"] as JsonPrimitive).content.toBoolean())
        assertEquals(setOf("task", "url", "tab_id", "new_tab", "mode", "model", "planner_model", "display_name", "max_steps", "timeout_s", "include_text", "llm_typing", "on_stuck", "consult_below", "max_consults", "inputs", "extract", "plan"),
            schema["properties"]!!.jsonObject.keys)
        assertEquals("task", schema["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    // ---- the whole flow, against fakes ----

    private companion object { const val ASK = "<ask the llm>" }

    private class FakeBrowser(val dir: File, val script: List<String>, val textPick: String? = "Palo Alto") : ToolInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var jevCalls = 0
        val askLlmOffered = mutableListOf<Boolean>()
        var shots = 0
        /** The screenshot content; clicks change it unless [frozen]. */
        var frozen = false
        var focused = """{"tag":"input","editable":true,"label":"Search"}"""
        var url = "https://sample.com/"
        /** Pages the next clicks navigate to, in order. */
        val clickPages = ArrayDeque<String>()
        var scrollY = 0.0
        var reportScroll = false
        var wheelWorks = true
        val pageTexts = mutableMapOf<String, String>()
        val jevArgs = mutableListOf<JsonObject>()
        /** RPA Engine's content hash of the visible text and form state; null = not reported. */
        var contentHash: (() -> String?)? = null
        /** The pending browser dialog, as `rpa_step` reports it; null = none. */
        var dialog: String? = null
        val dialogAnswers = mutableListOf<String>()
        var dialogPick = "OK"
        var dialogP = 0.9
        var dialogJevCalls = 0
        val urlByTab = mutableMapOf<String, String>()
        /** Called for each rpa_step action with its type and tab, before the reply is built. */
        var onAction: ((String, String) -> Unit)? = null
        var elements = """[{"id":"e1","role":"button","tag":"button","label":"Sign in","options":null,"sensitive":false,"in_viewport":true,"selector":{"type":"css","value":"#signin"}},""" +
            """{"id":"e2","role":"textbox","tag":"input","label":"Phone","options":null,"sensitive":false,"in_viewport":true,"selector":{"type":"css","value":"#phone"}},""" +
            """{"id":"e3","role":"textbox","tag":"input","label":"Password","options":null,"sensitive":true,"in_viewport":true,"selector":{"type":"css","value":"#pw"}}]"""
        override fun has(toolName: String) = true
        override fun inputSchema(toolName: String) = if (toolName == ToolNames.JEV_DECIDE) """{"properties":{"model":{"enum":["typesafe/jev-1.14"]},"assets":{}}}""" else null

        override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
            val a = arguments.jsonObject
            calls += toolName to a
            val tabArg = a["tab_id"]?.jsonPrimitive?.content ?: "t1"
            val pageUrl = urlByTab[tabArg] ?: url
            return when (toolName) {
                ToolNames.OBSERVE -> ToolReply("""{"tab_id":"$tabArg","url":"$pageUrl","title":"S","elements":$elements}""", false)
                ToolNames.STEP -> {
                    val action = a["action"]!!.jsonObject
                    onAction?.invoke(action["type"]!!.jsonPrimitive.content, tabArg)
                    val dlg = dialog?.let { ""","dialog":$it""" }.orEmpty()
                    when (action["type"]!!.jsonPrimitive.content) {
                        "dialog_answer" -> { dialogAnswers += action["value"]!!.jsonPrimitive.content; dialog = null; ToolReply("""{"ok":true}""", false) }
                        "cu_end" -> ToolReply("""{"ok":true}""", false)
                        "screenshot" -> {
                            val f = File(dir, "shot-${if (frozen) 0 else shots++}.jpg").also { it.writeText("shot ${if (frozen) 0 else shots}") }
                            ToolReply("""{"ok":true,"screenshot":{"path":"${f.absolutePath}","width":560,"height":390,"css_width":1120,"css_height":780,"dpr":2${if (reportScroll) ""","scroll":{"x":0,"y":$scrollY,"max_y":5000}""" else ""}${contentHash?.invoke()?.let { ""","content_hash":"$it"""" }.orEmpty()}}$dlg}""", false)
                        }
                        "focused" -> ToolReply("""{"ok":true,"focused":$focused}""", false)
                        "page_text" -> ToolReply("""{"ok":true,"text":"${pageTexts[pageUrl] ?: if (pageUrl == "https://sample.com/") "Best: A, B, C" else "Text of $pageUrl"}"}""", false)
                        "click_at" -> { clickPages.removeFirstOrNull()?.let { url = it }; ToolReply("""{"ok":true$dlg}""", false) }
                        "navigate" -> { url = action["value"]!!.jsonPrimitive.content; ToolReply("""{"ok":true}""", false) }
                        "scroll_by" -> { if (wheelWorks) scrollY = (scrollY + action["value"]!!.jsonPrimitive.content.toDouble()).coerceAtLeast(0.0); ToolReply("""{"ok":true}""", false) }
                        else -> ToolReply("""{"ok":true}""", false)
                    }
                }
                ToolNames.JEV_DECIDE -> {
                    val q = a["questions"]!!.jsonObject
                    if ("dialog" in q) {
                        dialogJevCalls++
                        jevArgs += a
                        ToolReply("""{"response":{"answers":{"dialog":{"type":"choice","choice":"$dialogPick","probabilities":{"$dialogPick":$dialogP}}},"usage":{"cost":0.002}}}""", false)
                    } else if ("text" in q) {
                        val criteria = q["text"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                        askLlmOffered += JevDecider.ASK_LLM in criteria
                        val key = if (textPick == ASK) JevDecider.ASK_LLM else criteria.entries.firstOrNull { it.value == textPick }?.key ?: JevDecider.NONE_OF_THESE
                        ToolReply("""{"response":{"answers":{"text":{"type":"choice","choice":"$key","probabilities":{"$key":0.95}}},"usage":{"cost":0.001}}}""", false)
                    } else {
                        jevArgs += a
                        val op = script[jevCalls.coerceAtMost(script.lastIndex)]
                        jevCalls++
                        val p = when { op.endsWith("@low") -> 0.3; op.endsWith("@mid") -> 0.55; else -> 0.9 }
                        ToolReply(reply(op.removeSuffix("@low").removeSuffix("@mid"), p), false)
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
        val revealed = mutableListOf<String>()
        val claimed = mutableListOf<String>()
        /** The browser tabs of the space on screen; a test changes it as a popup opens or closes. */
        var now: Map<String, String> = emptyMap()
        var refuse = emptySet<String>()
        override suspend fun reveal(tabId: String) { revealed += tabId }
        override suspend fun open(url: String): String? { opened += url; return "t1" }
        override fun claim(tabId: String): String? = if (tabId in refuse) "taken" else null.also { claimed += tabId }
        override fun release(tabId: String) { released += tabId }
        override suspend fun snapshot(): Map<String, String> = now
    }

    private var consultReplies = ArrayDeque<String>()
    private var extractReply = """{"answer":"A, B and C"}"""
    private var gatewayCaps = emptySet<String>()

    private fun gateway(requests: MutableList<AiRequest>, planner: String, typed: String) = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> {
            requests += request
            return Result.success(AiReply(when {
                request.system.startsWith("You plan") -> planner
                request.system.startsWith("You help a browser agent") -> consultReplies.removeFirstOrNull() ?: """{"give_up":"nothing left"}"""
                request.system.startsWith("You answer a user's") -> extractReply
                else -> typed
            }))
        }
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = gatewayCaps
        override fun activeModel(): AiModelInfo? = AiModelInfo("OPENAI", "OpenAI", "gpt-5")
    }

    private val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Search Palo Alto restaurants and read the top ones","type_values":["Palo Alto"]}"""
    private val typedJson = """{"text":"written text"}"""

    private fun flow(browser: FakeBrowser, tabs: FakeTabs, requests: MutableList<AiRequest>, plan: String = this.plan, log: RunLogStore? = null) =
        ComputerUseFlow(browser, { gateway(requests, plan, typedJson) }, tabs, openWaitsMs = listOf(0), settleMs = 0, stepSettleMs = 0, runLog = log, popupWaitMs = 0, popupPollMs = 0)

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
        assertEquals(listOf("t1"), tabs.revealed)
        assertEquals(listOf(VisionOp.CLICK, VisionOp.TYPE_TEXT, VisionOp.PRESS_ENTER, VisionOp.SCROLL_DOWN), report.steps.map { it.op })
        // click_at lands on the box centre of the 1120 x 780 viewport, with the pointer label
        val clicks = browser.stepArgs("click_at")
        assertEquals("560,195", clicks.first()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Max", clicks.first()["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("Palo Alto", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("195", browser.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Enter", browser.stepArgs("key").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        // The screenshot, and nothing with image data, is what the activity records.
        assertEquals(6, report.jevCalls) // five operations and the choice of text for the field
        assertEquals(1, requests.count { it.system.startsWith("You plan") })
        assertEquals(0, requests.count { it.system.startsWith("You write") })
        assertEquals(1, report.llmCalls)
        assertEquals("Done in 4 steps", report.summary.take(15))
        assertTrue(report.summary.contains("Clicked once, typed 'Palo Alto', pressed Enter, scrolled once."))
        assertNotNull(report.costUsd)
        assertEquals("Best: A, B, C", report.pageText)
        assertNotNull(report.screenshotPath)
        val j = report.toJson()
        assertEquals("done", j["status"]!!.jsonPrimitive.content)
        assertTrue(j["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4, j["steps"]!!.jsonArray.size)
        assertTrue("page_text" in j)
        assertFalse("page_text" in report.toJson(includeText = false))
        assertFalse("answer" in j)
    }

    @Test
    fun `three unchanged screenshots block the run`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK")).also { it.frozen = true }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(3, report.steps.size)
        assertTrue(report.error!!.contains("left the page where it was"))
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
    fun `blocked from jev returns needs_input with a blocker`() = runTest {
        val report = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertTrue(report.steps.isEmpty())
        assertNotNull(report.blocker)
    }

    @Test
    fun `a headless run proceeds when unsure and an interactive one asks first`() = runTest {
        val low = FakeBrowser(tmp(), listOf("SCROLL_DOWN@mid", "DONE"))
        val headless = flow(low, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", confirm = null))
        assertEquals(UseStatus.DONE, headless.status)
        assertEquals(1, headless.steps.size)

        val asked = mutableListOf<Double>()
        val declined = flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN@mid", "DONE")), FakeTabs(), mutableListOf())
            .runComputerUse(UseRequest("t", confirm = { _, p -> asked += p; false }))
        assertEquals(listOf(0.55), asked)
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
        val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Sign in","type_values":["hunter2"]}"""
        val report = flow(browser, FakeTabs(), mutableListOf(), plan).runComputerUse(UseRequest("t"))
        assertEquals("hunter2", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(Secrets.MASK, report.steps.single().text)
    }

    @Test
    fun `jev can ask the writing model for a field's text, which is recorded as an LLM call`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = ASK)
        val requests = mutableListOf<AiRequest>()
        val run = UseRun("t")
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("Book a table", llmTyping = true), run)
        assertEquals(listOf(true), browser.askLlmOffered)
        assertEquals("written text", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(ComputerUseFlow.WRITTEN_BY_MODEL, report.steps.single().textSource)
        assertEquals(2, report.llmCalls) // plan + typing
        val typing = requests.single { it.system.startsWith("You write") }
        assertTrue(typing.messages.single().text.contains("Field: label="))
        assertTrue(run.log.entries.value.any { it.kind == ActivityKind.LLM && it.name.contains("type text") })
        assertEquals("written by the model", report.toJson()["steps"]!!.jsonArray.single().jsonObject["text_source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with nothing to choose from the writing model is asked, unless typing is off or the field is private`() = runTest {
        val noPhrases = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Go","type_values":[]}"""
        val on = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"))
        val r1 = mutableListOf<AiRequest>()
        flow(on, FakeTabs(), r1, noPhrases).runComputerUse(UseRequest("t"))
        assertEquals(1, r1.count { it.system.startsWith("You write") })
        assertEquals("written text", on.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)

        val off = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"))
        val r2 = mutableListOf<AiRequest>()
        val rep = flow(off, FakeTabs(), r2, noPhrases).runComputerUse(UseRequest("t", llmTyping = false))
        assertEquals(0, r2.count { it.system.startsWith("You write") })
        assertEquals(0, off.stepArgs("type_text").size)
        assertTrue(rep.steps.single().note!!.contains("no text"))

        val secret = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE")).also { it.focused = """{"tag":"input","editable":true,"label":"Card CVV","type":"text"}""" }
        val r3 = mutableListOf<AiRequest>()
        flow(secret, FakeTabs(), r3, noPhrases).runComputerUse(UseRequest("t"))
        assertEquals(0, r3.count { it.system.startsWith("You write") })
        assertEquals(0, secret.stepArgs("type_text").size)
    }

    @Test
    fun `the choice only offers ask_llm when typing by the model is allowed`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"))
        flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", llmTyping = false))
        assertEquals(listOf(false), browser.askLlmOffered)
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
    fun `a tab that is given is claimed and not opened or navigated, unless a url says so`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("DONE"))
        val tabs = FakeTabs()
        flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", tabId = "t7"))
        assertEquals(listOf("t7"), tabs.revealed)
        assertTrue(tabs.opened.isEmpty())
        assertEquals(0, browser.stepArgs("navigate").size)
        val b2 = FakeBrowser(tmp(), listOf("DONE"))
        flow(b2, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", tabId = "t7", url = "https://given.com/x"))
        assertEquals("t7", b2.stepArgs("navigate").single()["tab_id"]!!.jsonPrimitive.content)
    }

    // ---- stuck handling ----

    @Test
    fun `the Zomato pattern, alternating scrolls on a page that ignores the wheel while its video changes every frame, returns needs_input`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_UP", "SCROLL_DOWN", "SCROLL_UP", "SCROLL_DOWN")).also { it.reportScroll = true; it.wheelWorks = false }
        val tabs = FakeTabs()
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("log in to zomato", maxSteps = 15))
        // Pixels differ on every screenshot, yet the run stops after 3 actions instead of 15.
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(3, report.steps.size)
        assertEquals(3, browser.jevCalls)
        val b = report.blocker!!
        assertTrue(b.reason.contains("same scroll position"))
        assertEquals("t1", b.tabId)
        assertEquals("https://sample.com/", b.url)
        assertEquals(3, b.step)
        assertNotNull(b.screenshotPath)
        assertNotNull(b.question)
        assertEquals(listOf("t1"), tabs.released) // the lock is given back; the tab stays open
        val j = report.toJson()
        assertEquals("needs_input", j["status"]!!.jsonPrimitive.content)
        assertEquals("t1", j["tab_id"]!!.jsonPrimitive.content)
        val blocker = j["blocker"]!!.jsonObject
        assertEquals(setOf("reason", "question", "url", "title", "tab_id", "screenshot_path", "step"), blocker.keys)
        assertFalse(report.ok)
    }

    @Test
    fun `scroll oscillation that does move the page is still caught`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_UP", "SCROLL_DOWN", "SCROLL_UP", "DONE")).also { it.reportScroll = true }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 15))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(4, report.steps.size)
        assertTrue(report.blocker!!.reason.contains("down and up"))
    }

    @Test
    fun `a Jev operation below consult_below is stuck, and a lower threshold lets it go on`() = runTest {
        val low = flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN@low", "DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, low.status)
        assertTrue(low.blocker!!.reason.contains("30% sure"))
        val ok = flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN@low", "DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", consultBelow = 0.2))
        assertEquals(UseStatus.DONE, ok.status)
    }

    @Test
    fun `consult mode asks the chat model with the elements and acts on its action with the pointer`() = runTest {
        consultReplies = ArrayDeque(listOf("""{"action":{"type":"click","element":"e1"}}"""))
        val browser = FakeBrowser(tmp(), listOf("BLOCKED", "DONE"))
        val requests = mutableListOf<AiRequest>()
        val run = UseRun("t")
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("log in", onStuck = OnStuck.CONSULT, pointerLabel = "Max"), run)
        assertEquals(UseStatus.DONE, report.status)
        val ask = requests.single { it.system.startsWith("You help a browser agent") }
        assertTrue(ask.messages.single().text.contains("e1 | button | \"Sign in\""))
        assertTrue(ask.messages.single().text.contains("Why Jev is stuck: Jev found no operation"))
        val click = browser.stepArgs("click").single()
        assertEquals("#signin", click["action"]!!.jsonObject["selector"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Max", click["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        val step = report.steps.single()
        assertEquals(VisionOp.CONSULT, step.op)
        assertTrue(step.description.startsWith("Consulted OpenAI gpt-5: click 'Sign in'"))
        assertEquals(2, report.llmCalls) // plan + consult
        assertTrue(run.log.entries.value.any { it.name == "gateway chat: consult" })
    }

    @Test
    fun `the screenshot goes with the consult only when the gateway can see`() = runTest {
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        val seen = mutableListOf<AiRequest>()
        gatewayCaps = setOf(AiGatewayAPI.CAPABILITY_VISION)
        try {
            flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), seen).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        } finally { gatewayCaps = emptySet() }
        assertEquals(1, seen.single { it.system.startsWith("You help") }.messages.single().images.size)
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        val blind = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), blind).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(0, blind.single { it.system.startsWith("You help") }.messages.single().images.size)
    }

    @Test
    fun `a hint becomes advice in Jev's next state, need_input stops with that question, done and give_up end the run`() = runTest {
        consultReplies = ArrayDeque(listOf("""Sure {"hint":"Use the phone number login"}"""))
        val hinted = FakeBrowser(tmp(), listOf("BLOCKED", "DONE"))
        val r1 = flow(hinted, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.DONE, r1.status)
        assertEquals(null, hinted.jevArgs.first()["state"]!!.jsonObject["advice"])
        assertEquals("Use the phone number login", hinted.jevArgs.last()["state"]!!.jsonObject["advice"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(r1.steps.single().description.contains("hint 'Use the phone number login'"))

        consultReplies = ArrayDeque(listOf("""{"need_input":"What is your phone number?"}"""))
        val r2 = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.NEEDS_INPUT, r2.status)
        assertEquals("What is your phone number?", r2.blocker!!.question)

        consultReplies = ArrayDeque(listOf("""{"done":"already logged in"}"""))
        val r3 = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.DONE, r3.status)

        consultReplies = ArrayDeque(listOf("""{"give_up":"captcha"}"""))
        val r4 = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.BLOCKED, r4.status)
        assertTrue(r4.error!!.contains("captcha"))

        consultReplies = ArrayDeque(listOf("not json at all"))
        val r5 = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.NEEDS_INPUT, r5.status)
    }

    @Test
    fun `consults are capped and then the run returns`() = runTest {
        consultReplies = ArrayDeque(List(5) { """{"hint":"try again"}""" })
        val browser = FakeBrowser(tmp(), listOf("BLOCKED"))
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, maxConsults = 2, maxSteps = 20))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(2, requests.count { it.system.startsWith("You help") })
        assertTrue(report.error!!.contains("already consulted 2 times"))
        // max_consults 0 never consults.
        val none = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), none).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, maxConsults = 0))
        assertEquals(0, none.count { it.system.startsWith("You help") })
    }

    @Test
    fun `a consulted action keeps the same rules for private fields, inputs, https and the typing switch`() = runTest {
        fun run(reply: String, inputs: Map<String, String> = emptyMap(), llmTyping: Boolean = true): Pair<FakeBrowser, UseReport> {
            consultReplies = ArrayDeque(listOf(reply, """{"give_up":"end"}"""))
            val b = FakeBrowser(tmp(), listOf("BLOCKED"))
            return b to kotlinx.coroutines.runBlocking { flow(b, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, inputs = inputs, llmTyping = llmTyping)) }
        }
        // Model-written text into a private field is refused.
        val (b1, r1) = run("""{"action":{"type":"input","element":"e3","value":"hunter2"}}""")
        assertEquals(0, b1.stepArgs("input").size)
        assertTrue(r1.steps.first().description.contains("refused"))
        // A value the person gave goes in, allowed for the private field and masked everywhere.
        val (b2, r2) = run("""{"action":{"type":"input","element":"e3","value":"{{password}}"}}""", mapOf("password" to "s3cret!"))
        assertEquals("s3cret!", b2.stepArgs("input").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(true, b2.stepArgs("input").single()["allow_sensitive"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(r2.steps.first().description.contains("s3cret!"))
        // A named input that was not given is refused.
        val (b3, _) = run("""{"action":{"type":"input","element":"e2","value":"{{phone}}"}}""")
        assertEquals(0, b3.stepArgs("input").size)
        // Typing by the model off: a literal value is refused, an input is fine.
        val (b4, _) = run("""{"action":{"type":"input","element":"e2","value":"555"}}""", llmTyping = false)
        assertEquals(0, b4.stepArgs("input").size)
        val (b5, _) = run("""{"action":{"type":"input","element":"e2","value":"{{phone}}"}}""", mapOf("phone" to "555"), llmTyping = false)
        assertEquals("555", b5.stepArgs("input").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        // navigate: https only.
        val (b6, _) = run("""{"action":{"type":"navigate","value":"http://insecure.com/"}}""")
        assertEquals(0, b6.stepArgs("navigate").size)
        val (b7, _) = run("""{"action":{"type":"navigate","value":"https://secure.com/"}}""")
        assertEquals("https://secure.com/", b7.stepArgs("navigate").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        // An unknown element is refused.
        val (b8, _) = run("""{"action":{"type":"click","element":"e99"}}""")
        assertEquals(0, b8.stepArgs("click").size)
    }

    @Test
    fun `every kind of consult reply parses and a malformed one does not`() {
        assertEquals(Cu.Consult.Act("click", "e1", null), Cu.parseConsult("""{"action":{"type":"click","element":"e1"}}"""))
        assertEquals(Cu.Consult.Act("navigate", null, "https://a.com"), Cu.parseConsult("""```json\n{"action":{"type":"navigate","value":"https://a.com"}}\n``` ok {x}"""))
        assertEquals(Cu.Consult.Hint("go"), Cu.parseConsult("""{"hint":"go"}"""))
        assertEquals(Cu.Consult.NeedInput("q?"), Cu.parseConsult("""{"need_input":"q?"}"""))
        assertEquals(Cu.Consult.Done("ok"), Cu.parseConsult("""{"done":"ok"}"""))
        assertEquals(Cu.Consult.GiveUp("no"), Cu.parseConsult("""{"give_up":"no"}"""))
        assertTrue(runCatching { Cu.parseConsult("""{"action":{"type":"fly","element":"e1"}}""") }.isFailure)
        assertTrue(runCatching { Cu.parseConsult("""{"action":{"type":"click"}}""") }.isFailure)
        assertTrue(runCatching { Cu.parseConsult("""{"other":1}""") }.isFailure)
        assertEquals("a 555 b", Cu.fillInputs("a {{ phone }} b", mapOf("phone" to "555")))
        assertNull(Cu.fillInputs("{{missing}}", emptyMap()))
    }

    // ---- inputs and continuing ----

    @Test
    fun `continuing on the same tab with inputs does not replan from the page, open a tab or navigate`() = runTest {
        val first = FakeBrowser(tmp(), listOf("BLOCKED"))
        val tabs1 = FakeTabs()
        val stopped = flow(first, tabs1, mutableListOf()).runComputerUse(UseRequest("log in with my phone"))
        assertEquals(UseStatus.NEEDS_INPUT, stopped.status)
        val tab = stopped.blocker!!.tabId!!

        val second = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "5551234")
        val tabs2 = FakeTabs()
        val requests = mutableListOf<AiRequest>()
        val done = flow(second, tabs2, requests, plan = """{"open":[],"jev_goal":"Log in","type_values":[]}""").runComputerUse(UseRequest("log in with my phone", tabId = tab, inputs = mapOf("phone" to "5551234")))
        assertEquals(UseStatus.DONE, done.status)
        assertTrue(tabs2.opened.isEmpty())
        assertEquals(0, second.stepArgs("navigate").size)
        assertEquals("5551234", second.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        // The planner sees the input's name, never its value.
        val planMessage = requests.single { it.system.startsWith("You plan") }.messages.single().text
        assertTrue(planMessage.contains("phone"))
        assertFalse(planMessage.contains("5551234"))
    }

    @Test
    fun `a resumed run in the same UseRun keeps its plan, steps and collected pages and does not plan again`() = runTest {
        val run = UseRun("t")
        val b1 = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED"))
        val r1 = mutableListOf<AiRequest>()
        val first = flow(b1, FakeTabs(), r1).runComputerUse(UseRequest("t"), run)
        assertEquals(UseStatus.NEEDS_INPUT, first.status)
        assertEquals(1, first.steps.size)

        val b2 = FakeBrowser(tmp(), listOf("PRESS_ENTER", "DONE"))
        val r2 = mutableListOf<AiRequest>()
        val second = flow(b2, FakeTabs(), r2).runComputerUse(UseRequest("t", tabId = "t1", resume = true, advice = listOf("The person replied: go on")), run)
        assertEquals(UseStatus.DONE, second.status)
        assertEquals(0, r2.count { it.system.startsWith("You plan") })
        assertEquals(listOf(VisionOp.SCROLL_DOWN, VisionOp.PRESS_ENTER), second.steps.map { it.op })
        assertEquals("The person replied: go on", b2.jevArgs.first()["state"]!!.jsonObject["advice"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `an input named like a secret is masked everywhere and only typed into a private field`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = null).also { it.focused = """{"tag":"input","editable":true,"label":"Password","type":"password"}""" }
        val run = UseRun("t")
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Sign in","type_values":[]}""")
            .runComputerUse(UseRequest("t", inputs = mapOf("password" to "zz-secret-99")), run)
        assertEquals("zz-secret-99", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(Secrets.MASK, report.steps.single().text)
        assertTrue(run.log.entries.value.none { it.args.contains("zz-secret-99") || it.result.contains("zz-secret-99") })
        // The same input is not offered for a plain field.
        assertTrue(Cu.textOptions("g", emptyList(), "https://a.com", sensitive = false, inputs = mapOf("password" to "zz-secret-99", "phone" to "555")).let { "zz-secret-99" !in it && "555" in it })
        assertTrue("zz-secret-99" in Cu.textOptions("g", emptyList(), "https://a.com", sensitive = true, inputs = mapOf("password" to "zz-secret-99")))
    }

    // ---- collected information ----

    @Test
    fun `COLLECT saves the page text and screenshot, once per distinct text`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("COLLECT", "COLLECT", "DONE"))
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.DONE, report.status)
        val item = report.collected.single()
        assertEquals("https://sample.com/", item.url)
        assertEquals("S", item.title)
        assertEquals("Best: A, B, C", item.text)
        assertEquals(1, item.step)
        assertFalse(item.auto)
        assertNotNull(item.screenshotPath)
        assertEquals(listOf("saved", "already saved"), report.steps.map { it.note })
        val j = report.toJson()["collected"]!!.jsonArray.single().jsonObject
        assertEquals("jev", j["source"]!!.jsonPrimitive.content)
        assertEquals("Best: A, B, C", j["text"]!!.jsonPrimitive.content)
        assertFalse("text" in report.toJson(includeText = false)["collected"]!!.jsonArray.single().jsonObject)
    }

    @Test
    fun `each page is snapshotted once before it is left, and the final page is kept too`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "CLICK", "CLICK", "DONE")).also {
            it.reportScroll = true
            it.clickPages += listOf("https://b.com/p1", "https://b.com/p2", "https://b.com/p1")
        }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(UseStatus.DONE, report.status)
        // sample.com (left by click 1), p1 (click 2), p2 (click 3); p1 again adds nothing; the last click leaves p1 for p1, then the end.
        assertEquals(listOf("https://sample.com/", "https://b.com/p1", "https://b.com/p2"), report.collected.map { it.url })
        assertTrue(report.collected.all { it.auto })
        assertEquals(listOf("https://sample.com/", "https://b.com/p1", "https://b.com/p2", "https://b.com/p1"), report.pagesVisited.map { it.url })
        assertEquals("https://b.com/p1", report.url)
    }

    @Test
    fun `identical text is saved once and the total is capped dropping the oldest`() {
        val store = CollectedStore(cap = 100)
        fun item(n: Int, text: String) = CollectedItem(n, "https://a.com/$n", "T$n", text, null, true)
        assertTrue(store.add(item(1, "x".repeat(40))))
        assertFalse(store.add(item(2, "x".repeat(40)))) // same text
        assertFalse(store.add(item(3, "   ")))
        assertTrue(store.add(item(4, "y".repeat(40))))
        assertTrue(store.add(item(5, "z".repeat(40)))) // 120 > 100: the oldest goes
        assertEquals(listOf(4, 5), store.list.map { it.step })
        assertTrue(store.add(item(6, "w".repeat(300)))) // one over the cap is cut, the rest dropped
        assertEquals(1, store.size)
        assertEquals(100, store.list.single().text.length)
    }

    @Test
    fun `extract is one LLM call over the collected text, off by default`() = runTest {
        val off = flow(FakeBrowser(tmp(), listOf("COLLECT", "DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("what are the top places"))
        assertNull(off.answer)
        assertFalse("answer" in off.toJson())
        assertEquals(1, off.llmCalls)

        val requests = mutableListOf<AiRequest>()
        val on = flow(FakeBrowser(tmp(), listOf("COLLECT", "DONE")), FakeTabs(), requests).runComputerUse(UseRequest("what are the top places", extract = true))
        assertEquals("A, B and C", on.answer)
        assertEquals("A, B and C", on.toJson()["answer"]!!.jsonPrimitive.content)
        assertEquals(2, on.llmCalls) // plan + extract
        val prompt = requests.single { it.system.startsWith("You answer a user's") }.messages.single().text
        assertTrue(prompt.contains("Best: A, B, C"))
    }

    @Test
    fun `needs_input and the other statuses read right in the summary`() {
        assertTrue(Cu.summary(UseStatus.NEEDS_INPUT, emptyList(), 3.0, "Zomato", "https://www.zomato.com/", "scrolling down and up in turn").startsWith("Stopped: needs your input after 0 steps (3.0 s) on 'Zomato' (zomato.com). scrolling down and up in turn."))
    }

    // ---- content hash, scroll position, scroll wording ----

    @Test
    fun `clicking the same spot while the content changes is progress, and a fixed content with moving pixels is not`() = runTest {
        // The shop: "+" clicked on the same spot three times, the cart changing each time; URL and scroll never move.
        var cart = 0
        val shop = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "CLICK", "DONE")).also {
            it.reportScroll = true
            it.contentHash = { "cart-${cart}" }
            it.onAction = { type, _ -> if (type == "click_at") cart++ }
        }
        val ok = flow(shop, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("add two mugs, then check out", maxSteps = 10))
        assertEquals(UseStatus.DONE, ok.status)
        assertEquals(3, ok.steps.size)

        // Zomato: every pixel changes (a video), the content hash and the scroll position do not.
        val video = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_UP", "SCROLL_DOWN", "DONE")).also { it.reportScroll = true; it.wheelWorks = false; it.contentHash = { "hero" } }
        val stuck = flow(video, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("log in", maxSteps = 10))
        assertEquals(UseStatus.NEEDS_INPUT, stuck.status)
        assertEquals(3, stuck.steps.size)
    }

    @Test
    fun `the content hash decides when it is there and the old rules when it is not`() {
        fun seen(content: String?, y: Double? = 0.0, hash: String = "h") = Seen("https://a.com/", y, hash, content)
        val now = seen("c3")
        // Content changed since each action: not stuck, even with the same URL, scroll and spot.
        val moving = listOf("c0", "c1", "c2").map { act(VisionOp.CLICK, seen(it), 50, 25) }
        assertNull(Cu.noProgress(moving, now))
        // Unchanged content, scroll and address: stuck, however the pixels move.
        val fixed = List(3) { act(VisionOp.SCROLL_DOWN, seen("c3", 0.0, "pixels-$it")) }
        assertNotNull(Cu.noProgress(fixed, now))
        // Same content but the page scrolled: progress.
        assertNull(Cu.noProgress(List(3) { act(VisionOp.SCROLL_DOWN, seen("c3", 400.0 * it)) }, now))
        // No content hash on either side: the previous rule (scroll position) still applies.
        assertNotNull(Cu.noProgress(List(3) { act(VisionOp.SCROLL_DOWN, seen(null, 0.0, "p$it")) }, seen(null)))
        // The same spot clicked three times with the content unchanged is stuck; changed content is not.
        assertNotNull(Cu.noProgress(List(3) { act(VisionOp.CLICK, seen("c3", it * 100.0), 50, 25) }, now.copy(scrollY = 10.0)))
        assertNull(Cu.noProgress(listOf("c0", "c1", "c3").map { act(VisionOp.CLICK, seen(it, 0.0), 50, 25) }, now))
    }

    @Test
    fun `the state tells Jev where the page is scrolled to, and recent scrolls read as progress`() {
        assertEquals(36, ScrollPos(1800.0, 5000.0).percentDown)
        assertEquals(0, ScrollPos(0.0, 0.0).percentDown)
        assertTrue(ScrollPos(4997.0, 5000.0).atBottom)
        assertFalse(ScrollPos(1800.0, 5000.0).atBottom)
        val mid = Cu.decideArgs("m", "g", "https://a.com/", "A", emptyList(), "/x.jpg", position = ScrollPos(1700.0, 5000.0))["state"]!!.jsonObject["scroll"]!!.jsonObject
        assertEquals(1700.0, mid["y"]!!.jsonPrimitive.content.toDouble())
        assertEquals(5000.0, mid["max_y"]!!.jsonPrimitive.content.toDouble())
        assertEquals(34, mid["percent_down"]!!.jsonPrimitive.content.toInt())
        assertFalse("position" in mid)
        val bottom = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg", position = ScrollPos(4998.0, 5000.0))["state"]!!.jsonObject["scroll"]!!.jsonObject
        assertTrue(bottom["position"]!!.jsonPrimitive.content.contains("at the bottom of the page"))
        assertNull(Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")["state"]!!.jsonObject["scroll"])
        val scrolled = UseStep(1, VisionOp.SCROLL_DOWN, confidence = 0.9, ms = 1, scrolledTo = ScrollPos(1700.0, 5000.0))
        assertEquals("scrolled to 34% of the page", Cu.recentAction(scrolled)["result"]!!.jsonPrimitive.content)
        assertTrue(Cu.recentAction(scrolled.copy(scrolledTo = ScrollPos(4999.0, 5000.0)))["result"]!!.jsonPrimitive.content.contains("the bottom of the page"))
        assertNull(Cu.recentAction(scrolled.copy(scrolledTo = null))["result"])
    }

    @Test
    fun `the flow sends the scroll state and the position each scroll reached`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "DONE")).also { it.reportScroll = true }
        flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("find item 87", maxSteps = 10))
        val first = browser.jevArgs[0]["state"]!!.jsonObject
        assertEquals(0, first["scroll"]!!.jsonObject["percent_down"]!!.jsonPrimitive.content.toInt())
        val last = browser.jevArgs.last()["state"]!!.jsonObject
        assertEquals(7, last["scroll"]!!.jsonObject["percent_down"]!!.jsonPrimitive.content.toInt()) // 2 x 195 px of 5000
        assertEquals("scrolled to 7% of the page", last["recent_actions"]!!.jsonArray.last().jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the scroll wording prefers large for a far target and small only for something cut off`() {
        val rules = Cu.SCROLL_RULES
        assertTrue(rules.contains("large (nearly a full screen) when the target is not visible and its distance is unknown or far"))
        assertTrue(rules.contains("medium when it is probably within a screen"))
        assertTrue(rules.contains("small only to reveal something cut off at the edge"))
        assertFalse(rules.contains("close by"))
        assertEquals(listOf(0.25, 0.5, 0.9), Cu.SCROLL_AMOUNTS.values.toList())
    }

    // ---- a dropdown, the model label, planning ----

    @Test
    fun `a focused select takes its option by type-ahead, chosen from its options`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "Support").also {
            it.focused = """{"tag":"select","editable":false,"label":"Topic","options":["Billing","Support","Sales"]}"""
        }
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests, plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Support request","type_values":[]}""")
            .runComputerUse(UseRequest("Support request"))
        assertEquals("Support", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(VisionOp.TYPE_TEXT, report.steps.single().op)
        assertNull(report.steps.single().note)
        // The choice offered the dropdown's options, and the writing model was not asked.
        assertEquals(listOf(false), browser.askLlmOffered)
        assertEquals(0, requests.count { it.system.startsWith("You write") })
    }

    @Test
    fun `a model label does not say the provider twice`() {
        assertEquals("Claude Code CLI", modelLabel("Claude Code CLI", "Claude Code CLI"))
        assertEquals("claude-opus-4", modelLabel("Anthropic", "claude-opus-4").let { if (it == "Anthropic claude-opus-4") "claude-opus-4" else it })
        assertEquals("OpenAI gpt-5", modelLabel("OpenAI", "gpt-5"))
        assertEquals("openai/gpt-5", modelLabel("OpenAI", "openai/gpt-5"))
    }

    @Test
    fun `with the page given there is no plan call, the task is the goal, and nothing asks a model unless typing needs it`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("click the red square", url = "https://sample.com/", planWithLlm = false))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(0, requests.size)
        assertEquals(0, report.llmCalls)
        assertEquals("click the red square", report.goal)
        assertTrue(browser.jevArgs.first()["questions"]!!.jsonObject["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("click the red square"))
        // Forcing a plan brings the call back; the default of a request is to plan.
        val planned = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("DONE")), FakeTabs(), planned).runComputerUse(UseRequest("t", url = "https://sample.com/"))
        assertEquals(1, planned.count { it.system.startsWith("You plan") })
        // Nothing to open and no plan: a clear error, not a guess.
        val none = flow(FakeBrowser(tmp(), listOf("DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", planWithLlm = false))
        assertEquals(UseStatus.ERROR, none.status)
        assertTrue(none.error!!.contains("give url or tab_id"))
        // A given tab skips the plan too.
        val onTab = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("DONE")), FakeTabs(), onTab).runComputerUse(UseRequest("t", tabId = "t7", planWithLlm = false))
        assertEquals(0, onTab.size)
    }

    @Test
    fun `the plan argument defaults by whether a page was given`() {
        assertNull(BrowserUseArgs.parse("""{"task":"t"}""").getOrThrow().plan)
        assertEquals(true, BrowserUseArgs.parse("""{"task":"t","plan":true}""").getOrThrow().plan)
        assertEquals(false, BrowserUseArgs.parse("""{"task":"t","url":"https://a.com","plan":false}""").getOrThrow().plan)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","plan":"yes"}""").isFailure)
    }

    // ---- popup windows ----

    @Test
    fun `a popup that opens is followed, driven with the pointer and left for the opener when it closes`() = runTest {
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        val browser = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { b ->
            b.urlByTab["t2"] = "https://accounts.sample.com/signin"
            var opened = false
            b.onAction = { type, tab ->
                if (type == "click_at" && tab == "t1" && !opened) { opened = true; tabs.now = mapOf("t1" to "Main", "t2" to "Sign in") }
                if (type == "click_at" && tab == "t2") tabs.now = mapOf("t1" to "Main")
            }
        }
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("sign in with the popup", pointerLabel = "Max", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals("Switched to popup 'Sign in'", report.steps[0].popup)
        assertEquals("Popup closed, back on 'Main'", report.steps[1].popup)
        assertEquals(listOf("t1", "t2"), report.steps.map { it.tabId })
        assertTrue(tabs.revealed.containsAll(listOf("t1", "t2")))
        assertEquals(listOf("t2"), tabs.claimed.filter { it == "t2" })
        // Screenshots follow the tab being driven: t1, then t2, then back on t1.
        val shotTabs = browser.calls.filter { it.first == ToolNames.STEP && it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content == "screenshot" }.map { it.second["tab_id"]!!.jsonPrimitive.content }
        assertEquals(listOf("t1", "t2", "t1"), shotTabs)
        assertEquals("Max", browser.stepArgs("click_at")[1]["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("t1" to "opened", "t2" to "popup"), report.tabs.map { it.tabId to it.kind })
        // Every tab touched is told the run is over; the locks go back.
        assertEquals(setOf("t1", "t2"), browser.stepArgs("cu_end").map { it["tab_id"]!!.jsonPrimitive.content }.toSet())
        assertTrue(tabs.released.containsAll(listOf("t1", "t2")))
        assertEquals("t1", report.tabId)
    }

    @Test
    fun `a tab that was already open, or that another run holds, is never taken over`() = runTest {
        // t9 was there before the action: not a popup of ours.
        val before = FakeTabs().also { it.now = mapOf("t1" to "Main", "t9" to "Somebody else's") }
        val b1 = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        val r1 = flow(b1, before, mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(listOf("t1"), r1.tabs.map { it.tabId })
        assertNull(r1.steps.single().popup)
        // A new tab whose lock another run holds is left alone.
        val taken = FakeTabs().also { it.now = mapOf("t1" to "Main"); it.refuse = setOf("t2") }
        val b2 = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.onAction = { type, _ -> if (type == "click_at") taken.now = mapOf("t1" to "Main", "t2" to "Other run") } }
        val r2 = flow(b2, taken, mutableListOf()).runComputerUse(UseRequest("t"))
        assertNull(r2.steps.single().popup)
        assertEquals(0, b2.calls.count { it.second["tab_id"]?.jsonPrimitive?.content == "t2" })
        // The tab being driven closing with nothing to go back to is just an ordinary failure, not a switch.
        val lone = FakeTabs().also { it.now = mapOf("t1" to "Main", "t6" to "Not ours") }
        val b3 = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.onAction = { type, _ -> if (type == "click_at") lone.now = mapOf("t6" to "Not ours") } }
        val r3 = flow(b3, lone, mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(0, b3.calls.count { it.second["tab_id"]?.jsonPrimitive?.content == "t6" })
        assertNull(r3.steps.firstOrNull()?.popup?.takeIf { it.startsWith("Popup closed") })
    }

    // ---- browser dialogs ----

    private val confirmDialog = """{"type":"confirm","message":"Delete this item?","ok_text":"OK","cancel_text":"Cancel","url":"https://sample.com/"}"""

    @Test
    fun `an alert is accepted without asking, a confirm goes to Jev, and every touched tab gets cu_end`() = runTest {
        val alert = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.onAction = { type, _ -> if (type == "click_at") b.dialog = """{"type":"alert","message":"Heads up","url":"https://sample.com/"}""" } }
        val r1 = flow(alert, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.DONE, r1.status)
        assertEquals(listOf("ok"), alert.dialogAnswers)
        assertEquals(0, alert.dialogJevCalls)
        assertEquals(VisionOp.DIALOG, r1.steps[1].op)
        assertEquals("Dialog 'Heads up': OK", r1.steps[1].description)
        assertEquals(1, alert.stepArgs("cu_end").size)

        val confirm = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.dialogPick = "CANCEL"
            b.onAction = { type, _ -> if (type == "click_at") b.dialog = confirmDialog }
        }
        val r2 = flow(confirm, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("remove nothing"))
        assertEquals(listOf("cancel"), confirm.dialogAnswers)
        assertEquals(1, confirm.dialogJevCalls)
        assertEquals("Dialog 'Delete this item?': CANCEL", r2.steps[1].description)
        // Jev saw the dialog in its state and was asked OK or CANCEL.
        val args = confirm.jevArgs.first { "dialog" in it["questions"]!!.jsonObject }
        assertEquals("confirm", args["state"]!!.jsonObject["dialog"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Delete this item?", args["state"]!!.jsonObject["dialog"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(listOf("OK", "CANCEL"), args["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject.keys.toList())
        assertEquals(UseStatus.DONE, r2.status)
    }

    @Test
    fun `a prompt is answered with the caller's input, and a prompt with nothing to type is cancelled when typing is off`() = runTest {
        val prompt = FakeBrowser(tmp(), listOf("CLICK", "DONE"), textPick = "Ada").also { b ->
            b.onAction = { type, _ -> if (type == "click_at") b.dialog = """{"type":"prompt","message":"What is your name?","default_value":"","url":"https://sample.com/"}""" }
        }
        val r1 = flow(prompt, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Say hello","type_values":[]}""")
            .runComputerUse(UseRequest("Say hello", inputs = mapOf("name" to "Ada")))
        assertEquals(listOf("ok:Ada"), prompt.dialogAnswers)
        assertEquals("Dialog 'What is your name?': OK 'Ada'", r1.steps[1].description)
        assertEquals("Ada", r1.steps[1].text)

        val none = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.onAction = { type, _ -> if (type == "click_at") b.dialog = """{"type":"prompt","message":"Your nickname?","url":"https://sample.com/"}""" }
        }
        val r2 = flow(none, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Go","type_values":[]}""")
            .runComputerUse(UseRequest("Go", llmTyping = false))
        assertEquals(listOf("cancel"), none.dialogAnswers)
        assertTrue(r2.steps[1].note!!.contains("no text for the prompt"))
    }

    @Test
    fun `beforeunload is accepted unless the goal says to stay, and a doubtful dialog answer counts as stuck`() = runTest {
        fun unload() = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.onAction = { type, _ -> if (type == "click_at") b.dialog = """{"type":"beforeunload","message":"Leave site?","url":"https://sample.com/"}""" } }
        val leave = unload()
        flow(leave, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("Open the next page"))
        assertEquals(listOf("ok"), leave.dialogAnswers)
        assertEquals(0, leave.dialogJevCalls)
        val stay = unload().also { it.dialogPick = "CANCEL" }
        flow(stay, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Edit the draft and stay on the page","type_values":[]}""").runComputerUse(UseRequest("t"))
        assertEquals(1, stay.dialogJevCalls)
        assertEquals(listOf("cancel"), stay.dialogAnswers)

        val unsure = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.dialogP = 0.3; b.onAction = { type, _ -> if (type == "click_at") b.dialog = confirmDialog } }
        val r = flow(unsure, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.blocker!!.reason.contains("30% sure how to answer the confirm dialog"))
        assertEquals(0, unsure.dialogAnswers.size)
        // The run still ends cleanly on the tab: cu_end goes out even when it stopped.
        assertEquals(1, unsure.stepArgs("cu_end").size)
    }

    @Test
    fun `a dialog that keeps coming back is a loop`() {
        val d = List(3) { UseStep(it + 1, VisionOp.DIALOG, confidence = 1.0, ms = 1, dialog = "Dialog 'x': OK") }
        assertTrue(Cu.noProgress(d, Seen("u", 0.0, "h"))!!.contains("dialog keeps coming back"))
        assertEquals(Cu.DialogInfo("prompt", "Name?", "x", "Send", "Skip", "https://a.com"),
            Cu.parseDialog(json("""{"dialog":{"type":"prompt","message":"Name?","default_value":"x","ok_text":"Send","cancel_text":"Skip","url":"https://a.com"}}""")))
        assertNull(Cu.parseDialog(json("""{"ok":true}""")))
        assertNull(Cu.parseDialog(json("""{"dialog":{"type":"weird","message":"m"}}""")))
    }

    // ---- run log ----

    @Test
    fun `a run is written while it runs and at the end, masked, with names only for inputs and no image data`() = runTest {
        val store = testRunLog()
        val run = UseRun("log in as ada")
        val seenWhileRunning = mutableListOf<String>()
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "SCROLL_DOWN", "DONE"), textPick = null).also { b ->
            b.focused = """{"tag":"input","editable":true,"label":"Password","type":"password"}"""
            b.onAction = { type, _ -> if (type == "scroll_by") store.read(run.id)?.let { seenWhileRunning += (it["status"] as JsonPrimitive).content + ":" + (it["steps_count"] as JsonPrimitive).content } }
        }
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Sign in","type_values":[]}""", log = store)
            .runComputerUse(UseRequest("log in as ada", inputs = mapOf("password" to "zz-secret-99")), run)
        assertEquals(run.id, report.runId)
        assertEquals(run.id, report.toJson()["run_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("running:1"), seenWhileRunning)
        val file = java.io.File(storeDir(store), run.id + ".json")
        assertTrue(file.exists())
        val text = file.readText()
        assertFalse(text.contains("zz-secret-99"))
        assertFalse(text.contains("base64"))
        val rec = json(text)
        assertEquals("done", rec["status"]!!.jsonPrimitive.content)
        assertEquals(setOf("password"), rec["inputs"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("log in as ada", rec["task"]!!.jsonPrimitive.content)
        assertEquals(2, rec["steps"]!!.jsonArray.size)
        val first = rec["steps"]!!.jsonArray[0].jsonObject
        assertEquals("TYPE_TEXT", first["op"]!!.jsonPrimitive.content)
        assertEquals(Secrets.MASK, first["text"]!!.jsonPrimitive.content)
        assertEquals("t1", first["tab_id"]!!.jsonPrimitive.content)
        assertTrue("box" in first && "screenshot_path" in first && "x_pct" in first && "url" in first)
        assertTrue(rec["activity"]!!.jsonArray.isNotEmpty())
        assertEquals("done", rec["report"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, rec["llm_calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(setOf("t1"), rec["tabs"]!!.jsonArray.map { it.jsonObject["tab_id"]!!.jsonPrimitive.content }.toSet())
    }

    private fun storeDir(store: RunLogStore): java.io.File {
        val f = RunLogStore::class.java.getDeclaredField("dir"); f.isAccessible = true
        return f.get(store) as java.io.File
    }

    @Test
    fun `the log keeps the newest runs, refuses bad ids and lists newest first`() {
        val store = testRunLog(keep = 3)
        (1..5).forEach { i ->
            store.writeRaw("run-$i", buildJsonObject { put("run_id", "run-$i"); put("task", "t$i"); put("status", "done"); put("started_at_ms", 1000L * i); put("steps_count", i) })
            java.io.File(storeDir(store), "run-$i.json").setLastModified(1_700_000_000_000L + i * 1000)
        }
        // writeRaw prunes before the new mtime is set, so one more write settles the order.
        store.writeRaw("run-6", buildJsonObject { put("run_id", "run-6"); put("task", "t6"); put("status", "error"); put("started_at_ms", 6000L) })
        val names = (storeDir(store).listFiles()!!.map { it.name }).sorted()
        assertEquals(3, names.size)
        assertTrue("run-6.json" in names && "run-1.json" !in names)
        assertNull(store.read("../etc/passwd"))
        assertNull(store.read("nope"))
        val listed = store.list(2)
        assertEquals(2, listed.size)
        assertEquals("run-6", listed[0]["run_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("run_id", "task", "status", "started_at_ms").intersect(listed[0].keys) + "x", listed[0].keys.let { setOf("run_id", "task", "status") + "x" })
    }

    @Test
    fun `browser_use_status shows one run, the live one and the recent runs`() = runTest {
        val store = testRunLog()
        val provider = LlmrpaMcpToolProvider("p", component = { null },
            headless = HeadlessRunner(FakeTools { _, _ -> error("unused") }, { null }, { null }, tabs = { emptyList() }, activeTabId = { null }, drivable = { true },
                openTab = { _, _ -> null }, reveal = { }, locks = TabLocks(), runs = RunHistory(), runLog = store),
            runs = RunHistory(), runLog = store)
        val tool = provider.tools().first { it.name == "browser_use_status" }
        assertTrue(tool.readOnly)
        suspend fun call(raw: String) = tool.handler.call(ai.rever.boss.plugin.api.McpToolArgs(emptyMap(), raw))
        val schema = json(tool.inputSchema)
        assertEquals(setOf("run_id", "limit", "include_activity"), schema["properties"]!!.jsonObject.keys)
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.content.toBoolean())

        // Nothing yet.
        val empty = json(call("{}").text)
        assertTrue(empty["live"] is kotlinx.serialization.json.JsonNull)
        assertEquals(0, empty["runs"]!!.jsonArray.size)

        val run = UseRun("find x")
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "DONE"))
        var liveText = ""
        browser.onAction = { type, _ -> if (type == "scroll_by") liveText = kotlinx.coroutines.runBlocking { call("{}").text } }
        val report = flow(browser, FakeTabs(), mutableListOf(), log = store).runComputerUse(UseRequest("find x"), run)
        // While it ran: the live run, with its activity cut to a count.
        val live = json(liveText)["live"]!!.jsonObject
        assertEquals(run.id, live["run_id"]!!.jsonPrimitive.content)
        assertEquals("running", live["status"]!!.jsonPrimitive.content)
        assertTrue("activity_count" in live && "activity" !in live)

        // One run by id: full record; activity on request.
        val one = json(call("""{"run_id":"${run.id}"}""").text)
        assertEquals("done", one["status"]!!.jsonPrimitive.content)
        assertEquals(report.runId, one["run_id"]!!.jsonPrimitive.content)
        assertTrue("steps" in one && "activity" !in one && "activity_count" in one)
        assertTrue("activity" in json(call("""{"run_id":"${run.id}","include_activity":true}""").text))
        // Without an id: nothing live now, and the run listed.
        val list = json(call("""{"limit":3}""").text)
        assertTrue(list["live"] is kotlinx.serialization.json.JsonNull)
        val row = list["runs"]!!.jsonArray.single().jsonObject
        assertEquals(run.id, row["run_id"]!!.jsonPrimitive.content)
        assertEquals("done", row["status"]!!.jsonPrimitive.content)
        assertEquals("find x", row["task"]!!.jsonPrimitive.content)
        assertEquals(1, row["steps_count"]!!.jsonPrimitive.content.toInt())
        assertTrue("seconds" in row && "cost_usd" in row)
        // Unknown id and unknown argument are errors that say what to do.
        val bad = call("""{"run_id":"run-nope"}""")
        assertTrue(bad.isError && bad.text.contains(run.id))
        assertTrue(call("""{"bogus":1}""").isError)
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
