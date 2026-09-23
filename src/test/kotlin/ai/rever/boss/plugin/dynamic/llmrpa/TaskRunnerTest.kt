package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.AiReply
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import ai.rever.boss.plugin.api.AiBudget
import ai.rever.boss.plugin.api.AiAgentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class TaskRunnerTest {
    private val instruction = "Search for 'wireless keyboard' and open the first result"

    init {
        TaskRunner.NAV_SETTLE_MS = 0
        TaskRunner.STEP_SETTLE_MS = 0
    }

    @Test
    fun `values come from quotes, emails and urls but not apostrophes`() {
        assertEquals(listOf("wireless keyboard"), Candidates.values("Don't stop: search for 'wireless keyboard' now"))
        assertEquals(
            listOf("Ada Lovelace", "ada@example.com", "https://example.com/form"),
            Candidates.values("Put \"Ada Lovelace\" and ada@example.com into https://example.com/form."),
        )
        assertEquals(emptyList(), Candidates.values("Open the orders page"))
    }

    @Test
    fun `candidates read as plain sentences and end with done and stuck`() {
        val page = SEARCH_PAGE.copy(elements = SEARCH_PAGE.elements + element("e4", "combobox", "Sort by", tag = "select", options = listOf("Featured", "Price: Low to High")) + element("e5", "button", null))
        val c = Candidates.build(page, instruction)
        val descriptions = c.map { it.description }
        assertTrue("Type into 'Search shop'" in descriptions)
        assertTrue("Open 'Sign in' link" in descriptions)
        assertTrue("Click 'Place your order' button" in descriptions)
        assertTrue("Select 'Price: Low to High' in 'Sort by'" in descriptions)
        assertTrue("Press Enter" in descriptions)
        assertEquals(listOf(Candidates.DONE, Candidates.STUCK), c.takeLast(2).map { it.key })
        // An element with no label cannot be described, so it is not offered.
        assertTrue(c.none { it.element?.id == "e5" })
        assertEquals(c.size, c.map { it.key }.toSet().size)
    }

    @Test
    fun `jev asks for a value only when something can be typed`() {
        val ctx = StepContext(instruction, SEARCH_PAGE, Candidates.build(SEARCH_PAGE, instruction), Candidates.values(instruction), emptyList())
        val args = JevDecider.decideArgs(ctx, "typesafe/jev-1.13")
        val q = args["questions"]!! as kotlinx.serialization.json.JsonObject
        assertEquals(ctx.candidates.size, (q["next"] as kotlinx.serialization.json.JsonObject)["criteria"].let { (it as kotlinx.serialization.json.JsonObject).size })
        assertTrue("value" in q)
        val noType = ctx.copy(candidates = ctx.candidates.filter { !it.needsValue })
        assertTrue("value" !in (JevDecider.decideArgs(noType, "m")["questions"] as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun `a jev run types the instruction's value, presses enter and finishes`() = runTest {
        val tools = FakeTools { criteria, call ->
            when (call) {
                0 -> Triple("Type into 'Search shop'", 0.94, 1)
                1 -> Triple("Press Enter", 0.88, null)
                else -> Triple("The task is complete", 0.91, null)
            }
        }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { error("should not ask") }.run()
        assertEquals(RunStatus.DONE, state.status)
        assertEquals(listOf("Type 'wireless keyboard' into 'Search shop'", "Press Enter"), state.steps.map { it.description })
        assertTrue(state.steps.all { it.outcome == StepRecord.Outcome.OK })
        assertEquals("wireless keyboard", (tools.steps[0]["value"] as JsonPrimitive).content)
        assertEquals("[name='q']", ((tools.steps[0]["selector"] as kotlinx.serialization.json.JsonObject)["value"] as JsonPrimitive).content)
        // Pressing Enter could submit, so it was risk-checked; typing was not. Done was verified once.
        assertEquals(1, tools.riskCalls)
        assertEquals(1, tools.verifyCalls)
        assertEquals(5, state.calls)
    }

    @Test
    fun `an unsure pick pauses for the person, whose choice is recorded`() = runTest {
        val tools = FakeTools { _, call -> if (call == 0) Triple("Open 'Sign in' link", 0.41, null) else Triple("The task is complete", 0.95, null) }
        var asked: PendingQuestion? = null
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { q ->
            asked = q
            Answer.Pick((q as PendingQuestion.Choose).options.first { it.first.description == "Open 'Sign in' link" }.first)
        }.run()
        val choose = assertIs<PendingQuestion.Choose>(asked)
        assertTrue(choose.reason.contains("41%"))
        assertEquals(StepRecord.ChosenBy.USER, state.steps.single().chosenBy)
        assertEquals(RunStatus.DONE, state.status)
    }

    @Test
    fun `an irreversible action waits for a yes and stopping never clicks it`() = runTest {
        val tools = FakeTools(risk = 0.87) { _, _ -> Triple("Click 'Place your order' button", 0.9, null) }
        var asked: PendingQuestion? = null
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { q -> asked = q; Answer.Stop }.run()
        assertIs<PendingQuestion.Confirm>(asked)
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(tools.steps.isEmpty())
    }

    @Test
    fun `two failures in a row stop the run`() = runTest {
        val tools = FakeTools(stepOk = { false }) { _, _ -> Triple("Open 'Sign in' link", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { Answer.Stop }.run()
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(2, tools.steps.size)
        assertTrue(state.summary!!.contains("element not found"))
    }

    @Test
    fun `the step limit ends a run that never finishes`() = runTest {
        val tools = FakeTools { _, _ -> Triple("Open 'Sign in' link", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction, RunLimits(maxSteps = 3)) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertEquals(3, state.steps.size)
        assertTrue(state.summary!!.contains("3-step limit"))
    }

    @Test
    fun `a decision model never types text the instruction does not contain`() = runTest {
        val tools = FakeTools { _, _ -> Triple("Type into 'Search shop'", 0.95, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Search for keyboards") { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(state.summary!!.contains("quotes"))
        assertTrue(tools.steps.isEmpty())
    }

    @Test
    fun `a claimed finish on the wrong page is checked and the run keeps going`() = runTest {
        val tools = FakeTools(complete = listOf(0.1, 0.95)) { _, call ->
            when (call) {
                0 -> Triple("The task is complete", 0.95, null)
                1 -> Triple("Open 'Sign in' link", 0.9, null)
                else -> Triple("The task is complete", 0.95, null)
            }
        }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { Answer.Stop }.run()
        assertEquals(RunStatus.DONE, state.status)
        assertEquals(listOf("Open 'Sign in' link"), state.steps.map { it.description })
        assertEquals(2, tools.verifyCalls)
    }

    @Test
    fun `a model that keeps claiming a finish the page contradicts is stopped, not believed`() = runTest {
        val tools = FakeTools(complete = listOf(0.1)) { _, _ -> Triple("The task is complete", 0.95, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status)
        assertTrue(state.summary!!.contains("does not look like it"))
    }

    @Test
    fun `an autocomplete search box is still a place to type`() {
        val box = PageElement("e8", "combobox", "input", "Search Wikipedia", type = "search", selector = SelectorInfo("id", "ooui-php-1"))
        assertTrue(Candidates.isTextField(box))
        val page = SEARCH_PAGE.copy(elements = listOf(box))
        assertTrue("Type into 'Search Wikipedia'" in Candidates.build(page, instruction).map { it.description })
        // A real select-like combobox with options is offered as choices instead.
        assertTrue(!Candidates.isTextField(box.copy(options = listOf("a", "b"))))
    }

    @Test
    fun `each step's history says which page it led to`() = runTest {
        val seen = mutableListOf<String>()
        val tools = FakeTools { criteria, call ->
            if (call == 0) Triple("Open 'Sign in' link", 0.9, null) else Triple("The task is complete", 0.95, null)
        }
        val decider = object : StepDecider by JevDecider(tools, JEV) {
            override suspend fun decide(ctx: StepContext): Result<Decision> {
                seen += ctx.history
                return JevDecider(tools, JEV).decide(ctx)
            }
        }
        TaskRunner(tools, decider, "t1", instruction) { Answer.Stop }.run()
        assertEquals(listOf("Open 'Sign in' link → now on 'Shop'"), seen.distinct())
    }

    @Test
    fun `jev models come from the jev_decide schema`() {
        assertEquals(listOf("typesafe/jev-1.13", "typesafe/jev-2"), ModelDirectory.jevModelIds(FakeTools { _, _ -> Triple("", 0.0, null) }.inputSchema(ToolNames.JEV_DECIDE)))
        assertEquals(emptyList(), ModelDirectory.jevModelIds("not json"))
    }

    @Test
    fun `a chat reply must name a listed action and marks text it wrote itself`() {
        val ctx = StepContext(instruction, SEARCH_PAGE, Candidates.build(SEARCH_PAGE, instruction), Candidates.values(instruction), emptyList())
        val key = ctx.candidates.first { it.needsValue }.key
        val d = ChatDecider.parseReply("""Sure. {"action":"$key","value":"cordless keyboard","confidence":0.8,"irreversible":false,"reason":"search"}""", ctx)
        assertEquals(key, d.key)
        assertTrue(d.valueWritten)
        assertEquals(0.0, d.risk)
        assertTrue(runCatching { ChatDecider.parseReply("""{"action":"a999"}""", ctx) }.isFailure)
    }

    @Test
    fun `a chat model is only used when the gateway can route to it`() {
        val gpt = ModelOption(ModelOption.Kind.CHAT, "OPENAI", "OpenAI", "gpt-5")
        assertNull(ChatDecider.routingProblem(gateway(setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE), null), gpt))
        assertNull(ChatDecider.routingProblem(gateway(emptySet(), AiModelInfo("OPENAI", "OpenAI", "gpt-5")), gpt))
        assertTrue(ChatDecider.routingProblem(gateway(emptySet(), AiModelInfo("ANTHROPIC", "Anthropic", "claude")), gpt)!!.contains("Update AI Gateway"))
        assertEquals(mapOf(AiRequest.EXTRAS_KEY_PROVIDER_ID to "OPENAI", AiRequest.EXTRAS_KEY_MODEL_OVERRIDE to "gpt-5"), ChatDecider.routingExtras(gpt))
    }

    @Test
    fun `the mcp transcript reports every step`() = runTest {
        val tools = FakeTools { _, call -> if (call == 0) Triple("Open 'Sign in' link", 0.9, null) else Triple("The task is complete", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction) { Answer.Stop }.run()
        val t = LlmrpaMcpToolProvider.transcript(state)
        assertEquals("done", (t["status"] as JsonPrimitive).content)
        assertEquals(1, (t["steps"] as kotlinx.serialization.json.JsonArray).size)
    }

    private fun gateway(caps: Set<String>, active: AiModelInfo?) = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> = Result.failure(UnsupportedOperationException())
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = caps
        override fun activeModel(): AiModelInfo? = active
    }
}
