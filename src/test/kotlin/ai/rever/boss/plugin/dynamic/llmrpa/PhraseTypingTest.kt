package ai.rever.boss.plugin.dynamic.llmrpa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typing words from an unquoted instruction, e.g. a search term, and never offering a field nothing can fill. */
class PhraseTypingTest {
    private val task = "Open wikipedia home page, then follow links till you reach breast cancer"

    private val search = PageElement("e1", "combobox", "input", "Search Wikipedia", type = "search", selector = SelectorInfo("css", "#searchInput"))
    private val wiki = PageSnapshot(
        url = "https://en.wikipedia.org/wiki/Main_Page",
        title = "Wikipedia, the free encyclopedia",
        truncated = false,
        elements = listOf(search, element("e2", "link", "Contents"), element("e3", "link", "Current events")),
    )
    private val article = wiki.copy(url = "https://en.wikipedia.org/wiki/Breast_cancer", title = "Breast cancer - Wikipedia")
    private val password = element("e7", "textbox", "Password", selector = "#pw").copy(sensitive = true, type = "password")

    private fun isStop(w: String) = w.lowercase() in setOf("open", "wikipedia", "home", "page", "then", "follow", "links", "till", "you", "reach")

    @Test
    fun `phrases are the meaningful words of the instruction, longest first`() {
        val p = Candidates.phrases(task)
        assertEquals("breast cancer", p.first())
        assertEquals(listOf("breast cancer", "breast", "cancer"), p)
        assertTrue(p.none { s -> s.split(" ").let { isStop(it.first()) || isStop(it.last()) } }, p.toString())
    }

    @Test
    fun `phrases leave out the site, quoted text, emails and addresses, and are capped`() {
        // The site is named by the page's host, not "home page", here.
        val p = Candidates.phrases("search wikipedia for breast cancer treatment", listOf("https://en.wikipedia.org/wiki/Main_Page"))
        assertFalse(p.any { it.equals("wikipedia", ignoreCase = true) }, p.toString())
        assertEquals("breast cancer treatment", p.first())
        val q = Candidates.phrases("Log in as ada@example.com with 'hunter two' at https://shop.example/login and buy socks")
        assertTrue(q.none { "hunter" in it || "ada" in it || "example" in it }, q.toString())
        assertTrue("socks" in q, q.toString())
        assertTrue(Candidates.phrases("red green blue cyan magenta yellow black white orange purple").size <= 8)
        assertEquals(emptyList(), Candidates.phrases("Open the home page"))
    }

    @Test
    fun `type is not offered when nothing can be typed`() {
        val page = wiki.copy(elements = listOf(search, password))
        val types = { text: String, writes: Boolean -> Candidates.build(page, text, writes).filter { it.needsValue }.map { it.element!!.id } }
        // Nothing quoted, no words, and a decider that cannot write: no field is offered.
        assertEquals(emptyList(), types("Open the home page", false))
        // A chat model can write its own text.
        assertEquals(listOf("e1", "e7"), types("Open the home page", true))
        // Words fill a search box, never a private field.
        assertEquals(listOf("e1"), types(task, false))
        // A quoted value can go anywhere.
        assertEquals(listOf("e1", "e7"), types("Sign in with 'secret'", false))
    }

    @Test
    fun `jev types a search term from the instruction and presses Enter`() = runTest {
        lateinit var tools: FakeTools
        tools = FakeTools(
            page = wiki,
            stepOk = { a -> if ((a["type"] as JsonPrimitive).content == "keypress") tools.page = article; true },
            chooseText = { opts -> opts.first() to 0.92 },
        ) { _, call ->
            when (call) {
                0 -> Triple("Type into 'Search Wikipedia'", 0.9, null)
                1 -> Triple("Press Enter", 0.9, null)
                else -> Triple("The task is complete", 0.95, null)
            }
        }
        val asked = mutableListOf<PendingQuestion>()
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task, RunLimits(navSettleMs = 0, stepSettleMs = 0)) { asked += it; Answer.Stop }.run()
        assertEquals(RunStatus.DONE, state.status, state.summary)
        assertTrue(asked.isEmpty(), asked.toString())
        assertEquals(listOf("breast cancer", "breast", "cancer"), tools.textOptions.single())
        val (typed, enter) = tools.steps
        assertEquals("input", (typed["type"] as JsonPrimitive).content)
        assertEquals("breast cancer", (typed["value"] as JsonPrimitive).content)
        assertEquals("#searchInput", ((typed["selector"] as JsonObject)["value"] as JsonPrimitive).content)
        assertEquals("Enter", (enter["value"] as JsonPrimitive).content)
        assertEquals(StepRecord.ValueSource.PHRASE, state.steps.first().valueSource)
        assertEquals("Type 'breast cancer' into 'Search Wikipedia'", state.steps.first().description)
        // Three decisions, the text pick, Enter's risk check and the done check.
        assertEquals(6, state.calls)
        assertEquals(1, tools.riskCalls)
        assertEquals("phrase", ((LlmrpaMcpToolProvider.transcript(state)["steps"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)["text_source"]!!.let { (it as JsonPrimitive).content })
    }

    private fun typeThenDone(text: (List<String>) -> Pair<String?, Double>) = FakeTools(page = wiki, chooseText = text) { _, call ->
        if (call == 0) Triple("Type into 'Search Wikipedia'", 0.9, null) else Triple("The task is complete", 0.95, null)
    }

    @Test
    fun `an unsure text pick asks the person in the panel`() = runTest {
        val tools = typeThenDone { it.first() to 0.3 }
        var q: PendingQuestion? = null
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task, RunLimits(navSettleMs = 0, stepSettleMs = 0)) {
            q = it; if (it is PendingQuestion.ChooseText) Answer.Text("cancer") else Answer.Stop
        }.run()
        val ask = assertIs<PendingQuestion.ChooseText>(q)
        assertTrue(ask.reason.contains("30%"), ask.reason)
        assertEquals("Search Wikipedia", ask.field)
        assertEquals(listOf("breast cancer", "breast", "cancer"), ask.options)
        assertEquals(RunStatus.DONE, state.status, state.summary)
        assertEquals("cancer", (tools.steps.single()["value"] as JsonPrimitive).content)
        assertEquals(StepRecord.ValueSource.USER, state.steps.single().valueSource)
    }

