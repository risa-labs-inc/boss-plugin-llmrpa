package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.McpToolArgs
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * RPA Engine's configuration model as rpaengine 1.3.1 declares it (`RpaEngineTypes.kt`) and its
 * reader's Json settings (`RpaEngineSettingsManager`), copied so an export is checked against the
 * parser that will read it rather than against this plugin's own writer.
 */
@Serializable
private data class EngineSelectorInfo(val type: String = "xpath", val value: String? = null, val isUnique: Boolean? = null)

@Serializable
private data class EngineActionConfig(
    val name: String = "",
    val actionType: String = "default",
    val type: String,
    val selector: EngineSelectorInfo = EngineSelectorInfo(type = "none"),
    val value: String? = null,
    val meta: Map<String, String>? = null,
)

@Serializable
private data class EngineConfig(val name: String, val description: String = "", val actions: List<EngineActionConfig>)

private val engineJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

@OptIn(ExperimentalCoroutinesApi::class)
class RunExportTest {
    private val dir: File = Files.createTempDirectory("rpa-export").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val now = 1_790_000_000_000L

    private fun step(index: Int, type: String, outcome: StepRecord.Outcome = StepRecord.Outcome.OK, value: String? = null, private: Boolean = false, description: String = "Step $index") =
        StepRecord(index, description, 0.9, outcome = outcome, action = StepAction(type, SelectorInfo("css", "#e$index"), value), privateValue = private)

    private fun run(status: RunStatus, steps: List<StepRecord>, instruction: String = "Search the shop for keyboards") = RunState(
        instruction, "jev-1.13", 12, status = status, steps = steps, startedAt = 0, startUrl = "https://shop.example/",
        modelName = "Jev typesafe/jev-1.13",
    )

    private fun read(file: File): EngineConfig = engineJson.decodeFromString(EngineConfig.serializer(), file.readText())

    @Test
    fun `only steps that worked are exported, after a navigate to the start`() {
        val r = run(RunStatus.STOPPED, listOf(
            step(1, "input", value = "keyboards", description = "Type 'keyboards' into 'Search shop'"),
            step(2, "click", StepRecord.Outcome.FAILED, description = "Click 'Go' button"),
            step(3, "keypress", value = "Enter", description = "Press Enter"),
        ))
        val e = RpaEngineHandoff.exportRun(r, dir, now).getOrThrow()
        val config = read(e.file)
        assertEquals(listOf("navigate", "input", "keypress"), config.actions.map { it.type })
        assertEquals(3, e.actionCount)
        assertEquals("https://shop.example/", config.actions[0].value)
        assertEquals("none", config.actions[0].selector.type)
        assertEquals("keyboards", config.actions[1].value)
        assertEquals(EngineSelectorInfo("css", "#e1", true), config.actions[1].selector)
        assertEquals("Type 'keyboards' into 'Search shop'", config.actions[1].name)
        assertEquals(mapOf("source" to "llm-rpa", "step" to "1"), config.actions[1].meta)
        assertTrue(e.notes.isEmpty())
        assertTrue(config.description.startsWith("Exported from LLM RPA on "))
        listOf("Search the shop for keyboards", "Jev typesafe/jev-1.13", "2 of 3 steps worked").forEach { assertTrue(config.description.contains(it), config.description) }
        assertTrue(config.name.startsWith("Search the shop for keyboards (exported "))
        assertTrue(e.file.name.startsWith("llm-rpa-export-search-the-shop-for-keyboards-"))
        assertEquals(dir.canonicalFile, e.file.parentFile.canonicalFile)
    }

    @Test
    fun `a tab found open loses its query, an address the run opened keeps it`() {
        val found = run(RunStatus.DONE, listOf(step(1, "click"))).copy(startUrl = "https://user:pw@mail.example:8443/inbox?session=abc123#msg")
        val e = RpaEngineHandoff.exportRun(found, dir, now).getOrThrow()
        assertEquals("https://mail.example:8443/inbox", read(e.file).actions[0].value)
        assertFalse(e.file.readText().contains("abc123") || e.file.readText().contains("pw@"))
        assertTrue(e.notes.single().contains("session or sign-in token"))
        val search = "https://duckduckgo.com/?q=weather+paris"
        val opened = run(RunStatus.DONE, listOf(step(1, "click"))).copy(startUrl = search, opened = OpenedPage(search, StartSource.SEARCH))
        val kept = RpaEngineHandoff.exportRun(opened, dir, now + 1_000).getOrThrow()
        assertEquals(search, read(kept.file).actions[0].value)
        assertTrue(kept.notes.isEmpty())
        // Credentials go on every path, even from an address the run opened as given.
        val given = "https://bob:t0ken@intranet.example/app?view=1"
        val caller = run(RunStatus.DONE, listOf(step(1, "click"))).copy(startUrl = given, opened = OpenedPage(given, StartSource.CALLER))
        assertEquals("https://intranet.example/app?view=1", read(RpaEngineHandoff.exportRun(caller, dir, now + 2_000).getOrThrow().file).actions[0].value)
    }

