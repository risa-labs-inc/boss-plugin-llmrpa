package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
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
import kotlinx.coroutines.launch
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
        assertNull(StartPages.fromInstruction("Open http://user:pw@x.example/"))
    }

    @Test
    fun `a model address must be a public https address`() {
        assertEquals("https://www.bbc.co.uk/weather", StartPages.usable(" https://www.bbc.co.uk/weather ", httpsOnly = true))
        // A name that merely starts with 0x is a real host.
        assertEquals("https://www.0xproject.com/", StartPages.usable("https://www.0xproject.com/", httpsOnly = true))
        assertNull(StartPages.usable("https://0x7f.0x1/", httpsOnly = true))
        listOf(
            "http://example.com", "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd", "https://user:pw@example.com/",
            "https://localhost/", "https://127.0.0.1/", "https://[::1]/", "https://exa mple.com", "example.com", "", null,
            "https://0x7f.1/", "https://127.1/", "https://localhost./", "https://foo.localhost/", "https://nas.local/",
            "https://git.internal/", "https://router.lan/", "https://box.home.arpa/", "https://example.com./",
            "https://site.test/", "https://www.example/", "https://x.invalid/",
        ).forEach { assertNull(StartPages.usable(it, httpsOnly = true), "$it") }
    }

    @Test
    fun `the start page comes from the instruction, then the model, then a search`() = runTest {
        val calls = mutableListOf<AiRequest>()
        val fromText = StartPages.choose("Open https://news.example/today", chatDecider("""{"url":"https://other.example"}""", calls))
        assertEquals(OpenedPage("https://news.example/today", StartSource.INSTRUCTION), fromText.page)
        assertTrue(calls.isEmpty(), "the model is not asked when the instruction names the page")

        val picked = StartPages.choose("Check the weather in Paris", chatDecider("""Sure: {"url": "https://weather.example.com/paris"}""", calls))
        assertEquals(OpenedPage("https://weather.example.com/paris", StartSource.MODEL), picked.page)
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

    @Test
    fun `a model address that carries text from the instruction keeps only its site`() = runTest {
        val text = "Log in with password 'hunter2' and check my orders"
        val leaky = StartPages.choose(text, chatDecider("""{"url":"https://shop.example.com/login?pw=hunter2"}"""))
        assertEquals("https://shop.example.com/", leaky.page.url)
        assertEquals(StartSource.MODEL, leaky.page.source)
        val encoded = StartPages.choose("Search for 'wireless keyboard'", chatDecider("""{"url":"https://shop.example.com/s?k=wireless+keyboard"}"""))
        assertEquals("https://shop.example.com/", encoded.page.url)
        val spaced = StartPages.choose("Sign in with passphrase 'my secret phrase'", chatDecider("""{"url":"https://shop.example.com/login?pw=my%20Secret%20phrase"}"""))
        assertEquals("https://shop.example.com/", spaced.page.url)
        val inPath = StartPages.choose("Search for 'wireless keyboard'", chatDecider("""{"url":"https://shop.example.com/search/wireless%20keyboard"}"""))
        assertEquals("https://shop.example.com/", inPath.page.url)
        // In the host, cutting to the site does not help: search instead.
        val inHost = StartPages.choose(text, chatDecider("""{"url":"https://hunter2.shop.example.com/"}"""))
        assertEquals(StartSource.SEARCH, inHost.page.source)
        assertFalse(inHost.page.url.contains("hunter2"), inHost.page.url)
        val clean = StartPages.choose(text, chatDecider("""{"url":"https://shop.example.com/orders"}"""))
        assertEquals("https://shop.example.com/orders", clean.page.url)
    }

    @Test
    fun `an address outside quotes is the start page before one meant to be typed`() {
        assertEquals("https://blog.example/new", StartPages.fromInstruction("Paste 'https://paste.example/x' into the post at https://blog.example/new"))
        assertEquals("https://paste.example/x", StartPages.fromInstruction("Open 'https://paste.example/x'"))
    }

    @Test
    fun `the search scrub keeps the words around apostrophes and quoted values`() {
        assertEquals("https://duckduckgo.com/?q=Find+Ada%27s+order+for+and+don%27t+pay",
            StartPages.searchUrl("Find Ada's order for 'ACME-4471' and don't pay"))
    }

    @Test
    fun `the search drops the word after a secret-sounding keyword`() {
        listOf(
            "log into my bank, username bob, password hunter2",
            "log into my bank with password: hunter2 and username=bob",
            "log into my bank, the password is hunter2, user name bob",
            "log into my bank; the password for github is hunter2 and the username to use is bob",
        ).forEach { text ->
            val q = java.net.URLDecoder.decode(StartPages.searchUrl(text)!!.substringAfter("q="), Charsets.UTF_8)
            assertTrue("hunter2" !in q && "bob" !in q, q)
            assertTrue("bank" in q, q)
        }
        assertEquals(listOf("pin 1234", "1234"), Candidates.keywordSecrets("use pin 1234"))
    }

    @Test
    fun `a username that prefixes the password does not shield it, and other words stay whole`() {
        fun q(text: String) = java.net.URLDecoder.decode(StartPages.searchUrl(text)!!.substringAfter("q="), Charsets.UTF_8)
        val leak = q("log into my bank, username bob, password bob123")
        assertTrue("bob" !in leak && "123" !in leak, leak)
        assertEquals("Login to Amazon and search for tomato soup", q("Login to Amazon and search for tomato soup"))
        assertTrue("weather in Paris" in q("pin the weather widget, then search weather in Paris"))
        // A quoted value is cut as a whole word only.
        assertEquals("Open the category for", q("Open the category for 'cat'"))
    }

    @Test
    fun `an empty page followed by NO_BROWSER reports the hint, not the stale page`() = runTest {
        val tools = FakeTools(decide = done)
        var n = 0
        tools.observeHook = { if (n++ == 0) ToolReply("""{"url":"https://orders.example/","title":"Orders","elements":[]}""", false) else noBrowser(it) }
        val state = headless(tools, emptyList(), emptySet()).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.FAILED, state.status)
        assertTrue(state.summary!!.contains("another space"), state.summary)
        assertEquals(0, tools.decideCalls)
    }

    @Test
    fun `a new tab that is not registered yet is waited for`() = runTest {
        val tools = FakeTools(decide = done)
        var n = 0
        tools.observeHook = { if (n++ < 2) ToolReply("""{"error":{"code":"${TaskRunner.TAB_NOT_FOUND}","message":"No tab with id 'new1'"}}""", true) else null }
        val state = headless(tools, emptyList(), emptySet()).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.DONE, state.status)
    }

    @Test
    fun `the search scrub is not capped like the typing list`() {
        val long = "x".repeat(250)
        val many = (1..25).joinToString(" ") { "'secret$it'" }
        val url = StartPages.searchUrl("Find the page for \"$long\" and $many")!!
        assertFalse(url.contains("xxxx") || url.contains("secret"), url)
    }

    @Test
    fun `the search never carries the quoted values, emails or addresses from the instruction`() = runTest {
        val text = "Log in to my bank with username \"ada@bank.example\" and password 'hunter2', then download the statement"
        val choice = StartPages.choose(text, JevDecider(FakeTools(decide = done), JEV))
        val url = choice.page.url
        assertEquals(StartSource.SEARCH, choice.page.source)
        listOf("hunter2", "ada", "bank.example", "%22", "%27").forEach { assertFalse(url.contains(it), "$it in $url") }
        assertTrue(url.contains("Log+in+to+my+bank"), url)
        // Smart quotes, as macOS types them.
        val smart = StartPages.searchUrl("Sign in with password ‘hunter2’ and check the balance")!!
        assertFalse(smart.contains("hunter2") || smart.contains("%E2%80"), smart)

        // Nothing left to search for: stop and ask for the site rather than search for the secret.
        val bare = TaskRunner(FakeTools(decide = done), JevDecider(FakeTools(decide = done), JEV), null, "'hunter2'", fast,
            newTab = NewTab(open = { _, _ -> error("must not open") }, claim = { null })) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, bare.status)
        assertTrue(bare.summary!!.contains("Put the site's address in the instruction"), bare.summary)
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
        assertEquals(mapOf("t1" to true, "t2" to false), c.drivable.value)
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
    fun `focus on a pane with no browser keeps a drivable pick`() = withMain {
        var focused: String? = "t1"
        val c = component(FakeTools(decide = done), FakeTabs(listOf(tab("t1")), focused = { focused }))
        assertEquals("t1", c.selectedTab.value?.tabId)
        focused = null
        c.refreshDrivable()
        assertEquals("t1", c.selectedTab.value?.tabId)
        assertFalse(c.newTab.value)
    }

    @Test
    fun `a probe asked for mid-probe runs again afterwards`() = withMain {
        val provider = FakeTabs(listOf(tab("t1"), tab("t2")), focused = { "t1" })
        val c = component(FakeTools(decide = done), provider)
        var asked = false
        provider.onProbe = { if (!asked) { asked = true; provider.drivable = setOf("t1"); c.refreshDrivable() } }
        val before = provider.probes
        c.refreshDrivable()
        // Two tabs, probed twice: the second pass sees the change made during the first.
        assertEquals(before + 4, provider.probes)
        assertEquals(mapOf("t1" to true, "t2" to false), c.drivable.value)
    }

    @Test
    fun `a throwing probe reads as drivable`() {
        val provider = object : ActiveTabsProvider by FakeTabs(listOf(tab("t1"))) {
            override fun getBrowserIntegration(tabId: String): ai.rever.boss.plugin.api.BrowserIntegration? = throw NoSuchMethodError("x")
        }
        assertTrue(StartPages.drivable(provider, "t1"))
    }

    @Test
    fun `the panel does not probe the host while a run holds a tab`() = withMain {
        val locks = TabLocks()
        val provider = FakeTabs(listOf(tab("t1")), focused = { "t1" })
        val c = component(FakeTools(decide = done), provider, locks)
        val before = provider.probes
        locks.tryAcquire("t9", TabLocks.Owner.HEADLESS)
        c.refreshDrivable()
        assertEquals(before, provider.probes)
        locks.release("t9")
        c.refreshDrivable()
        assertTrue(provider.probes > before)
        // A tab opened while a run holds the probe off is unknown, so it is not shown as away.
        locks.tryAcquire("t9", TabLocks.Owner.HEADLESS)
        provider.tabs.value = provider.tabs.value + tab("t5")
        assertNull(c.drivable.value!!["t5"])
        c.selectTab(tab("t5"))
        assertNull(c.blocker())
        locks.release("t9")
        // The readiness tick does not probe every time.
        val afterOne = provider.probes
        c.recheck()
        assertEquals(afterOne, provider.probes)
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
    fun `llmrpa_run refuses at once while the target is New tab`() = withMain {
        val api = object : AiGatewayAPI {
            override suspend fun complete(request: AiRequest): Result<AiReply> = error("must not be called")
            override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
            override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
                Result.failure(UnsupportedOperationException())
            override fun capabilities(): Set<String> = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
            override fun activeModel(): AiModelInfo? = AiModelInfo(chat.providerId, chat.providerName, chat.modelId)
        }
        val c = LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, FakeTabs(emptyList()), { api }, tools = FakeTools(decide = done),
            llmProvider = { null }, tabLocks = TabLocks(), io = Dispatchers.Main, work = Dispatchers.Main, baseLimits = fast)
        assertTrue(c.newTab.value)
        c.selectModel(chat)
        assertTrue(c.aiAvailable())
        val tool = LlmrpaMcpToolProvider("p", component = { c }, headless = headless(FakeTools(decide = done), emptyList(), emptySet()))
            .tools().first { it.name == "llmrpa_run" }.handler
        val r = kotlinx.coroutines.runBlocking { tool.call(McpToolArgs(mapOf("instruction" to "Open the orders page"), """{"instruction":"Open the orders page"}""")) }
        assertTrue(r.isError, r.text)
        assertEquals(LlmrpaComponent.DRAFT_NEEDS_TAB, r.text)
        assertTrue(c.executionHistory.value.isEmpty(), "nothing was started")
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
        val tool = LlmrpaMcpToolProvider("p", component = { null }, headless = r).tools().first { it.name == "llmrpa_execute" }.handler
        val blank = tool.call(ai.rever.boss.plugin.api.McpToolArgs(emptyMap(), """{"instruction":"Open https://orders.example/","tab_id":"","new_tab":true}"""))
        assertFalse(blank.isError, blank.text)
    }

    @Test
    fun `headless lists which tabs can be driven and refuses one in another space`() = runTest {
        val tabs = listOf(tab("t1"), tab("t2", workspace = "Fluck"))
        val r = headless(FakeTools(decide = done), tabs, setOf("t1"))
        val err = r.execute("x", "t2", 3, null).exceptionOrNull()!!.message!!
        assertTrue(err.contains(NO_BROWSER_HINT) && err.contains("new_tab"), err)
        assertTrue(err.contains("t2 ('Tab t2', shop.example, not drivable: In another space (Fluck) — switch to it to use this tab)"), err)
        assertFalse(err.substringAfter("one of: ").startsWith("t2"), "drivable tabs are listed first: $err")
        assertEquals(RunStatus.DONE, r.execute("x", "t1", 3, null).getOrThrow().status)

        // Focused only in the host's eyes: the drivable tabs are what it is asked about.
        val asked = mutableListOf<List<String>>()
        val focused = HeadlessRunner(FakeTools(decide = done), { null }, { null }, tabs = { tabs }, activeTabId = { c -> asked += c.map { it.tabId }; null },
            drivable = { it == "t1" }, openTab = { _, _ -> null }, locks = TabLocks(), limits = fast)
        val none = focused.execute("x", null, 3, null).exceptionOrNull()!!.message!!
        assertTrue(none.startsWith("No drivable tab is focused"), none)
        assertEquals(listOf(listOf("t1")), asked)
    }

    @Test
    fun `a tab that is not drivable in the space on screen is called not loaded, not away`() {
        val a = tab("t1"); val b = tab("t2"); val c = tab("t3", workspace = "Fluck")
        val probed = mapOf("t1" to true, "t2" to false, "t3" to false)
        assertTrue(StartPages.awayReason(b, listOf(a, b, c), probed).startsWith("Not loaded"))
        assertTrue(StartPages.awayReason(c, listOf(a, b, c), probed).startsWith("In another space (Fluck)"))
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
        assertTrue(slow.summary!!.contains("could not be read") && slow.summary.contains("another space"), slow.summary)
        assertEquals(fast.openWaitsMs.size, never.observedTabs.size)

        // Other errors are retried too (a script can fail mid-navigation), and the last one is reported.
        val broken = FakeTools(decide = done).apply { observeHook = { ToolReply("""{"error":{"code":"SCRIPT_FAILED","message":"boom"}}""", true) } }
        val failed = headless(broken, emptyList(), emptySet()).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertTrue(failed.summary!!.contains("boom"), failed.summary)
        assertEquals(fast.openWaitsMs.size, broken.observedTabs.size)
    }

    @Test
    fun `a new tab with nothing to act on is used once it reads the same twice past half the wait`() = runTest {
        val waits = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = listOf(300, 500, 800, 1_200, 2_000, 2_000, 3_000, 3_000))
        val tools = FakeTools(decide = done).apply { observeHook = { ToolReply("""{"url":"https://orders.example/","title":"Orders","elements":[]}""", false) } }
        val r = HeadlessRunner(tools, { null }, { null }, tabs = { emptyList() }, activeTabId = { null }, drivable = { true },
            openTab = { _, _ -> "new1" }, locks = TabLocks(), limits = waits)
        assertEquals(RunStatus.DONE, r.execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow().status)
        // 300+500+800+1200+2000 = 4800 < 6400; the sixth read (6800) is the first past half.
        assertEquals(6, tools.observedTabs.size)
    }

    @Test
    fun `an app that spins under its final title is waited for, not taken empty`() = runTest {
        val waits = RunLimits(navSettleMs = 0, stepSettleMs = 0, openWaitsMs = listOf(300, 500, 800, 1_200, 2_000, 2_000, 3_000, 3_000))
        val tools = FakeTools(decide = done)
        var n = 0
        tools.observeHook = { if (n++ < 4) ToolReply("""{"url":"https://mail.example/","title":"Inbox","elements":[]}""", false) else null }
        val r = HeadlessRunner(tools, { null }, { null }, tabs = { emptyList() }, activeTabId = { null }, drivable = { true },
            openTab = { _, _ -> "new1" }, locks = TabLocks(), limits = waits)
        r.execute("Open https://mail.example/", null, 3, null, newTab = true).getOrThrow()
        // Four empty reads, the rendered one, then the settled re-read the first step uses.
        assertEquals(6, tools.observedTabs.size)
        assertTrue(tools.decideCalls > 0, "the first step saw the rendered page")
    }

    @Test
    fun `Stop while the new tab is being created still records it, so its lock is released`() = runTest {
        val locks = TabLocks()
        lateinit var job: kotlinx.coroutines.Job
        val runner = TaskRunner(FakeTools(decide = done), JevDecider(FakeTools(decide = done), JEV), null, "Open https://orders.example/", fast,
            newTab = NewTab(open = { _, _ -> job.cancel(); "new1" }, claim = { locks.tryAcquire(it, TabLocks.Owner.PANEL) })) { Answer.Stop }
        job = launch { runner.run() }
        job.join()
        runner.markStopped()
        val state = runner.state.value
        assertEquals(RunStatus.STOPPED, state.status)
        // What the panel's completion handler and HeadlessRunner's finally release.
        assertEquals("new1", state.tabId)
        state.tabId?.let(locks::release)
        assertNull(locks.tryAcquire("new1", TabLocks.Owner.HEADLESS))
    }

    @Test
    fun `a new tab another run already holds is refused with its id`() = runTest {
        val locks = TabLocks()
        assertNull(locks.tryAcquire("new1", TabLocks.Owner.PANEL))
        val state = headless(FakeTools(decide = done), emptyList(), emptySet(), locks).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals("Opened new1, but: ${TabLocks.Owner.PANEL.busy}", state.summary)
        assertNull(state.tabId)
        // Still the panel's.
        assertEquals(TabLocks.Owner.PANEL.busy, locks.tryAcquire("new1", TabLocks.Owner.HEADLESS))
    }

    @Test
    fun `the new tab is read again after it settles, and that look is the first step's page`() = runTest {
        val tools = FakeTools(decide = done)
        var n = 0
        // Loading: an address but nothing rendered yet, then the page.
        tools.observeHook = { if (n++ == 0) ToolReply("""{"url":"https://orders.example/","title":"","elements":[]}""", false) else null }
        headless(tools, emptyList(), emptySet()).execute("Open https://orders.example/", null, 3, null, newTab = true).getOrThrow()
        // Empty, loaded, settled re-read; the loop does not look again.
        assertEquals(3, tools.observedTabs.size)
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
    fun `a start-address call that went out and failed still counts as a call`() = runTest {
        val failing = ChatDecider({
            object : AiGatewayAPI {
                override suspend fun complete(request: AiRequest): Result<AiReply> = Result.failure(IllegalStateException("HTTP 502"))
                override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
                    Result.failure(UnsupportedOperationException())
                override fun capabilities(): Set<String> = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE)
                override fun activeModel(): AiModelInfo? = null
            }
        }, chat)
        val choice = StartPages.choose("Check the weather", failing)
        assertEquals(StartSource.SEARCH, choice.page.source)
        assertEquals(1, choice.calls)
        assertEquals("HTTP 502", choice.page.note)
    }

    @Test
    fun `a headless run that times out before its tab exists releases nothing and names no tab`() = runTest {
        val locks = TabLocks()
        val slowDecider = object : StepDecider by JevDecider(FakeTools(decide = done), JEV) {
            override suspend fun startUrl(instruction: String): Result<StartUrlReply> = kotlinx.coroutines.awaitCancellation()
        }
        assertNull(locks.tryAcquire("other", TabLocks.Owner.PANEL))
        val runner = TaskRunner(FakeTools(decide = done), slowDecider, null, "Check the weather", fast,
            newTab = NewTab(open = { _, _ -> error("must not open") }, claim = { locks.tryAcquire(it, TabLocks.Owner.HEADLESS) })) { Answer.Stop }
        val state = kotlinx.coroutines.withTimeoutOrNull(1_000) { runner.run() } ?: runner.also { it.timedOut(1_000) }.state.value
        assertEquals(RunStatus.STOPPED, state.status)
        assertNull(state.tabId)
        assertNull(LlmrpaMcpToolProvider.transcript(state)["opened"])
        // The other tab's lock is untouched.
        assertEquals(TabLocks.Owner.PANEL.busy, locks.tryAcquire("other", TabLocks.Owner.HEADLESS))
    }

    @Test
    fun `a model reply's url is read from the first JSON object`() {
        assertEquals("https://a.example", ChatDecider.parseStartUrl("""Here: {"url":"https://a.example"} done"""))
        assertNull(ChatDecider.parseStartUrl("""{"url":null}"""))
        assertNull(ChatDecider.parseStartUrl("no json"))
    }
}
