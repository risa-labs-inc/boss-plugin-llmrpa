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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLoopTest {
    private val dir: File = java.nio.file.Files.createTempDirectory("agent").toFile().also { it.deleteOnExit() }

    private fun el(id: String, role: String, label: String, context: String = "", editable: Boolean = false, x: Int = 100, y: Int = 100) =
        """{"id":"$id","role":"$role","tag":"${if (editable) "input" else "button"}","label":"$label","rect":{"x":$x,"y":$y,"w":80,"h":30},"disabled":false,"covered":false,"context":"$context","editable":$editable}"""

    /** RPA Engine as the agent uses it: look, the action verbs, dialogs and popups. Actions change the page unless [frozen]. */
    private inner class Browser : ToolInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var url = "https://shop.example.com/"
        var title = "Shop"
        var content = 0
        var frozen = false
        var elements = "[" + listOf(el("m1", "textbox", "Search", editable = true), el("m2", "button", "Add", "Tres Leches"), el("m3", "button", "Place order"), el("m4", "textbox", "Password", editable = true)).joinToString(",") + "]"
        var dialog: String? = null
        /** Popups as `popups` on the next step results (then cleared). */
        var popupsJson: String? = null
        var lookFailsFor = mutableSetOf<String>()
        var jevBox = """{"top":0.1,"bottom":0.2,"left":0.1,"right":0.2}"""
        var scrollY = 0.0
        /** locate_text replies by phrase. */
        var locate: (String) -> String = { """{"ok":true,"located":{"found":false,"count":0,"in_view":false}}""" }
        private val shot = File(dir, "shot.jpg").also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(560, 390, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it)
        }

        override fun has(toolName: String) = true
        override fun inputSchema(toolName: String) = if (toolName == ToolNames.JEV_DECIDE) """{"properties":{"assets":{}}}""" else null

        fun actions(type: String) = calls.filter { it.first == ToolNames.STEP && it.second["action"]!!.jsonObject["type"]!!.jsonPrimitive.content == type }.map { it.second }
        fun value(o: JsonObject) = o["action"]!!.jsonObject["value"]?.jsonPrimitive?.content

        override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
            val a = arguments.jsonObject
            calls += toolName to a
            val tab = a["tab_id"]?.jsonPrimitive?.content ?: "t1"
            val r = when (toolName) {
                ToolNames.OBSERVE -> ToolReply("""{"url":"$url","title":"$title"}""", false)
                ToolNames.JEV_DECIDE -> ToolReply("""{"response":{"answers":{"target":{"type":"box","box":$jevBox}}}}""", false)
                else -> {
                    val type = a["action"]!!.jsonObject["type"]!!.jsonPrimitive.content
                    val dlg = dialog
                    when {
                        type == "cu_end" -> ToolReply("""{"ok":true}""", false)
                        type == "dialog_answer" -> { dialog = null; content++; ToolReply("""{"ok":true}""", false) }
                        dlg != null -> ToolReply("""{"ok":false,"error":"A dialog is waiting","dialog":$dlg}""", false)
                        type == "look" && tab in lookFailsFor -> ToolReply("""{"error":{"code":"TAB_NOT_FOUND","message":"closed"}}""", true)
                        type == "look" -> ToolReply("""{"ok":true,"look":{"screenshot":{"path":"${shot.absolutePath}","width":560,"height":390,"css_width":1120,"css_height":780,"dpr":2,"url":"$url","title":"$title","content_hash":"h$content","scroll":{"x":0,"y":$scrollY,"max_y":3000}},"elements":$elements,"page_text":"Page text $content"}}""", false)
                        type == "locate_text" -> ToolReply(locate(value(a)!!), false)
                        type == "scroll_by" -> { scrollY = (scrollY + value(a)!!.toDouble()).coerceIn(0.0, 3000.0); if (!frozen) content++; ToolReply("""{"ok":true}""", false) }
                        type == "navigate" -> { url = value(a)!!; content++; ToolReply("""{"ok":true}""", false) }
                        else -> { if (!frozen) content++; ToolReply("""{"ok":true}""", false) }
                    }
                }
            }
            val pj = popupsJson
            if (toolName == ToolNames.STEP && pj != null && !r.isError) { popupsJson = null; return r.copy(text = "{\"popups\":$pj," + r.text.substring(1)) }
            return r
        }
    }

    private class Tabs : UseTabs {
        val opened = mutableListOf<String>()
        val released = mutableListOf<String>()
        override suspend fun open(url: String): String? { opened += url; return "t1" }
        override fun claim(tabId: String): String? = null
        override fun release(tabId: String) { released += tabId }
    }

    private val requests = mutableListOf<AiRequest>()
    private val replies = ArrayDeque<String>()
    private var caps = setOf(AiGatewayAPI.CAPABILITY_VISION, AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)

    private val gateway = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> {
            requests += request
            return Result.success(AiReply(replies.removeFirstOrNull() ?: """{"thought":"done","action":{"type":"done","result":"nothing left"}}"""))
        }
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = caps
        override fun activeModel(): AiModelInfo? = AiModelInfo("OPENAI", "OpenAI", "gpt-5")
        override fun availableModels() = listOf(ai.rever.boss.plugin.api.AiProviderModels("ANTHROPIC", "Anthropic", listOf("claude-3-5-haiku", "claude-haiku-4-5", "claude-sonnet-4-5").map { ai.rever.boss.plugin.api.AiAvailableModel(it, it, null) }))
    }

    private fun say(vararg actions: String) = actions.forEach { replies += """{"thought":"next","action":$it}""" }

    private fun loop(browser: Browser, tabs: Tabs = Tabs()) = AgentLoop(browser, { gateway }, tabs, openWaitsMs = listOf(0), settleMs = 0, popupRecheckMs = 0)

    // ---- parsing ----

    @Test
    fun `the decision is read from the first object with an action, junk around it ignored`() {
        val d = AgentPrompt.parse("Sure! {\"note\":1}\n```json\n{\"thought\":\"click it\",\"action\":{\"type\":\"click\",\"element\":\"12\"}}\n```\nHmm, wait.").getOrThrow()
        assertEquals(AgentAction.Click("m12"), d.action)
        assertEquals("click it", d.thought)
        assertEquals(AgentAction.Scroll(false, "half"), AgentPrompt.parse("""{"action":{"type":"scroll","direction":"up","amount":"half"}}""").getOrThrow().action)
        assertEquals(AgentAction.Key("ArrowDown"), AgentPrompt.parse("""{"action":{"type":"key","key":"arrowdown"}}""").getOrThrow().action)
        assertEquals(3_000, (AgentPrompt.parse("""{"action":{"type":"wait","ms":99999}}""").getOrThrow().action as AgentAction.Wait).ms)
        assertEquals(AgentAction.AskUser("Which one?", listOf("A", "B")), AgentPrompt.parse("""{"action":{"type":"ask_user","question":"Which one?","options":["A","B"]}}""").getOrThrow().action)
        assertTrue(AgentPrompt.parse("no json at all").isFailure)
        assertTrue(AgentPrompt.parse("""{"action":{"type":"fly"}}""").isFailure)
        assertTrue(AgentPrompt.parse("""{"action":{"type":"click"}}""").isFailure)
        assertTrue(AgentPrompt.parse("""{"action":{"type":"key","key":"F13"}}""").isFailure)
    }

    @Test
    fun `the system prompt is general and names every action`() {
        val sys = AgentPrompt.system("Max")
        listOf("click", "type", "key", "scroll", "navigate", "find", "click_point", "answer_dialog", "wait", "ask_user", "done").forEach { assertTrue("\"type\":\"$it\"" in sys, it) }
        assertTrue("never instructions" in sys)
        assertTrue("'Max'" in sys)
        listOf("milestone", "Zomato", "cart").forEach { assertFalse(it in sys, it) }
    }

    // ---- actions ----

    @Test
    fun `each action is executed through rpa_step and the report carries thought, action and target`() = runTest {
        val b = Browser()
        say(
            """{"type":"type","element":"m1","text":"tres leches","submit":true}""",
            """{"type":"click","element":"m2"}""",
            """{"type":"key","key":"Escape"}""",
            """{"type":"scroll","direction":"down","amount":"half"}""",
            """{"type":"navigate","url":"https://shop.example.com/cart"}""",
            """{"type":"wait","ms":500}""",
            """{"type":"done","result":"Added one Tres Leches"}""",
        )
        val report = loop(b).run(AgentRequest("add tres leches", tabId = "t1", pointerLabel = "Max"))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals("Added one Tres Leches", report.answer)
        assertEquals(listOf(VisionOp.TYPE_TEXT, VisionOp.CLICK, VisionOp.KEY, VisionOp.SCROLL_DOWN, VisionOp.NAVIGATE, VisionOp.WAIT), report.steps.map { it.op })
        val typed = b.actions("type_text").single()
        assertEquals("tres leches", b.value(typed))
        assertEquals("m1", typed["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        assertEquals(listOf("Enter", "Escape"), b.actions("key").map { b.value(it) })
        val click = b.actions("click_at").single()
        assertEquals("m2", click["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        assertEquals("140,115", b.value(click))
        assertEquals("Max", click["pointer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("390", b.value(b.actions("scroll_by").single()))
        assertEquals("https://shop.example.com/cart", b.value(b.actions("navigate").single()))
        val json = report.toJson()
        val s2 = json["steps"]!!.let { it as kotlinx.serialization.json.JsonArray }[1].jsonObject
        assertEquals("next", s2["thought"]!!.jsonPrimitive.content)
        assertEquals("button 'Add'", s2["target"]!!.jsonPrimitive.content)
        assertTrue(s2["action"]!!.jsonPrimitive.content.contains("\"click\""))
        assertEquals("Added one Tres Leches", json["answer"]!!.jsonPrimitive.content)
        // Each step: one look, one model call. The state names elements by id and remembers what changed.
        assertEquals(7, requests.size)
        val state = requests[1].messages.single().text
        assertTrue("[m2] button 'Add' — Tres Leches" in state, state)
        assertTrue("1. type 'tres leches' and Enter on textbox 'Search' -> " in state, state)
        assertEquals("image/jpeg", requests[0].messages.single().images.single().mediaType)
        assertTrue(requests.all { it.extras[AiRequest.EXTRAS_KEY_MODEL_OVERRIDE] == "claude-haiku-4-5" }) // the newest Haiku by default
    }

    @Test
    fun `an unknown element is told back to the model and nothing is clicked`() = runTest {
        val b = Browser()
        say("""{"type":"click","element":"m99"}""", """{"type":"done","result":"ok"}""")
        val report = loop(b).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status)
        assertTrue(b.actions("click_at").isEmpty())
        assertTrue("there is no element m99" in requests[1].messages.single().text)
    }

    // ---- guardrails ----

    @Test
    fun `a payment click asks first, and runs after the person confirms`() = runTest {
        val b = Browser()
        val run = UseRun("order it")
        say("""{"type":"click","element":"m3"}""")
        val first = loop(b).run(AgentRequest("order it", tabId = "t1"), run)
        assertEquals(UseStatus.NEEDS_INPUT, first.status)
        assertEquals("About to 'Place order'. Confirm?", first.blocker!!.question)
        assertEquals(listOf("Confirm", "Cancel"), first.blocker!!.options.map { it.label })
        assertTrue(b.actions("click_at").isEmpty())

        say("""{"type":"click","element":"m3"}""", """{"type":"done","result":"ordered"}""")
        val second = loop(b).run(AgentRequest("order it", tabId = "t1", resume = true, reply = "Confirm"), run)
        assertEquals(UseStatus.DONE, second.status)
        assertEquals(1, b.actions("click_at").size)
        assertTrue("the person replied: 'Confirm'" in requests.last { it.messages.single().text.contains("Recent steps") }.messages.single().text)
        assertTrue(AgentPrompt.isPayment("Pay ₹470"))
        assertTrue(AgentPrompt.isPayment("Buy now"))
        assertFalse(AgentPrompt.isPayment("Add to cart"))
        assertFalse(AgentPrompt.isPayment("Payment methods help", ""))
    }

    @Test
    fun `secret inputs reach the page but never the model or the record`() = runTest {
        val b = Browser()
        say("""{"type":"type","element":"m4","text":"{{password}}"}""", """{"type":"type","element":"m4","text":"guess123"}""", """{"type":"done","result":"in"}""")
        val run = UseRun("log in")
        val report = loop(b).run(AgentRequest("log in", tabId = "t1", inputs = mapOf("password" to "hunter2", "email" to "a@b.c")), run)
        assertEquals(UseStatus.DONE, report.status)
        val typed = b.actions("type_text")
        assertEquals(listOf("hunter2"), typed.map { b.value(it) }) // the model's own guess is refused
        assertEquals("true", typed.single()["allow_sensitive"]!!.jsonPrimitive.content)
        requests.forEach { r -> assertFalse("hunter2" in r.messages.single().text); assertFalse("hunter2" in r.system) }
        assertTrue("- password (private: value hidden)" in requests[0].messages.single().text)
        assertTrue("- email = a@b.c" in requests[0].messages.single().text)
        assertTrue("only text the person gave" in requests[2].messages.single().text)
        assertEquals(Secrets.MASK, report.steps.single().text)
        assertFalse("hunter2" in report.toJson().toString())
        assertFalse("hunter2" in run.log.entries.value.joinToString { it.args + it.result })
    }

    @Test
    fun `the same action three times with no change stops the run`() = runTest {
        val b = Browser().apply { frozen = true }
        repeat(5) { say("""{"type":"click","element":"m2"}""") }
        val report = loop(b).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.BLOCKED, report.status)
        assertEquals(3, b.actions("click_at").size)
        assertTrue(report.error!!.contains("three times"), report.error)
        assertTrue("-> nothing changed" in requests.last().messages.single().text)
    }

    @Test
    fun `ask_user stops for the person, and a reply resumes the same run with its history`() = runTest {
        val b = Browser()
        val run = UseRun("book a table")
        say("""{"type":"click","element":"m2"}""", """{"type":"ask_user","question":"Which time?","options":["7 pm","8 pm"]}""")
        val first = loop(b).run(AgentRequest("book a table", tabId = "t1"), run)
        assertEquals(UseStatus.NEEDS_INPUT, first.status)
        assertEquals("Which time?", first.blocker!!.question)
        assertEquals("t1", first.blocker!!.tabId)
        say("""{"type":"done","result":"booked"}""")
        val second = loop(b).run(AgentRequest("book a table", resume = true, reply = "8 pm"), run)
        assertEquals(UseStatus.DONE, second.status)
        assertEquals(1, second.steps.size) // the first segment's step is kept
        val state = requests.last().messages.single().text
        assertTrue("1. click on button 'Add'" in state, state)
        assertTrue("2. the person replied: '8 pm'" in state, state)
    }

    // ---- dialogs, popups, click_point, start ----

    @Test
    fun `a pending dialog is drawn and answered, and other actions wait for it`() = runTest {
        val b = Browser().apply { dialog = """{"type":"confirm","message":"Remove the item?","ok_text":"Remove","cancel_text":"Keep"}""" }
        say("""{"type":"click","element":"m2"}""", """{"type":"answer_dialog","answer":"cancel"}""", """{"type":"done","result":"kept"}""")
        val report = loop(b).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(listOf("cancel"), b.actions("dialog_answer").map { b.value(it) })
        assertTrue(b.actions("click_at").isEmpty())
        val state = requests[0].messages.single().text
        assertTrue("Dialog pending (confirm): 'Remove the item?' buttons: Remove / Keep" in state, state)
        assertTrue("answer it with answer_dialog first" in requests[1].messages.single().text)
        assertEquals("Dialog 'Remove the item?': Cancel", report.steps.single().dialog)
    }

    @Test
    fun `a popup window becomes the driven tab and the run goes back when it closes`() = runTest {
        val b = Browser()
        say("""{"type":"click","element":"m2"}""", """{"type":"click","element":"m2"}""", """{"type":"done","result":"ok"}""")
        b.popupsJson = null
        var clicks = 0
        val wrapped = object : ToolInvoker by b {
            override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
                val type = arguments.jsonObject["action"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                if (type == "click_at") {
                    clicks++
                    b.popupsJson = if (clicks == 1) """[{"id":"t1#popup-1","url":"https://pay.example.com/","title":"Login","open":true}]"""
                    else """[{"id":"t1#popup-1","open":false}]"""
                }
                return b.invoke(toolName, arguments)
            }
        }
        val report = AgentLoop(wrapped, { gateway }, Tabs(), openWaitsMs = listOf(0), settleMs = 0, popupRecheckMs = 0).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(listOf("t1", "t1#popup-1"), b.actions("click_at").map { it["tab_id"]!!.jsonPrimitive.content })
        assertEquals("t1", b.actions("look").last()["tab_id"]!!.jsonPrimitive.content)
        assertTrue(report.steps[0].popup!!.contains("Switched to popup window 'Login'"))
        assertTrue(report.steps[1].popup!!.contains("Popup closed"))
        assertEquals(listOf("given", "popup_window"), report.tabs.map { it.kind })
    }

    @Test
    fun `click_point asks Jev's box question on the plain screenshot and clicks the box centre with snap`() = runTest {
        val b = Browser()
        say("""{"type":"click_point","description":"the green circle on the map"}""", """{"type":"done","result":"ok"}""")
        val report = loop(b).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status)
        val jev = b.calls.single { it.first == ToolNames.JEV_DECIDE }.second
        assertEquals("box", jev["questions"]!!.jsonObject["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(jev["questions"]!!.jsonObject["target"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("the green circle on the map"))
        assertTrue(jev["assets"]!!.jsonObject.values.single().jsonObject["path"]!!.jsonPrimitive.content.endsWith("shot.jpg"))
        val click = b.actions("click_at").single()
        assertEquals("168,117", b.value(click))
        assertEquals("112,78,224,156", click["action"]!!.jsonObject["snap"]!!.jsonObject["box"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with no tab or url the model's first navigate opens a new tab`() = runTest {
        val b = Browser()
        val tabs = Tabs()
        say("""{"type":"click","element":"m1"}""", """{"type":"navigate","url":"https://www.google.com/search?q=tres+leches"}""", """{"type":"done","result":"ok"}""")
        val report = loop(b, tabs).run(AgentRequest("find tres leches"))
        assertEquals(UseStatus.DONE, report.status)
        assertEquals(listOf("https://www.google.com/search?q=tres+leches"), tabs.opened)
        assertEquals(listOf("t1"), tabs.released)
        val first = requests[0]
        assertTrue("Page: none open yet" in first.messages.single().text)
        assertTrue(first.messages.single().images.isEmpty())
        assertTrue("no page is open; navigate first" in requests[1].messages.single().text)
        assertEquals("opened", report.tabs.single().kind)
        assertNotNull(requests[2].messages.single().images.singleOrNull())
    }

    @Test
    fun `a gateway that cannot take images fails the run naming the setting`() = runTest {
        caps = emptySet()
        val report = loop(Browser()).run(AgentRequest("x", tabId = "t1"))
        assertEquals(UseStatus.ERROR, report.status)
        assertTrue(report.error!!.contains("agent_model") && report.error!!.contains("Model setting"), report.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `browser_use defaults to agent mode, keeps fast and dom, and takes agent_model`() {
        val a = BrowserUseArgs.parse("""{"task":"x","agent_model":"claude-haiku-4-5"}""").getOrThrow()
        assertEquals("agent", a.mode)
        assertEquals("claude-haiku-4-5", a.agentModel)
        assertEquals("fast", BrowserUseArgs.parse("""{"task":"x","mode":"fast"}""").getOrThrow().mode)
        assertEquals("dom", BrowserUseArgs.parse("""{"task":"x","mode":"dom"}""").getOrThrow().mode)
        assertTrue(BrowserUseArgs.parse("""{"task":"x","mode":"smart"}""").isFailure)
        assertNull(BrowserUseArgs.parse("""{"task":"x"}""").getOrThrow().agentModel)
        assertTrue("agent_model" in LlmrpaMcpToolProvider.BROWSER_USE_SCHEMA)
    }

    // ---- what the view shows and how it moved ----

    @Test
    fun `a scroll reports how far the view moved even when the content hash stays the same`() = runTest {
        val b = Browser().apply { frozen = true }
        repeat(5) { say("""{"type":"scroll","direction":"down","amount":"page"}""") }
        say("""{"type":"done","result":"read it all"}""")
        val report = loop(b).run(AgentRequest("read the list", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status, report.error)
        val state = requests.last().messages.single().text
        assertTrue("1. scroll down page -> scrolled from 0% to 26% of the page" in state, state)
        assertTrue("4. scroll down page -> scrolled from 78% to 100% of the page (at the bottom)" in state, state)
        assertTrue("5. scroll down page -> did not move (already at the bottom)" in state, state)
        assertEquals(listOf(true, true, true, true, false), report.steps.map { it.changed })
        assertTrue("Visible now: 'Search' … 'Password'" in requests[0].messages.single().text)
    }

    @Test
    fun `visible now names the first and last elements top to bottom, else the first and last text lines`() {
        fun m(id: String, label: String, top: Double) = Mark(id, "link", "a", label, null, Cu.Rect(10.0, top, 90.0, top + 20), false, false, "", false, null)
        assertEquals("'Item 28' … 'Item 37'", AgentPrompt.visibleNow(listOf(m("m2", "Item 37", 700.0), m("m1", "Item 28", 10.0), m("m3", "Item 30", 200.0)), null))
        assertEquals("'Top line' … 'Bottom line'", AgentPrompt.visibleNow(emptyList(), "Top line\n\n middle \nBottom line"))
        assertNull(AgentPrompt.visibleNow(emptyList(), null))
        assertEquals("did not move (already at the top)", AgentPrompt.movement(ScrollPos(0.0, 3000.0), ScrollPos(0.0, 3000.0)))
        assertNull(AgentPrompt.movement(null, ScrollPos(0.0, 3000.0)))
    }

    @Test
    fun `find works like Ctrl+F - it scrolls the match into view or says it is not on the page`() = runTest {
        val b = Browser().apply {
            frozen = true
            locate = { phrase -> when (phrase) {
                "Item 87" -> """{"ok":true,"located":{"found":true,"count":1,"in_view":false,"direction":"down","screens":2.4,"rect":{"left":20,"top":2000,"width":200,"height":24}}}"""
                "Item 5" -> """{"ok":true,"located":{"found":true,"count":1,"in_view":true,"rect":{"left":20,"top":300,"width":200,"height":24}}}"""
                else -> """{"ok":true,"located":{"found":false,"count":0,"in_view":false}}"""
            } }
        }
        say("""{"type":"find","text":"Item 87"}""", """{"type":"find","text":"Item 5"}""", """{"type":"find","text":"Item 999"}""", """{"type":"done","result":"ok"}""")
        val report = loop(b).run(AgentRequest("open item 87", tabId = "t1"))
        assertEquals(UseStatus.DONE, report.status, report.error)
        assertEquals(listOf("Item 87", "Item 5", "Item 999"), b.actions("locate_text").map { b.value(it) })
        assertEquals(listOf("1740"), b.actions("scroll_by").map { b.value(it) }) // 2000 - 780 / 3: a third of the way down
        val state = requests.last().messages.single().text
        assertTrue("1. find 'Item 87' -> found 'Item 87' and scrolled it into view" in state, state)
        assertTrue("2. find 'Item 5' -> found 'Item 5', already in view" in state, state)
        assertTrue("3. find 'Item 999' -> not found on this page" in state, state)
        assertEquals(VisionOp.FIND, report.steps.first().op)
        assertTrue("\"type\":\"find\"" in AgentPrompt.system("Jev") && "Ctrl+F" in AgentPrompt.system("Jev"))
        assertEquals(AgentAction.Find("Item 87"), AgentPrompt.parse("""{"action":{"type":"find","text":"Item 87"}}""").getOrThrow().action)
    }

    // ---- frames ----

    @Test
    fun `an element in a frame names the frame, and click and type by element pass through unchanged`() = runTest {
        val b = Browser().apply {
            elements = """[{"id":"m14","role":"textbox","tag":"input","label":"Phone","rect":{"x":400,"y":300,"w":200,"h":40},"disabled":false,"covered":false,"context":"","editable":true,"frame":["www.zomato.com","https://accounts.zomato.com/login?x=1"]},""" +
                """{"id":"m15","role":"button","tag":"button","label":"Send OTP","rect":{"x":400,"y":360,"w":200,"h":40},"disabled":false,"covered":false,"context":"Login","editable":false,"frame":"accounts.zomato.com"}]"""
        }
        say("""{"type":"type","element":"m14","text":"{{phone}}"}""", """{"type":"click","element":"m15"}""", """{"type":"done","result":"sent"}""")
        val report = loop(b).run(AgentRequest("log in", tabId = "t1", inputs = mapOf("phone" to "9999999999")))
        assertEquals(UseStatus.DONE, report.status, report.error)
        val state = requests[0].messages.single().text
        assertTrue("[m14] textbox 'Phone' — (in frame accounts.zomato.com)" in state, state)
        assertTrue("[m15] button 'Send OTP' — Login (in frame accounts.zomato.com)" in state, state)
        val typed = b.actions("type_text").single()
        assertEquals("m14", typed["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        assertEquals("9999999999", b.value(typed))
        val click = b.actions("click_at").single()
        assertEquals("m15", click["action"]!!.jsonObject["element"]!!.jsonPrimitive.content)
        assertEquals("500,380", b.value(click)) // the top-viewport centre as the fallback point
    }

    @Test
    fun `frame names are read from a host, a url, a path or an object`() {
        fun f(json: String) = Marks.frameName(kotlinx.serialization.json.Json.parseToJsonElement(json))
        assertEquals("accounts.zomato.com", f("\"accounts.zomato.com\""))
        assertEquals("pay.example.com", f("\"https://pay.example.com/checkout?id=1\""))
        assertEquals("inner.example.com", f("""["outer.example.com","https://inner.example.com/x"]"""))
        assertEquals("auth.example.com", f("""{"host":"auth.example.com","index":2}"""))
        assertNull(f("null"))
        assertNull(f("\"  \""))
        assertNull(Marks.parseMarks(kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"m1","role":"link","label":"x","rect":{"x":1,"y":1,"w":2,"h":2}}]""") as kotlinx.serialization.json.JsonArray).single().frame)
    }

    @Test
    fun `the prompt says a split code is typed into the first box in one go`() {
        assertTrue("type the whole code into the first box in one go; the site spreads it across the boxes" in AgentPrompt.system("Jev"))
    }
}