    @Test
    fun `a run that stopped before its private field writes none of the quoted values`() = runTest {
        val page = SEARCH_PAGE.copy(elements = listOf(element("u1", "textbox", "Username"), element("p1", "textbox", "Password").copy(sensitive = true)))
        val tools = FakeTools(page = page) { _, _ -> Triple("Type into 'Username'", 0.95, 1) }
        val instruction = "Log in to shop.example as \"bob\" with \"hunter2x\""
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", instruction, RunLimits(maxSteps = 1, navSettleMs = 0, stepSettleMs = 0)) { Answer.Stop }.run()
        assertEquals(RunStatus.STOPPED, state.status, state.summary)
        assertEquals(StepRecord.Outcome.OK, state.steps.single().outcome)
        // The run never learned hunter2x was the password, so the shareable text still has it; the file must not.
        assertTrue(state.shareableInstruction.contains("hunter2x"))
        val raw = RpaEngineHandoff.exportRun(state, dir, now).getOrThrow().file.readText()
        assertFalse(raw.contains("hunter2x"), raw)
        assertTrue(read(RpaEngineHandoff.exportRun(state, dir, now + 1_000).getOrThrow().file).name.startsWith("Log in to shop.example as \"${Secrets.MASK}\" with \"${Secrets.MASK}\""))
    }

    @Test
    fun `exported verbs are the ones RPA Engine's plan runner performs`() {
        // rpaengine 1.3.1 ActionRunner.execute, less screenshot/switch_frame (refused on a real browser),
        // assert and run_script (never produced by a run) and download (rpa_step only).
        assertEquals(setOf("navigate", "click", "input", "select", "keypress", "submit", "wait", "scroll"), RpaEngineHandoff.PLAN_VERBS)
    }

    @Test
    fun `a download is left out with a note, since plans cannot download`() {
        val r = run(RunStatus.DONE, listOf(step(1, "click"), step(2, "download", description = "Download image 'cat.jpg'")))
        val e = RpaEngineHandoff.exportRun(r, dir, now).getOrThrow()
        assertEquals(listOf("navigate", "click"), read(e.file).actions.map { it.type })
        assertTrue(e.notes.single().contains("Download image 'cat.jpg'") && e.notes.single().contains("cannot download"), e.notes.toString())
        assertTrue(read(e.file).description.contains("cannot download"))
    }

    @Test
    fun `a private field is written with no text and a note`() {
        val r = run(RunStatus.DONE, listOf(step(1, "input", private = true, description = "Type ${Secrets.MASK} into 'Password'")),
            instruction = "Sign in with password hunter2x")
        val e = RpaEngineHandoff.exportRun(r.copy(shareableInstruction = Secrets.mask(r.instruction, Secrets.of(r.instruction))), dir, now).getOrThrow()
        val raw = e.file.readText()
        assertFalse(raw.contains("hunter2x"), raw)
        val input = read(e.file).actions[1]
        assertEquals("", input.value)
        assertEquals("true", input.meta?.get("private"))
        assertTrue(e.notes.single().contains("private field"))
        assertTrue(read(e.file).description.contains("fill it in before running"))
    }

    @Test
    fun `a real run that typed a secret exports without it`() = runTest {
        val page = SEARCH_PAGE.copy(elements = listOf(element("p1", "textbox", "Access code").copy(sensitive = true), element("e2", "link", "Sign in")))
        val tools = FakeTools(page = page) { _, call ->
            when (call) {
                0 -> Triple("Type into 'Access code' (private field)", 0.95, 1)
                1 -> Triple("Open 'Sign in' link", 0.9, null)
                else -> Triple("The task is complete", 0.9, null)
            }
        }
        val state = TaskRunner(tools, JevDecider(tools, JEV), "t1", "Enter \"s3cretvalue\" as the access code, then sign in", RunLimits(navSettleMs = 0, stepSettleMs = 0)) { Answer.Stop }.run()
        assertEquals(RunStatus.DONE, state.status, state.summary)
        assertEquals("https://shop.example/", state.startUrl)
        val e = RpaEngineHandoff.exportRun(state, dir, now).getOrThrow()
        val raw = e.file.readText()
        assertFalse(raw.contains("s3cretvalue"), raw)
        assertEquals(listOf("navigate", "input", "click"), read(e.file).actions.map { it.type })
        assertEquals("#p1", read(e.file).actions[1].selector.value)
    }

