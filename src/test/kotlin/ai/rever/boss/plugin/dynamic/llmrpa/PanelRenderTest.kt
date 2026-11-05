package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.EncodedImageFormat

/** Renders the real panel in each state at sidebar and wide widths, for visual review. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PanelRenderTest {
    private val tab = ActiveTabData("t1", "fluck", "Shop · Wireless keyboards", "w", "Work", "p", "win", url = "https://shop.example/search")

    private fun tabs(list: List<ActiveTabData>) = FakeTabs(list, focused = { list.firstOrNull()?.tabId })

    private val exportDir = Files.createTempDirectory("render-export").toFile().apply { deleteOnExit() }

    private fun component(tools: ToolInvoker, openTabs: List<ActiveTabData> = listOf(tab)) =
        LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, tabs(openTabs), { null }, tools = tools, llmProvider = { null }, tabLocks = TabLocks(), runHistory = RunHistory(), runLog = testRunLog(),
            io = Dispatchers.Main, work = Dispatchers.Main, baseLimits = RunLimits(navSettleMs = 0, stepSettleMs = 0), exportDir = exportDir)

    @Test
    fun `renders every state at every width`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val instruction = "Search for 'wireless keyboard' and open the first result"

            val idle = component(FakeTools { _, _ -> Triple("The task is complete", 0.9, null) })
            listOf(280, 360, 520).forEach { render(idle, it, 900, "idle-$it") }
            idle.updateInstruction(instruction)
            render(idle, 360, 900, "ready-360")

            val asking = component(FakeTools { _, call ->
                if (call == 0) Triple("Type into 'Search shop'", 0.94, 1) else Triple("Open 'Sign in' link", 0.46, null)
            })
            asking.updateInstruction(instruction)
            assertEquals(null, asking.startRun())
            assertEquals(RunStatus.WAITING, asking.run.value?.status)
            listOf(280, 360).forEach { render(asking, it, 1000, "asking-$it") }
            render(asking, 960, 760, "asking-wide-960")

            val words = "Open wikipedia home page, then follow links till you reach breast cancer"
            idle.updateInstruction(words)
            render(idle, 360, 900, "ready-words-360")
            val askingText = component(FakeTools(chooseText = { it.first() to 0.3 }) { _, _ -> Triple("Type into 'Search shop'", 0.94, null) })
            askingText.updateInstruction(words)
            assertEquals(null, askingText.startRun())
            assertIs<PendingQuestion.ChooseText>(askingText.run.value?.question)
            render(askingText, 360, 1000, "asking-text-360")

            val done = component(FakeTools { _, call ->
                when (call) {
                    0 -> Triple("Type into 'Search shop'", 0.94, 1)
                    1 -> Triple("Press Enter", 0.88, null)
                    else -> Triple("The task is complete", 0.91, null)
                }
            })
            done.updateInstruction(instruction)
            done.startRun()
            assertEquals(RunStatus.DONE, done.run.value?.status)
            render(done, 360, 1000, "done-360")
            render(done, 1440, 800, "done-wide-1440")
            done.exportRun(done.run.value!!)
            render(done, 360, 1100, "exported-360")
            val calls = done.run.value!!.modelCalls
            listOf(2, 1).forEach { step ->
                renderContent(360, 900, "calls-step$step-360") { BossTheme { Column(Modifier.background(RpaTokens.Content).padding(12.dp)) { ModelCallsRow(calls.filter { it.step == step }, initiallyExpanded = true) } } }
            }

            val noTab = component(FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }, openTabs = emptyList())
            noTab.updateInstruction(instruction)
            render(noTab, 360, 800, "no-tab-360")

            val opened = LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, FakeTabs(emptyList(), create = { "new1" }), { null },
                tools = FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }, llmProvider = { null }, tabLocks = TabLocks(), runHistory = RunHistory(), runLog = testRunLog(),
                io = Dispatchers.Main, work = Dispatchers.Main, baseLimits = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = listOf(0)))
            opened.updateInstruction("Check the weather in Paris")
            assertEquals(null, opened.startRun())
            assertEquals("new1", opened.run.value?.tabId)
            render(opened, 360, 900, "opened-360")
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun jevGateway() = object : ai.rever.boss.plugin.api.AiGatewayAPI {
        override suspend fun complete(request: ai.rever.boss.plugin.api.AiRequest) = Result.failure<ai.rever.boss.plugin.api.AiReply>(UnsupportedOperationException())
        override fun stream(request: ai.rever.boss.plugin.api.AiRequest) = kotlinx.coroutines.flow.emptyFlow<ai.rever.boss.plugin.api.AiChunk>()
        override suspend fun runAgent(request: ai.rever.boss.plugin.api.AiRequest, tools: List<ai.rever.boss.plugin.api.AiToolSpec>, budget: ai.rever.boss.plugin.api.AiBudget,
            invoke: suspend (ai.rever.boss.plugin.api.AiToolCall) -> ai.rever.boss.plugin.api.AiToolOutcome) = Result.failure<ai.rever.boss.plugin.api.AiAgentResult>(UnsupportedOperationException())
        override fun capabilities(): Set<String> = emptySet()
        override fun activeModel() = ai.rever.boss.plugin.api.AiModelInfo("OPENAI", "OpenAI", "gpt-5")
    }

    private fun jevComponent(): LlmrpaComponent {
        val tools = object : ToolInvoker by FakeTools(decide = { _, _ -> Triple("x", 0.9, null) }) {
            override fun inputSchema(toolName: String) = """{"properties":{"model":{"enum":["typesafe/jev-1.13","typesafe/jev-1.14"]},"assets":{}}}"""
        }
        val api = jevGateway()
        return LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, tabs(listOf(tab)), { api }, tools = tools, llmProvider = { null },
            tabLocks = TabLocks(), runHistory = RunHistory(), runLog = testRunLog(), io = Dispatchers.Main, work = Dispatchers.Main,
            baseLimits = RunLimits(navSettleMs = 0, stepSettleMs = 0), exportDir = exportDir).also { it.selectPanelTab(LlmrpaComponent.PanelTab.JEV) }
    }

    private fun entry(kind: ActivityKind, name: String, args: String, result: String, ms: Long, confidence: Double? = null, cost: Double? = null, tokens: Int? = null, failed: Boolean = false) =
        ActivityEntry(0, 0, kind, name, args, result, ms, confidence, cost, tokens, failed)

    private fun useRun(phase: UsePhase, stepCount: Int, report: UseReport? = null, question: String? = null): UseRun {
        val run = UseRun("Find the top 10 restaurants in Palo Alto and list their ratings", startedAt = 0)
        run.phase.value = phase
        run.plan.value = UsePlan(listOf("https://www.google.com/search?q=top+restaurants+palo+alto"), "Read the ratings of the top restaurants in the results and scroll for ten", emptyList())
        val all = listOf(
            UseStep(1, VisionOp.CLICK, 560, 195, 50, 25, null, 0.92, 2100, true),
            UseStep(2, VisionOp.TYPE_TEXT, 400, 120, 36, 15, "Palo Alto restaurants", 0.88, 3400, true, textSource = ComputerUseFlow.WRITTEN_BY_MODEL),
            UseStep(3, VisionOp.PRESS_ENTER, null, null, null, null, null, 0.81, 1800, true),
            UseStep(4, VisionOp.SCROLL_DOWN, confidence = 0.64, ms = 1500, changed = false, note = "amount medium"),
            UseStep(5, VisionOp.CLICK, 12, 12, 1, 1, null, 0.40, 900, null, "the action failed", true),
        )
        run.steps.value = all.take(stepCount)
        listOf(
            entry(ActivityKind.LLM, "gateway chat: plan", "OpenAI gpt-5, 62 chars", """{"open":[{"url":"https://www.google.com/search?q=top+restaurants"}],"jev_goal":"Read the ratings"}""", 2310, tokens = 420),
            entry(ActivityKind.TOOL, "rpa_step", "navigate https://www.google.com/search?q=top+restaurants+palo+alto tab t1", "ok", 640),
            entry(ActivityKind.TOOL, "rpa_step", "screenshot tab t1", "784x546px (viewport 1120x780)", 380),
            entry(ActivityKind.JEV, "jev_decide", "typesafe/jev-1.14 questions=operation,target,scroll image=t1-1700000000.jpg", "CLICK p=0.92 target 50%,25%", 2100, 0.92, 0.0102),
            entry(ActivityKind.TOOL, "rpa_step", "click_at 560,195 tab t1", "ok", 310),
            entry(ActivityKind.TOOL, "rpa_step", "click_at 12,12 tab t1", "failed: outside the viewport", 40, failed = true),
        ).forEach(run.log::add)
        question?.let { run.question.value = UseQuestion(it) }
        run.report.value = report
        return run
    }

    @Test
    fun `renders the Jev tab in each state`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val idle = jevComponent()
            listOf(360, 960).forEach { render(idle, it, 700, "jev-idle-$it") }
            idle.updateUseInput("Find the top 10 restaurants in Palo Alto")
            render(idle, 360, 700, "jev-typed-360")

            val running = jevComponent().also { it.showUse(useRun(UsePhase.RUNNING, 4)) }
            listOf(360, 960).forEach { render(running, it, 1200, "jev-running-$it") }

            val asking = jevComponent().also { it.showUse(useRun(UsePhase.RUNNING, 4, question = "Jev is only 40% sure it should click")) }
            listOf(360, 960).forEach { render(asking, it, 1200, "jev-asking-$it") }

            val doneSteps = useRun(UsePhase.FINISHED, 4).steps.value
            val list = (1..40).joinToString("\n") { "$it. Restaurant ${it}  4.${(it % 9) + 1} stars  ${100 + it * 37} reviews  Open now  Palo Alto" }
            val title = "top restaurants palo alto - Google Search"
            val pageUrl = "https://www.google.com/search?q=top+restaurants+palo+alto"
            val report = UseReport(
                UseStatus.DONE, "Find the top 10 restaurants in Palo Alto and list their ratings", "Read the ratings",
                Cu.summary(UseStatus.DONE, doneSteps, 31.4, title, pageUrl, null),
                pageUrl, title, list, doneSteps, 31.4, 5, 2, 12, 0.0614, 1180,
                "typesafe/jev-1.14", "OpenAI gpt-5", Cu.confidenceStats(doneSteps), "/Users/me/.boss/cache/rpaengine/shots/t1-1700000000.jpg",
            )
            val done = jevComponent().also { it.showUse(useRun(UsePhase.FINISHED, 4, report = report)) }
            listOf(360, 960).forEach { render(done, it, 1500, "jev-done-$it") }
            val blocked = jevComponent().also { it.showUse(useRun(UsePhase.FINISHED, 5, report = report.copy(status = UseStatus.BLOCKED, summary = Cu.summary(UseStatus.BLOCKED, useRun(UsePhase.FINISHED, 5).steps.value, 31.4, title, pageUrl, "3 actions in a row changed nothing on screen"), pageText = "No results found.", error = "3 actions in a row changed nothing on screen"))) }
            render(blocked, 360, 1300, "jev-blocked-360")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `renders the Jev tab stuck, consulted and with collected items`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val seen = Seen("https://www.zomato.com/", 0.0, "h")
            val steps = listOf(
                UseStep(1, VisionOp.SCROLL_DOWN, confidence = 0.71, ms = 1500, changed = false, note = "amount medium", before = seen),
                UseStep(2, VisionOp.SCROLL_UP, confidence = 0.66, ms = 1400, changed = false, note = "amount medium", before = seen),
                UseStep(3, VisionOp.SCROLL_DOWN, confidence = 0.62, ms = 1500, changed = false, note = "amount small", before = seen),
                UseStep(4, VisionOp.CONSULT, confidence = 1.0, ms = 2600, consult = "Consulted OpenAI gpt-5: click 'Log in'", before = seen),
                UseStep(5, VisionOp.COLLECT, confidence = 0.9, ms = 1800, note = "saved", before = seen),
            )
            val title = "Zomato - Restaurant Delivery"
            val url = "https://www.zomato.com/"
            fun base(status: UseStatus, summaryError: String?, blocker: UseBlocker?, extract: Boolean = false, answer: String? = null) = UseReport(
                status, "Log in to Zomato", "Log in to Zomato", Cu.summary(status, steps, 41.2, title, url, summaryError), url, title,
                "Log in\nSign up\nOrder food online from the best restaurants", steps, 41.2, 6, 2, 17, 0.0731, 640, "typesafe/jev-1.14", "OpenAI gpt-5",
                Cu.confidenceStats(steps.filter { it.op != VisionOp.CONSULT }), "/Users/me/.boss/cache/rpaengine/shots/t1-1700000000.jpg", summaryError,
                tabId = "t1", blocker = blocker, extract = extract, answer = answer,
                collected = listOf(
                    CollectedItem(5, url, title, "Zomato\nOrder food online from the best restaurants in your city.\nLog in\nSign up", null, false),
                    CollectedItem(2, "https://www.zomato.com/palo-alto/restaurants", "Best restaurants in Palo Alto", (1..30).joinToString("\n") { "$it. Restaurant $it 4.${it % 9 + 1}" }, null, true),
                ),
                pagesVisited = listOf(PageVisit("https://www.zomato.com/palo-alto/restaurants", "Best restaurants in Palo Alto", 2), PageVisit(url, title, 5)),
            )
            fun runOf(report: UseReport): UseRun = useRun(UsePhase.FINISHED, 0).also { r ->
                r.steps.value = steps
                r.report.value = report
            }
            val reason = "3 actions in a row left the page where it was (same address, same scroll position)"
            val question = "The page ignores scrolling and shows no way in. Do you want to log in with your phone number? If so, what is it?"
            val stuck = jevComponent().also { it.showUse(runOf(base(UseStatus.NEEDS_INPUT, reason, UseBlocker(reason, question, url, title, "t1", "/Users/me/shots/t1.jpg", 3)))) }
            listOf(360, 960).forEach { render(stuck, it, 1500, "jev-needs-input-$it") }
            stuck.updateUseInput("My phone is 555 0100")
            render(stuck, 360, 1500, "jev-needs-input-typed-360")

            val which = "Several items match 'Tres Leches' and Jev is only 40% sure which one you want"
            val options = listOf(ChoiceOption("1", "Classic Tres Leches \u20B9450"), ChoiceOption("2", "Eggless Classic Tres Leches \u20B9470"), ChoiceOption("3", "Chocolate Tres Leches \u20B9500"))
            val choose = jevComponent().also { it.showUse(runOf(base(UseStatus.NEEDS_INPUT, which, UseBlocker(which, "Which one do you want? 1) Classic Tres Leches \u20B9450 2) Eggless Classic Tres Leches \u20B9470 3) Chocolate Tres Leches \u20B9500 (reply with the number or the name)", url, title, "t1", null, 4, options)))) }
            listOf(360, 960).forEach { render(choose, it, 1500, "jev-choose-$it") }

            val extracted = jevComponent().also { it.showUse(runOf(base(UseStatus.DONE, null, null, extract = true, answer = "The top restaurants in Palo Alto on Zomato are Restaurant 1 (4.2) and Restaurant 2 (4.3)."))) }
            listOf(360, 960).forEach { render(extracted, it, 1500, "jev-collected-$it") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `renders agent mode as the default with its Model setting, thoughts and a payment confirm`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val c = jevComponent()
            assertEquals(LlmrpaComponent.UseMode.AGENT, c.useMode.value)
            assertEquals(null, c.agentModel.value)
            val haiku = ModelOption(ModelOption.Kind.CHAT, "ANTHROPIC", "Anthropic", "claude-haiku-4-5")
            c.selectAgentModel(haiku)
            listOf(360, 960).forEach { render(c, it, 700, "jev-agent-idle-$it") }
            val fast = jevComponent().also { it.selectUseMode(LlmrpaComponent.UseMode.FAST) }
            render(fast, 360, 700, "jev-fast-idle-360")
            val steps = listOf(
                UseStep(1, VisionOp.NAVIGATE, confidence = 1.0, ms = 1900, target = "https://www.zomato.com/", thought = "Open Zomato to find the bakery.", result = "now on 'Zomato' (www.zomato.com)"),
                UseStep(2, VisionOp.TYPE_TEXT, confidence = 1.0, ms = 1700, text = "Magnolia Bakery", target = "textbox 'Search for restaurant'", thought = "Search for the bakery.", result = "page now shows 'Magnolia Bakery, Bandra'"),
                UseStep(3, VisionOp.CLICK, confidence = 1.0, ms = 1500, target = "button 'ADD'", thought = "Add the Tres Leches to the cart.", result = "page now shows '1 item added'"),
            )
            val q = "About to 'Place order'. Confirm?"
            val report = UseReport(UseStatus.NEEDS_INPUT, "Order tres leches from Magnolia Bakery on Zomato", null, Cu.summary(UseStatus.NEEDS_INPUT, steps, 12.3, "Checkout", "https://www.zomato.com/checkout", "About to 'Place order', which may pay or place an order"),
                "https://www.zomato.com/checkout", "Checkout", "Total 470", steps, 12.3, 0, 4, 9, null, 5200, "typesafe/jev-1.14", "Anthropic claude-haiku-4-5", emptyList(), null,
                error = "About to 'Place order', which may pay or place an order", tabId = "t1",
                blocker = UseBlocker("About to 'Place order', which may pay or place an order", q, "https://www.zomato.com/checkout", "Checkout", "t1", null, 3, listOf(ChoiceOption("1", "Confirm"), ChoiceOption("2", "Cancel"))))
            val run = UseRun(report.task, startedAt = 0).also { r -> r.steps.value = steps; r.report.value = report; r.phase.value = UsePhase.FINISHED; r.agent = AgentMemory() }
            val asked = jevComponent().also { it.showUse(run) }
            listOf(360, 960).forEach { render(asked, it, 1300, "jev-agent-confirm-$it") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the navigator model is a setting of the Jev tab, automatic until one is picked`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val c = jevComponent()
            assertEquals(null, c.navigatorModel.value)
            val haiku = ModelOption(ModelOption.Kind.CHAT, "ANTHROPIC", "Anthropic", "claude-haiku-4-5")
            c.selectNavigatorModel(haiku)
            assertEquals(haiku, c.navigatorModel.value)
            c.selectNavigatorModel(null)
            assertEquals(null, c.navigatorModel.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `readiness recovers when Jev registers after the panel opened`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val tools = FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }
            tools.registered = setOf(ToolNames.OBSERVE, ToolNames.STEP)
            val c = component(tools)
            // Plugins load in any order: at creation there is no model to pick.
            assertEquals(LlmrpaComponent.Blocker.MODEL, c.readiness.value)
            assertEquals(null, c.selectedModel.value)

            tools.registered = null
            c.recheck()
            assertEquals("typesafe/jev-1.13", c.selectedModel.value?.modelId)
            assertEquals(null, c.readiness.value)

            // Jev briefly unregistering (a hot reload) must not wipe the pick.
            tools.registered = setOf(ToolNames.OBSERVE, ToolNames.STEP)
            c.recheck()
            assertEquals("typesafe/jev-1.13", c.selectedModel.value?.modelId)
            assertEquals(LlmrpaComponent.Blocker.JEV, c.readiness.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun render(component: LlmrpaComponent, width: Int, height: Int, name: String) = renderContent(width, height, name) { LlmrpaContent(component) }

    private fun renderContent(width: Int, height: Int, name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val out = Path.of("build/reports/visual/llmrpa-$name.png")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            val png = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            Files.createDirectories(out.parent)
            Files.write(out, png.bytes)
            assertTrue(Files.size(out) > 0)
        } finally {
            scene.close()
        }
    }
}