    @Test
    fun `an unsure text pick stops a headless run, naming the options`() = runTest {
        val tools = typeThenDone { it.first() to 0.3 }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(state.summary!!.contains("'breast cancer', 'breast', 'cancer'"), state.summary)
        assertTrue(tools.steps.isEmpty())
        // The text call counts, with its cost.
        assertEquals(2, state.calls)
        assertTrue(state.costUsd >= 0.00005, state.costUsd.toString())
        val stopped = LlmrpaMcpToolProvider.transcript(state)["stopped_at_question"] as JsonObject
        assertEquals("Search Wikipedia", (stopped["field"] as JsonPrimitive).content)
    }

    @Test
    fun `jev answering none of these is not typed`() = runTest {
        val tools = typeThenDone { null to 0.95 }
        var q: PendingQuestion? = null
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task) { q = it; Answer.Stop }.run()
        assertTrue(assertIs<PendingQuestion.ChooseText>(q).reason.contains("none of these"), (q as PendingQuestion.ChooseText).reason)
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(tools.steps.isEmpty())
    }

    @Test
    fun `an answer that is not one of the options is refused`() = runTest {
        val tools = typeThenDone { null to 0.95 }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task) { Answer.Text("rm -rf") }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(tools.steps.isEmpty())
    }

    @Test
    fun `words from the instruction never go into a private field`() = runTest {
        val page = wiki.copy(elements = listOf(password))
        // A chat model typing a phrase into a password box is refused like any text it wrote.
        val chat = ModelOption(ModelOption.Kind.CHAT, "OPENROUTER", "OpenRouter", "openrouter/free")
        val key = Candidates.build(page, task, writes = true).first { it.needsValue }.key
        val api = object : ai.rever.boss.plugin.api.AiGatewayAPI {
            override suspend fun complete(request: ai.rever.boss.plugin.api.AiRequest) =
                Result.success(ai.rever.boss.plugin.api.AiReply("""{"action":"$key","value":"breast cancer","confidence":0.95,"irreversible":false}"""))
            override fun stream(request: ai.rever.boss.plugin.api.AiRequest) = kotlinx.coroutines.flow.emptyFlow<ai.rever.boss.plugin.api.AiChunk>()
            override suspend fun runAgent(
                request: ai.rever.boss.plugin.api.AiRequest, tools: List<ai.rever.boss.plugin.api.AiToolSpec>, budget: ai.rever.boss.plugin.api.AiBudget,
                invoke: suspend (ai.rever.boss.plugin.api.AiToolCall) -> ai.rever.boss.plugin.api.AiToolOutcome,
            ) = Result.failure<ai.rever.boss.plugin.api.AiAgentResult>(UnsupportedOperationException())
            override fun capabilities() = setOf(ai.rever.boss.plugin.api.AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
            override fun activeModel(): ai.rever.boss.plugin.api.AiModelInfo? = null
        }
        val tools = FakeTools(page = page) { _, _ -> error("jev is not used") }
        val state = TaskRunner(tools, ChatDecider({ api }, chat), "t1", task) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(state.summary!!.contains("private field"), state.summary)
        assertTrue(tools.steps.isEmpty())

        // Jev with two quoted values and a private field: no text question with words in it, just the old stop.
        val two = FakeTools(page = page) { _, _ -> Triple("Type into 'Password' (private field)", 0.95, null) }
        val s2 = TaskRunner(two, JevDecider(two, JEV), "t1", "Sign in with 'ada' and 'secret', then reach breast cancer") { Answer.Stop }.run()
        assertEquals(0, two.textCalls)
        assertTrue(s2.summary!!.contains("Not sure which text"), s2.summary)
    }

    @Test
    fun `enter after instruction words outside a search box asks first`() = runTest {
        val comment = element("e5", "textbox", "Add a comment")
        val tools = FakeTools(page = wiki.copy(elements = listOf(comment)), chooseText = { it.first() to 0.9 }) { _, call ->
            if (call == 0) Triple("Type into 'Add a comment'", 0.9, null) else Triple("Press Enter", 0.9, null)
        }
        var q: PendingQuestion? = null
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", task, RunLimits(navSettleMs = 0, stepSettleMs = 0)) { q = it; Answer.Stop }.run()
        val confirm = assertIs<PendingQuestion.Confirm>(q)
        assertEquals(1.0, confirm.risk)
        assertEquals(RunStatus.STOPPED, state.status)
        assertEquals(1, tools.steps.size)
    }

    @Test
    fun `the chat prompt says a search term from the instruction may be typed`() {
        assertTrue(ChatDecider.SYSTEM.contains("search term taken from the instruction"))
        val ctx = StepContext(task, wiki, Candidates.build(wiki, task, writes = true), emptyList(), emptyList(), Candidates.phrases(task))
        assertTrue(ChatDecider.prompt(ctx).contains("breast cancer | breast | cancer"))
    }
}