    @Test
    fun `an export never replaces a file, even one written the same second`() {
        val r = run(RunStatus.DONE, listOf(step(1, "click")))
        val first = RpaEngineHandoff.exportRun(r, dir, now).getOrThrow()
        val before = first.file.readText()
        val second = RpaEngineHandoff.exportRun(r.copy(steps = listOf(step(1, "click"), step(2, "keypress", value = "Enter"))), dir, now).getOrThrow()
        assertTrue(first.file != second.file)
        assertEquals(first.file.name.removeSuffix(".json") + "-2.json", second.file.name)
        assertEquals(before, first.file.readText())
        assertTrue(second.name.endsWith("#2)"))
        assertEquals(2, dir.listFiles()!!.size, dir.listFiles()!!.joinToString { it.name })
    }

    @Test
    fun `what can be exported`() {
        assertTrue(RpaEngineHandoff.exportable(run(RunStatus.DONE, emptyList())))
        assertTrue(RpaEngineHandoff.exportable(run(RunStatus.FAILED, listOf(step(1, "click"), step(2, "click", StepRecord.Outcome.FAILED)))))
        assertFalse(RpaEngineHandoff.exportable(run(RunStatus.STOPPED, listOf(step(1, "click", StepRecord.Outcome.FAILED)))))
        assertFalse(RpaEngineHandoff.exportable(run(RunStatus.RUNNING, listOf(step(1, "click")))))
        assertFalse(RpaEngineHandoff.exportable(run(RunStatus.DONE, emptyList()).copy(startUrl = null)))
        assertTrue(RpaEngineHandoff.exportRun(run(RunStatus.STOPPED, emptyList()), dir, now).isFailure)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `llmrpa_export writes the last run and reports its path and action count`() = runTest {
        val history = RunHistory()
        val provider = LlmrpaMcpToolProvider(
            "p", component = { null },
            headless = HeadlessRunner(FakeTools { _, _ -> error("unused") }, { null }, { null }, tabs = { emptyList() }, activeTabId = { null },
                drivable = { true }, openTab = { _, _ -> null }, locks = TabLocks(), runs = history),
            runs = history, exportDir = dir,
        )
        val tool = provider.tools().first { it.name == "llmrpa_export" }
        assertFalse(tool.readOnly)
        suspend fun call(raw: String) = tool.handler.call(McpToolArgs(emptyMap(), raw))
        assertTrue(call("{}").isError)

        history.add(run(RunStatus.STOPPED, listOf(step(1, "click", StepRecord.Outcome.FAILED)), instruction = "Older"))
        history.add(run(RunStatus.DONE, listOf(step(1, "click"), step(2, "download"))))
        val result = call("{}")
        assertFalse(result.isError, result.text)
        val out = Json.parseToJsonElement(result.text) as JsonObject
        assertEquals(2, (out["actions"] as JsonPrimitive).intOrNull)
        val path = (out["path"] as JsonPrimitive).content
        assertTrue(File(path).exists() && File(path).parentFile.canonicalFile == dir.canonicalFile)
        assertNotNull(out["notes"])
        val older = call("""{"run":2}""")
        assertTrue(older.isError && older.text.contains("no step that worked"), older.text)
        assertTrue(call("""{"run":3}""").text.contains("There is no run 3"))
    }

    @Test
    fun `the panel exports a finished run next to it`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val tools = FakeTools { _, call -> if (call == 0) Triple("Open 'Sign in' link", 0.9, null) else Triple("The task is complete", 0.9, null) }
            val tab = ActiveTabData("t1", "fluck", "Shop", "w", "Work", "p", "win", url = "https://shop.example/")
            val history = RunHistory()
            val c = LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, FakeTabs(listOf(tab), focused = { "t1" }), { null },
                tools = tools, llmProvider = { null }, tabLocks = TabLocks(), runHistory = history,
                io = Dispatchers.Main, work = Dispatchers.Main, baseLimits = RunLimits(navSettleMs = 0, stepSettleMs = 0), exportDir = dir)
            c.updateInstruction("Sign in to the shop")
            assertEquals(null, c.startRun())
            val done = assertNotNull(c.run.value)
            assertEquals(RunStatus.DONE, done.status)
            assertEquals(done, history.recent().single())
            c.exportRun(done)
            val notice = assertNotNull(c.export.value)
            assertEquals(done.startedAt, notice.runStartedAt)
            val e = assertNotNull(notice.export, notice.error)
            assertEquals(listOf("navigate", "click"), read(e.file).actions.map { it.type })
            // RPA Engine's rpa_load is not registered by these fakes' answers, so the load reports what it said.
            c.openInEngine(notice)
            assertEquals("unknown tool", c.export.value?.loaded)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
