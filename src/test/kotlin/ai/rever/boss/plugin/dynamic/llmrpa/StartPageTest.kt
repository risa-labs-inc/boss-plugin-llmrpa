package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
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
import ai.rever.boss.plugin.api.McpToolArgs
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tabs the host cannot drive, and runs that open their own page. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartPageTest {
    private val chat = ModelOption(ModelOption.Kind.CHAT, "OPENROUTER", "OpenRouter", "openrouter/free")
    private val done = { _: Map<String, String>, _: Int -> Triple("The task is complete", 0.9, null) }
    private val fast = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = List(4) { 0L })

    private fun tab(id: String, workspace: String = "Work") =
        ActiveTabData(id, "fluck", "Tab $id", workspace, workspace, "p-$workspace", "win", url = "https://shop.example/$id")

    /** A chat decider whose gateway answers every request with [reply], counting calls in [calls]. */
    private fun chatDecider(reply: String, calls: MutableList<AiRequest> = mutableListOf()) = ChatDecider({
        object : AiGatewayAPI {
            override suspend fun complete(request: AiRequest): Result<AiReply> { calls += request; return Result.success(AiReply(reply)) }
            override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
            override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
                Result.failure(UnsupportedOperationException())
            override fun capabilities(): Set<String> = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
            override fun activeModel(): AiModelInfo? = null
        }
    }, chat)

    private fun withMain(block: () -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try { block() } finally { Dispatchers.resetMain() }
    }

    private fun component(tools: ToolInvoker, provider: FakeTabs, locks: TabLocks = TabLocks()) =
        LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, provider, { null }, tools = tools, llmProvider = { null }, tabLocks = locks,
            io = Dispatchers.Main, work = Dispatchers.Main, baseLimits = fast)

    // ---- Start address ----

    @Test
    fun `the start address is the first usable http(s) address in the instruction`() {
        assertEquals("https://en.wikipedia.org/wiki/Cat", StartPages.fromInstruction("Open https://en.wikipedia.org/wiki/Cat, then http://b.example"))
        assertEquals("http://intranet.example/x", StartPages.fromInstruction("Go to http://intranet.example/x and log in"))
        // Credentials in the address are skipped, not opened.
        assertEquals("https://b.example/", StartPages.fromInstruction("Open https://me:pw@a.example/ or https://b.example/"))
        assertNull(StartPages.fromInstruction("Find the weather in Paris"))
    }

    @Test
    fun `a model address must be a public https address`() {
        assertEquals("https://www.bbc.co.uk/weather", StartPages.usable(" https://www.bbc.co.uk/weather ", httpsOnly = true))
        listOf(
            "http://example.com", "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd", "https://user:pw@example.com/",
            "https://localhost/", "https://127.0.0.1/", "https://[::1]/", "https://exa mple.com", "example.com", "", null,
        ).forEach { assertNull(StartPages.usable(it, httpsOnly = true), "$it") }
    }

    @Test
    fun `the start page comes from the instruction, then the model, then a search`() = runTest {
        val calls = mutableListOf<AiRequest>()
        val fromText = StartPages.choose("Open https://news.example/today", chatDecider("""{"url":"https://other.example"}""", calls))
        assertEquals(OpenedPage("https://news.example/today", StartSource.INSTRUCTION), fromText.page)
        assertTrue(calls.isEmpty(), "the model is not asked when the instruction names the page")

        val picked = StartPages.choose("Check the weather in Paris", chatDecider("""Sure: {"url": "https://weather.example/paris"}""", calls))
        assertEquals(OpenedPage("https://weather.example/paris", StartSource.MODEL), picked.page)
        assertEquals(1, picked.calls)
        assertEquals(1, calls.size)
        // Same routing as decide: the chosen provider and model, never the active one.
        assertEquals(ChatDecider.routingExtras(chat), calls.single().extras)

        val unsafe = StartPages.choose("Check the weather in Paris", chatDecider("""{"url":"javascript:alert(1)"}"""))
        assertEquals(StartSource.SEARCH, unsafe.page.source)
        assertEquals("https://duckduckgo.com/?q=Check+the+weather+in+Paris", unsafe.page.url)
        assertTrue(unsafe.page.note!!.contains("not a safe https address"), unsafe.page.note)

        val prose = StartPages.choose("Check the weather", chatDecider("I would try a weather site."))
        assertEquals(StartSource.SEARCH, prose.page.source)
        assertEquals("the model named no address", prose.page.note)

        // Jev answers choices only: straight to the search, with no call made.
        val tools = FakeTools(decide = done)
        val jev = StartPages.choose("Check the weather & wind", JevDecider(tools, JEV))
        assertEquals("https://duckduckgo.com/?q=Check+the+weather+%26+wind", jev.page.url)
        assertEquals(0, jev.calls)
        assertEquals(0, tools.decideCalls)
    }

    // ---- NO_BROWSER ----

    @Test
    fun `a tab the host cannot drive fails with a plain reason, at the first look or mid-run`() = runTest {
        val tools = FakeTools(decide = done).apply { observeHook = { noBrowser(it) } }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Open the orders page", fast) { Answer.Stop }.run()
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals("Could not read the page: $NO_BROWSER_HINT", state.summary)

        val midRun = FakeTools(decide = { _, _ -> Triple("Open 'Sign in' link", 0.9, null) }).apply { stepReply = noBrowser("t1") }
        val stopped = TaskRunner(midRun, JevDecider(midRun, JEV), "t1", "Sign in", fast) { Answer.Stop }.run()
        assertEquals(RunStatus.FAILED, stopped.status)
        assertEquals("Stopped at step 1: $NO_BROWSER_HINT", stopped.summary)
        // Stops at once rather than retrying a tab that cannot be reached.
        assertEquals(1, midRun.steps.size)
        assertEquals(NO_BROWSER_HINT, stopped.steps.single().detail)
    }

    // ---- Picker ----

    @Test
    fun `the picker marks tabs in another space and Run refuses them`() = withMain {
        val here = tab("t1")
        val away = tab("t2", workspace = "Fluck")
        val provider = FakeTabs(listOf(here, away), focused = { "t1" }, drivable = setOf("t1"))
        val c = component(FakeTools(decide = done), provider)
        assertEquals(setOf("t1"), c.drivable.value)
        assertEquals("t1", c.selectedTab.value?.tabId)

        c.selectTab(away)
        assertEquals(LlmrpaComponent.Blocker.AWAY, c.blocker())
        c.updateInstruction("Open the orders page")
        assertEquals(NO_BROWSER_HINT, c.startRun())

        // Switching to that space makes it drivable at the next look.
        provider.drivable = setOf("t2")
        c.refreshDrivable()
        assertNull(c.blocker())
    }

    @Test
    fun `the panel does not probe the host while a run holds a tab`() = withMain {
        val locks = TabLocks()
        val provider = FakeTabs(listOf(tab("t1")), focused = { "t1" })
        val c = component(FakeTools(decide = done), provider, locks)
        val before = provider.probes
        locks.tryAcquire("t9", TabLocks.Owner.HEADLESS)
        c.recheck()
        assertEquals(before, provider.probes)
        locks.release("t9")
        c.recheck()
        assertTrue(provider.probes > before)
    }

    @Test
    fun `the panel opens a new tab when no drivable tab is focused, and says where it went`() = withMain {
        val locks = TabLocks()
        val provider = FakeTabs(listOf(tab("t2", workspace = "Fluck")), focused = { null }, drivable = emptySet(), create = { "new1" })
        val tools = FakeTools(decide = done)
        val c = component(tools, provider, locks)
        assertTrue(c.newTab.value)
        c.updateInstruction("Open https://orders.example/ and check the latest order")
        assertNull(c.startRun())
        val run = c.run.value!!
        assertEquals(RunStatus.DONE, run.status)
        assertEquals(listOf("https://orders.example/"), provider.created)
        assertEquals("new1", run.tabId)
        assertEquals("Opened https://orders.example/ (chosen by instruction)", run.opened?.description)
        assertTrue(tools.observedTabs.all { it == "new1" })
        // The new tab's lock is given back.
        assertNull(locks.tryAcquire("new1", TabLocks.Owner.HEADLESS))
    }

    @Test
    fun `a new tab that BOSS does not create fails with a clear reason`() = withMain {
        val provider = FakeTabs(emptyList(), create = { null })
        val c = component(FakeTools(decide = done), provider)
        c.updateInstruction("Open https://orders.example/")
        assertNull(c.startRun())
        assertEquals(RunStatus.FAILED, c.run.value?.status)
        assertTrue(c.run.value!!.summary!!.contains("Could not open a new tab for https://orders.example/"), c.run.value!!.summary)
    }

    // ---- Headless ----

    private fun headless(tools: FakeTools, tabs: List<ActiveTabData>, drivable: Set<String>, locks: TabLocks = TabLocks(), open: (String) -> String? = { "new1" }, opened: MutableList<String> = mutableListOf()) =
        HeadlessRunner(tools, { null }, { null }, tabs = { tabs }, activeTabId = { null }, drivable = { it in drivable },
            openTab = { url, _ -> opened += url; open(url) }, locks = locks, limits = fast)

    @Test
    fun `headless new_tab opens the page, waits for it to load, and reports it`() = runTest {
        val tools = FakeTools(decide = done)
        var loading = 2
        tools.observeHook = { if (loading-- > 0) noBrowser(it) else null }
        val opened = mutableListOf<String>()
        val locks = TabLocks()
        val state = headless(tools, emptyList(), emptySet(), locks, opened = opened)
            .execute("Open https://orders.example/ and check the latest order", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.DONE, state.status)
        assertEquals(listOf("https://orders.example/"), opened)
        assertEquals(StartSource.INSTRUCTION, state.opened?.source)
        assertNull(locks.tryAcquire("new1", TabLocks.Owner.PANEL))

        val t = LlmrpaMcpToolProvider.transcript(state)
        val o = t["opened"] as JsonObject
        assertEquals("https://orders.example/", (o["url"] as JsonPrimitive).content)
        assertEquals("instruction", (o["chosen_by"] as JsonPrimitive).content)
        assertEquals("new1", (o["tab_id"] as JsonPrimitive).content)
    }

    @Test
    fun `headless start_url opens exactly that address, and a bad one is refused`() = runTest {
        val opened = mutableListOf<String>()
        val r = headless(FakeTools(decide = done), emptyList(), emptySet(), opened = opened)
        assertEquals(StartSource.CALLER, r.execute("Check the latest order", null, 3, null, startUrl = "https://orders.example/").getOrThrow().opened?.source)
        assertEquals(listOf("https://orders.example/"), opened)
        assertTrue(r.execute("x", null, 3, null, startUrl = "javascript:alert(1)").isFailure)
        assertTrue(r.execute("x", "t1", 3, null, newTab = true).exceptionOrNull()!!.message!!.contains("not both"))
    }

    @Test
    fun `headless lists which tabs can be driven and refuses one in another space`() = runTest {
        val tabs = listOf(tab("t1"), tab("t2", workspace = "Fluck"))
        val r = headless(FakeTools(decide = done), tabs, setOf("t1"))
        val err = r.execute("x", "t2", 3, null).exceptionOrNull()!!.message!!
        assertTrue(err.contains(NO_BROWSER_HINT) && err.contains("new_tab"), err)
        assertTrue(err.contains("t2 ('Tab t2', shop.example, in another space 'Fluck', not drivable)"), err)
        assertFalse(err.substringAfter("one of: ").startsWith("t2"), "drivable tabs are listed first: $err")
        assertEquals(RunStatus.DONE, r.execute("x", "t1", 3, null).getOrThrow().status)
    }

    @Test
    fun `headless new_tab fails clearly when BOSS makes no tab or the page never loads`() = runTest {
        val none = headless(FakeTools(decide = done), emptyList(), emptySet(), open = { null })
        val state = none.execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.FAILED, state.status)
        assertTrue(state.summary!!.contains("BOSS did not create one"), state.summary)

        val never = FakeTools(decide = done).apply { observeHook = { noBrowser(it) } }
        val slow = headless(never, emptyList(), emptySet()).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.FAILED, slow.status)
        assertTrue(slow.summary!!.contains("could not be read"), slow.summary)
        assertEquals(fast.openWaitsMs.size, never.observedTabs.size)
    }

    @Test
    fun `llmrpa_execute passes new_tab and start_url through`() = runTest {
        val opened = mutableListOf<String>()
        val provider = LlmrpaMcpToolProvider("p", component = { null }, headless = headless(FakeTools(decide = done), emptyList(), emptySet(), opened = opened))
        val execute = provider.tools().first { it.name == "llmrpa_execute" }.handler
        val r = execute.call(McpToolArgs(emptyMap(), """{"instruction":"Open https://orders.example/","new_tab":true}"""))
        assertFalse(r.isError, r.text)
        execute.call(McpToolArgs(emptyMap(), """{"instruction":"Check it","start_url":"https://b.example/"}"""))
        assertEquals(listOf("https://orders.example/", "https://b.example/"), opened)
        val schema = provider.tools().first { it.name == "llmrpa_execute" }.inputSchema
        assertTrue(schema.contains("\"new_tab\"") && schema.contains("\"start_url\""), schema)
    }

    @Test
    fun `a model reply's url is read from the first JSON object`() {
        assertEquals("https://a.example", ChatDecider.parseStartUrl("""Here: {"url":"https://a.example"} done"""))
        assertNull(ChatDecider.parseStartUrl("""{"url":null}"""))
        assertNull(ChatDecider.parseStartUrl("no json"))
    }
}
