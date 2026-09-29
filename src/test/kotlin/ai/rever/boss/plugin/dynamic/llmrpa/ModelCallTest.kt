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
import ai.rever.boss.plugin.api.AiUsage
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ModelCallTest {
    private val chat = ModelOption(ModelOption.Kind.CHAT, "OPENROUTER", "OpenRouter", "anthropic/claude-haiku", "Haiku")
    private val fast = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = listOf(0))

    /** Every stored text of [c], to check what could be shown or written. */
    private fun texts(c: ModelCall): String = listOfNotNull(c.request.text, c.response?.text, c.pick, c.error).joinToString("\n") +
        c.questions.joinToString("\n") { q -> q.text + q.options.joinToString { it.label } }

    @Test
    fun `a jev run records decide, text, risk and done check against their steps`() = runTest {
        val tools = FakeTools(chooseText = { it.first() to 0.9 }) { _, call ->
            when (call) {
                0 -> Triple("Type into 'Search shop'", 0.94, null)
                1 -> Triple("Press Enter", 0.88, null)
                else -> Triple("The task is complete", 0.91, null)
            }
        }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Search the shop for breast cancer", fast) { error("should not ask") }.run()
        assertEquals(RunStatus.DONE, state.status)
        assertEquals(
            listOf(1 to CallKind.DECIDE, 1 to CallKind.TEXT, 2 to CallKind.DECIDE, 2 to CallKind.RISK, 3 to CallKind.DECIDE, 3 to CallKind.VERIFY_DONE),
            state.modelCalls.map { it.step to it.kind },
        )
        // The header's count and cost are the list's.
        assertEquals(state.modelCalls.size, state.calls)
        assertEquals(0.00002 + 0.00003 + 0.00002 + 0.00001 + 0.00002 + 0.00001, state.costUsd, 1e-9)

        val decide = state.modelCalls.first()
        assertEquals(ToolNames.JEV_DECIDE, decide.tool)
        assertEquals("typesafe/jev-1.13", decide.model)
        assertEquals("Type into 'Search shop'", decide.pick)
        assertEquals(0.94, decide.confidence)
        val next = decide.questions.single { it.id == "next" }
        assertTrue(next.text.startsWith("Pick the single action"))
        val picked = next.options.single { it.key == next.pick }
        assertEquals("Type into 'Search shop'" to 0.94, picked.label to picked.probability)
        assertTrue(next.options.all { it.key.startsWith("a") || it.key in setOf(Candidates.DONE, Candidates.STUCK) })
        assertTrue(decide.request.text.contains("\"questions\""))
        assertTrue(decide.response!!.text.contains("probabilities"))
        assertTrue(decide.latencyMs >= 0)

        val text = state.modelCalls[1]
        assertEquals("breast cancer", text.pick)
        assertEquals(listOf("breast cancer", "breast", "cancer", "None of these"), text.questions.single().options.map { it.label })

        val risk = state.modelCalls[3]
        assertEquals(0.1, risk.risk)
        val yes = risk.questions.single().options.single { it.key == "true" }
        assertEquals(0.1, yes.probability)
        assertEquals(0.9, state.modelCalls.last().confidence)
    }

    @Test
    fun `a failed jev call is recorded with its error`() = runTest {
        val base = FakeTools { _, _ -> error("unused") }
        val jev = object : ToolInvoker by base {
            override suspend fun invoke(toolName: String, arguments: kotlinx.serialization.json.JsonElement): ToolReply =
                if (toolName == ToolNames.JEV_DECIDE) ToolReply("""{"error":{"message":"402 payment required"}}""", true)
                else base.invoke(toolName, arguments)
        }
        val state = TaskRunner(jev, JevDecider(jev, JEV), "t1", "Open the orders page", fast) { Answer.Stop }.run()
        assertEquals(RunStatus.FAILED, state.status)
        val call = state.modelCalls.single()
        assertEquals("402 payment required", call.error)
        assertNull(call.pick)
        assertEquals(1, state.calls)
    }

    @Test
    fun `a chat run records the start page, a retried decision and the done check`() = runTest {
        val tools = FakeTools { _, _ -> error("jev is not used") }
        val signIn = Candidates.build(SEARCH_PAGE, "Sign in to the shop", writes = true).first { it.description == "Open 'Sign in' link" }.key
        val decisions = ArrayDeque(listOf("I would sign in first.", """{"action":"$signIn","confidence":0.9,"irreversible":false}""", """{"action":"done","confidence":0.9,"irreversible":false}"""))
        val api = gateway { r ->
            when {
                r.system == ChatDecider.START_SYSTEM -> """{"url":"https://shop.com/"}"""
                r.system.contains("check whether") -> """{"complete":true,"confidence":0.8}"""
                else -> decisions.removeFirst()
            }
        }
        val newTab = NewTab(open = { _, _ -> "t1" }, claim = { null })
        val state = TaskRunner(tools, ChatDecider({ api }, chat), null, "Sign in to the shop", fast, newTab = newTab) { Answer.Stop }.run()
        assertEquals(RunStatus.DONE, state.status, state.summary)
        assertEquals(
            listOf(0 to CallKind.START_URL, 1 to CallKind.DECIDE, 1 to CallKind.DECIDE, 2 to CallKind.DECIDE, 2 to CallKind.VERIFY_DONE),
            state.modelCalls.map { it.step to it.kind },
        )
        val start = state.modelCalls[0]
        assertEquals(ModelCall.GATEWAY, start.tool)
        assertEquals("OPENROUTER/anthropic/claude-haiku-20250101", start.model)
        assertEquals("https://shop.com/", start.pick)
        assertTrue(start.request.text.startsWith("[system]\n" + ChatDecider.START_SYSTEM))
        assertTrue(start.request.text.contains("[user]\nInstruction: \"Sign in to the shop\""))
        assertEquals(12 to 5, start.inputTokens to start.outputTokens)
        // Chat models report tokens, not money.
        assertNull(start.costUsd)
        assertEquals(0.0, state.costUsd)

        val prose = state.modelCalls[1]
        assertEquals("I would sign in first.", prose.response?.text)
        assertNotNull(prose.error)
        val retried = state.modelCalls[2]
        assertNull(retried.error)
        assertEquals("Open 'Sign in' link", retried.pick)
        assertEquals(0.0, retried.risk)
        assertTrue(retried.request.text.contains("[assistant]\nI would sign in first."))
        assertTrue(retried.questions.isEmpty())
        assertEquals(0.8, state.modelCalls.last().confidence)
        assertEquals(5, state.calls)
    }

    @Test
    fun `a private field's text never appears in a recorded call, even one made before it was typed`() = runTest {
        val page = SEARCH_PAGE.copy(elements = listOf(element("p1", "textbox", "Access code").copy(sensitive = true), element("e2", "link", "Sign in")))
        val tools = FakeTools(page = page) { _, call -> if (call == 0) Triple("Type into 'Access code' (private field)", 0.95, 1) else Triple("The task is complete", 0.9, null) }
        val instruction = "Enter \"s3cretvalue\" as the access code"
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction, fast) { Answer.Stop }.run()
        assertEquals(RunStatus.DONE, state.status, state.summary)
        assertEquals("s3cretvalue", (tools.steps.single()["value"] as JsonPrimitive).content)
        // The first call went out before anyone knew the value was private; it is masked now too.
        assertTrue(state.modelCalls.size >= 2)
        state.modelCalls.forEach { c ->
            assertFalse(texts(c).contains("s3cretvalue"), texts(c))
        }
        assertTrue(state.modelCalls.first().request.text.contains(Secrets.MASK))
        assertEquals("Enter \"${Secrets.MASK}\" as the access code", state.shareableInstruction)
        assertEquals("Type ${Secrets.MASK} into 'Access code'", state.steps.single().description)
        assertTrue(state.steps.single().privateValue)
        assertNull(state.steps.single().action?.value)
        val full = LlmrpaMcpToolProvider.transcript(state, includeCalls = true).toString()
        assertFalse(full.contains("s3cretvalue"))
    }

    @Test
    fun `a password after its keyword is masked from the first call`() = runTest {
        val tools = FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Log in with password hunter2x and open orders", fast) { Answer.Stop }.run()
        assertTrue(state.modelCalls.isNotEmpty())
        state.modelCalls.forEach { assertFalse(texts(it).contains("hunter2x"), texts(it)) }
        assertEquals("Log in with password ${Secrets.MASK} and open orders", state.shareableInstruction)
    }

    @Test
    fun `a recorder that throws does not fail a good decision`() = runTest {
        val tools = FakeTools { _, _ -> Triple("Open 'Sign in' link", 0.9, null) }
        val ctx = StepContext("Sign in", SEARCH_PAGE, Candidates.build(SEARCH_PAGE, "Sign in", writes = false), emptyList(), emptyList())
        val d = kotlinx.coroutines.withContext(CallRecorder(1) { throw NoSuchMethodError("AiReply.getUsage") }) { JevDecider(tools, JEV).decide(ctx) }
        assertEquals("Open 'Sign in' link", ctx.candidates.first { it.key == d.getOrThrow().key }.description)
    }

    @Test
    fun `older runs in the history keep their call summaries but not the text`() {
        val history = RunHistory(keepCallText = 1)
        val call = ModelCall(1, CallKind.DECIDE, ToolNames.JEV_DECIDE, "m", request = CappedText("req"), response = CappedText("resp"), pick = "x", latencyMs = 3, costUsd = 0.1)
        val run = RunState("i", "m", 12, modelCalls = listOf(call), startedAt = 0)
        history.add(run)
        history.add(run.copy(startedAt = 1))
        val (newest, older) = history.recent()
        assertEquals("req", newest.modelCalls.single().request.text)
        assertEquals(CappedText("", truncated = true), older.modelCalls.single().request)
        assertEquals("x" to 0.1, older.modelCalls.single().pick to older.costUsd)
    }

    @Test
    fun `masking is whole-word and covers the JSON spelling`() {
        // After an escaped newline in JSON text, which ends in a letter.
        assertEquals("""{"instruction":"line one\n${Secrets.MASK} next"}""", Secrets.mask("""{"instruction":"line one\nhunter2x next"}""", setOf("hunter2x")))
        assertEquals("the pin is ${Secrets.MASK}, not 12345", Secrets.mask("the pin is 1234, not 12345", setOf("1234")))
        assertEquals("""{"v1":"${Secrets.MASK}"}""", Secrets.mask("""{"v1":"pa\"ss"}""", setOf("pa\"ss")))
        assertEquals("there", Secrets.mask("there", setOf("the")))
    }

    @Test
    fun `stored text is capped at 8 KB and marked, never mid-character or mid-value`() {
        val short = ModelCall.cap("hello")
        assertEquals(CappedText("hello"), short)
        val long = ModelCall.cap("é".repeat(10_000))
        assertTrue(long.truncated)
        assertTrue(long.text.toByteArray().size <= ModelCall.MAX_TEXT_BYTES)
        assertEquals(ModelCall.MAX_TEXT_BYTES / 2, long.text.length)
        // A value the cut would split is left out whole, so a later mask still finds nothing of it.
        val text = "x".repeat(ModelCall.MAX_TEXT_BYTES - 3) + "s3cretvalue" + "y".repeat(100)
        val cut = ModelCall.cap(text, listOf("s3cretvalue"))
        assertEquals("x".repeat(ModelCall.MAX_TEXT_BYTES - 3), cut.text)
        assertTrue(cut.truncated)
        // Moving the cut back past one value can land inside another; it moves until none straddles it.
        val overlap = "x".repeat(ModelCall.MAX_TEXT_BYTES - 6) + "abcdefghij" + "y".repeat(100)
        assertEquals("x".repeat(ModelCall.MAX_TEXT_BYTES - 6) + "a", ModelCall.cap(overlap, listOf("ghij", "defgh", "bcde")).text)
    }

    @Test
    fun `a long request is truncated in the run and flagged in the full transcript`() = runTest {
        val many = SEARCH_PAGE.copy(elements = (1..120).map { element("l$it", "link", "Result number $it with a long descriptive title for the page") })
        val tools = FakeTools(page = many) { _, _ -> Triple("The task is complete", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Open the orders page", fast) { Answer.Stop }.run()
        val decide = state.modelCalls.first()
        assertTrue(decide.request.truncated)
        assertTrue(decide.request.text.toByteArray().size <= ModelCall.MAX_TEXT_BYTES)
        // The parsed options are kept whole: the bars do not depend on the capped text.
        assertEquals(123, decide.questions.single().options.size)
        val detail = (LlmrpaMcpToolProvider.transcript(state, includeCalls = true)["calls"] as JsonArray).first() as JsonObject
        assertEquals(JsonPrimitive(true), detail["request_truncated"])
    }

    @Test
    fun `the default transcript summarises calls per step and include_calls adds them in full`() = runTest {
        val tools = FakeTools { _, call -> if (call == 0) Triple("Open 'Sign in' link", 0.9, null) else Triple("The task is complete", 0.9, null) }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Sign in to the shop", fast) { Answer.Stop }.run()
        val compact = LlmrpaMcpToolProvider.transcript(state)
        assertNull(compact["calls"])
        val step = (compact["steps"] as JsonArray).single() as JsonObject
        val summary = (step["model_calls"] as JsonArray).map { (it as JsonObject)["kind"] }
        assertEquals(listOf(JsonPrimitive("decide"), JsonPrimitive("risk")), summary)
        val other = (compact["other_model_calls"] as JsonArray).map { (it as JsonObject)["kind"] }
        assertEquals(listOf(JsonPrimitive("decide"), JsonPrimitive("verify_done")), other)
        assertFalse(compact.toString().contains("\"request\""))

        val full = LlmrpaMcpToolProvider.transcript(state, includeCalls = true)["calls"] as JsonArray
        assertEquals(state.calls, full.size)
        val first = full.first() as JsonObject
        assertTrue(listOf("step", "kind", "tool", "model", "questions", "request", "response", "latency_ms").all { it in first })
    }

    private fun gateway(answer: (AiRequest) -> String) = object : AiGatewayAPI {
        override suspend fun complete(request: AiRequest): Result<AiReply> =
            Result.success(AiReply(answer(request), AiUsage(12, 5), "anthropic/claude-haiku-20250101"))
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities(): Set<String> = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
        override fun activeModel(): AiModelInfo? = null
    }
}
