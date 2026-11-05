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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        assertEquals(390, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "small", 780))
        assertEquals(780, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "medium", 780))
        assertEquals(-1560, Cu.scrollDelta(VisionOp.SCROLL_UP, "large", 780))
        assertEquals(780, Cu.scrollDelta(VisionOp.SCROLL_DOWN, null, 780))
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
        assertEquals(setOf("task", "url", "tab_id", "new_tab", "mode", "model", "planner_model", "display_name", "max_steps", "timeout_s", "include_text", "llm_typing", "on_stuck", "consult_below", "max_consults", "inputs", "extract", "plan", "wait_s", "consult_model", "max_replans"),
            schema["properties"]!!.jsonObject.keys)
        assertEquals("task", schema["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    // ---- the whole flow, against fakes ----

    private companion object { const val ASK = "<ask the llm>" }

    private class FakeBrowser(val dir: File, val script: List<String>, val textPick: String? = "Palo Alto") : ToolInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var jevCalls = 0
        val askLlmOffered = mutableListOf<Boolean>()
        val textOptionsSeen = mutableListOf<List<String>>()
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
        /** `snapped` for the next click_at replies, as RPA Engine reports a moved click. */
        var snapReply: String? = null
        /** Like RPA Engine: a dialog waits, every verb but dialog_answer and cu_end is refused with the dialog attached. */
        var strictDialogs = false
        /** A dialog that shows up on the call after the click, as a real confirm() does. */
        var armedDialog: String? = null
        val refused = mutableListOf<String>()
        var autoDialogs: String? = null
        var armedDialogOnClick: String? = null
        /** locate_text answers by phrase; null = the engine does not have the verb. */
        var locateReply: ((String) -> String)? = { locatedFor(it) }
        private fun locatedFor(@Suppress("UNUSED_PARAMETER") phrase: String) = """{"ok":true,"located":{"found":false,"count":0,"in_view":false}}"""
        var dialogPick = "OK"
        /** Pick the dialog answer whose description contains this; else [dialogPick]. */
        var dialogPickLabel: String? = null
        var dialogConfidence: Double? = null
        /** screenshot results carry url and title (RPA Engine 1.3.5+). */
        var shotMeta = false
        val observeFailsFor = mutableSetOf<String>()
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

        /** Popup windows as listed in `popups` on every rpa_step result (a JSON array); a closed one is told once. */
        var popupsJson: String? = null
        /** Tabs whose calls fail as closed. */
        val deadTabs = mutableSetOf<String>()
        var jevDelayMs = 0L
        var modalLabel: String? = null
        /** The dialog the screenshot result says is open (RPA Engine puts `modal` in it); observe may know nothing of it. */
        var shotModalLabel: String? = null
        /** Jev's answer to the milestone question per decision call, in order (null = not given). */
        var milestoneScript: List<Double?> = emptyList()
        var currentUrlOverride: String? = null
        var currentTitle = "S"
        /** One entry per screenshot call, in order: (revealed, visible) as the engine reports them; none = the plain reply. */
        val screenshotFlags = ArrayDeque<Pair<Boolean?, Boolean?>>()
        var pageTextFn: (() -> String)? = null
        /** RPA Engine's `look` verb: false answers like an older engine (unknown action type). */
        var lookSupported = false
        /** Write real JPEGs (the marks are drawn on them) instead of a text stand-in. */
        var realImages = false
        /** The `elements` a look returns (a JSON array), and the options each look was asked with. */
        var lookElements = "[]"
        val lookArgs = mutableListOf<JsonObject>()
        /** Which mark Jev picks for click_target / type_target when those questions are asked; null = NONE. */
        var clickPick: String? = null
        var typePick: String? = null
        /** The box Jev answers with. */
        var boxJson = """{"top":0.2,"bottom":0.3,"left":0.4,"right":0.6}"""
        /** The next click_at replies fail with this error (the engine's disabled / covered refusal). */
        val clickErrors = ArrayDeque<String>()
        /** The question keys of every decision call. */
        val questionKeys = mutableListOf<Set<String>>()

        override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
            val r = invoke0(toolName, arguments)
            val pj = popupsJson
            return if (toolName == ToolNames.STEP && !r.isError && pj != null && r.text.startsWith("{")) r.copy(text = "{\"popups\":$pj," + r.text.substring(1)) else r
        }

        private suspend fun invoke0(toolName: String, arguments: JsonElement): ToolReply {
            val a = arguments.jsonObject
            calls += toolName to a
            if (toolName == ToolNames.STEP && a["tab_id"]?.jsonPrimitive?.content in deadTabs && a["action"]!!.jsonObject["type"]!!.jsonPrimitive.content != "cu_end")
                return ToolReply("""{"error":{"code":"TAB_NOT_FOUND","message":"The popup window is closed"}}""", true)
            val tabArg = a["tab_id"]?.jsonPrimitive?.content ?: "t1"
            val pageUrl = urlByTab[tabArg] ?: url
            return when (toolName) {
                ToolNames.OBSERVE -> if (tabArg in observeFailsFor) ToolReply("""{"error":{"code":"NO_BROWSER","message":"observe does not work on popup windows"}}""", true)
                    else if (strictDialogs && dialog != null) { refused += "observe"; ToolReply("""{"ok":false,"error":"A browser dialog is waiting","dialog":$dialog}""", false) }
                    else ToolReply("""{"tab_id":"$tabArg","url":"${currentUrlOverride ?: pageUrl}","title":"$currentTitle"${modalLabel?.let { ""","modal":{"label":"$it"}""" }.orEmpty()},"elements":$elements}""", false)
                ToolNames.STEP -> {
                    val action = a["action"]!!.jsonObject
                    val actionType = action["type"]!!.jsonPrimitive.content
                    if (strictDialogs && actionType != "dialog_answer" && actionType != "cu_end") {
                        if (armedDialog != null) { dialog = armedDialog; armedDialog = null }
                        if (dialog != null) { refused += actionType; return ToolReply("""{"ok":false,"error":"A browser dialog is waiting; call dialog_answer first","dialog":$dialog}""", false) }
                    }
                    onAction?.invoke(actionType, tabArg)
                    val dlg = dialog?.let { ""","dialog":$it""" }.orEmpty()
                    when (action["type"]!!.jsonPrimitive.content) {
                        "locate_text" -> locateReply?.let { ToolReply(it(action["value"]!!.jsonPrimitive.content), false) } ?: ToolReply("""{"ok":false,"error":"Unknown action type locate_text"}""", false)
                        "dialog_answer" -> { dialogAnswers += action["value"]!!.jsonPrimitive.content; dialog = null; ToolReply("""{"ok":true}""", false) }
                        "cu_end" -> ToolReply("""{"ok":true}""", false)
                        "screenshot" -> ToolReply(screenshotText(pageUrl, dlg), false)
                        "look" -> if (!lookSupported) ToolReply("""{"ok":false,"error":"Unknown action type look"}""", false) else {
                            val opts = kotlinx.serialization.json.Json.parseToJsonElement(action["value"]!!.jsonPrimitive.content).jsonObject
                            lookArgs += opts
                            val b = kotlinx.serialization.json.Json.parseToJsonElement(screenshotText(pageUrl, dlg)).jsonObject
                            val located = buildJsonObject {
                                (opts["locate"] as? JsonArray).orEmpty().forEach { ph ->
                                    val phrase = ph.jsonPrimitive.content
                                    val l = locateReply?.let { f -> kotlinx.serialization.json.Json.parseToJsonElement(f(phrase)).jsonObject["located"] } ?: kotlinx.serialization.json.Json.parseToJsonElement("""{"found":false,"count":0,"in_view":false}""")
                                    put(phrase, l ?: JsonPrimitive(false))
                                }
                            }
                            val look = buildJsonObject {
                                put("screenshot", b["screenshot"]!!)
                                put("located", located)
                                put("elements", kotlinx.serialization.json.Json.parseToJsonElement(lookElements))
                                if (opts["page_text"]?.jsonPrimitive?.content == "true") put("page_text", JsonPrimitive(pageTextFn?.invoke() ?: pageTexts[pageUrl] ?: if (pageUrl == "https://sample.com/") "Best: A, B, C" else "Text of $pageUrl"))
                            }
                            ToolReply(JsonObject(b - "screenshot" + ("look" to look)).toString(), false)
                        }
                        "focused" -> ToolReply("""{"ok":true,"focused":$focused}""", false)
                        "page_text" -> ToolReply("""{"ok":true,"text":"${pageTextFn?.invoke() ?: pageTexts[pageUrl] ?: if (pageUrl == "https://sample.com/") "Best: A, B, C" else "Text of $pageUrl"}"}""", false)
                        "click_at" -> if (clickErrors.isNotEmpty()) ToolReply("""{"ok":false,"error":"${clickErrors.removeFirst()}"}""", false) else { clickPages.removeFirstOrNull()?.let { url = it }; if (strictDialogs && armedDialogOnClick != null) { armedDialog = armedDialogOnClick; armedDialogOnClick = null }; ToolReply("""{"ok":true${autoDialogs?.let { ""","dialog_auto":$it""" }.orEmpty()}${snapReply?.let { ""","snapped":$it""" }.orEmpty()}$dlg}""", false) }
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
                        val criteria = q["dialog"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                        val key = dialogPickLabel?.let { l -> criteria.entries.first { it.value.contains(l) }.key } ?: dialogPick
                        val conf = dialogConfidence?.let { ""","confidence":$it""" }.orEmpty()
                        ToolReply("""{"response":{"answers":{"dialog":{"type":"choice","choice":"$key","probabilities":{"$key":$dialogP}$conf}},"usage":{"cost":0.002}}}""", false)
                    } else if ("text" in q) {
                        val criteria = q["text"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                        askLlmOffered += JevDecider.ASK_LLM in criteria
                        textOptionsSeen += criteria.filterKeys { it != JevDecider.NONE_OF_THESE && it != JevDecider.ASK_LLM }.values.toList()
                        val key = if (textPick == ASK) JevDecider.ASK_LLM else criteria.entries.firstOrNull { it.value == textPick }?.key ?: JevDecider.NONE_OF_THESE
                        ToolReply("""{"response":{"answers":{"text":{"type":"choice","choice":"$key","probabilities":{"$key":0.95}}},"usage":{"cost":0.001}}}""", false)
                    } else {
                        jevArgs += a
                        questionKeys += q.keys
                        if (jevDelayMs > 0) kotlinx.coroutines.delay(jevDelayMs)
                        val op = script[jevCalls.coerceAtMost(script.lastIndex)]
                        jevCalls++
                        val p = when { op.endsWith("@low") -> 0.3; op.endsWith("@mid") -> 0.55; else -> 0.9 }
                        ToolReply(reply(op.removeSuffix("@low").removeSuffix("@mid"), p, milestoneScript.getOrNull(jevCalls - 1), q), false)
                    }
                }
                else -> ToolReply("unknown", true)
            }
        }
        private fun screenshotText(pageUrl: String, dlg: String): String {
            val n = if (frozen) 0 else shots++
            val f = File(dir, "shot-$n.jpg").also {
                if (!realImages) it.writeText("shot ${if (frozen) 0 else shots}")
                else javax.imageio.ImageIO.write(java.awt.image.BufferedImage(560, 390, java.awt.image.BufferedImage.TYPE_INT_RGB).also { img -> img.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 560, 390); color = java.awt.Color(n % 255, 0, 0); fillRect(0, 0, 10, 10); dispose() } }, "jpg", it)
            }
            return """{"ok":true,"screenshot":{"path":"${f.absolutePath}","width":560,"height":390,"css_width":1120,"css_height":780,"dpr":2${if (shotMeta) ""","url":"$pageUrl","title":"Shot title"""" else ""}${if (reportScroll) ""","scroll":{"x":0,"y":$scrollY,"max_y":5000}""" else ""}${contentHash?.invoke()?.let { ""","content_hash":"$it"""" }.orEmpty()}${shotModalLabel?.let { ""","modal":{"label":"$it"}""" }.orEmpty()}}${screenshotFlags.removeFirstOrNull()?.let { (r, v) -> listOfNotNull(r?.let { ""","revealed":$it""" }, v?.let { ""","visible":$it""" }).joinToString("") }.orEmpty()}$dlg}"""
        }
        private fun pickJson(key: String, pick: String?, q: JsonObject?) =
            if (q != null && key in q) """"$key":{"type":"choice","choice":"${pick ?: Marks.NONE}","probabilities":{"${pick ?: Marks.NONE}":0.9}},""" else ""
        private fun reply(op: String, p: Double, milestone: Double? = null, q: JsonObject? = null) =
            """{"response":{"answers":{${milestone?.let { """"milestone":{"type":"noul","noul":$it},""" }.orEmpty()}${pickJson("click_target", clickPick, q)}${pickJson("type_target", typePick, q)}"operation":{"type":"choice","choice":"$op","probabilities":{"$op":$p}},""" +
                """"target":{"type":"box","box":$boxJson,"confidence":0.8},"scroll":{"type":"choice","choice":"small"}},"usage":{"cost":0.01}}}"""
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
        var snapshots = 0
        /** Called with the call number before each snapshot is returned, so a tab can appear between two checks. */
        var onSnapshot: ((Int) -> Unit)? = null
        override suspend fun snapshot(): Map<String, String> { onSnapshot?.invoke(++snapshots); return now }
    }

    private var consultReplies = ArrayDeque<String>()
    private var replanReplies = ArrayDeque<String>()
    private var extractReply = """{"answer":"A, B and C"}"""
    private val defaultCaps = setOf(AiGatewayAPI.CAPABILITY_VISION, AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
    private var gatewayCaps = defaultCaps
    private var activeInfo = AiModelInfo("OPENAI", "OpenAI", "gpt-5")
    private var catalog = emptyList<ai.rever.boss.plugin.api.AiProviderModels>()

    private fun gateway(requests: MutableList<AiRequest>, planner: String, typed: String) = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> {
            requests += request
            return Result.success(AiReply(when {
                request.system.startsWith("You plan") -> planner
                request.system.startsWith("You help a browser agent") -> consultReplies.removeFirstOrNull() ?: """{"give_up":"nothing left"}"""
                request.system.startsWith("A browser agent, Jev, is stuck") -> replanReplies.removeFirstOrNull() ?: "no idea"
                request.system.startsWith("You answer a user's") -> extractReply
                else -> typed
            }))
        }
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = gatewayCaps
        override fun activeModel(): AiModelInfo? = activeInfo
        override fun availableModels(): List<ai.rever.boss.plugin.api.AiProviderModels> = catalog
    }

    private val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Search Palo Alto restaurants and read the top ones","type_values":["Palo Alto"]}"""
    private val typedJson = """{"text":"written text"}"""

    private fun flow(browser: FakeBrowser, tabs: FakeTabs, requests: MutableList<AiRequest>, plan: String = this.plan, log: RunLogStore? = null) =
        ComputerUseFlow(browser, { gateway(requests, plan, typedJson) }, tabs, openWaitsMs = listOf(0), settleMs = 0, runLog = log, popupRecheckMs = 0)

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
        assertEquals("390", browser.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
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
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("log in", onStuck = OnStuck.CONSULT, pointerLabel = "Max", maxReplans = 0), run)
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
    fun `the screenshot goes with the consult, and without a gateway that can see nothing is consulted`() = runTest {
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        val seen = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), seen).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(1, seen.single { it.system.startsWith("You help") }.messages.single().images.size)
        // A gateway with no vision: a blind consult is no help, so there is none and the run returns.
        gatewayCaps = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
        val blind = mutableListOf<AiRequest>()
        val r = try { flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), blind).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT)) } finally { gatewayCaps = defaultCaps }
        assertEquals(0, blind.count { it.system.startsWith("You help") })
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.error!!.contains("no vision-capable model to consult"))
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
        // Even a value the person gave never goes into a private field through a consult: credentials are for them.
        val (b2, r2) = run("""{"action":{"type":"input","element":"e3","value":"{{password}}"}}""", mapOf("password" to "s3cret!"))
        assertEquals(0, b2.stepArgs("input").size)
        assertTrue(r2.steps.first().description.contains("refused"))
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
        assertEquals(15, last["scroll"]!!.jsonObject["percent_down"]!!.jsonPrimitive.content.toInt()) // 2 x 390 px of 5000
        assertEquals("scrolled to 15% of the page", last["recent_actions"]!!.jsonArray.last().jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the scroll wording prefers large for a far target and small only for something cut off`() {
        val rules = Cu.SCROLL_RULES
        assertTrue(rules.contains("Pick large when the target is not visible and its distance is unknown or far"))
        assertTrue(rules.contains("medium when it is probably within a screen or two"))
        assertTrue(rules.contains("small only to reveal something cut off at the edge"))
        assertFalse(rules.contains("close by"))
        assertEquals(listOf(0.5, 1.0, 2.0), Cu.SCROLL_AMOUNTS.values.toList())
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

    // ---- snapping clicks onto the element Jev boxed ----

    @Test
    fun `every vision click and type click sends Jev's box in css pixels as snap`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK", "TYPE_TEXT", "DONE"), textPick = "Palo Alto")
        flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", pointerLabel = "Max"))
        val clicks = browser.stepArgs("click_at")
        assertEquals(2, clicks.size)
        clicks.forEach { c ->
            val action = c["action"]!!.jsonObject
            // box left .4 right .6 top .2 bottom .3 of a 1120 x 780 viewport: "left,top,right,bottom"
            assertEquals("448,156,672,234", action["snap"]!!.jsonObject["box"]!!.jsonPrimitive.content)
            assertEquals("560,195", action["value"]!!.jsonPrimitive.content)
            assertEquals("Max", c["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        }
        // Other actions carry no snap.
        assertTrue(browser.stepArgs("type_text").none { "snap" in it["action"]!!.jsonObject })
        assertEquals("448,156,672,234", Cu.snapBox(Box(0.2, 0.3, 0.4, 0.6), 1120, 780).let { "448,156,672,234" })
        assertEquals("112,156,448,468", Cu.snapBox(Box(0.2, 0.6, 0.1, 0.4), 1120, 780))
    }

    @Test
    fun `a click that was moved is noted in the step and the run log, and one that stayed is not`() = runTest {
        val store = testRunLog()
        val run = UseRun("t")
        val moved = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { it.snapReply = """{"from":"560,195","to":"560,221","tag":"button","label":"Send"}""" }
        val report = flow(moved, FakeTabs(), mutableListOf(), log = store).runComputerUse(UseRequest("send it"), run)
        val step = report.steps.single()
        assertEquals("snapped to button 'Send'", step.note)
        assertEquals(Snapped("560,195", "560,221", "button", "Send"), step.snapped)
        val rec = store.read(run.id)!!["steps"]!!.jsonArray.single().jsonObject
        assertEquals("button", rec["snapped"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("560,221", rec["snapped"]!!.jsonObject["to"]!!.jsonPrimitive.content)
        assertEquals("snapped to button 'Send'", rec["note"]!!.jsonPrimitive.content)

        val still = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        val plain = flow(still, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).steps.single()
        assertNull(plain.note)
        assertNull(plain.snapped)
    }

    @Test
    fun `a type click that snapped onto a text field types into it and keeps the note`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "Palo Alto").also {
            it.snapReply = """{"from":"560,195","to":"540,230","tag":"input","label":"Search"}"""
        }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        val step = report.steps.single()
        assertEquals("Palo Alto", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("snapped to input 'Search'", step.note)
        // A snapped click that still is not a text field says both.
        val none = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE")).also {
            it.snapReply = """{"from":"1,1","to":"2,2","tag":"div","label":"Banner"}"""
            it.focused = """{"tag":"div","editable":false,"label":""}"""
        }
        val r2 = flow(none, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals("snapped to div 'Banner'; the box did not land on a text field; nothing typed", r2.steps.single().note)
        assertEquals(0, none.stepArgs("type_text").size)
    }

    @Test
    fun `snapped is read from the reply only when the click moved`() {
        assertNull(Cu.parseSnapped(json("""{"ok":true}""")))
        assertEquals(Snapped("a", "b", "button", "Go"), Cu.parseSnapped(json("""{"ok":true,"snapped":{"from":"a","to":"b","tag":"button","label":"Go"}}""")))
    }

    // ---- the engine refuses everything while a dialog waits ----

    @Test
    fun `a dialog that arrives after the click is answered from the last screenshot, and nothing else is asked while it waits`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { it.strictDialogs = true; it.armedDialogOnClick = confirmDialog; it.dialogPick = "OK" }
        val run = UseRun("delete it")
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("delete it"), run)
        assertEquals(UseStatus.DONE, report.status, report.error)
        // The refused screenshot was the only call made while the dialog waited: no observe, page_text, locate_text or focused.
        assertEquals(listOf("screenshot"), browser.refused)
        assertEquals(listOf("ok"), browser.dialogAnswers)
        assertEquals(listOf(VisionOp.CLICK, VisionOp.DIALOG), report.steps.map { it.op })
        assertEquals("Dialog 'Delete this item?': OK", report.steps[1].description)
        // Jev judged it on the last screenshot, with the dialog in its state and a note that it sits on top of the page.
        val dialogArgs = browser.jevArgs.first { "dialog" in it["questions"]!!.jsonObject }
        // Jev is shown the dialog drawn on that screenshot, a new file next to it that the activity lists.
        val firstShot = run.log.entries.value.first { it.image != null && "dialog" !in it.image }.image!!
        val sent = dialogArgs["assets"]!!.jsonObject[Cu.SHOT_REF]!!.jsonObject["path"]!!.jsonPrimitive.content
        assertEquals(firstShot.removeSuffix(".jpg") + "-dialog.jpg", sent)
        assertTrue(java.io.File(sent).isFile)
        assertTrue(run.log.entries.value.any { it.name == "render dialog" && it.image == sent })
        val rendered = javax.imageio.ImageIO.read(java.io.File(sent))
        assertEquals(1100 to 700, rendered.width to rendered.height) // the dialog image's own size
        assertTrue(dialogArgs["state"]!!.jsonObject["dialog"]!!.jsonObject["note"]!!.jsonPrimitive.content.contains("on top of this page"))
        // After the answer, pages are read again.
        val after = browser.calls.map { it.first to (it.second["action"]?.jsonObject?.get("type")?.jsonPrimitive?.content) }
        assertTrue(after.indexOf(ToolNames.STEP to "dialog_answer") < after.lastIndexOf(ToolNames.STEP to "screenshot"))
    }

    @Test
    fun `a prompt that arrives late is answered with a goal phrase`() = runTest {
        val prompt = """{"type":"prompt","message":"What is your name?","default_value":"","url":"https://sample.com/"}"""
        val browser = FakeBrowser(tmp(), listOf("CLICK", "DONE"), textPick = "Grace").also { it.strictDialogs = true; it.armedDialogOnClick = prompt; it.dialogPickLabel = "enter 'Grace'" }
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Say hello to Grace","type_values":[]}""")
            .runComputerUse(UseRequest("Say hello to Grace"))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertEquals(listOf("ok:Grace"), browser.dialogAnswers)
        assertEquals("Grace", report.steps[1].text)
        assertEquals("Dialog 'What is your name?': OK 'Grace'", report.steps[1].description)
        assertEquals(listOf("screenshot"), browser.refused)
    }

    @Test
    fun `a type click whose dialog shows up on the focus read is not a failure`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE")).also { it.strictDialogs = true; it.armedDialogOnClick = confirmDialog }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertTrue(report.steps[0].note!!.contains("a dialog opened"))
        assertFalse(report.steps[0].failed)
        assertEquals(1, browser.dialogAnswers.size)
        assertEquals(0, browser.stepArgs("type_text").size)
    }

    @Test
    fun `a dialog before any screenshot is answered with the safe default and auto-answered dialogs are noted`() = runTest {
        val early = FakeBrowser(tmp(), listOf("DONE")).also { it.strictDialogs = true; it.armedDialog = confirmDialog }
        val r1 = flow(early, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(listOf("cancel"), early.dialogAnswers) // nothing to show Jev, so no deleting
        assertEquals(UseStatus.DONE, r1.status, r1.error)
        assertEquals(0, early.dialogJevCalls)

        val auto = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { it.autoDialogs = """[{"type":"alert","message":"Saved","answer":"ok"}]""" }
        val r2 = flow(auto, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals("Dialog 'Saved' (alert) was answered automatically: ok", r2.steps.single().note)
    }

    // ---- scroll amounts and targets ----

    @Test
    fun `scroll amounts are half a screen, one screen and two screens`() {
        assertEquals(listOf(0.5, 1.0, 2.0), Cu.SCROLL_AMOUNTS.values.toList())
        assertEquals(390, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "small", 780))
        assertEquals(780, Cu.scrollDelta(VisionOp.SCROLL_DOWN, "medium", 780))
        assertEquals(-1560, Cu.scrollDelta(VisionOp.SCROLL_UP, "large", 780))
        assertTrue(Cu.SCROLL_RULES.contains("small: half a screen, medium: one screen, large: two screens"))
        val crit = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")["questions"]!!.jsonObject["scroll"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(listOf("Half a screen", "One screen", "Two screens"), crit.values.map { it.jsonPrimitive.content })
        assertTrue(Cu.RULES.contains("When a target from state.targets is below or above, scroll toward it"))
    }

    @Test
    fun `targets are quoted values first, then goal phrases with a number or a proper noun, at most three`() {
        assertEquals(listOf("Open item 87".let { "item 87" }), Cu.targetPhrases("Open item 87").take(1))
        val t = Cu.targetPhrases("Open item 87")
        assertTrue("item 87" in t, t.toString())
        assertFalse(t.any { it.equals("Open", true) })
        // Quoted text comes first; a phrase inside one already chosen is dropped.
        val q = Cu.targetPhrases("Find \"Blue Mug\" in the Acme Store, size 42")
        assertEquals("Blue Mug", q.first())
        assertTrue(q.size <= 3)
        assertEquals(q.size, q.map { it.lowercase() }.toSet().size)
        // Emails and addresses are not things to find on a page.
        assertTrue(Cu.targetPhrases("Write to ada@example.com about https://example.com/x").none { it.contains("@") || it.startsWith("http") })
        // No number and no proper noun: nothing to locate.
        assertEquals(emptyList(), Cu.targetPhrases("scroll down and click the button"))
        assertTrue(Cu.targetPhrases("Compare the Samsung Galaxy S24 Ultra, the Apple iPhone 15 Pro and the Google Pixel 8 Pro").size <= 3)
    }

    private fun locatedReply(found: Boolean, inView: Boolean = false, dir: String? = null, screens: Double? = null) =
        """{"ok":true,"located":{"found":$found,"count":${if (found) 1 else 0},"in_view":$inView${dir?.let { ""","direction":"$it"""" }.orEmpty()}${screens?.let { ""","screens":$it""" }.orEmpty()}}}"""

    @Test
    fun `the state carries targets with where they are, in view or screens below or not on the page`() {
        val args = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg", targets = listOf(
            Cu.Target("item 87", Cu.Located(true, 1, false, "down", 4.2)),
            Cu.Target("Blue Mug", Cu.Located(true, 1, true, null, 0.0)),
            Cu.Target("Acme", Cu.Located(false, 0, false, null, null)),
            Cu.Target("item 3", Cu.Located(true, 1, false, "up", 1.0)),
        ))
        val targets = args["state"]!!.jsonObject["targets"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content to it.jsonObject["where"]!!.jsonPrimitive.content }
        assertEquals(listOf("item 87" to "4.2 screens below", "Blue Mug" to "in view", "Acme" to "not on this page", "item 3" to "1.0 screens above"), targets)
        assertNull(Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")["state"]!!.jsonObject["targets"])
        assertEquals(Cu.Located(true, 1, false, "down", 3.5), Cu.parseLocated(json(locatedReply(true, false, "down", 3.5))))
        assertEquals(Cu.Located(true, 1, false, "up", 2.0), Cu.parseLocated(json(locatedReply(true, false, "above", 2.0))))
        assertNull(Cu.parseLocated(json("""{"ok":true}""")))
    }

    private fun locateBrowser(script: List<String>, reply: (String) -> String = { locatedReply(true, false, "down", 4.2) }) = object : FakeBrowserHook {
        val b = FakeBrowser(tmp(), script)
        val phrases = mutableListOf<String>()
        init {
            b.locateReply = { phrase -> phrases += phrase; reply(phrase) }
        }
    }

    private interface FakeBrowserHook

    @Test
    fun `at most three phrases are located per step, answers are cached by page state, and Jev sees them`() = runTest {
        val h = locateBrowser(listOf("WAIT", "WAIT", "DONE"))
        h.b.contentHash = { "same-content" }
        val goal = "Find \"Blue Mug\", item 87 and the Acme Store 9"
        flow(h.b, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"${goal.replace("\"", "\\\"")}","type_values":[]}""")
            .runComputerUse(UseRequest("t", maxSteps = 10))
        // Three steps asked about the same unchanged page: three phrases located once, not nine times.
        assertEquals(3, h.phrases.size)
        assertEquals(Cu.targetPhrases(goal, "https://sample.com/"), h.phrases)
        val state = h.b.jevArgs.first()["state"]!!.jsonObject
        assertEquals(3, state["targets"]!!.jsonArray.size)
        assertEquals("4.2 screens below", state["targets"]!!.jsonArray[0].jsonObject["where"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new scroll position or content asks again`() = runTest {
        val h = locateBrowser(listOf("SCROLL_DOWN", "SCROLL_DOWN", "DONE"))
        h.b.reportScroll = true
        flow(h.b, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Open item 87","type_values":[]}""").runComputerUse(UseRequest("t", maxSteps = 10))
        val perStep = Cu.targetPhrases("Open item 87", "https://sample.com/").size
        assertEquals(perStep * 3, h.phrases.size) // the page moved each time
    }

    @Test
    fun `an engine without locate_text is asked once and the run goes on without targets`() = runTest {
        val b = FakeBrowser(tmp(), listOf("WAIT", "WAIT", "DONE")).also { it.locateReply = null }
        val report = flow(b, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Open item 87","type_values":[]}""").runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(1, b.stepArgs("locate_text").size)
        assertNull(b.jevArgs.first()["state"]!!.jsonObject["targets"])
    }

    @Test
    fun `one target two or more screens away in the chosen direction is scrolled to directly`() = runTest {
        val h = locateBrowser(listOf("SCROLL_DOWN", "DONE"), reply = { locatedReply(true, false, "down", 4.2) })
        h.b.reportScroll = true
        val report = flow(h.b, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"item 87","type_values":[]}""").runComputerUse(UseRequest("t", maxSteps = 5))
        // min(4.2 - 0.5, 3) = 3 screens of 780 px, not the 390 px of "small".
        assertEquals("2340", h.b.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("amount 3.0 screens toward 'item 87' (it was 4.2 screens away)", report.steps.single().note)
        assertTrue(report.steps.single().description.contains("3.0 screens toward 'item 87'"))
    }

    @Test
    fun `the direct scroll goes to the first off-screen target the current milestone names, toward it, at least half a screen`() {
        val far = Cu.Target("Tres Leches", Cu.Located(true, 1, false, "down", 2.5))
        assertEquals(2.0, Cu.scrollToTarget(listOf(far), VisionOp.SCROLL_DOWN, "Tres Leches is in the cart")!!.second)
        assertEquals(-3.0, Cu.scrollToTarget(listOf(Cu.Target("Tres Leches", Cu.Located(true, 1, false, "up", 9.0))), VisionOp.SCROLL_UP, "Tres Leches is in the cart")!!.second)
        // Toward the target, whatever direction Jev chose; a near one still moves half a screen.
        assertEquals(2.0, Cu.scrollToTarget(listOf(far), VisionOp.SCROLL_UP, "Tres Leches is in the cart")!!.second)
        assertEquals(0.5, Cu.scrollToTarget(listOf(Cu.Target("Tres Leches", Cu.Located(true, 1, false, "down", 0.8))), VisionOp.SCROLL_DOWN, "Tres Leches")!!.second)
        // Several found: the first one the milestone names and that is not in view, not "none because two".
        val magnolia = Cu.Target("Magnolia Bakery", Cu.Located(true, 1, false, "up", 6.0))
        val inView = Cu.Target("Brownie", Cu.Located(true, 1, true, null, 0.0))
        val pick = Cu.scrollToTarget(listOf(magnolia, inView, far), VisionOp.SCROLL_DOWN, "Tres Leches is in the cart")!!
        assertEquals("Tres Leches", pick.first.text)
        assertEquals(2.0, pick.second)
        // Nothing the milestone names is off screen: Jev's own amount stands.
        assertNull(Cu.scrollToTarget(listOf(magnolia, inView), VisionOp.SCROLL_DOWN, "Tres Leches is in the cart"))
        assertNull(Cu.scrollToTarget(listOf(Cu.Target("Tres Leches", Cu.Located(true, 1, true, null, 0.0))), VisionOp.SCROLL_DOWN, "Tres Leches"))
        assertNull(Cu.scrollToTarget(listOf(Cu.Target("Tres Leches", Cu.Located(false, 0, false, null, null))), VisionOp.SCROLL_DOWN, "Tres Leches"))
        assertNull(Cu.scrollToTarget(emptyList(), VisionOp.SCROLL_DOWN, "Tres Leches"))
        assertNull(Cu.scrollToTarget(listOf(far), VisionOp.CLICK, "Tres Leches"))
        // With no milestone only a single off-screen target counts.
        assertEquals(2.0, Cu.scrollToTarget(listOf(far, inView), VisionOp.SCROLL_DOWN, null)!!.second)
        assertNull(Cu.scrollToTarget(listOf(far, magnolia), VisionOp.SCROLL_DOWN, null))
    }

    @Test
    fun `text is saved whenever the content changed after an action, once per page state, without COLLECT`() = runTest {
        var n = 0
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "SCROLL_DOWN", "DONE")).also { b ->
            b.contentHash = { "c$n" }
            b.pageTextFn = { "Restaurants page $n" }
            // The first and third scroll load more; the second changes nothing.
            b.onAction = { type, _ -> if (type == "scroll_by" && b.stepArgs("scroll_by").size != 2) n++ }
        }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status)
        val auto = report.collected.filter { it.auto }.map { it.text }
        assertEquals(listOf("Restaurants page 1", "Restaurants page 2"), auto.take(2))
        assertTrue(report.steps.none { it.op == VisionOp.COLLECT })
        // The same text is kept once however many times it was read.
        assertEquals(report.collected.size, report.collected.map { it.text }.toSet().size)
    }

    @Test
    fun `Jev is told what has been collected and to pick COLLECT then DONE for information`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("COLLECT", "DONE"))
        flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        val first = browser.jevArgs[0]["state"]!!.jsonObject["collected"]!!.jsonObject
        assertEquals(0, first["pages"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, first["chars"]!!.jsonPrimitive.content.toInt())
        assertNull(first["last_step"])
        val second = browser.jevArgs[1]["state"]!!.jsonObject["collected"]!!.jsonObject
        assertEquals(1, second["pages"]!!.jsonPrimitive.content.toInt())
        assertEquals("Best: A, B, C".length, second["chars"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, second["last_step"]!!.jsonPrimitive.content.toInt())
        assertTrue(Cu.RULES.contains("pick COLLECT and then DONE"))
        assertTrue(Cu.RULES.contains("Do not keep scrolling for perfect information"))
    }

    @Test
    fun `extract still answers when the run ends as budget, needs input or blocked, marked partial, and not after an error`() = runTest {
        suspend fun run(script: List<String>, maxSteps: Int = 10, model: String = Cu.VISION_DEFAULT_MODEL): Pair<UseReport, List<AiRequest>> {
            val reqs = mutableListOf<AiRequest>()
            val r = flow(FakeBrowser(tmp(), script), FakeTabs(), reqs).runComputerUse(UseRequest("what is listed", extract = true, maxSteps = maxSteps, jevModel = model))
            return r to reqs
        }
        val (budget, rb) = run(listOf("COLLECT", "WAIT", "WAIT", "WAIT"), maxSteps = 2)
        assertEquals(UseStatus.BUDGET, budget.status)
        assertEquals("A, B and C (partial run)", budget.answer)
        assertEquals(1, rb.count { it.system.startsWith("You answer a user's") })
        val (needs, _) = run(listOf("COLLECT", "BLOCKED"))
        assertEquals(UseStatus.NEEDS_INPUT, needs.status)
        assertEquals("A, B and C (partial run)", needs.answer)
        val (done, _) = run(listOf("COLLECT", "DONE"))
        assertEquals("A, B and C", done.answer)
        val (failed, rf) = run(listOf("DONE"), model = "typesafe/jev-1.13")
        assertEquals(UseStatus.ERROR, failed.status)
        assertNull(failed.answer)
        assertEquals(0, rf.count { it.system.startsWith("You answer a user's") })
        assertTrue(budget.toJson()["answer"]!!.jsonPrimitive.content.endsWith("(partial run)"))
    }

    @Test
    fun `the planner is told to keep the goal short and literal and to prefer a URL that applies sorting`() {
        assertTrue(Cu.PLAN_SYSTEM.contains("short and literal"))
        assertTrue(Cu.PLAN_SYSTEM.contains("Read the names and ratings of the first 5 Indian restaurants listed"))
        assertTrue(Cu.PLAN_SYSTEM.contains("cannot judge subtle filters"))
        assertTrue(Cu.PLAN_SYSTEM.contains("already applies the sorting"))
    }

    // ---- a call that outlives its caller ----

    private fun headless(browser: FakeBrowser, store: RunLogStore) = HeadlessRunner(browser, { gateway(mutableListOf(), plan, typedJson) }, { null },
        tabs = { emptyList() }, activeTabId = { null }, drivable = { true }, openTab = { _, _ -> "t1" }, reveal = { }, locks = TabLocks(), runs = RunHistory(), runLog = store,
        limits = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = listOf(0)))

    private fun useArgs(waitS: Int, extra: BrowserUseArgs.() -> BrowserUseArgs = { this }) = BrowserUseArgs.parse("""{"task":"find places","url":"https://sample.com/","plan":false}""").getOrThrow().copy(waitS = waitS).extra()

    private suspend fun awaitStatus(store: RunLogStore, id: String, want: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        var status = ""
        for (i in 1..200) {
            status = store.read(id)?.get("status")?.jsonPrimitive?.content.orEmpty()
            if (status == want) break
            kotlinx.coroutines.delay(25)
        }
        status
    }

    @Test
    fun `a run that outlasts wait_s returns running at once and goes on, and browser_use_status then returns the report`() = runTest {
        val store = testRunLog()
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "DONE")).also { it.jevDelayMs = 150 }
        val h = headless(browser, store)
        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { h.browserUse(useArgs(waitS = 0)).getOrThrow() }
        val running = (outcome as HeadlessRunner.UseOutcome.Running).run
        val json = UseReport.running(running)
        assertEquals("running", json["status"]!!.jsonPrimitive.content)
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(running.id, json["run_id"]!!.jsonPrimitive.content)
        assertTrue(json["summary"]!!.jsonPrimitive.content.startsWith("Still running after "))
        assertTrue(json["summary"]!!.jsonPrimitive.content.endsWith("poll browser_use_status with run_id"))
        assertTrue("steps" in json)
        // It finishes by itself.
        assertEquals("done", awaitStatus(store, running.id, "done"))
        val rec = store.read(running.id)!!
        assertEquals("done", rec["report"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(2, rec["steps_count"]!!.jsonPrimitive.content.toInt())
        // A call that waits long enough gets the report itself.
        val quick = FakeBrowser(tmp(), listOf("DONE"))
        val finished = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { headless(quick, store).browserUse(useArgs(waitS = 30)).getOrThrow() }
        assertEquals(UseStatus.DONE, (finished as HeadlessRunner.UseOutcome.Finished).report.status)
    }

    @Test
    fun `cancelling the waiting call never cancels the run`() = runTest {
        val store = testRunLog()
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "DONE")).also { it.jevDelayMs = 200 }
        val h = headless(browser, store)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val call = launch { h.browserUse(useArgs(waitS = 30)) }
            kotlinx.coroutines.delay(250)
            call.cancelAndJoin() // the MCP client gave up
        }
        val id = store.list(5).single()["run_id"]!!.jsonPrimitive.content
        assertEquals("done", awaitStatus(store, id, "done"))
        assertEquals(2, store.read(id)!!["steps_count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wait_s is an argument from 5 to 600 seconds, default 45`() {
        assertEquals(45, BrowserUseArgs.parse("""{"task":"t"}""").getOrThrow().waitS)
        assertEquals(5, BrowserUseArgs.parse("""{"task":"t","wait_s":1}""").getOrThrow().waitS)
        assertEquals(600, BrowserUseArgs.parse("""{"task":"t","wait_s":9999}""").getOrThrow().waitS)
        assertTrue(BrowserUseArgs.parse("""{"task":"t","wait_s":"soon"}""").isFailure)
        assertTrue(LlmrpaMcpToolProvider.BROWSER_USE_SCHEMA.contains("\"wait_s\""))
    }

    // ---- the cursor overlay is in the screenshot ----

    @Test
    fun `Jev is told the labelled purple arrow is its own cursor, in both questions and the consult prompt`() = runTest {
        val note = "A purple arrow labelled 'Max' is your own cursor overlay, not part of the page; ignore it and never box or click it."
        assertEquals(note, Cu.cursorNote("Max"))
        assertTrue(Cu.cursorNote("").contains("labelled 'Jev'"))
        val args = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg", pointerLabel = "Max")["questions"]!!.jsonObject
        assertTrue(args["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains(note))
        assertTrue(args["target"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains(note))
        // The default label is the default pointer name.
        assertTrue(Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")["questions"]!!.jsonObject["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("labelled 'Jev'"))
        // A run sends its own display name.
        val browser = FakeBrowser(tmp(), listOf("BLOCKED", "DONE")).also { }
        consultReplies = ArrayDeque(listOf("""{"hint":"go on"}"""))
        val requests = mutableListOf<AiRequest>()
        flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("t", pointerLabel = "Max", onStuck = OnStuck.CONSULT))
        assertTrue(browser.jevArgs.first()["questions"]!!.jsonObject["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("labelled 'Max'"))
        assertTrue(requests.single { it.system.startsWith("You help a browser agent") }.messages.single().text.contains(note))
        // The overlay is not hidden by this plugin: no hide_overlay request.
        assertEquals(0, browser.calls.count { it.second.toString().contains("hide_overlay") })
    }

    @Test
    fun `the prompts name the pointer exactly as it is rendered, clipped to 40 characters, in every mode`() = runTest {
        val long = "Research bot · Palo Alto restaurants and reviews"
        assertTrue(long.length > 40)
        val clipped = long.take(40).trim()
        assertEquals(clipped, Cu.pointerLabel(long))
        assertEquals("Jev", Cu.pointerLabel("   "))
        assertEquals("Jev", Cu.pointerLabel(""))
        assertEquals("Bot", Cu.pointerLabel("  Bot  "))
        assertEquals(40, Cu.pointerLabel("x".repeat(90)).length)
        // Vision: the label sent with every action and the label in the prompts are the same string.
        consultReplies = ArrayDeque(listOf("""{"hint":"go on"}"""))
        val requests = mutableListOf<AiRequest>()
        val browser = FakeBrowser(tmp(), listOf("CLICK", "BLOCKED", "DONE"))
        flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("t", pointerLabel = long, onStuck = OnStuck.CONSULT))
        val sent = browser.stepArgs("click_at").first()["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content
        assertEquals(clipped, sent)
        val q = browser.jevArgs.first()["questions"]!!.jsonObject
        assertTrue(q["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("labelled '$sent'"))
        assertTrue(q["target"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("labelled '$sent'"))
        assertTrue(requests.single { it.system.startsWith("You help a browser agent") }.messages.single().text.contains("labelled '$sent'"))
        assertFalse(browser.jevArgs.any { it.toString().contains(long) })
        // A blank display name falls back to the default, in the pointer and in the prompt.
        val blank = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        flow(blank, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", pointerLabel = "  "))
        assertEquals("Jev", blank.stepArgs("click_at").first()["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(blank.jevArgs.first()["questions"]!!.jsonObject["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("labelled 'Jev'"))
        // DOM mode sends the same normalised label.
        val dom = FakeTools(decide = { _, _ -> Triple("The task is complete", 0.9, null) })
        TaskRunner(dom, JevDecider(dom, JEV), "t1", "Search for 'wireless keyboard'", pointerLabel = long) { Answer.Stop }.run()
        // Done on the first look: no action, so run a click through to see the label.
        val dom2 = FakeTools(decide = { c, call -> if (call == 0) Triple(c.values.first { it.startsWith("Click") || it.startsWith("Open") }, 0.9, null) else Triple("The task is complete", 0.9, null) })
        TaskRunner(dom2, JevDecider(dom2, JEV), "t1", "Open the sign in link", pointerLabel = long) { Answer.Stop }.run()
        assertTrue(dom2.stepArgs.isNotEmpty())
        assertEquals(clipped, dom2.stepArgs.first()["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
    }

    // ---- free text, whole clauses, and the retype loop ----

    private val messageGoal = "Fill the contact form and send a message saying the export button is broken"

    @Test
    fun `the goal's clause after saying, that, about, asking or a colon is a whole-sentence option`() {
        assertEquals(listOf("The export button is broken"), Cu.clauseOptions(messageGoal))
        assertEquals(listOf("Hello there, I need help"), Cu.clauseOptions("Write to support: hello there, I need help."))
        assertEquals(listOf("Pricing for teams"), Cu.clauseOptions("Ask sales about pricing for teams"))
        // Up to 20 words, with no cap of 4.
        val long = Cu.clauseOptions("Send a message saying " + (1..30).joinToString(" ") { "w$it" }).single()
        assertEquals(20, long.split(" ").size)
        assertEquals(emptyList(), Cu.clauseOptions("Open the orders page"))
        // A secret the goal names is never part of an option.
        assertTrue(Cu.clauseOptions("Log in saying password hunter2x please").none { it.contains("hunter2x") })
        // They join every text choice, but never a private field's.
        val opts = Cu.textOptions(messageGoal, emptyList(), "https://a.com", sensitive = false)
        assertTrue("The export button is broken" in opts)
        assertTrue("export button" in opts) // the 4-word phrases are still there
        assertFalse("The export button is broken" in Cu.textOptions(messageGoal, listOf("hunter2"), "https://a.com", sensitive = true))
    }

    @Test
    fun `a textarea, a contenteditable and a message-like input are free-text fields, other fields are not`() {
        fun f(tag: String, label: String, editable: Boolean = true) = Focused(tag, editable, label, null, null)
        assertTrue(Cu.freeTextField(f("textarea", "")))
        assertTrue(Cu.freeTextField(f("div", "", editable = true)))
        assertTrue(Cu.freeTextField(f("input", "Your Message")))
        assertTrue(Cu.freeTextField(f("input", "Additional details")))
        assertTrue(Cu.freeTextField(f("input", "Reason for contact")))
        assertFalse(Cu.freeTextField(f("input", "Email")))
        assertFalse(Cu.freeTextField(f("input", "Search")))
        assertFalse(Cu.freeTextField(f("select", "Topic", editable = false)))
        assertFalse(Cu.freeTextField(f("div", "", editable = false)))
    }

    @Test
    fun `the same text typed again into the same field with nothing changed is no progress after two`() {
        fun typed(text: String, field: String = "message|textarea", x: Int = 50, y: Int = 60, content: String? = "c1") =
            act(VisionOp.TYPE_TEXT, Seen("https://a.com/", 0.0, "h", content), x, y, text).copy(field = field)
        val now = Seen("https://a.com/", 0.0, "caret-blink", "c1")
        assertNotNull(Cu.noProgress(listOf(typed("export button"), typed("export button")), now))
        // One typing is not a loop; another text, another field or a click elsewhere is not the same.
        assertNull(Cu.noProgress(listOf(typed("export button")), now))
        assertNull(Cu.noProgress(listOf(typed("export button"), typed("the export button is broken")), now))
        assertNull(Cu.noProgress(listOf(typed("export button"), typed("export button", field = "email|input")), now))
        assertNull(Cu.noProgress(listOf(typed("export button"), typed("export button", x = 58)), now))
        // Within 3% is the same spot.
        assertNotNull(Cu.noProgress(listOf(typed("export button"), typed("export button", x = 52, y = 58)), now))
        // The page changed with the last typing: it did something.
        assertNull(Cu.noProgress(listOf(typed("export button"), typed("export button", content = "c0")), now))
    }

    @Test
    fun `a retyped phrase in a message box stops the run instead of looping until the budget`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT"), textPick = "export button").also {
            it.focused = """{"tag":"textarea","editable":true,"label":"Message"}"""
            it.contentHash = { "form-state" }
        }
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"$messageGoal","type_values":[]}""")
            .runComputerUse(UseRequest("t", llmTyping = false, maxSteps = 15))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(2, report.steps.size)
        assertEquals(2, browser.stepArgs("type_text").size)
        assertTrue(report.blocker!!.reason.contains("same text typed twice into the same field"))
    }

    @Test
    fun `a textarea gets a sentence written by the model from the goal, not a phrase`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE")).also { it.focused = """{"tag":"textarea","editable":true,"label":"Message"}""" }
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests, plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"$messageGoal","type_values":[]}""").runComputerUse(UseRequest("t"))
        assertEquals("written text", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(ComputerUseFlow.WRITTEN_BY_MODEL, report.steps.single().textSource)
        // No phrase choice was made: the writing model was asked, with the field and the goal's own clause.
        assertEquals(0, browser.textOptionsSeen.size)
        val ask = requests.single { it.system.startsWith("You write") }.messages.single().text
        assertTrue(ask.contains("free-text field"))
        assertTrue(ask.contains("The export button is broken"))
        assertTrue(ask.contains("Field: label=\"Message\""))
        // A private field never gets it, and a given value wins over writing.
        val given = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "Please call me").also { it.focused = """{"tag":"textarea","editable":true,"label":"Message"}""" }
        val r2 = mutableListOf<AiRequest>()
        flow(given, FakeTabs(), r2, plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Send my note","type_values":[]}""").runComputerUse(UseRequest("t", inputs = mapOf("note" to "Please call me")))
        assertEquals("Please call me", given.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(0, r2.count { it.system.startsWith("You write") })
    }

    @Test
    fun `with typing by the model off, the goal's clause is offered as an option for a message box`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "The export button is broken").also { it.focused = """{"tag":"textarea","editable":true,"label":"Message"}""" }
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests, plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"$messageGoal","type_values":[]}""").runComputerUse(UseRequest("t", llmTyping = false))
        assertTrue("The export button is broken" in browser.textOptionsSeen.single())
        assertEquals("The export button is broken", browser.stepArgs("type_text").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertNull(report.steps.single().textSource)
        assertEquals(0, requests.count { it.system.startsWith("You write") })
        assertEquals(listOf(false), browser.askLlmOffered)
    }

    // ---- the dialog drawn into the image, concrete answers, memory ----

    private val promptGoal = "Click Confirm delete and confirm it. Then click Prompt name and enter the name \"Grace\"."

    @Test
    fun `the dialog is painted onto the screenshot like the browser shows it, on its own 1100 x 700 canvas with a wide box and large text`() {
        val dir = tmp()
        val src = java.io.File(dir, "page.jpg")
        val page = java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB).also { img -> img.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 800, 600); dispose() } }
        javax.imageio.ImageIO.write(page, "jpg", src)
        val d = Cu.DialogInfo("prompt", "What is your name?", "Ada", null, null, "http://127.0.0.1:8811/popup.html")
        val out = DialogImage.render(src.absolutePath, d, 560, 390)!!
        // Kept for a look: build/reports/visual.
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/visual"))
        java.io.File(out).copyTo(java.io.File("build/reports/visual/llmrpa-dialog-prompt.jpg"), overwrite = true)
        DialogImage.render(src.absolutePath, d.copy(type = "confirm", message = "Delete this item?", defaultValue = null, url = "http://127.0.0.1:8811/popup.html"), 560, 390)
            ?.let { java.io.File(it).copyTo(java.io.File("build/reports/visual/llmrpa-dialog-confirm.jpg"), overwrite = true) }
        assertEquals(java.io.File(dir, "page-dialog.jpg").absolutePath, out)
        val img = javax.imageio.ImageIO.read(java.io.File(out))
        // Its own size, not the page shot's (800 x 600 here; the live look image is 1100 px wide).
        assertEquals(1100 to 700, img.width to img.height)
        assertEquals(1100 to 700, DialogImage.WIDTH to DialogImage.HEIGHT)
        // The page is dimmed (white to well under it) and the box is white in the middle area.
        fun lum(x: Int, y: Int) = java.awt.Color(img.getRGB(x, y)).let { (it.red + it.green + it.blue) / 3 }
        assertTrue(lum(5, 5) < 200, "page corner dimmed: ${lum(5, 5)}")
        val boxLum = (0 until 40).map { lum(550 + it * 4 - 80, 250) }
        assertTrue(boxLum.any { it > 230 }, "a white box in the middle")
        // The box is about 70% of the width (770 px, from x=165): white just inside its edges, the dimmed page just outside.
        val row = (150..650 step 2).first { y -> lum(550, y) > 230 } + 20
        assertTrue(lum(175, row) > 230 && lum(925, row) > 230, "box reaches ${lum(175, row)} / ${lum(925, row)}")
        assertTrue(lum(150, row) < 200 && lum(950, row) < 200, "page dimmed outside the box")
        // Large text: dark glyph rows at least 20 px tall somewhere in the box's body (28 px font).
        val dark = (0 until 700).filter { y -> (180..920).any { x -> lum(x, y) < 90 } }
        val runs = dark.fold(mutableListOf<MutableList<Int>>()) { acc, y -> if (acc.isNotEmpty() && acc.last().last() == y - 1) acc.last() += y else acc += mutableListOf(y); acc }
        assertTrue(runs.any { it.size >= 18 }, "text rows: ${runs.map { it.size }}")
        // An alert, a confirm and an unreadable source all draw something, the last on a blank page of the given size.
        assertNotNull(DialogImage.render(src.absolutePath, d.copy(type = "alert", message = "Heads up"), 0, 0))
        assertNotNull(DialogImage.render(src.absolutePath, d.copy(type = "confirm", message = "Delete this item? ".repeat(20), okText = "Delete", cancelText = "Keep"), 0, 0))
        val blank = DialogImage.render(java.io.File(dir, "not-an-image.jpg").also { it.writeText("x") }.absolutePath, d, 560, 390)!!
        assertEquals(1100 to 700, javax.imageio.ImageIO.read(java.io.File(blank)).let { it.width to it.height })
    }

    @Test
    fun `a prompt offers OK, enter each candidate text and cancel, and a confirm offers its own buttons`() {
        val texts = Cu.textOptions(promptGoal, emptyList(), "http://127.0.0.1:8811/", sensitive = false)
        assertTrue("Grace" in texts)
        val prompt = Cu.DialogInfo("prompt", "What is your name?", "", null, null, "http://127.0.0.1:8811/")
        val crit = Cu.dialogArgs("m", promptGoal, "u", "t", emptyList(), "/x.jpg", prompt, texts = texts.take(8), askLlm = true)["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject
        val labels = crit.values.map { it.jsonPrimitive.content }
        assertTrue("OK, enter 'Grace' as the answer to 'What is your name?'" in labels, labels.toString())
        assertTrue("OK, enter text written by the writing model as the answer to 'What is your name?'" in labels)
        assertEquals("Cancel \u2014 don't answer 'What is your name?'", labels.last())
        assertEquals("OK_LLM", crit.keys.toList()[crit.size - 2])
        // Without typing by the model there is no such option.
        assertFalse(Cu.dialogArgs("m", "g", "u", "t", emptyList(), "/x.jpg", prompt, texts = listOf("Grace"), askLlm = false)["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject.keys.contains("OK_LLM"))
        // A confirm: OK and Cancel, named by the dialog's buttons.
        val confirm = Cu.dialogArgs("m", "g", "u", "t", emptyList(), "/x.jpg", Cu.DialogInfo("confirm", "Delete?", null, "Delete", "Keep", null))["questions"]!!.jsonObject["dialog"]!!.jsonObject
        assertEquals(listOf("OK", "CANCEL"), confirm["criteria"]!!.jsonObject.keys.toList())
        assertEquals("Delete \u2014 answer YES to 'Delete?'", confirm["criteria"]!!.jsonObject["OK"]!!.jsonPrimitive.content)
        assertEquals("Keep \u2014 answer NO to 'Delete?'", confirm["criteria"]!!.jsonObject["CANCEL"]!!.jsonPrimitive.content)
        assertTrue(confirm["instructions"]!!.jsonPrimitive.content.contains("A browser dialog (confirm) is open on top of the page (shown in the image)"))
        // The goal and the message are in the question: "which answer advances the goal?".
        assertTrue(confirm["instructions"]!!.jsonPrimitive.content.startsWith("Goal: g\nThe browser asks: 'Delete?'. Which answer advances the goal?"))
        // The site's own button names are used when it gives them, OK and Cancel otherwise.
        val plainConfirm = Cu.dialogArgs("m", "g", "u", "t", emptyList(), "/x.jpg", Cu.DialogInfo("confirm", "Delete this item?", null, null, null, null))["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals("OK \u2014 answer YES to 'Delete this item?'", plainConfirm["OK"]!!.jsonPrimitive.content)
        assertEquals("Cancel \u2014 answer NO to 'Delete this item?'", plainConfirm["CANCEL"]!!.jsonPrimitive.content)
        val leave = Cu.dialogArgs("m", "g", "u", "t", emptyList(), "/x.jpg", Cu.DialogInfo("beforeunload", "Changes may be lost", null, null, null, null))["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(mapOf("OK" to "Leave this page", "CANCEL" to "Stay on this page"), leave.mapValues { it.value.jsonPrimitive.content })
        // A prompt with no text to offer still says what is being answered.
        val bare = Cu.dialogArgs("m", "g", "u", "t", emptyList(), "/x.jpg", prompt)["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject
        assertTrue(bare["OK"]!!.jsonPrimitive.content.contains("'What is your name?'") && bare["CANCEL"]!!.jsonPrimitive.content.contains("don't answer"))
        // The choice maps back to the text, to the writing model, or to cancel.
        fun reply(choice: String) = json("""{"response":{"answers":{"dialog":{"choice":"$choice","probabilities":{"$choice":0.8},"confidence":0.7}},"usage":{"cost":0.001}}}""")
        assertEquals(Cu.DialogChoice(true, 1, false, 0.8, 0.7, 0.001), Cu.parseDialogChoice(reply("OK_2"), 3))
        assertTrue(Cu.parseDialogChoice(reply("OK_LLM"), 3).askLlm)
        assertFalse(Cu.parseDialogChoice(reply("CANCEL"), 3).ok)
        assertTrue(runCatching { Cu.parseDialogChoice(reply("OK_9"), 3) }.isFailure)
    }

    @Test
    fun `the prompt in the live scenario is answered with the quoted name chosen in the dialog question`() = runTest {
        val prompt = """{"type":"prompt","message":"What is your name?","default_value":"","url":"http://127.0.0.1:8811/popup.html"}"""
        val browser = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { it.strictDialogs = true; it.armedDialogOnClick = prompt; it.dialogPickLabel = "enter 'Grace'" }
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Click Prompt name and enter the name \"Grace\".","type_values":[]}""")
            .runComputerUse(UseRequest("t"))
        assertEquals(listOf("ok:Grace"), browser.dialogAnswers)
        assertEquals("Dialog 'What is your name?': OK 'Grace'", report.steps[1].description)
        val labels = browser.jevArgs.first { "dialog" in it["questions"]!!.jsonObject }["questions"]!!.jsonObject["dialog"]!!.jsonObject["criteria"]!!.jsonObject.values.map { it.jsonPrimitive.content }
        assertTrue("OK, enter 'Grace' as the answer to 'What is your name?'" in labels)
    }

    @Test
    fun `what changed on the page after a step is remembered in the recent actions`() {
        assertEquals("page now shows 'Deleted'", Cu.changeSummary("Nothing yet\nDelete", "Deleted\nDelete"))
        assertEquals("page now shows 'A / B'", Cu.changeSummary("x", "x\nA\nB\nC"))
        assertEquals("'Spinner' is gone", Cu.changeSummary("Spinner\nx", "x"))
        assertNull(Cu.changeSummary("same", "same"))
        assertNull(Cu.changeSummary(null, "x"))
        assertTrue(Cu.changeSummary("a", "b".repeat(300))!!.length <= "page now shows ''".length + 80)
        val step = UseStep(1, VisionOp.CLICK, xPct = 50, yPct = 20, confidence = 0.9, ms = 1, result = "page now shows 'Deleted'")
        assertEquals("page now shows 'Deleted'", Cu.recentAction(step)["result"]!!.jsonPrimitive.content)
        val dialog = UseStep(2, VisionOp.DIALOG, confidence = 1.0, ms = 1, dialogMessage = "Delete this item?", dialogAnswer = "OK", result = "page now shows 'Deleted'")
        val r = Cu.recentAction(dialog)
        assertEquals("DIALOG", r["operation"]!!.jsonPrimitive.content)
        assertEquals("Delete this item?", r["dialog"]!!.jsonPrimitive.content)
        assertEquals("OK", r["answer"]!!.jsonPrimitive.content)
        assertEquals("page now shows 'Deleted'", r["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a click that changed the page, and a dialog answered, carry what came of them into Jev's next state`() = runTest {
        var n = 0
        val click = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.contentHash = { "c$n" }
            b.pageTextFn = { if (n == 0) "Nothing yet" else "Deleted" }
            b.onAction = { type, _ -> if (type == "click_at") n++ }
        }
        flow(click, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals("page now shows 'Deleted'", click.jevArgs.last()["state"]!!.jsonObject["recent_actions"]!!.jsonArray.single().jsonObject["result"]!!.jsonPrimitive.content)

        // The confirm: Jev answers it, the page changes, and the next decision remembers both steps.
        var m = 0
        val confirm = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.strictDialogs = true; b.armedDialogOnClick = confirmDialog
            b.contentHash = { "d$m" }
            b.pageTextFn = { if (m == 0) "Nothing yet" else "Deleted" }
            b.onAction = { type, _ -> if (type == "dialog_answer") m++ }
        }
        flow(confirm, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        val recent = confirm.jevArgs.last()["state"]!!.jsonObject["recent_actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("CLICK", "DIALOG"), recent.map { it["operation"]!!.jsonPrimitive.content })
        val dlg = recent[1]
        assertEquals("Delete this item?", dlg["dialog"]!!.jsonPrimitive.content)
        assertEquals("OK", dlg["answer"]!!.jsonPrimitive.content)
        assertEquals("page now shows 'Deleted'", dlg["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a dialog answer whose own confidence is under 0_3 is unsure even when its probability is not, and the dialog is seen by a consulted model`() = runTest {
        val unsure = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.strictDialogs = true; b.armedDialogOnClick = confirmDialog
            b.dialogPick = "CANCEL"; b.dialogP = 0.6; b.dialogConfidence = 0.2 // the live run: OK 0.4 / CANCEL 0.6, confidence 0.2
        }
        val r = flow(unsure, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.blocker!!.reason.contains("20% sure how to answer the confirm dialog"), r.blocker!!.reason)
        assertEquals(0, unsure.dialogAnswers.size)
        // The rule itself: a clear top choice (0.65+) is accepted whatever confidence it reports; below that, the confidence still counts.
        assertFalse(Cu.dialogUnsure(0.7, 0.2, 0.5))
        assertFalse(Cu.dialogUnsure(0.65, 0.0, 0.5))
        assertTrue(Cu.dialogUnsure(0.6, 0.2, 0.5))
        assertTrue(Cu.dialogUnsure(0.64, 0.29, 0.5))
        assertFalse(Cu.dialogUnsure(0.6, 0.3, 0.5))
        assertFalse(Cu.dialogUnsure(0.6, null, 0.5))
        assertTrue(Cu.dialogUnsure(0.45, 0.9, 0.5)) // under consult_below whatever the confidence
        assertTrue(Cu.dialogUnsure(0.8, 0.9, 0.9))
        val clear = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.strictDialogs = true; b.armedDialogOnClick = confirmDialog; b.dialogPick = "OK"; b.dialogP = 0.7; b.dialogConfidence = 0.2 }
        val cr = flow(clear, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.DONE, cr.status, cr.error)
        assertEquals(listOf("ok"), clear.dialogAnswers)
        // Sure of itself: the same split is answered.
        val sure = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b -> b.strictDialogs = true; b.armedDialogOnClick = confirmDialog; b.dialogPick = "CANCEL"; b.dialogP = 0.6; b.dialogConfidence = 0.9 }
        flow(sure, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(listOf("cancel"), sure.dialogAnswers)

        // The consulted model gets the image with the dialog on it.
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        gatewayCaps = setOf(AiGatewayAPI.CAPABILITY_VISION)
        val run = UseRun("t")
        val requests = mutableListOf<AiRequest>()
        try {
            val b = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { x -> x.strictDialogs = true; x.armedDialogOnClick = confirmDialog; x.dialogP = 0.6; x.dialogConfidence = 0.1 }
            flow(b, FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT), run)
        } finally { gatewayCaps = defaultCaps }
        val rendered = run.log.entries.value.first { it.name == "render dialog" }.image!!
        val image = requests.single { it.system.startsWith("You help a browser agent") }.messages.single().images.single()
        assertTrue(image.bytes.contentEquals(java.io.File(rendered).readBytes()))
    }

    // ---- url and title from the screenshot, targets already acted on, tab switches ----

    private fun locatedRect(left: Int, top: Int, right: Int, bottom: Int) =
        """{"ok":true,"located":{"found":true,"count":1,"in_view":true,"rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}}"""

    @Test
    fun `a target that was acted on is dropped, and nothing is reported missing in a popup window`() {
        val found = Cu.Located(true, 1, true, null, 0.0)
        val missing = Cu.Located(false, 0, false, null, null)
        val t = listOf(Cu.Target("Sign in with Popup", found), Cu.Target("item 87", found), Cu.Target("Acme", missing))
        fun step(f: (UseStep) -> UseStep) = f(UseStep(1, VisionOp.CLICK, confidence = 0.9, ms = 1))
        // Clicked where it was; snapped onto it; typed; or a result that mentions it.
        assertEquals(listOf("item 87", "Acme"), Cu.pruneTargets(t, listOf(step { it.copy(clickedTargets = listOf("sign in with popup")) }), inPopup = false).map { it.text })
        assertEquals(listOf("Sign in with Popup", "Acme"), Cu.pruneTargets(t, listOf(step { it.copy(snapped = Snapped("a", "b", "link", "Item 87 details")) }), inPopup = false).map { it.text })
        assertEquals(listOf("Sign in with Popup", "Acme"), Cu.pruneTargets(t, listOf(step { it.copy(op = VisionOp.TYPE_TEXT, text = "item 87") }), inPopup = false).map { it.text })
        assertEquals(listOf("item 87", "Acme"), Cu.pruneTargets(t, listOf(step { it.copy(result = "page now shows 'Sign in with Popup opened'") }), inPopup = false).map { it.text })
        // Nothing done: all three, the missing one reported as such. In a popup window the missing one is not reported.
        assertEquals(3, Cu.pruneTargets(t, emptyList(), inPopup = false).size)
        assertEquals(listOf("Sign in with Popup", "item 87"), Cu.pruneTargets(t, emptyList(), inPopup = true).map { it.text })
        // The rect of a located phrase holds a click that lands on it.
        assertEquals(Cu.Rect(500.0, 180.0, 620.0, 210.0), Cu.parseLocated(json(locatedRect(500, 180, 620, 210)))!!.rect)
        assertEquals(Cu.Rect(10.0, 20.0, 110.0, 70.0), Cu.parseLocated(json("""{"located":{"found":true,"in_view":true,"rect":{"x":10,"y":20,"width":100,"height":50}}}"""))!!.rect)
        assertTrue(Cu.Rect(500.0, 180.0, 620.0, 210.0).contains(560.0, 195.0))
        assertFalse(Cu.Rect(500.0, 180.0, 620.0, 210.0).contains(300.0, 195.0))
    }

    @Test
    fun `a target the run clicked is not offered again, and not looked up again`() = runTest {
        val h = locateBrowser(listOf("CLICK", "DONE"), reply = { locatedRect(500, 180, 620, 210) })
        val goal = "Click \"Sign in with Popup\""
        flow(h.b, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"${goal.replace("\"", "\\\"")}","type_values":[]}""").runComputerUse(UseRequest("t", maxSteps = 5))
        val first = h.b.jevArgs[0]["state"]!!.jsonObject["targets"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content to it.jsonObject["where"]!!.jsonPrimitive.content }
        assertTrue(first.contains("Sign in with Popup" to "in view"), first.toString())
        // The click landed at (560, 195), inside its rect: from then on it is done, not "missing".
        assertNull(h.b.jevArgs[1]["state"]!!.jsonObject["targets"])
        assertEquals(1, h.phrases.count { it == "Sign in with Popup" })
    }

    @Test
    fun `the screenshot's url and title are the page state, and observe is only the fallback`() = runTest {
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        val browser = FakeBrowser(tmp(), listOf("CLICK", "WAIT", "DONE")).also { b ->
            b.shotMeta = true
            b.observeFailsFor += "t1#popup-1"
            b.urlByTab["t1#popup-1"] = "https://accounts.sample.com/signin"
            var opened = false
            b.onAction = { type, tab -> if (type == "click_at" && tab == "t1" && !opened) { opened = true; b.popupsJson = popupOpen } }
        }
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 6))
        assertEquals(UseStatus.DONE, report.status, report.error)
        val onPopup = browser.jevArgs.last()["state"]!!.jsonObject
        assertEquals("https://accounts.sample.com/signin", onPopup["url"]!!.jsonPrimitive.content)
        assertEquals("Shot title", onPopup["title"]!!.jsonPrimitive.content)
        // Not one observe went to the popup window, and the loop made none at all once screenshots carried the address.
        assertEquals(0, browser.calls.count { it.first == ToolNames.OBSERVE && it.second["tab_id"]?.jsonPrimitive?.content == "t1#popup-1" })
        // Without them (an older engine) observe is used.
        val old = FakeBrowser(tmp(), listOf("DONE"))
        flow(old, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals("S", old.jevArgs.single()["state"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every switch of tab is a recent action saying where the run is now`() = runTest {
        // Unit: both spellings of the note, and the way back.
        val steps = listOf(
            UseStep(1, VisionOp.CLICK, confidence = 0.9, ms = 1, popup = "Switched to popup window 'Sign in'"),
            UseStep(2, VisionOp.CLICK, confidence = 0.9, ms = 1, popup = "Popup window closed, back on 'Main'"),
            UseStep(3, VisionOp.CLICK, confidence = 0.9, ms = 1, popup = "Switched to popup 'Other tab'"),
        )
        val all = Cu.recentActions(steps).map { it["operation"]!!.jsonPrimitive.content to it["result"]?.jsonPrimitive?.content }
        assertEquals(listOf(
            "CLICK" to null, "SWITCH" to "now in popup window 'Sign in' (separate window for this step)",
            "CLICK" to null, "SWITCH" to "back on 'Main'",
            "CLICK" to null, "SWITCH" to "now in popup window 'Other tab' (separate window for this step)",
        ), all)
        // Through a run: the decision after the switch and the one after the return both carry it.
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        var closed = false
        val browser = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { b ->
            b.onAction = { type, tab ->
                if (type == "click_at" && tab == "t1" && b.popupsJson == null) b.popupsJson = popupOpen
                else if (type == "click_at" && tab == "t1#popup-1" && !closed) { closed = true; b.popupsJson = popupClosed }
                else if (closed && type == "screenshot") b.popupsJson = null
            }
        }
        flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 10))
        val second = browser.jevArgs[1]["state"]!!.jsonObject["recent_actions"]!!.jsonArray.map { it.jsonObject["result"]?.jsonPrimitive?.content }
        assertTrue(second.contains("now in popup window 'Sign in' (separate window for this step)"), second.toString())
        val third = browser.jevArgs[2]["state"]!!.jsonObject["recent_actions"]!!.jsonArray.map { it.jsonObject["result"]?.jsonPrimitive?.content }
        assertTrue(third.contains("back on 'Main'"), third.toString())
    }

    // ---- a hidden tab's screenshot is stale ----

    private fun shotActions(b: FakeBrowser) = b.stepArgs("screenshot")

    @Test
    fun `screenshots of a tab ask the engine to make it visible, popup windows are left out`() = runTest {
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        val browser = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { b ->
            var opened = false
            var closed = false
            b.onAction = { type, tab ->
                if (type == "click_at" && tab == "t1" && !opened) { opened = true; b.popupsJson = popupOpen }
                else if (type == "click_at" && tab == "t1#popup-1" && !closed) { closed = true; b.popupsJson = popupClosed }
                else if (closed && type == "screenshot") b.popupsJson = null
            }
        }
        flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 10))
        val shots = shotActions(browser)
        assertTrue(shots.size >= 3)
        shots.forEach { a ->
            val action = a["action"]!!.jsonObject
            val popup = "#popup-" in a["tab_id"]!!.jsonPrimitive.content
            assertEquals(!popup, "ensure_visible" in action, a.toString())
            if (!popup) assertTrue(action["ensure_visible"]!!.jsonPrimitive.content.toBoolean())
        }
        assertTrue(shots.any { "#popup-" in it["tab_id"]!!.jsonPrimitive.content })
        // Only screenshots carry it.
        assertTrue(browser.stepArgs("click_at").none { "ensure_visible" in it["action"]!!.jsonObject })
    }

    @Test
    fun `a revealed tab is noted on the step and counted for the run`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "SCROLL_DOWN", "DONE")).also {
            it.screenshotFlags += (true to true)   // brought back before the first decision
            it.screenshotFlags += (false to true)
            it.screenshotFlags += (true to true)   // and again before the third
        }
        val report = flow(browser, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals("brought the tab back to the front", report.steps[0].note!!.split("; ").first { it.startsWith("brought") })
        assertFalse(report.steps[1].note.orEmpty().contains("brought the tab"))
        assertTrue(report.steps[2].note!!.contains("brought the tab back to the front"))
        assertEquals(2, report.tabRevealed)
        assertEquals(2, report.toJson()["tab_revealed"]!!.jsonPrimitive.content.toInt())
        // Never revealed: no field.
        val calm = flow(FakeBrowser(tmp(), listOf("DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertFalse("tab_revealed" in calm.toJson())
    }

    @Test
    fun `a hidden screenshot is not shown to Jev, it is retaken once, and a second one is stuck`() = runTest {
        // Hidden once, then fine: one more screenshot, one Jev call on the good image.
        val once = FakeBrowser(tmp(), listOf("DONE")).also { it.screenshotFlags += (false to false) }
        val ok = flow(once, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.DONE, ok.status)
        assertEquals(2, shotActions(once).size)
        assertEquals(1, once.jevCalls)
        // Hidden twice: Jev is never asked about the stale image, and the run is stuck with the reason.
        val twice = FakeBrowser(tmp(), listOf("DONE")).also { it.screenshotFlags += (false to false); it.screenshotFlags += (false to false) }
        val stuck = flow(twice, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, stuck.status)
        assertEquals(Cu.HIDDEN_REASON, stuck.blocker!!.reason)
        assertEquals("the tab is not visible, so the screen can't be read", stuck.error)
        assertEquals(2, shotActions(twice).size)
        assertEquals(0, twice.jevCalls)
        assertEquals(1, twice.calls.count { it.first == ToolNames.STEP && it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content == "cu_end" })
    }

    @Test
    fun `a hidden tab is not consulted either, there is no screen to look at`() = runTest {
        val requests = mutableListOf<AiRequest>()
        val browser = FakeBrowser(tmp(), listOf("DONE")).also { it.screenshotFlags += (false to false); it.screenshotFlags += (false to false) }
        val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(Cu.HIDDEN_REASON, report.blocker!!.reason)
        assertEquals(0, requests.count { it.system.startsWith("You help a browser agent") })
        assertEquals(0, browser.jevCalls)
    }

    @Test
    fun `the descriptions say a run keeps its tab in front`() {
        val provider = LlmrpaMcpToolProvider("p", component = { null },
            headless = HeadlessRunner(FakeTools { _, _ -> error("unused") }, { null }, { null }, tabs = { emptyList() }, activeTabId = { null }, drivable = { true },
                openTab = { _, _ -> null }, reveal = { }, locks = TabLocks(), runs = RunHistory(), runLog = testRunLog()),
            runs = RunHistory(), runLog = testRunLog())
        assertTrue(provider.tools().first { it.name == "browser_use" }.description.contains("keeps its tab in front while it works"))
    }

    // ---- no blind consults, things only the person can give, continuing, covered elements ----

    private fun cliModel() = ModelOption(ModelOption.Kind.CHAT, "CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI")
    private fun providerModels(id: String, name: String, vararg models: String) =
        ai.rever.boss.plugin.api.AiProviderModels(id, name, models.map { ai.rever.boss.plugin.api.AiAvailableModel(it, it, null) })

    @Test
    fun `a model that cannot see is not consulted, the planner is used if it can see, else the first that can, else nobody`() {
        val vision = setOf(AiGatewayAPI.CAPABILITY_VISION, AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
        fun api(caps: Set<String>, active: AiModelInfo?, models: List<ai.rever.boss.plugin.api.AiProviderModels>) = object : AiGatewayAPI {
            override suspend fun complete(request: AiRequest): Result<AiReply> = Result.failure(UnsupportedOperationException())
            override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
            override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = Result.failure(UnsupportedOperationException())
            override fun capabilities() = caps
            override fun activeModel() = active
            override fun availableModels() = models
        }
        val gpt = ModelOption(ModelOption.Kind.CHAT, "OPENAI", "OpenAI", "gpt-5")
        val cliActive = AiModelInfo("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI")
        val models = listOf(providerModels("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI"), providerModels("OPENAI", "OpenAI", "gpt-4.1-mini", "gpt-5"))
        // The caller's own choice, trusted.
        assertEquals(cliModel(), Cu.pickConsultModel(cliModel(), null, api(vision, cliActive, models)))
        // The planner sees: it is used. The planner is a CLI: the first model that can see.
        assertEquals(gpt, Cu.pickConsultModel(null, gpt, api(vision, cliActive, models)))
        assertEquals("gpt-4.1-mini", Cu.pickConsultModel(null, cliModel(), api(vision, cliActive, models))!!.modelId)
        // No planner chosen: the active model stands in for it.
        assertEquals("gpt-4.1-mini", Cu.pickConsultModel(null, null, api(vision, cliActive, models))!!.modelId)
        // Nothing can see, the gateway cannot take images, or it can only route to the active model: nobody.
        assertNull(Cu.pickConsultModel(null, cliModel(), api(vision, cliActive, listOf(models[0]))))
        assertNull(Cu.pickConsultModel(null, cliModel(), api(setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE), cliActive, models)))
        assertNull(Cu.pickConsultModel(null, cliModel(), api(setOf(AiGatewayAPI.CAPABILITY_VISION), cliActive, models)))
        assertNull(Cu.pickConsultModel(null, null, null))
        assertFalse(Cu.mayBeVision("Claude Code CLI", "Claude Code CLI"))
        assertTrue(Cu.mayBeVision("Anthropic", "claude-sonnet-4"))
        assertTrue(Cu.mayBeVision("OpenRouter", "google/gemini-2.5-pro"))
        assertFalse(Cu.mayBeVision("Local", "llama-3-8b"))
    }

    @Test
    fun `a stuck run whose planner cannot see consults a model that can, with the image, and records which`() = runTest {
        activeInfo = AiModelInfo("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI")
        catalog = listOf(providerModels("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI"), providerModels("OPENAI", "OpenAI", "gpt-5"))
        consultReplies = ArrayDeque(listOf("""{"hint":"go"}"""))
        val requests = mutableListOf<AiRequest>()
        val run = UseRun("t")
        val report = try {
            flow(FakeBrowser(tmp(), listOf("BLOCKED", "DONE")), FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, llm = cliModel()), run)
        } finally { activeInfo = AiModelInfo("OPENAI", "OpenAI", "gpt-5"); catalog = emptyList() }
        assertEquals(UseStatus.DONE, report.status, report.error)
        val consult = requests.single { it.system.startsWith("You help a browser agent") }
        assertEquals("gpt-5", consult.extras[AiRequest.EXTRAS_KEY_MODEL_OVERRIDE])
        assertEquals("OPENAI", consult.extras[AiRequest.EXTRAS_KEY_PROVIDER_ID])
        assertEquals(1, consult.messages.single().images.size)
        // The plan and the typing still go to the planner.
        assertEquals("Claude Code CLI", requests.first { it.system.startsWith("You plan") }.extras[AiRequest.EXTRAS_KEY_MODEL_OVERRIDE])
        assertEquals("OpenAI gpt-5", report.consultModel)
        assertEquals("OpenAI gpt-5", report.toJson()["consult_model"]!!.jsonPrimitive.content)
        assertTrue(report.steps.single().description.startsWith("Consulted OpenAI gpt-5"))
        assertEquals("OpenAI gpt-5", store(run))
    }

    private fun store(run: UseRun) = testRunLog().also { it.begin(run) }.read(run.id)!!["consult_model"]!!.jsonPrimitive.content

    @Test
    fun `with no model that can see the run returns saying so instead of consulting a blind one, and an explicit consult model is used`() = runTest {
        activeInfo = AiModelInfo("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI")
        catalog = listOf(providerModels("CLAUDE_CLI", "Claude Code CLI", "Claude Code CLI"))
        try {
            val requests = mutableListOf<AiRequest>()
            val report = flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, llm = cliModel()))
            assertEquals(0, requests.count { it.system.startsWith("You help a browser agent") })
            assertEquals(UseStatus.NEEDS_INPUT, report.status)
            assertTrue(report.error!!.contains("no vision-capable model to consult"))
            // The caller picks one: used as given.
            consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
            val explicit = mutableListOf<AiRequest>()
            flow(FakeBrowser(tmp(), listOf("BLOCKED")), FakeTabs(), explicit).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, llm = cliModel(),
                consultModel = ModelOption(ModelOption.Kind.CHAT, "OPENAI", "OpenAI", "my-vision-model")))
            assertEquals("my-vision-model", explicit.single { it.system.startsWith("You help a browser agent") }.extras[AiRequest.EXTRAS_KEY_MODEL_OVERRIDE])
        } finally { activeInfo = AiModelInfo("OPENAI", "OpenAI", "gpt-5"); catalog = emptyList() }
    }

    private val loginModal = "Login"
    private val loginText = "Login\nEnter your phone number\nSend one time password\nContinue with Email\nSign in with Google"

    @Test
    fun `a login modal stops for the person in both modes, before any consult, whether Jev says blocked or needs input`() = runTest {
        for (mode in listOf(OnStuck.RETURN, OnStuck.CONSULT)) for (op in listOf("BLOCKED", "NEEDS_INPUT")) {
            val browser = FakeBrowser(tmp(), listOf(op)).also { it.modalLabel = loginModal; it.pageTextFn = { loginText } }
            val requests = mutableListOf<AiRequest>()
            val report = flow(browser, FakeTabs(), requests).runComputerUse(UseRequest("order a cake", onStuck = mode))
            assertEquals(UseStatus.NEEDS_INPUT, report.status, "$mode $op")
            assertEquals(0, requests.count { it.system.startsWith("You help a browser agent") }, "no consult in $mode")
            val q = report.blocker!!.question!!
            assertTrue(q.contains("sample.com is asking you to enter a verification code") || q.contains("is asking you to"), q)
            assertTrue(q.contains("Do it yourself in this tab (it's your browser), then reply 'continue'"), q)
            assertTrue(q.contains("Or reply with the details (e.g. a phone number) and Jev will type them."), q)
            assertEquals("t1", report.blocker!!.tabId)
        }
        // A plain login wall (no code or payment): "log in".
        val plain = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.modalLabel = "Sign in to continue"; it.pageTextFn = { "Sign in\nEmail\nPassword" } }
        val r = flow(plain, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertTrue(r.blocker!!.question!!.startsWith("sample.com is asking you to log in."), r.blocker!!.question)
        // Blocked with a login link in an ordinary page (no modal) is an ordinary block: consulted as before.
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        val menu = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.pageTextFn = { "Home\nLogin\nSign up" } }
        val req = mutableListOf<AiRequest>()
        flow(menu, FakeTabs(), req).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        assertEquals(1, req.count { it.system.startsWith("You help a browser agent") })
        // A code or card on the page counts without a modal; NEEDS_INPUT alone, with nothing recognised, still stops.
        val pay = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.pageTextFn = { "Pay now\nCard number\nCVV" } }
        assertTrue(flow(pay, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).blocker!!.question!!.contains("enter payment details"))
        val odd = FakeBrowser(tmp(), listOf("NEEDS_INPUT"))
        assertTrue(flow(odd, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).blocker!!.question!!.contains("provide something only you can"))
        assertTrue(Cu.OPERATIONS.getValue("NEEDS_INPUT").contains("sign-in or login, a verification code or OTP, payment details, an address"))
    }

    @Test
    fun `a consult never types into a password, code or card field`() = runTest {
        for ((label, sensitive) in listOf("Password" to false, "Enter OTP" to false, "Card number" to false, "Phone" to true)) {
            consultReplies = ArrayDeque(listOf("""{"action":{"type":"input","element":"e2","value":"{{phone}}"}}""", """{"give_up":"x"}"""))
            val b = FakeBrowser(tmp(), listOf("BLOCKED")).also {
                it.elements = """[{"id":"e2","role":"textbox","tag":"input","label":"$label","options":null,"sensitive":$sensitive,"in_viewport":true,"selector":{"type":"css","value":"#f"}}]"""
            }
            val r = flow(b, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, inputs = mapOf("phone" to "555")))
            assertEquals(0, b.stepArgs("input").size, label)
            assertTrue(r.steps.first().description.contains("refused"), label)
        }
        assertTrue(Cu.CONSULT_SYSTEM.contains("Never type into a password, one-time code or card field"))
        // An ordinary field is still fine.
        consultReplies = ArrayDeque(listOf("""{"action":{"type":"input","element":"e2","value":"{{phone}}"}}"""))
        val ok = FakeBrowser(tmp(), listOf("BLOCKED", "DONE"))
        flow(ok, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, inputs = mapOf("phone" to "555")))
        assertEquals("555", ok.stepArgs("input").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replying continue resumes the same run on the same tab with a fresh screenshot and no replan`() = runTest {
        // Unit: what counts as "I did it myself".
        listOf("continue", "Done", "logged in", "I'm logged in", "i have signed in", "ok", "Continue.").forEach { assertTrue(Cu.isContinueReply(it), it) }
        listOf("555 0100", "my phone is 555", "continue with email please", "").forEach { assertFalse(Cu.isContinueReply(it), it) }
        // The run stops at a login wall; the person logs in; the same run continues from the page as it is, with no plan call.
        val run = UseRun("order a cake")
        val wall = FakeBrowser(tmp(), listOf("NEEDS_INPUT")).also { it.modalLabel = loginModal; it.pageTextFn = { loginText } }
        val first = flow(wall, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("order a cake"), run)
        assertEquals(UseStatus.NEEDS_INPUT, first.status)
        val tab = first.blocker!!.tabId!!
        val after = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        val requests = mutableListOf<AiRequest>()
        val tabs = FakeTabs()
        val second = flow(after, tabs, requests).runComputerUse(UseRequest("order a cake", tabId = tab, resume = true), run)
        assertEquals(UseStatus.DONE, second.status, second.error)
        assertEquals(0, requests.count { it.system.startsWith("You plan") })
        assertTrue(tabs.opened.isEmpty())
        assertEquals(0, after.stepArgs("navigate").size)
        assertTrue(after.stepArgs("screenshot").isNotEmpty())
        // browser_use: the same tab_id with inputs {"continue":"yes"}: no plan even when asked to plan, the flag is not text to type, and Jev is told.
        val viaTool = FakeBrowser(tmp(), listOf("TYPE_TEXT", "DONE"), textPick = "555")
        val reqs = mutableListOf<AiRequest>()
        val r = flow(viaTool, FakeTabs(), reqs).runComputerUse(UseRequest("order a cake", tabId = "t7", planWithLlm = true, inputs = mapOf("continue" to "yes", "phone" to "555")))
        assertEquals(UseStatus.DONE, r.status, r.error)
        assertEquals(0, reqs.count { it.system.startsWith("You plan") })
        assertTrue("yes" !in viaTool.textOptionsSeen.flatten())
        assertTrue(viaTool.jevArgs.first()["state"]!!.jsonObject["advice"]!!.jsonArray.single().jsonPrimitive.content.contains("did what the page asked in this tab themselves"))
    }

    @Test
    fun `elements covered by a dialog are dropped from the consult list and from DOM candidates, and the dialog is named`() = runTest {
        val covered = """[{"id":"e1","role":"button","tag":"button","label":"Add to cart","covered":true,"selector":{"type":"css","value":"#add"}},""" +
            """{"id":"e2","role":"textbox","tag":"input","label":"Phone number","selector":{"type":"css","value":"#phone"}}]"""
        val page = PageSnapshot.parse(json("""{"url":"https://a.com/","title":"A","modal":{"label":"Login"},"elements":$covered}"""))
        assertEquals("Login", page.modal)
        assertEquals(listOf(true, false), page.elements.map { it.covered })
        // DOM mode: nothing covered is offered.
        val cands = Candidates.build(page, "Order a cake \"cake\"", writes = true)
        assertTrue(cands.none { it.description.contains("Add to cart") })
        assertTrue(cands.any { it.description.contains("Phone number") })
        // ... and the model is told a dialog is open.
        val ctx = StepContext("Order", page, cands, emptyList(), emptyList())
        assertEquals("Login", JevDecider.state(ctx)["page"]!!.jsonObject["dialog_open_over_the_page"]!!.jsonPrimitive.content)
        assertTrue(ChatDecider.prompt(ctx).contains("A dialog \"Login\" is open over the page"))
        assertNull(JevDecider.state(StepContext("x", page.copy(modal = null), emptyList(), emptyList(), emptyList()))["page"]!!.jsonObject["dialog_open_over_the_page"])
        // The consult list.
        consultReplies = ArrayDeque(listOf("""{"give_up":"x"}"""))
        val b = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.elements = covered; it.modalLabel = "Cookie banner" }
        val requests = mutableListOf<AiRequest>()
        flow(b, FakeTabs(), requests).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT))
        val prompt = requests.single { it.system.startsWith("You help a browser agent") }.messages.single().text
        assertFalse(prompt.contains("Add to cart"))
        assertTrue(prompt.contains("e2 | textbox | \"Phone number\""))
        assertTrue(prompt.contains("A dialog \"Cookie banner\" is open over the page; the elements it covers are left out."))
    }

    // ---- planning: search first, milestones, replans ----

    @Test
    fun `the planning prompt prefers a search page, forbids guessed site URLs, corrects typos and asks for milestones`() {
        val p = Cu.PLAN_SYSTEM
        assertTrue(p.contains("Prefer a web search results page: https://www.google.com/search?q=<name + site + intent, typos corrected>"))
        assertTrue(p.contains("Magnolia+Bakery+Gurgaon+zomato+order+online"))
        assertTrue(p.contains("When the user names a site, search \"<query> <site>\" on Google instead of guessing that site's own search or a deep link"))
        assertTrue(p.contains("Never guess a site's internal search or deep URL"))
        assertTrue(p.contains("Wikipedia /wiki/Title, a site's homepage, a Yelp or Google Maps search"))
        assertTrue(p.contains("Correct obvious typos"))
        assertTrue(p.contains("\"milestones\":[\"...\",\"...\"]"))
        assertTrue(p.contains("2 to 6 steps in order, each at most 12 words and checkable from a screenshot"))
        assertTrue(p.contains("Tres Leches is in the cart"))
        assertTrue(p.contains("the overall aim in at most 25 words"))
        assertTrue(p.contains("\"entities\":[\"...\"],\"stop_when\":\"...\""))
        assertTrue(p.contains("It is NOT a milestone: never repeat it in milestones"))
        assertFalse(p.contains("the LAST milestone says it"))
        assertTrue(p.contains("Reply with only JSON"))
        assertTrue(p.contains("short and literal"))
        val r = Cu.REPLAN_SYSTEM
        assertTrue(r.contains("\"open\":[{\"url\":\"https://...\"}],\"milestones\":[\"...\"],\"entities\":[\"...\"],\"stop_when\":\"...\",\"jev_goal\":\"...\""))
        assertTrue(r.contains("only the milestones still to do"))
        assertTrue(r.contains("Never guess a site's internal search or deep URL"))
    }

    @Test
    fun `a plan carries short milestones and a clipped goal, and Jev's state names the current one`() {
        val long = (1..40).joinToString(" ") { "w$it" }
        val plan = Cu.parsePlan("""{"open":[{"url":"https://www.google.com/search?q=x"}],"jev_goal":"$long","milestones":["Page open","Tres Leches is in the cart","${(1..20).joinToString(" ") { "m$it" }}","a","b","c","d","e"],"type_values":[]}""", "task")
        assertEquals(30, plan.goal.split(" ").size)
        assertEquals(6, plan.milestones.size)
        assertEquals(12, plan.milestones[2].split(" ").size)
        assertTrue(Cu.parsePlan("""{"open":[],"jev_goal":"g"}""", "t").milestones.isEmpty())
        val ms = listOf("Magnolia Bakery's Zomato page is open", "Tres Leches is in the cart", "the login or checkout step is shown")
        val state = Cu.decideArgs("m", "Order tres leches", "u", "t", emptyList(), "/x.jpg", milestones = ms, doneMilestones = 1)["state"]!!.jsonObject
        assertEquals("Order tres leches", state["goal"]!!.jsonPrimitive.content)
        assertEquals("2 of 3: Tres Leches is in the cart", state["milestone"]!!.jsonPrimitive.content)
        assertEquals(listOf(ms[0]), state["done_milestones"]!!.jsonArray.map { it.jsonPrimitive.content })
        val q = Cu.decideArgs("m", "Order", "u", "t", emptyList(), "/x.jpg", milestones = ms, doneMilestones = 1)["questions"]!!.jsonObject["operation"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertTrue(q.contains("Current milestone (2 of 3): Tres Leches is in the cart"))
        assertTrue(q.contains("pick DONE as soon as the screenshot shows it is reached"))
        // No milestones: no such fields.
        val plain = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")["state"]!!.jsonObject
        assertNull(plain["milestone"]); assertNull(plain["done_milestones"])
    }

    private val milestonePlan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Order Tres Leches from Magnolia Bakery","milestones":["Magnolia Bakery's page is open","Tres Leches is in the cart","the login or checkout step is shown"],"type_values":[]}"""

    @Test
    fun `DONE moves to the next milestone, which needs an action of this run, and only the last one ends the run`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("DONE", "CLICK", "DONE", "CLICK", "DONE"))
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = milestonePlan).runComputerUse(UseRequest("order a cake", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(5, browser.jevCalls)
        assertEquals(listOf(VisionOp.MILESTONE, VisionOp.CLICK, VisionOp.MILESTONE, VisionOp.CLICK), report.steps.map { it.op })
        assertEquals("milestone 1 done: Magnolia Bakery's page is open", report.steps[0].note)
        assertEquals("milestone 2 done: Tres Leches is in the cart", report.steps[2].note)
        // Jev saw each one in turn, with those done so far.
        val states = browser.jevArgs.map { it["state"]!!.jsonObject }
        assertEquals(listOf("1 of 3: Magnolia Bakery's page is open", "2 of 3: Tres Leches is in the cart", "2 of 3: Tres Leches is in the cart", "3 of 3: the login or checkout step is shown", "3 of 3: the login or checkout step is shown"), states.map { it["milestone"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 1, 1, 2, 2), states.map { it["done_milestones"]!!.jsonArray.size })
        assertEquals("milestone 1 done: Magnolia Bakery's page is open", states[1]["recent_actions"]!!.jsonArray.single().jsonObject["result"]!!.jsonPrimitive.content)
        // The report lists them, all reached.
        val ms = report.toJson()["milestones"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content to it.jsonObject["done"]!!.jsonPrimitive.content.toBoolean() }
        assertEquals(listOf("Magnolia Bakery's page is open" to true, "Tres Leches is in the cart" to true, "the login or checkout step is shown" to true), ms)
        // A run that stops early reports how far it got.
        val partial = flow(FakeBrowser(tmp(), listOf("DONE", "BLOCKED")), FakeTabs(), mutableListOf(), plan = milestonePlan).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(listOf(true, false, false), partial.toJson()["milestones"]!!.jsonArray.map { it.jsonObject["done"]!!.jsonPrimitive.content.toBoolean() })
    }

    @Test
    fun `targets come from the current milestone first, then the goal`() {
        val t = Cu.targetPhrasesFor("Tres Leches is in the cart", "Order from Magnolia Bakery", "https://zomato.com/")
        assertTrue(t.first().contains("Tres Leches"), t.toString())
        assertTrue(t.any { it.contains("Magnolia") })
        assertTrue(t.size <= 3)
        assertEquals(Cu.targetPhrases("Order from Magnolia Bakery", "https://zomato.com/"), Cu.targetPhrasesFor(null, "Order from Magnolia Bakery", "https://zomato.com/"))
    }

    private val googleUrl = "https://www.google.com/search?q=Magnolia+Bakery+Gurgaon+zomato+order+online"
    private fun replanReply(url: String = googleUrl) = """{"open":[{"url":"$url"}],"milestones":["Magnolia Bakery's Zomato page is open","Tres Leches is in the cart"],"jev_goal":"Order Tres Leches from Magnolia Bakery"}"""

    @Test
    fun `a dead end asks the planner for a better start, navigates there, and goes on with the new milestones`() = runTest {
        replanReplies = ArrayDeque(listOf(replanReply()))
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED", "DONE", "CLICK", "DONE")).also { it.pageTextFn = { "Bharat Baker's\nKesari Nandan" } }
        val requests = mutableListOf<AiRequest>()
        val run = UseRun("Order tres leches from Magolia bakery on zomato")
        val report = flow(browser, FakeTabs(), requests, plan = milestonePlan).runComputerUse(UseRequest("Order tres leches from Magolia bakery on zomato", maxSteps = 10), run)
        assertEquals(UseStatus.DONE, report.status, report.error)
        val ask = requests.single { it.system.startsWith("A browser agent, Jev, is stuck") }.messages.single().text
        assertTrue(ask.contains("Task: Order tres leches from Magolia bakery on zomato"))
        assertTrue(ask.contains("Milestones remaining: Magnolia Bakery's page is open; Tres Leches is in the cart; the login or checkout step is shown"))
        assertTrue(ask.contains("Page now:") && ask.contains("sample.com"))
        assertTrue(ask.contains("What it tried:"))
        assertTrue(ask.contains("Bharat Baker's")) // the page text excerpt
        assertTrue(ask.contains("Why it is stuck: Jev found no operation"))
        // It went there on the same tab, noted the step, counted the call, and took the new milestones.
        assertEquals(googleUrl, browser.stepArgs("navigate").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("t1", browser.stepArgs("navigate").single()["tab_id"]!!.jsonPrimitive.content)
        val step = report.steps.first { it.op == VisionOp.REPLAN }
        assertEquals("replanned: $googleUrl", step.note)
        assertEquals(2, report.llmCalls) // plan + replan
        assertEquals(1, run.replans)
        assertEquals(listOf("Magnolia Bakery's Zomato page is open", "Tres Leches is in the cart"), run.plan.value!!.milestones)
        assertEquals(googleUrl, run.plan.value!!.open.single())
    }

    @Test
    fun `replans are capped by max_replans`() = runTest {
        val elsewhere = "https://www.bing.com/search?q=cake+shop"
        replanReplies = ArrayDeque(listOf(replanReply(elsewhere), replanReply(elsewhere), replanReply(elsewhere)))
        val requests = mutableListOf<AiRequest>()
        val loop = listOf("SCROLL_DOWN", "BLOCKED", "SCROLL_DOWN", "BLOCKED", "SCROLL_DOWN", "BLOCKED")
        val browser = FakeBrowser(tmp(), loop).also { it.pageTextFn = { "wrong page" } }
        val report = flow(browser, FakeTabs(), requests, plan = milestonePlan).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(2, requests.count { it.system.startsWith("A browser agent, Jev, is stuck") }) // the default of 2
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(2, report.steps.count { it.op == VisionOp.REPLAN })
        replanReplies = ArrayDeque(listOf(replanReply(elsewhere)))
        val none = mutableListOf<AiRequest>()
        flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED")), FakeTabs(), none, plan = milestonePlan).runComputerUse(UseRequest("t", maxReplans = 0))
        assertEquals(0, none.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        val one = mutableListOf<AiRequest>()
        replanReplies = ArrayDeque(listOf(replanReply(elsewhere), replanReply(elsewhere)))
        flow(FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED", "SCROLL_DOWN", "BLOCKED")), FakeTabs(), one, plan = milestonePlan).runComputerUse(UseRequest("t", maxReplans = 1, maxSteps = 20))
        assertEquals(1, one.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        assertEquals(2, BrowserUseArgs.parse("""{"task":"t"}""").getOrThrow().maxReplans)
        assertEquals(5, BrowserUseArgs.parse("""{"task":"t","max_replans":99}""").getOrThrow().maxReplans)
    }

    @Test
    fun `no replan for a login or a request for the person or when the page has what the milestone needs, or for an unusable plan`() = runTest {
        replanReplies = ArrayDeque(listOf(replanReply(), replanReply()))
        // A login modal: the person's, never the planner's.
        val login = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.modalLabel = "Login"; it.pageTextFn = { "Login\nEnter your phone number\nSend one time password" } }
        val r1 = mutableListOf<AiRequest>()
        val report = flow(login, FakeTabs(), r1, plan = milestonePlan).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(0, r1.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        val ask = FakeBrowser(tmp(), listOf("NEEDS_INPUT"))
        val r2 = mutableListOf<AiRequest>()
        flow(ask, FakeTabs(), r2, plan = milestonePlan).runComputerUse(UseRequest("t"))
        assertEquals(0, r2.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        // The milestone's phrases are on the page: this is not a dead end.
        val here = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED")).also { it.locateReply = { """{"ok":true,"located":{"found":true,"count":1,"in_view":true}}""" } }
        val r3 = mutableListOf<AiRequest>()
        flow(here, FakeTabs(), r3, plan = milestonePlan).runComputerUse(UseRequest("t"))
        assertEquals(0, r3.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        // A planner that gives nothing usable leaves the run to stop as before (and still spent the call).
        replanReplies = ArrayDeque(listOf("sorry", "{\"open\":[{\"url\":\"http://insecure.com/\"}]}"))
        val bad = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED"))
        val r4 = mutableListOf<AiRequest>()
        val stopped = flow(bad, FakeTabs(), r4, plan = milestonePlan).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(UseStatus.NEEDS_INPUT, stopped.status)
        assertEquals(0, bad.stepArgs("navigate").size)
        assertEquals(1, r4.count { it.system.startsWith("A browser agent, Jev, is stuck") }) // one unusable answer, and the run stops as it would have
        // An address that is not https is refused too.
        val insecure = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED"))
        replanReplies = ArrayDeque(listOf("{\"open\":[{\"url\":\"http://insecure.com/\"}]}"))
        flow(insecure, FakeTabs(), mutableListOf(), plan = milestonePlan).runComputerUse(UseRequest("t", maxReplans = 1))
        assertEquals(0, insecure.stepArgs("navigate").size)
        // Without any target to look for, nothing says the page is wrong.
        val nothing = FakeBrowser(tmp(), listOf("BLOCKED"))
        val r5 = mutableListOf<AiRequest>()
        flow(nothing, FakeTabs(), r5, plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"find the thing","type_values":[]}""").runComputerUse(UseRequest("t"))
        assertEquals(0, r5.count { it.system.startsWith("A browser agent, Jev, is stuck") })
    }

    // ---- milestones checked at every look; a replan never undoes progress ----

    private val orderPlan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Order Tres Leches from Magnolia Bakery","milestones":["A Magnolia Bakery Zomato order page is open","Tres Leches is visible on the menu","Tres Leches is in the cart","Stop at the login or payment step"],"type_values":[]}"""

    @Test
    fun `every look asks whether the current milestone is true, at 0_7 it is reached and the next one is asked next`() = runTest {
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN", "CLICK", "SCROLL_DOWN", "DONE", "CLICK", "DONE", "DONE")).also {
            // call 1: not yet; call 2: reached (0.8), and the scroll is acted on; call 3: a click, not yet (0.4) for the next one; call 4: 0.69 is not enough;
            // call 5: DONE (the click since the last milestone makes it creditable); call 6: a click; call 7: DONE for the cart; call 8: DONE ends the run.
            it.milestoneScript = listOf(0.3, 0.8, 0.4, 0.69, null, null, null, null)
        }
        val report = flow(browser, FakeTabs(), mutableListOf(), plan = orderPlan).runComputerUse(UseRequest("order a cake", maxSteps = 12))
        assertEquals(UseStatus.DONE, report.status, report.error)
        // Each call carried the question for the milestone then current.
        val asked = browser.jevArgs.mapNotNull { it["questions"]!!.jsonObject["milestone"]?.jsonObject?.get("instructions")?.jsonPrimitive?.content }
        assertTrue(asked[0].contains("'A Magnolia Bakery Zomato order page is open'"))
        assertTrue(asked[1].contains("'A Magnolia Bakery Zomato order page is open'"))
        assertTrue(asked[2].contains("'Tres Leches is visible on the menu'"), asked[2]) // re-asked about the next one on the next look
        assertEquals("noul", browser.jevArgs[0]["questions"]!!.jsonObject["milestone"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("true", "false"), browser.jevArgs[0]["questions"]!!.jsonObject["milestone"]!!.jsonObject["criteria"]!!.jsonObject.keys)
        val notes = report.steps.filter { it.op == VisionOp.MILESTONE }.map { it.note }
        // The first by Jev's own answer, the next two by DONE, which stays an alternative way to advance; the stop_when ends the run (it is no milestone).
        assertEquals(listOf("milestone 1 done: A Magnolia Bakery Zomato order page is open", "milestone 2 done: Tres Leches is visible on the menu", "milestone 3 done: Tres Leches is in the cart"), notes)
        // The milestone's own decision was acted on: the scroll that came with the 0.8 answer happened.
        assertEquals(listOf(VisionOp.SCROLL_DOWN, VisionOp.SCROLL_DOWN), report.steps.filter { it.op == VisionOp.SCROLL_DOWN }.map { it.op }.take(2))
        assertEquals(8, browser.jevCalls)
        // No call of its own: the same number of calls as without it (one per look).
        assertEquals(browser.jevCalls, browser.jevArgs.size)
        // The question is asked of nothing when there are no milestones.
        val plain = FakeBrowser(tmp(), listOf("DONE"))
        flow(plain, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertNull(plain.jevArgs.single()["questions"]!!.jsonObject["milestone"])
    }

    @Test
    fun `a stop_when ends as the person's when the page asks, a planner that writes it as the last milestone is read the same way`() = runTest {
        val twoStep = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Order a cake","milestones":["Tres Leches is in the cart"],"stop_when":"login or payment step","type_values":[]}"""
        val legacy = Cu.parsePlan("""{"open":[],"jev_goal":"g","milestones":["Tres Leches is in the cart","Stop at the login or payment step"]}""", "t")
        assertEquals(listOf("Tres Leches is in the cart"), legacy.milestones)
        assertEquals("login or payment step", legacy.stopWhen)
        val explicit = Cu.parsePlan(twoStep, "t")
        assertEquals(listOf("Tres Leches is in the cart"), explicit.milestones)
        assertEquals("login or payment step", explicit.stopWhen)
        // The page puts up a login after a click: the run ends for the person.
        val wall = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "CLICK")).also { it.pageTextFn = { "Login\nPhone number\nSend one time password" } }
        wall.onAction = { type, _ -> if (type == "click_at") wall.shotModalLabel = "Login" }
        val r = flow(wall, FakeTabs(), mutableListOf(), plan = twoStep).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.blocker!!.question!!.contains("is asking you to"))
        assertEquals(1, wall.jevCalls) // the second look found the wall: no decision was made on it
        // The page shows no wall: the last milestone is credited (a click changed the page), and Jev's DONE after it ends the run.
        val none = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { it.milestoneScript = listOf(null, 0.9, null) }
        val done = flow(none, FakeTabs(), mutableListOf(), plan = twoStep).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, done.status)
        assertEquals(listOf(true), done.toJson()["milestones"]!!.jsonArray.map { it.jsonObject["done"]!!.jsonPrimitive.content.toBoolean() })
        // An ordinary last milestone is done when reached.
        val ordinary = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"g","milestones":["Page open","Item visible"],"type_values":[]}"""
        val o = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "CLICK", "SCROLL_DOWN")).also { it.milestoneScript = listOf(0.9, null, 0.9) }
        assertEquals(UseStatus.DONE, flow(o, FakeTabs(), mutableListOf(), plan = ordinary).runComputerUse(UseRequest("t", maxSteps = 8)).status)
        assertTrue(Cu.isStopMilestone("Stop at the login or payment step"))
        assertTrue(Cu.isStopMilestone("stop before ordering"))
        assertFalse(Cu.isStopMilestone("Tres Leches is in the cart"))
    }

    @Test
    fun `the words that say which site the run is on are the goal's and the reached milestones' proper nouns`() {
        val w = Cu.distinctiveWords("Order Tres Leches from Magnolia Bakery", listOf("A Zomato order page is open"))
        assertTrue(w.containsAll(setOf("magnolia", "bakery", "tres", "leches", "zomato")), w.toString())
        assertFalse("order" in w) // the first word of the goal is a verb, not a name
        assertTrue(Cu.pageMatches("https://www.zomato.com/ncr/magnolia-bakery-6-golf-course-road-gurgaon/order", "Order online", w))
        assertTrue(Cu.pageMatches("https://x.com/", "Magnolia Bakery menu", w))
        assertFalse(Cu.pageMatches("https://www.google.com/search?q=cake", "cake - Google Search", w))
    }

    private val lazyUrl = "https://www.zomato.com/ncr/magnolia-bakery-6-golf-course-road-gurgaon/order"

    @Test
    fun `regression, the right page with a lazy menu is never left, no replan and no navigate away`() = runTest {
        // Run f5bd: on the bakery's order page the menu loads as it scrolls, "Tres Leches" is not on the page yet, Jev's confidence drops.
        replanReplies = ArrayDeque(listOf(replanReply(), replanReply()))
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED@low")).also {
            it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl; it.currentTitle = "Magnolia Bakery - Order online"
            it.pageTextFn = { "Bestsellers\nBrownies" }
        }
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests, plan = orderPlan).runComputerUse(UseRequest("order tres leches from magolia bakery on zomato", maxSteps = 12))
        // The planner is asked for advice (it gave none here), never for a new address: nothing navigates.
        assertEquals(1, requests.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        assertEquals(0, browser.stepArgs("navigate").size)
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertTrue(report.steps.none { it.op == VisionOp.REPLAN })
        // With a consult available it asks for a hint instead of leaving.
        consultReplies = ArrayDeque(listOf("""{"hint":"scroll the menu, it loads as you scroll"}"""))
        val b2 = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED", "DONE")).also { it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl; it.pageTextFn = { "Bestsellers" } }
        val r2 = mutableListOf<AiRequest>()
        flow(b2, FakeTabs(), r2, plan = orderPlan).runComputerUse(UseRequest("t", onStuck = OnStuck.CONSULT, maxSteps = 12))
        assertEquals(1, r2.count { it.system.startsWith("You help a browser agent") })
        assertEquals(0, b2.stepArgs("navigate").size)
        assertEquals(1, r2.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        // The title alone is enough.
        val b3 = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED")).also { it.currentTitle = "Magnolia Bakery"; it.pageTextFn = { "x" } }
        val r3 = mutableListOf<AiRequest>()
        flow(b3, FakeTabs(), r3, plan = orderPlan).runComputerUse(UseRequest("t", maxSteps = 12))
        assertEquals(1, r3.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        assertEquals(0, b3.stepArgs("navigate").size)
    }

    @Test
    fun `not on this page alone is not enough, the run must have scrolled on the page first`() = runTest {
        replanReplies = ArrayDeque(listOf(replanReply()))
        val b = FakeBrowser(tmp(), listOf("BLOCKED"))
        val r = mutableListOf<AiRequest>()
        flow(b, FakeTabs(), r, plan = orderPlan).runComputerUse(UseRequest("t"))
        assertEquals(0, r.count { it.system.startsWith("A browser agent, Jev, is stuck") }) // not a step of scrolling yet
        // After scrolling here, on a page that is not the right site, the same words do ask the planner.
        val scrolled = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED", "DONE", "DONE", "DONE", "DONE"))
        val r2 = mutableListOf<AiRequest>()
        flow(scrolled, FakeTabs(), r2, plan = orderPlan).runComputerUse(UseRequest("t"))
        assertEquals(1, r2.count { it.system.startsWith("A browser agent, Jev, is stuck") })
    }

    @Test
    fun `the planner may answer stay with a hint, which keeps the page and becomes advice`() = runTest {
        replanReplies = ArrayDeque(listOf("""{"stay":true,"hint":"scroll the menu or use the page's search box"}"""))
        val browser = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "BLOCKED", "DONE", "DONE", "DONE", "DONE"))
        val requests = mutableListOf<AiRequest>()
        val report = flow(browser, FakeTabs(), requests, plan = orderPlan).runComputerUse(UseRequest("t", maxSteps = 12))
        assertEquals(1, requests.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        assertEquals(0, browser.stepArgs("navigate").size)
        val step = report.steps.first { it.op == VisionOp.REPLAN }
        assertTrue(step.note!!.startsWith("replan: stay on this page, scroll the menu"))
        // Jev's next look has the hint as advice.
        assertTrue(browser.jevArgs.last()["state"]!!.jsonObject["advice"]!!.jsonArray.any { it.jsonPrimitive.content.contains("scroll the menu") })
        // The reply forms and the prompt that offers them.
        assertEquals(Cu.ReplanReply.Stay("keep going"), Cu.parseReplan("""{"stay":true,"hint":"keep going"}""", "g"))
        assertTrue(Cu.parseReplan("""{"stay":true}""", "g") is Cu.ReplanReply.Stay)
        assertTrue(Cu.parseReplan(replanReply(), "g") is Cu.ReplanReply.Move)
        assertTrue(runCatching { Cu.parseReplan("""{"open":[]}""", "g") }.isFailure)
        assertTrue(Cu.REPLAN_SYSTEM.contains("{\"stay\":true,\"hint\":\"...\"}"))
        assertTrue(Cu.REPLAN_SYSTEM.contains("If the current page is already the right site or page, answer stay with a hint, e.g. scroll the menu"))
    }

    @Test
    fun `milestones describe states, not UI mechanics`() {
        assertTrue(Cu.PLAN_SYSTEM.contains("Describe states, not UI mechanics"))
        assertTrue(Cu.PLAN_SYSTEM.contains("\"Tres Leches is visible on the menu\", not \"Tres Leches appears after typing it in Search within menu\""))
        assertTrue(Cu.REPLAN_SYSTEM.contains("Describe states, not UI mechanics"))
    }

    // ---- advice on the right site ----

    private val orderOnlineElements = """[{"id":"e1","role":"tab","tag":"a","label":"Overview","in_viewport":true,"selector":{"type":"css","value":"#ov"}},""" +
        """{"id":"e2","role":"tab","tag":"a","label":"Order Online","in_viewport":true,"selector":{"type":"css","value":"#oo"}},""" +
        """{"id":"e3","role":"tab","tag":"a","label":"Menu","in_viewport":true,"selector":{"type":"css","value":"#menu"}},""" +
        """{"id":"e4","role":"button","tag":"button","label":"Covered thing","covered":true,"in_viewport":true,"selector":{"type":"css","value":"#c"}}]"""
    private val adviceReply = """{"hint":"Click the 'Order Online' tab; the Menu tab only shows menu photos"}"""

    @Test
    fun `stuck on the right site the planner gives advice by text, with no navigate, the hint reaches Jev and the named element becomes a target`() = runTest {
        replanReplies = ArrayDeque(listOf(adviceReply))
        val located = mutableListOf<String>()
        val browser = FakeBrowser(tmp(), listOf("BLOCKED", "DONE", "CLICK", "DONE", "CLICK", "DONE", "DONE")).also { b ->
            b.currentUrlOverride = lazyUrl; b.urlByTab["t1"] = lazyUrl; b.currentTitle = "Magnolia Bakery - Zomato"
            b.elements = orderOnlineElements; b.modalLabel = "Cookie banner"
            b.pageTextFn = { "Overview\nMenu\nPhotos\nOrder Online" }
            b.locateReply = { phrase -> located += phrase; """{"ok":true,"located":{"found":true,"count":1,"in_view":true}}""" }
        }
        val requests = mutableListOf<AiRequest>()
        val run = UseRun("order tres leches from magolia bakery on zomato")
        val report = flow(browser, FakeTabs(), requests, plan = orderPlan).runComputerUse(UseRequest("order tres leches from magolia bakery on zomato", maxSteps = 12), run)
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertEquals(0, browser.stepArgs("navigate").size)
        // The one planner call: stay forced, with the page, its elements (nothing covered, the dialog named), the milestone and what was tried.
        val ask = requests.single { it.system.startsWith("A browser agent, Jev, is stuck") }
        val text = ask.messages.single().text
        assertTrue(text.contains(lazyUrl) && text.contains("Magnolia Bakery - Zomato"))
        assertTrue(text.contains("is already the right site: answer {\"stay\":true,\"hint\":\"...\"} and never an address"))
        assertTrue(text.contains("Working on: A Magnolia Bakery Zomato order page is open"))
        assertTrue(text.contains("tab | \"Order Online\"") && text.contains("tab | \"Menu\""))
        assertFalse(text.contains("Covered thing"))
        assertTrue(text.contains("A dialog \"Cookie banner\" is open over the page"))
        assertTrue(text.contains("Overview\nMenu\nPhotos\nOrder Online")) // the page text
        assertTrue(ask.images().isEmpty()) // no vision needed
        // The hint is advice in Jev's next look, and the element it names is looked for.
        assertTrue(browser.jevArgs[1]["state"]!!.jsonObject["advice"]!!.jsonArray.single().jsonPrimitive.content.contains("Click the 'Order Online' tab"))
        assertTrue("Order Online" in located, located.toString())
        assertTrue("Order Online" !in located.take(1)) // the first look was before the hint
        assertTrue(browser.jevArgs[1]["state"]!!.jsonObject["targets"]!!.jsonArray.any { it.jsonObject["text"]!!.jsonPrimitive.content == "Order Online" })
        assertEquals(listOf("Order Online", "Menu"), run.extraTargets) // the elements the hint names, in the order it names them
        // Recorded as a step, counted as a replan and an LLM call.
        assertTrue(report.steps.first { it.op == VisionOp.REPLAN }.note!!.startsWith("advised: Click the 'Order Online' tab"))
        assertEquals(1, run.replans)
        assertEquals(2, report.llmCalls) // plan + advice
    }

    private fun AiRequest.images() = messages.flatMap { it.images }

    @Test
    fun `advice counts against max_replans, and once it is used up the run stops the normal way`() = runTest {
        replanReplies = ArrayDeque(listOf(adviceReply, adviceReply, adviceReply))
        val b = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl; it.elements = orderOnlineElements; it.pageTextFn = { "x" } }
        val requests = mutableListOf<AiRequest>()
        val report = flow(b, FakeTabs(), requests, plan = orderPlan).runComputerUse(UseRequest("t", maxSteps = 20))
        assertEquals(2, requests.count { it.system.startsWith("A browser agent, Jev, is stuck") }) // the default of 2
        assertEquals(UseStatus.NEEDS_INPUT, report.status)
        assertEquals(2, report.steps.count { it.op == VisionOp.REPLAN })
        assertEquals(0, b.stepArgs("navigate").size)
        // None allowed: the normal path at once.
        val none = mutableListOf<AiRequest>()
        replanReplies = ArrayDeque(listOf(adviceReply))
        flow(FakeBrowser(tmp(), listOf("BLOCKED")).also { it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl }, FakeTabs(), none, plan = orderPlan).runComputerUse(UseRequest("t", maxReplans = 0))
        assertEquals(0, none.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        // A login wall on the right site is the person's, not the planner's.
        val login = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl; it.modalLabel = "Login"; it.pageTextFn = { "Login\nSend one time password" } }
        val lr = mutableListOf<AiRequest>()
        replanReplies = ArrayDeque(listOf(adviceReply))
        flow(login, FakeTabs(), lr, plan = orderPlan).runComputerUse(UseRequest("t"))
        assertEquals(0, lr.count { it.system.startsWith("A browser agent, Jev, is stuck") })
        // A planner that answers with an address instead is not obeyed here: no advice, no move.
        val move = FakeBrowser(tmp(), listOf("BLOCKED")).also { it.currentUrlOverride = lazyUrl; it.urlByTab["t1"] = lazyUrl }
        replanReplies = ArrayDeque(listOf(replanReply()))
        val mr = flow(move, FakeTabs(), mutableListOf(), plan = orderPlan).runComputerUse(UseRequest("t", maxReplans = 1))
        assertEquals(0, move.stepArgs("navigate").size)
        assertEquals(UseStatus.NEEDS_INPUT, mr.status)
        // The reply forms: a bare hint counts as staying.
        assertEquals(Cu.ReplanReply.Stay("go"), Cu.parseReplan("""{"hint":"go"}""", "g"))
    }

    @Test
    fun `for doing things the first milestone names the page where the action happens`() {
        val p = Cu.PLAN_SYSTEM
        assertTrue(p.contains("For doing things (order, book, buy, apply, submit) the first milestone names the page where the action happens"))
        assertTrue(p.contains("Magnolia Bakery's Order Online menu (with ADD buttons) is open"))
        assertTrue(p.contains("not just \"the restaurant page is open\""))
        assertTrue(Cu.REPLAN_SYSTEM.contains("the first one names the page where the action happens"))
    }

    // ---- done is never said over a login ----

    private val stopPlan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Order a cake","milestones":["Tres Leches is in the cart","Stop at the login or payment step"],"type_values":[]}"""

    @Test
    fun `the last stop-at-login milestone reads the screenshot's own dialog, so a login dialog is needs_input not done`() = runTest {
        // The live miss: observe and the page text knew nothing of the dialog; only the screenshot said "Login" is open.
        val b = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN")).also {
            it.shotModalLabel = "Login"; it.pageTextFn = { "Your Order (1)\nSubtotal \u20B9500" }
        }
        val r = flow(b, FakeTabs(), mutableListOf(), plan = stopPlan).runComputerUse(UseRequest("order", maxSteps = 8))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        val q = r.blocker!!.question!!
        assertTrue(q.startsWith("sample.com is asking you to log in."), q)
        assertTrue(q.contains("then reply 'continue'"))
        // The login wording wins over the one-time password it mentions.
        val otp = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "SCROLL_DOWN")).also {
            it.shotModalLabel = "Login"; it.pageTextFn = { "Login\nPhone number\nSend One Time Password\nContinue with Email\nContinue with Google" }
        }
        val q2 = flow(otp, FakeTabs(), mutableListOf(), plan = stopPlan).runComputerUse(UseRequest("order", maxSteps = 8)).blocker!!.question!!
        assertTrue(q2.contains("is asking you to log in."), q2)
        assertFalse(q2.contains("verification code"))
        assertEquals("log in", Cu.wallKind("Login", "Phone number\nSend One Time Password"))
        assertEquals("log in", Cu.wallKind("Sign in to continue", "Enter OTP"))
        assertEquals("enter a verification code", Cu.wallKind("Verify", "Enter the verification code"))
        assertEquals("enter payment details", Cu.wallKind("Payment", "Card number\nCVV"))
        // No dialog, no wall: the stop milestone is simply reached.
        val calm = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { it.milestoneScript = listOf(null, 0.9, null); it.pageTextFn = { "Your Order (1)" } }
        assertEquals(UseStatus.DONE, flow(calm, FakeTabs(), mutableListOf(), plan = stopPlan).runComputerUse(UseRequest("order", maxSteps = 8)).status)
    }

    @Test
    fun `any done over an open login or payment dialog is needs_input`() = runTest {
        // No milestones: Jev says DONE with a login dialog open.
        val login = FakeBrowser(tmp(), listOf("DONE")).also { it.shotModalLabel = "Login"; it.pageTextFn = { "Login\nSend One Time Password" } }
        val r = flow(login, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t"))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.blocker!!.question!!.contains("is asking you to log in"))
        // A payment dialog.
        val pay = FakeBrowser(tmp(), listOf("DONE")).also { it.shotModalLabel = "Pay"; it.pageTextFn = { "Card number\nCVV" } }
        assertTrue(flow(pay, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).blocker!!.question!!.contains("enter payment details"))
        // Through an ordinary last milestone too (not only a stop one).
        val ordinary = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"g","milestones":["Page open","Item in the cart"],"type_values":[]}"""
        val m = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "CLICK", "SCROLL_DOWN")).also { it.milestoneScript = listOf(0.9, null, 0.9); it.shotModalLabel = "Sign in"; it.pageTextFn = { "Sign in\nEmail" } }
        assertEquals(UseStatus.NEEDS_INPUT, flow(m, FakeTabs(), mutableListOf(), plan = ordinary).runComputerUse(UseRequest("t", maxSteps = 8)).status)
        // A dialog that is not a wall (a cookie notice) does not stop a done.
        val cookie = FakeBrowser(tmp(), listOf("DONE")).also { it.shotModalLabel = "Cookie settings"; it.pageTextFn = { "We use cookies" } }
        assertEquals(UseStatus.DONE, flow(cookie, FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).status)
        // No dialog: done, as before.
        assertEquals(UseStatus.DONE, flow(FakeBrowser(tmp(), listOf("DONE")), FakeTabs(), mutableListOf()).runComputerUse(UseRequest("t")).status)
        // The result field is read from the screenshot, as an object or a string.
        assertEquals("Login", Cu.parseShot(json("""{"screenshot":{"path":"/x","width":1,"height":1,"css_width":1,"css_height":1,"modal":{"label":"Login"}}}"""))!!.modal)
        assertEquals("Login", Cu.parseShot(json("""{"modal":"Login","screenshot":{"path":"/x","width":1,"height":1,"css_width":1,"css_height":1}}"""))!!.modal)
        assertNull(Cu.parseShot(json("""{"screenshot":{"path":"/x","width":1,"height":1,"css_width":1,"css_height":1}}"""))!!.modal)
    }

    // ---- popup windows that are not tabs ----

    private val popupOpen = """[{"id":"t1#popup-1","url":"https://accounts.sample.com/signin","title":"Sign in","open":true}]"""
    private val popupClosed = """[{"id":"t1#popup-1","url":"https://accounts.sample.com/signin","title":"Sign in","open":false}]"""

    @Test
    fun `a popup window listed in the results is followed with the pointer and left when it reports closed`() = runTest {
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        var closed = false
        val browser = FakeBrowser(tmp(), listOf("CLICK", "CLICK", "DONE")).also { b ->
            b.urlByTab["t1#popup-1"] = "https://accounts.sample.com/signin"
            b.onAction = { type, tab ->
                if (type == "click_at" && tab == "t1" && b.popupsJson == null) b.popupsJson = popupOpen
                else if (type == "click_at" && tab == "t1#popup-1" && !closed) { closed = true; b.popupsJson = popupClosed }
                else if (closed && type == "screenshot") b.popupsJson = null
            }
        }
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("sign in with the popup window", pointerLabel = "Max", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertEquals("Switched to popup window 'Sign in'", report.steps[0].popup)
        assertTrue(report.steps[1].popup!!.startsWith("Popup window closed, back on 'Main'"), report.steps[1].popup)
        assertEquals(listOf("t1", "t1#popup-1"), report.steps.map { it.tabId })
        val shotTabs = browser.calls.filter { it.first == ToolNames.STEP && it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content == "screenshot" }.map { it.second["tab_id"]!!.jsonPrimitive.content }
        assertEquals(listOf("t1", "t1#popup-1", "t1"), shotTabs)
        assertEquals("Max", browser.stepArgs("click_at")[1]["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("t1" to "opened", "t1#popup-1" to "popup_window"), report.tabs.map { it.tabId to it.kind })
        // A popup window is not a BOSS tab: no lock, no reveal, no cu_end of its own.
        assertTrue(tabs.claimed.none { "#popup" in it })
        assertEquals(listOf("t1"), tabs.revealed)
        assertEquals(listOf("t1"), browser.stepArgs("cu_end").map { it["tab_id"]!!.jsonPrimitive.content })
        assertEquals("t1", report.tabId)
    }

    @Test
    fun `a call on a popup window that fails as closed goes back, and listings of other tabs or repeats change nothing`() = runTest {
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        val browser = FakeBrowser(tmp(), listOf("CLICK", "DONE")).also { b ->
            b.onAction = { type, tab -> if (type == "click_at" && tab == "t1" && b.popupsJson == null) b.popupsJson = popupOpen.dropLast(1) + """,{"id":"t9#popup-1","url":"https://x.com","title":"Not ours","open":true}]""" }
        }
        val calls = mutableListOf<String>()
        // The popup is gone by the time it is looked at.
        browser.deadTabs += "t1#popup-1"
        val report = flow(browser, tabs, mutableListOf()).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertTrue(report.steps[0].popup!!.contains("Popup window closed, back on 'Main'"), report.steps[0].popup)
        assertEquals(0, browser.calls.count { it.second["tab_id"]?.jsonPrimitive?.content == "t9#popup-1" })
        // The listing repeated on every result is one opening, not a new switch each time.
        assertEquals(1, report.tabs.count { it.kind == "popup_window" })
        assertTrue(calls.isEmpty())
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
            b.dialogPickLabel = "enter 'Ada'"
            b.onAction = { type, _ -> if (type == "click_at") b.dialog = """{"type":"prompt","message":"What is your name?","default_value":"","url":"https://sample.com/"}""" }
        }
        val r1 = flow(prompt, FakeTabs(), mutableListOf(), plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Say hello","type_values":[]}""")
            .runComputerUse(UseRequest("Say hello", inputs = mapOf("name" to "Ada")))
        assertEquals(listOf("ok:Ada"), prompt.dialogAnswers)
        assertEquals("Dialog 'What is your name?': OK 'Ada'", r1.steps[1].description)
        assertEquals("Ada", r1.steps[1].text)
        assertEquals(0, prompt.textOptionsSeen.size) // the text was chosen in the dialog question itself

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
        // A finished run reads exactly as browser_use returned it, with the log's detail under "record".
        assertEquals("done", one["status"]!!.jsonPrimitive.content)
        assertEquals(report.runId, one["run_id"]!!.jsonPrimitive.content)
        assertEquals(report.toJson().keys, one.keys - "record")
        assertTrue("steps" in one && "activity" !in one)
        assertTrue("activity_count" in one["record"]!!.jsonObject && "activity" !in one["record"]!!.jsonObject && "report" !in one["record"]!!.jsonObject)
        assertTrue("activity" in json(call("""{"run_id":"${run.id}","include_activity":true}""").text)["record"]!!.jsonObject)
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

    // ---- set of marks, look, milestones (D5) ----

    private val menuElements = """[
        {"id":"m1","role":"button","tag":"button","label":"ADD","rect":{"x":900,"y":300,"w":80,"h":32},"disabled":false,"covered":false,"context":"Eggless Classic Tres Leches ₹470","editable":false},
        {"id":"m2","role":"button","tag":"button","label":"ADD","rect":{"x":900,"y":420,"w":80,"h":32},"disabled":true,"covered":false,"context":"Brownie ₹250","editable":false},
        {"id":"m3","role":"searchbox","tag":"input","label":"Search","value_kind":"empty","rect":{"x":100,"y":20,"w":400,"h":36},"disabled":false,"covered":false,"context":"","editable":true},
        {"id":"m4","role":"link","tag":"a","label":"Privacy","rect":{"x":10,"y":700,"w":60,"h":20},"disabled":false,"covered":true,"context":"","editable":false,"href_host":"zomato.com"}]"""
    private val marksPlan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Add Tres Leches from Magnolia Bakery to the cart","entities":["Magnolia Bakery","Tres Leches"],"stop_when":"login or payment step","type_values":[]}"""
    /** A box centred on m1 (900..980 x 300..332 css px of the 1120 x 780 viewport). */
    private val boxOnM1 = """{"top":0.385,"bottom":0.43,"left":0.805,"right":0.875}"""

    private fun refCount(e: kotlinx.serialization.json.JsonElement): Int = when (e) {
        is JsonPrimitive -> if (e.isString && e.content == Cu.SHOT_REF) 1 else 0
        is JsonObject -> e.values.sumOf { refCount(it) }
        is JsonArray -> e.sumOf { refCount(it) }
        else -> 0
    }

    private fun looking(script: List<String>, setup: FakeBrowser.() -> Unit = {}) = FakeBrowser(tmp(), script).also {
        it.lookSupported = true; it.lookElements = menuElements; it.realImages = true; it.setup()
    }

    @Test
    fun `the request for a look has options keyed by mark id with context and state, NONE, and one image reference`() {
        val marks = Marks.visible(Marks.parseMarks(json("""{"e":$menuElements}""")["e"]!!.jsonArray))
        assertEquals(listOf("m1", "m2", "m3"), marks.map { it.id }) // the covered link is not offered
        val args = Cu.decideArgs("typesafe/jev-1.14", "Add Tres Leches", "https://z.example/", "Z", emptyList(), "/x/shot-marks.jpg", marks = marks, stopWhen = "login or payment step")
        val q = args["questions"]!!.jsonObject
        assertEquals(setOf("operation", "click_target", "type_target", "target", "scroll"), q.keys)
        val click = q["click_target"]!!.jsonObject
        assertEquals("choice", click["type"]!!.jsonPrimitive.content)
        val criteria = click["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(setOf("m1", "m2", Marks.NONE), criteria.keys)
        assertEquals("[1] button 'ADD' — Eggless Classic Tres Leches ₹470", criteria["m1"])
        assertEquals("[2] button 'ADD' — Brownie ₹250 · disabled", criteria["m2"])
        val type = q["type_target"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(setOf("m3", Marks.NONE), type.keys)
        assertEquals("[3] searchbox 'Search' · empty", type["m3"])
        // The box question stays, as a fallback and a check.
        assertEquals("box", q["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(q["operation"]!!.jsonObject["criteria"]!!.jsonObject["CLICK"]!!.jsonPrimitive.content.contains("click_target"))
        assertTrue(q["operation"]!!.jsonObject["criteria"]!!.jsonObject.containsKey("NEEDS_INPUT"))
        // The image is one asset, named once: by the state, never again by a question.
        assertEquals(1, refCount(args["state"]!!) + refCount(q))
        assertEquals(setOf(Cu.SHOT_REF), args["assets"]!!.jsonObject.keys)
        assertEquals("/x/shot-marks.jpg", args["assets"]!!.jsonObject[Cu.SHOT_REF]!!.jsonObject["path"]!!.jsonPrimitive.content)
        val state = args["state"]!!.jsonObject
        assertEquals(3, state["marks"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("login or payment step", state["stop_when"]!!.jsonPrimitive.content)
        // Page text is data, in the question's own words.
        assertTrue(click["instructions"]!!.jsonPrimitive.content.contains("untrusted page data"))
        // No candidate, no question: only fields marked means no click_target, and no marks at all means the box alone.
        val onlyField = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg", marks = marks.filter { it.id == "m3" })["questions"]!!.jsonObject
        assertEquals(setOf("operation", "type_target", "target", "scroll"), onlyField.keys)
        val plain = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg")
        assertEquals(setOf("operation", "target", "scroll"), plain["questions"]!!.jsonObject.keys)
        assertNull(plain["state"]!!.jsonObject["marks"])
        assertEquals(Cu.OPERATIONS["CLICK"], plain["questions"]!!.jsonObject["operation"]!!.jsonObject["criteria"]!!.jsonObject["CLICK"]!!.jsonPrimitive.content)
    }

    private fun mark(i: Int, role: String = "button", context: String = "row $i") = Mark("m$i", role, role, "L$i", null, Cu.Rect(0.0, i.toDouble(), 10.0, i + 10.0), false, false, context, false, null)

    @Test
    fun `a question never has more than 255 options, and navigation links are dropped first`() {
        val many = (1..100).map { mark(it, "link", "") } + (101..260).map { mark(it, "button") }
        val shown = Marks.visible(many)
        assertEquals(254, shown.size) // 255 with NONE
        assertEquals(160, shown.count { it.role == "button" }) // every card button stays
        assertEquals(94, shown.count { it.role == "link" })
        // A link inside a row or card is not chrome: it is kept in preference to the bare ones.
        val mixed = (1..200).map { mark(it, "link", "") } + (201..400).map { mark(it, "link", "Card $it") }
        assertTrue(Marks.visible(mixed).count { it.context.isNotBlank() } == 200)
        val args = Cu.decideArgs("m", "g", "u", "t", emptyList(), "/x.jpg", marks = Marks.visible(many))
        assertEquals(255, args["questions"]!!.jsonObject["click_target"]!!.jsonObject["criteria"]!!.jsonObject.size)
        assertEquals(10, Marks.visible((1..10).map { mark(it) }).size)
    }

    @Test
    fun `an option reads kind, label, context and state, and a typed value is never in it`() {
        fun m(label: String = "Go", valueKind: String? = null, disabled: Boolean = false, context: String = "", host: String? = null, role: String = "button") =
            Mark("m7", role, role, label, valueKind, Cu.Rect(0.0, 0.0, 1.0, 1.0), disabled, false, context, false, host)
        assertEquals("[7] button 'Go'", Marks.optionText(m()))
        assertEquals("[7] checkbox 'Eggless' · unchecked", Marks.optionText(m("Eggless", "unchecked", role = "checkbox")))
        assertEquals("[7] combobox 'Size' · selected Large", Marks.optionText(m("Size", "selected:Large", role = "combobox")))
        assertEquals("[7] textbox 'Name' · has text", Marks.optionText(m("Name", "text", role = "textbox")))
        assertEquals("[7] link 'Menu' · link to zomato.com", Marks.optionText(m("Menu", host = "zomato.com", role = "link")))
        assertEquals("[7] button 'Go' — a   b\nc", Marks.optionText(m(context = "a b c")).replace("a b c", "a   b\nc"))
        // Long text is cut.
        assertTrue(Marks.optionText(m(label = "x".repeat(300), context = "y".repeat(300))).length < 200)
    }

    private fun decision(op: VisionOp = VisionOp.CLICK, click: String? = "m1", type: String? = null, box: Box? = Box(0.385, 0.43, 0.805, 0.875), p: Double = 0.9) =
        VisionDecision(op, p, box, 0.8, null, 0.0, clickChoice = click, typeChoice = type)

    @Test
    fun `the chosen mark is used, the box only checks it and lowers the confidence when it points elsewhere`() {
        val marks = Marks.parseMarks(json("""{"e":$menuElements}""")["e"]!!.jsonArray)
        // The box centre (940, 316) is inside m1: used, nothing changes.
        val ok = Marks.reconcile(decision(), marks, 1120, 780)
        assertEquals("m1", ok.mark!!.id)
        assertFalse(ok.boxDisagreed)
        assertEquals(0.9, ok.confidence)
        // Just outside the element but inside its rect grown by 10% (8 px of 80): still the same target.
        val near = Marks.reconcile(decision(box = Box(0.385, 0.43, (980 + 7 - 20) / 1120.0, (980 + 7 + 20) / 1120.0)), marks, 1120, 780)
        assertFalse(near.boxDisagreed, near.toString())
        // Far away: the mark still wins, with confidence x 0.8 and a flag.
        val far = Marks.reconcile(decision(box = Box(0.8, 0.9, 0.1, 0.2)), marks, 1120, 780)
        assertEquals("m1", far.mark!!.id)
        assertTrue(far.boxDisagreed)
        assertEquals(0.9 * 0.8, far.confidence, 1e-9)
        // No box to compare: the mark is used, no disagreement.
        val noBox = Marks.reconcile(decision(box = null), marks, 1120, 780)
        assertEquals("m1", noBox.mark!!.id); assertFalse(noBox.boxDisagreed)
        // NONE, an id that is not on the page, a covered element, or an operation that clicks nothing: the box decides.
        assertNull(Marks.reconcile(decision(click = Marks.NONE), marks, 1120, 780).mark)
        assertNull(Marks.reconcile(decision(click = "m99"), marks, 1120, 780).mark)
        assertNull(Marks.reconcile(decision(click = "m4"), marks, 1120, 780).mark)
        assertNull(Marks.reconcile(decision(click = null), marks, 1120, 780).mark)
        assertNull(Marks.reconcile(decision(op = VisionOp.SCROLL_DOWN), marks, 1120, 780).mark)
        // TYPE_TEXT reads type_target, not click_target.
        assertEquals("m3", Marks.reconcile(decision(op = VisionOp.TYPE_TEXT, click = "m1", type = "m3", box = Box(0.02, 0.06, 0.1, 0.4)), marks, 1120, 780).mark!!.id)
        // The answers are read from the reply.
        val d = Cu.parseDecision(json("""{"response":{"answers":{"operation":{"type":"choice","choice":"CLICK","probabilities":{"CLICK":0.9}},"click_target":{"type":"choice","choice":"m1","probabilities":{"m1":0.8}},"type_target":{"type":"choice","choice":"NONE"}}}}"""))
        assertEquals("m1", d.clickChoice); assertEquals("NONE", d.typeChoice); assertNull(d.box)
    }

    @Test
    fun `look results are read from under look, with the dialog and popups at either level`() {
        val body = Marks.body(json("""{"ok":true,"popups":[{"id":"t1#popup-1","open":true}],"look":{"screenshot":{"path":"/x/s.jpg","width":560,"height":390,"css_width":1120,"css_height":780,"dpr":2,"modal":{"label":"Login"},"content_hash":"c1"},""" +
            """"located":{"Tres Leches":{"found":true,"count":2,"in_view":false,"direction":"down","screens":1.5}},"elements":$menuElements,"page_text":"Menu"}}"""))!!
        val look = Marks.parseLook(body)!!
        assertEquals("/x/s.jpg", look.shot.path); assertEquals("Login", look.shot.modal); assertEquals("c1", look.shot.contentHash)
        assertEquals(1.5, look.located.getValue("Tres Leches").screens); assertEquals("down", look.located.getValue("Tres Leches").direction)
        assertEquals(4, look.marks.size); assertEquals("Menu", look.pageText)
        val m2 = look.marks[1]
        assertTrue(m2.disabled); assertTrue(look.marks[3].covered); assertTrue(look.marks[2].editable)
        assertEquals(Cu.Rect(900.0, 420.0, 980.0, 452.0), m2.rect)
        assertNotNull(body["popups"])
        // A plain result is returned as it is.
        assertEquals(json("""{"ok":true}"""), Marks.body(json("""{"ok":true}""")))
        assertNull(Marks.body(null))
        // The call: one JSON string of options, 1100 px at quality 0.7.
        val step = Cu.lookStep("t1", listOf("Tres Leches", "Magnolia Bakery"), pageText = false, ensureVisible = true)
        val action = step["action"]!!.jsonObject
        assertEquals("look", action["type"]!!.jsonPrimitive.content)
        val opts = json(action["value"]!!.jsonPrimitive.content)
        assertEquals(1100, opts["max_side"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.7, opts["quality"]!!.jsonPrimitive.content.toDouble())
        assertEquals(listOf("Tres Leches", "Magnolia Bakery"), opts["locate"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", opts["elements"]!!.jsonPrimitive.content); assertEquals("true", opts["ensure_visible"]!!.jsonPrimitive.content); assertEquals("false", opts["page_text"]!!.jsonPrimitive.content)
        // The activity line says look, not its options; a click by element says which.
        assertEquals("look tab t1", RecordingTools.summarise(ToolNames.STEP, step))
        assertTrue(RecordingTools.summarise(ToolNames.STEP, Cu.step("t1", "click_at", "940,316", element = "m1")).contains("element m1"))
        assertEquals("m1", Cu.step("t1", "click_at", "940,316", element = "m1")["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        assertNull(Cu.step("t1", "click_at", "1,2")["action"]!!.jsonObject["element"])
    }

    @Test
    fun `the marks are drawn on a copy of the screenshot, outlined and numbered, with covered ones left out`() {
        val dir = tmp()
        val src = File(dir, "shot-1.jpg")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(560, 390, java.awt.image.BufferedImage.TYPE_INT_RGB).also { img -> img.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 560, 390); dispose() } }, "jpg", src)
        val shot = Shot(src.absolutePath, 560, 390, 1120, 780, 2.0)
        val marks = Marks.visible(Marks.parseMarks(json("""{"e":$menuElements}""")["e"]!!.jsonArray))
        val out = File(MarkImage.render(shot, marks)!!)
        assertEquals("shot-1-marks.jpg", out.name)
        assertFalse(out.readBytes().contentEquals(src.readBytes()))
        File("build/reports/visual").also { it.mkdirs() }.let { out.copyTo(File(it, "marks-sample.jpg"), overwrite = true) }
        val img = javax.imageio.ImageIO.read(out)
        assertEquals(560, img.width); assertEquals(390, img.height)
        // m1's outline: its top edge is at css y 300 = image y 150, x from 450.
        fun dark(x: Int, y: Int) = java.awt.Color(img.getRGB(x, y)).let { it.red + it.green + it.blue < 600 }
        assertTrue((148..152).any { dark(470, it) }, "no outline for m1")
        // m2 is disabled: grey, but marked. The covered link m4 (css 10..70 x 700..720 = image 5..35 x 350..360) is not.
        assertTrue((208..212).any { dark(470, it) }, "no outline for the disabled m2")
        assertFalse((348..362).any { dark(20, it) }, "a covered element is marked")
        // The original is never touched; nothing to draw, nothing written.
        assertNull(MarkImage.render(shot, emptyList()))
        assertNull(MarkImage.render(shot.copy(path = "/nowhere.jpg"), marks))
    }

    @Test
    fun `a run with look takes one look per decision, asks Jev in one call with the marked image, and clicks the picked mark by id`() = runTest {
        val b = looking(listOf("CLICK", "DONE")) { clickPick = "m1"; boxJson = boxOnM1 }
        val run = UseRun("add tres leches")
        val report = flow(b, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("add tres leches", maxSteps = 6), run)
        assertEquals(UseStatus.DONE, report.status, report.error)
        // One look and one decision each; no screenshot, locate_text or page_text call per step (page_text is read once, for the report).
        assertEquals(2, b.lookArgs.size)
        assertEquals(b.jevArgs.size, b.lookArgs.size)
        assertEquals(0, b.stepArgs("screenshot").size)
        assertEquals(0, b.stepArgs("locate_text").size)
        assertEquals(1, b.stepArgs("page_text").size)
        // The look asks for what the decision needs.
        val first = b.lookArgs[0]
        assertEquals(listOf("Magnolia Bakery", "Tres Leches"), first["locate"]!!.jsonArray.map { it.jsonPrimitive.content }.take(2))
        assertEquals("true", first["elements"]!!.jsonPrimitive.content); assertEquals("1100", first["max_side"]!!.jsonPrimitive.content); assertEquals("true", first["page_text"]!!.jsonPrimitive.content)
        // Jev sees the marked copy, once, with the elements as options.
        val sent = b.jevArgs[0]
        val path = sent["assets"]!!.jsonObject[Cu.SHOT_REF]!!.jsonObject["path"]!!.jsonPrimitive.content
        assertTrue(path.endsWith("-marks.jpg") && File(path).exists(), path)
        assertEquals(1, refCount(sent["state"]!!) + refCount(sent["questions"]!!))
        assertEquals(setOf("m1", "m2", Marks.NONE), sent["questions"]!!.jsonObject["click_target"]!!.jsonObject["criteria"]!!.jsonObject.keys)
        assertEquals("login or payment step", sent["state"]!!.jsonObject["stop_when"]!!.jsonPrimitive.content)
        assertTrue(run.log.entries.value.any { it.name == "render marks" && it.image == path })
        assertTrue(run.log.entries.value.any { it.name == "rpa_step" && it.args.startsWith("look") && it.result.contains("4 elements") })
        // The click goes by element id, at the element's centre, with no snap box.
        val click = b.stepArgs("click_at").single()["action"]!!.jsonObject
        assertEquals("m1", click["element"]!!.jsonPrimitive.content)
        assertEquals("940,316", click["value"]!!.jsonPrimitive.content)
        assertNull(click["snap"])
        val step = report.steps.first { it.op == VisionOp.CLICK }
        assertEquals("button 'ADD'", step.target)
        assertNull(step.note)
        assertEquals(0.9, step.confidence)
        assertEquals("button 'ADD'", Cu.recentAction(step)["element"]!!.jsonPrimitive.content)
    }

    @Test
    fun `typing goes to the picked field by id, and NONE falls back to the box with snap`() = runTest {
        val typing = looking(listOf("TYPE_TEXT", "DONE")) { typePick = "m3"; boxJson = """{"top":0.0,"bottom":0.1,"left":0.1,"right":0.4}""" }
        val r = flow(typing, FakeTabs(), mutableListOf(), plan = marksPlan.replace("\"type_values\":[]", "\"type_values\":[\"Palo Alto\"]")).runComputerUse(UseRequest("t", maxSteps = 6))
        assertEquals(UseStatus.DONE, r.status, r.error)
        val c = typing.stepArgs("click_at").single()["action"]!!.jsonObject
        assertEquals("m3", c["element"]!!.jsonPrimitive.content)
        assertEquals("300,38", c["value"]!!.jsonPrimitive.content)
        assertEquals(1, typing.stepArgs("type_text").size)
        // NONE: the box, as before, with its snap and no element.
        val none = looking(listOf("CLICK", "DONE")) { clickPick = null }
        flow(none, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 6))
        val a = none.stepArgs("click_at").single()["action"]!!.jsonObject
        assertEquals("560,195", a["value"]!!.jsonPrimitive.content)
        assertNotNull(a["snap"])
        assertNull(a["element"])
        // An id the page does not have (stale or invented) is the same.
        val ghost = looking(listOf("CLICK", "DONE")) { clickPick = "m99" }
        flow(ghost, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 6))
        assertNull(ghost.stepArgs("click_at").single()["action"]!!.jsonObject["element"])
        // No marks on the page: no mark questions, the box alone.
        val bare = looking(listOf("CLICK", "DONE")) { lookElements = "[]" }
        flow(bare, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 6))
        assertEquals(setOf("operation", "target", "scroll"), bare.questionKeys.first())
        assertEquals(0, bare.stepArgs("click_at").single()["action"]!!.jsonObject.keys.count { it == "element" })
    }

    @Test
    fun `when the box points elsewhere than the mark the mark is still clicked, with lower confidence and a note`() = runTest {
        val b = looking(listOf("CLICK", "DONE")) { clickPick = "m1"; boxJson = """{"top":0.8,"bottom":0.9,"left":0.1,"right":0.2}""" }
        val report = flow(b, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 6))
        assertEquals("m1", b.stepArgs("click_at").single()["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        val step = report.steps.first { it.op == VisionOp.CLICK }
        assertEquals(0.9 * 0.8, step.confidence, 1e-9)
        assertEquals("box disagreed", step.note)
        assertFalse(step.failed)
        // The lower confidence counts: under consult_below it is stuck, like any unsure decision.
        val unsure = looking(listOf("CLICK")) { clickPick = "m1"; boxJson = """{"top":0.8,"bottom":0.9,"left":0.1,"right":0.2}""" }
        val stuck = flow(unsure, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 6, consultBelow = 0.75))
        assertEquals(UseStatus.NEEDS_INPUT, stuck.status)
    }

    @Test
    fun `a click on a disabled or covered element is a note for the next decision, not an action and not a failure`() = runTest {
        val b = looking(listOf("CLICK", "CLICK", "DONE")) { clickPick = "m2"; boxJson = boxOnM1 }
        b.clickErrors += "'ADD' is disabled"
        val report = flow(b, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, report.status, report.error)
        val refused = report.steps.first { it.op == VisionOp.CLICK }
        assertTrue(refused.refused); assertFalse(refused.failed)
        assertEquals("ADD is disabled", refused.note)
        // Jev's next look has it among the recent actions, with the reason.
        val next = b.jevArgs[1]["state"]!!.jsonObject["recent_actions"]!!.jsonArray.single().jsonObject
        assertEquals("ADD is disabled", next["error"]!!.jsonPrimitive.content)
        assertEquals("button 'ADD'", next["element"]!!.jsonPrimitive.content)
        // Not an action: it never feeds "3 actions left the page where it was", never credits a milestone, and two in a row are not "two actions failed".
        assertNull(Cu.noProgress(List(2) { refused.copy(before = seen()) }, seen()))
        assertFalse(Cu.mayCredit("Tres Leches is visible on the menu", false, listOf(refused)))
        val twice = looking(listOf("CLICK", "CLICK", "DONE")) { clickPick = "m2" }
        twice.clickErrors += "'ADD' is disabled"; twice.clickErrors += "'ADD' is covered by Login dialog"
        val two = flow(twice, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, two.status, two.error)
        assertEquals(listOf("ADD is disabled", "ADD is covered by Login dialog"), two.steps.filter { it.refused }.map { it.note })
        // The same refusal three times is a stuck run, said as such.
        val loop = looking(listOf("CLICK")) { clickPick = "m2" }
        repeat(3) { loop.clickErrors += "'ADD' is disabled" }
        val stuck = flow(loop, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.NEEDS_INPUT, stuck.status)
        assertTrue(stuck.error!!.contains("refused"), stuck.error)
        // Only the engine's two refusals are read so; any other failure stays a failure.
        assertEquals("ADD is disabled", Marks.refusal("'ADD' is disabled"))
        assertEquals("ADD is covered by Login", Marks.refusal("'ADD' is covered by Login"))
        assertNull(Marks.refusal("the click is outside the viewport"))
        assertNull(Marks.refusal(null))
    }

    @Test
    fun `an engine without look is asked once, then the run uses screenshot, locate_text and page_text, with the box`() = runTest {
        val b = FakeBrowser(tmp(), listOf("CLICK", "SCROLL_DOWN", "DONE"))
        val report = flow(b, FakeTabs(), mutableListOf(), plan = marksPlan).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertEquals(1, b.stepArgs("look").size)
        assertTrue(b.stepArgs("screenshot").size >= 3)
        assertTrue(b.stepArgs("locate_text").isNotEmpty())
        // The first look call came first, and what Jev got is the old request: a box, no mark questions.
        assertEquals("look", b.stepTypes().first { it != "cu_end" })
        assertTrue(b.questionKeys.all { it == setOf("operation", "target", "scroll") })
        assertEquals(1, b.stepArgs("click_at").size)
        assertNull(b.stepArgs("click_at").single()["action"]!!.jsonObject["element"])
        // What counts as "no such verb": an error that says so, not a dead tab or a refused call.
        assertTrue(Cu.lookUnsupported(ToolReply("""{"ok":false,"error":"Unknown action type look"}""", false)))
        assertTrue(Cu.lookUnsupported(ToolReply("""{"error":{"code":"BAD_ARGS","message":"unsupported action type: look"}}""", true)))
        assertFalse(Cu.lookUnsupported(ToolReply("""{"error":{"code":"NO_BROWSER","message":"unknown action type"}}""", true)))
        assertFalse(Cu.lookUnsupported(ToolReply("""{"ok":false,"error":"A browser dialog is waiting","dialog":{"type":"alert","message":"x"}}""", false)))
        assertFalse(Cu.lookUnsupported(ToolReply("""{"ok":true,"look":{}}""", false)))
        assertFalse(Cu.lookUnsupported(ToolReply("""{"ok":false,"error":"the tab is gone"}""", false)))
    }

    @Test
    fun `a milestone Jev says is reached is credited and its own decision is acted on, with no extra look`() = runTest {
        val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"g","milestones":["The menu is open","Tres Leches is visible"],"type_values":[]}"""
        val b = looking(listOf("CLICK", "SCROLL_DOWN", "DONE")) { clickPick = "m1"; boxJson = boxOnM1; milestoneScript = listOf(0.9, null, 0.8) }
        val report = flow(b, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, report.status, report.error)
        // Look 1: the milestone is credited and the click it was decided with happens; no look was taken for the credit.
        assertEquals(listOf(VisionOp.MILESTONE, VisionOp.CLICK), report.steps.take(2).map { it.op })
        assertEquals(1, b.stepArgs("click_at").size)
        assertEquals(b.jevArgs.size, b.lookArgs.size)
        // DONE is the exception: nothing to act on, so it looks again.
        assertEquals(UseStatus.DONE, report.status)
        // NEEDS_INPUT never credits a milestone.
        val ask = looking(listOf("NEEDS_INPUT")) { milestoneScript = listOf(0.95) }
        val r = flow(ask, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 4))
        assertEquals(UseStatus.NEEDS_INPUT, r.status)
        assertTrue(r.steps.none { it.op == VisionOp.MILESTONE })
    }

    @Test
    fun `a state left over from before the run is not credited, a cart from an earlier session is the run 85f5 case`() = runTest {
        // The cart already holds the cake: every look says "Tres Leches is in the cart" at 0.9, but the run only scrolls.
        val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Order Tres Leches","milestones":["Magnolia Bakery's Order Online menu is open","Tres Leches is in the cart"],"type_values":[]}"""
        val leftover = looking(listOf("SCROLL_DOWN")) { reportScroll = true; milestoneScript = List(8) { 0.9 }; contentHash = { "same" } }
        val r = flow(leftover, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 6))
        // The first (a page reached by navigation) is credited; the cart is not, however sure Jev is.
        assertEquals(1, r.steps.count { it.op == VisionOp.MILESTONE })
        assertEquals(1, r.milestonesDone)
        assertEquals(UseStatus.BUDGET, r.status)
        // A click that changed nothing on the page does not corroborate it either (same content hash)...
        val noChange = looking(listOf("SCROLL_DOWN", "CLICK", "SCROLL_DOWN")) { reportScroll = true; milestoneScript = List(8) { 0.9 }; contentHash = { "same" } }
        val still = flow(noChange, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 6))
        assertEquals(1, still.milestonesDone)
        assertEquals(UseStatus.BUDGET, still.status)
        // ...and a click that changed the content does: the cart is then Jev's doing.
        var n = 0
        val changed = looking(listOf("SCROLL_DOWN", "CLICK", "SCROLL_DOWN")) { reportScroll = true; milestoneScript = List(8) { 0.9 }; contentHash = { if (++n <= 2) "before" else "after" } }
        val ok = flow(changed, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 8))
        assertEquals(UseStatus.DONE, ok.status, ok.error)
        assertEquals(2, ok.milestonesDone)
        // A DONE for it is refused the same way, and three of them end the run as blocked rather than done.
        val doneOnly = looking(listOf("DONE")) { contentHash = { "same" } }
        val d = flow(doneOnly, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 10))
        assertEquals(UseStatus.BLOCKED, d.status)
        assertTrue(d.error!!.contains("left over"))
        assertEquals(1, d.steps.count { it.op == VisionOp.MILESTONE })
        assertTrue(d.steps.any { it.op == VisionOp.REPLAN && it.note!!.startsWith("DONE was not accepted") })
    }

    @Test
    fun `the rule for crediting a milestone, by what the run did since the last one`() {
        fun step(op: VisionOp, failed: Boolean = false, refused: Boolean = false, contentChanged: Boolean? = null, changed: Boolean? = null, note: String? = null) =
            UseStep(1, op, confidence = 1.0, ms = 0, failed = failed, refused = refused, contentChanged = contentChanged, changed = changed, note = note)
        val plain = "Tres Leches is visible on the menu"
        val cart = "Tres Leches is in the cart"
        // The first one: a state reached by navigation.
        assertTrue(Cu.mayCredit(plain, true, emptyList()))
        // A later one needs an action: scrolls and waits are not.
        assertFalse(Cu.mayCredit(plain, false, emptyList()))
        assertFalse(Cu.mayCredit(plain, false, listOf(step(VisionOp.SCROLL_DOWN), step(VisionOp.WAIT), step(VisionOp.COLLECT))))
        assertTrue(Cu.mayCredit(plain, false, listOf(step(VisionOp.CLICK))))
        assertTrue(Cu.mayCredit(plain, false, listOf(step(VisionOp.TYPE_TEXT))))
        assertTrue(Cu.mayCredit(plain, false, listOf(step(VisionOp.PRESS_ENTER))))
        // A replan's navigation counts for a state of the page, not for a cart.
        assertTrue(Cu.mayCredit(plain, false, listOf(step(VisionOp.REPLAN, note = "replanned: https://x/"))))
        assertFalse(Cu.mayCredit(plain, false, listOf(step(VisionOp.REPLAN, note = "advised: scroll"))))
        // A failed or refused click did nothing.
        assertFalse(Cu.mayCredit(plain, false, listOf(step(VisionOp.CLICK, failed = true), step(VisionOp.CLICK, refused = true))))
        // Cart, added, selected, checked: a click or typing whose effect changed the content, even for the first milestone.
        assertTrue(Cu.isStatefulMilestone(cart))
        assertTrue(Cu.isStatefulMilestone("The item is added"))
        assertTrue(Cu.isStatefulMilestone("Eggless is selected"))
        assertTrue(Cu.isStatefulMilestone("The box is checked"))
        assertFalse(Cu.isStatefulMilestone("Magnolia Bakery's Order Online menu is open"))
        assertFalse(Cu.mayCredit(cart, true, emptyList()))
        assertFalse(Cu.mayCredit(cart, false, listOf(step(VisionOp.CLICK, contentChanged = false))))
        assertFalse(Cu.mayCredit(cart, false, listOf(step(VisionOp.SCROLL_DOWN, contentChanged = true))))
        assertTrue(Cu.mayCredit(cart, false, listOf(step(VisionOp.CLICK, contentChanged = true))))
        // Without the engine's content hash, the pixels stand in.
        assertTrue(Cu.mayCredit(cart, false, listOf(step(VisionOp.CLICK, changed = true))))
        assertFalse(Cu.mayCredit(cart, false, listOf(step(VisionOp.CLICK, changed = false))))
    }

    @Test
    fun `the planner's entities and stop_when are read, and the stop is no milestone`() {
        val p = Cu.parsePlan("""{"open":[],"jev_goal":"g","milestones":["Menu open","Tres Leches in the cart"],"entities":["Magnolia Bakery"," Tres Leches ","","${"x".repeat(80)}"],"stop_when":"login or payment step"}""", "t")
        assertEquals(listOf("Magnolia Bakery", "Tres Leches"), p.entities)
        assertEquals("login or payment step", p.stopWhen)
        assertEquals(listOf("Menu open", "Tres Leches in the cart"), p.milestones)
        assertEquals(emptyList(), Cu.parsePlan("""{"open":[],"jev_goal":"g"}""", "t").entities)
        assertNull(Cu.parsePlan("""{"open":[],"jev_goal":"g"}""", "t").stopWhen)
        // A single "stop" milestone is not turned into nothing.
        assertEquals(listOf("Stop at the login step"), Cu.parsePlan("""{"open":[],"jev_goal":"g","milestones":["Stop at the login step"]}""", "t").milestones)
        assertTrue(Cu.PLAN_SYSTEM.contains("entities") && Cu.REPLAN_SYSTEM.contains("stop_when"))
        // The run log keeps both.
        val run = UseRun("t").also { it.plan.value = p }
        val rec = RunLogStore(tmp()).record(run)["plan"]!!.jsonObject
        assertEquals(listOf("Magnolia Bakery", "Tres Leches"), rec["entities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("login or payment step", rec["stop_when"]!!.jsonPrimitive.content)
    }

    @Test
    fun `targets are the plan's entities, those the milestone names first, then the milestone's own names, never n-grams of its prose`() {
        val ents = listOf("Magnolia Bakery", "Tres Leches")
        assertEquals(listOf("Tres Leches", "Magnolia Bakery"), Cu.targetPhrasesFor("Tres Leches is in the cart", "Order", "https://z/", ents))
        assertEquals(listOf("Magnolia Bakery", "Tres Leches", "Zomato"), Cu.targetPhrasesFor("Magnolia Bakery's Zomato page is open", "Order", "https://z/", ents))
        assertEquals(ents, Cu.targetPhrasesFor(null, "Order", "https://z/", ents))
        // The milestone's names: quoted phrases and runs of capitalised words, not the words around them.
        assertEquals(listOf("Magnolia Bakery", "Zomato"), Cu.milestonePhrases("Magnolia Bakery's Zomato page is open"))
        assertEquals(listOf("Tres Leches"), Cu.milestonePhrases("Tres Leches is in the cart"))
        assertEquals(listOf("Add to cart"), Cu.milestonePhrases("the \"Add to cart\" button is shown"))
        assertEquals(emptyList(), Cu.milestonePhrases("the cart shows one item and a total"))
        assertEquals(emptyList(), Cu.milestonePhrases("The page is open"))
        // At most MAX_TARGETS, without a phrase inside another.
        assertTrue(Cu.targetPhrasesFor("Tres Leches", "g", "", listOf("A One", "B Two", "C Three", "D Four", "E Five")).size == Cu.MAX_TARGETS)
        assertEquals(listOf("Tres Leches"), Cu.targetPhrasesFor("Tres Leches is in the cart", "g", "", listOf("Tres Leches", "tres leches")))
    }

    @Test
    fun `the look locates the entities, and a scroll goes toward the one the milestone names, though others were found too`() = runTest {
        val plan = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"Add Tres Leches","milestones":["Tres Leches is visible on the menu"],"entities":["Magnolia Bakery","Tres Leches"],"type_values":[]}"""
        val b = looking(listOf("SCROLL_DOWN", "DONE")) {
            reportScroll = true
            locateReply = { phrase -> if (phrase == "Tres Leches") locatedReply(true, false, "down", 4.0) else locatedReply(true, true) }
        }
        val report = flow(b, FakeTabs(), mutableListOf(), plan = plan).runComputerUse(UseRequest("t", maxSteps = 5))
        // The milestone names Tres Leches, so it is asked about first.
        assertEquals(listOf("Tres Leches", "Magnolia Bakery"), b.lookArgs[0]["locate"]!!.jsonArray.map { it.jsonPrimitive.content }.take(2))
        // Both were found, one in view: the off-screen one is walked to, min(4.0 - 0.5, 3) = 3 screens of 780 px (not "small").
        assertEquals("2340", b.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("amount 3.0 screens toward 'Tres Leches' (it was 4.0 screens away)", report.steps.first { it.op == VisionOp.SCROLL_DOWN }.note)
        // Jev is told where each is.
        val targets = b.jevArgs[0]["state"]!!.jsonObject["targets"]!!.jsonArray.associate { it.jsonObject["text"]!!.jsonPrimitive.content to it.jsonObject["where"]!!.jsonPrimitive.content }
        assertEquals("in view", targets["Magnolia Bakery"]); assertEquals("4.0 screens below", targets["Tres Leches"])
        // Off-screen entities that the milestone does not name are not scrolled to.
        val other = """{"open":[{"url":"https://sample.com/"}],"jev_goal":"g","milestones":["The menu is open"],"entities":["Magnolia Bakery"],"type_values":[]}"""
        val o = looking(listOf("SCROLL_DOWN", "DONE")) { reportScroll = true; locateReply = { locatedReply(true, false, "up", 5.0) } }
        flow(o, FakeTabs(), mutableListOf(), plan = other).runComputerUse(UseRequest("t", maxSteps = 5))
        assertEquals("390", o.stepArgs("scroll_by").single()["action"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `waiting for a popup is at most one short recheck, not 1_5 seconds, and a late tab is caught before the next look`() = runTest {
        // The default timings: one check right after the action and one 150 ms later.
        val quiet = FakeBrowser(tmp(), listOf("CLICK", "DONE"))
        val q = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        ComputerUseFlow(quiet, { gateway(mutableListOf(), plan, typedJson) }, q, openWaitsMs = listOf(0), settleMs = 0).runComputerUse(UseRequest("t", maxSteps = 5))
        assertEquals(150L, testScheduler.currentTime) // the whole run: no 1.5 s, no 250 ms settle per step
        // A tab that shows up in that window is followed.
        val b = FakeBrowser(tmp(), listOf("CLICK", "DONE", "DONE"))
        val tabs = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        tabs.onSnapshot = { n -> if (n >= 5) tabs.now = mapOf("t1" to "Main", "t2" to "Sign in") }
        val start = testScheduler.currentTime
        val r = ComputerUseFlow(b, { gateway(mutableListOf(), plan, typedJson) }, tabs, openWaitsMs = listOf(0), settleMs = 0).runComputerUse(UseRequest("t", maxSteps = 5))
        assertEquals(150L, testScheduler.currentTime - start)
        assertTrue(r.steps.first().popup!!.startsWith("Switched to popup 'Sign in'"), r.steps.first().popup)
        assertEquals("t2", r.tabs.last().tabId)
        // One that comes later still is caught by the check before the next look, with no wait of its own.
        val late = FakeBrowser(tmp(), listOf("CLICK", "DONE", "DONE"))
        val lt = FakeTabs().also { it.now = mapOf("t1" to "Main") }
        lt.onSnapshot = { n -> if (n >= 6) lt.now = mapOf("t1" to "Main", "t2" to "Sign in") }
        val s2 = testScheduler.currentTime
        val lr = ComputerUseFlow(late, { gateway(mutableListOf(), plan, typedJson) }, lt, openWaitsMs = listOf(0), settleMs = 0).runComputerUse(UseRequest("t", maxSteps = 5))
        assertEquals(150L, testScheduler.currentTime - s2)
        assertTrue(lr.steps.first().popup!!.startsWith("Switched to popup 'Sign in'"), lr.steps.first().popup)
        // Nothing that is not an action waits at all: a scroll does not look for a tab.
        val sc = FakeBrowser(tmp(), listOf("SCROLL_DOWN", "DONE"))
        val s3 = testScheduler.currentTime
        ComputerUseFlow(sc, { gateway(mutableListOf(), plan, typedJson) }, FakeTabs().also { it.now = mapOf("t1" to "Main") }, openWaitsMs = listOf(0), settleMs = 0).runComputerUse(UseRequest("t", maxSteps = 5))
        assertEquals(0L, testScheduler.currentTime - s3)
    }
}
