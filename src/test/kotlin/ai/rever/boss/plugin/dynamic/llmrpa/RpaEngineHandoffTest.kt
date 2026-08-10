package ai.rever.boss.plugin.dynamic.llmrpa

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The handoff has to produce the shape the *engine* reads, which is not the shape this plugin
 * produces internally.
 *
 * Two differences would each yield a file the engine silently cannot use: it wraps actions as
 * `{name, description, actions}` rather than `{configuration, status, message}`, and it names the
 * field `actionType` where this plugin and its prompt say `action_type`. Neither shows up until a
 * user loads the config and finds it empty, which is why they are pinned here.
 */
class RpaEngineHandoffTest {
    private fun action(
        type: String,
        selectorValue: String?,
        value: String? = null,
    ) = RpaActionConfig(
        name = "Step",
        action_type = "default",
        type = type,
        selector = SelectorInfo(type = "css", value = selectorValue, isUnique = true),
        value = value,
    )

    /** A temp directory per test: the first version of this suite wrote into the real one. */
    private val configDir: File = java.nio.file.Files.createTempDirectory("rpa-handoff").toFile()

    private fun written(instruction: String, actions: List<RpaActionConfig>): File {
        val file = RpaEngineHandoff.write(instruction, actions, configDir)
        return assertNotNull(file, "the handoff wrote nothing")
    }

    @kotlin.test.AfterTest
    fun cleanUp() {
        configDir.deleteRecursively()
    }

    @Test
    fun `the written config uses the engine's envelope and field names`() {
        val file = written("search for cats", listOf(action("input", "input[name='q']", "cats")))

        val root = Json.parseToJsonElement(file.readText()).jsonObject

        // The engine's envelope, not this plugin's.
        assertNotNull(root["actions"], "no 'actions' array: the engine reads that key")
        assertEquals(null, root["configuration"], "left this plugin's own envelope key in place")
        val first = root["actions"]!!.jsonArray.first().jsonObject
        // camelCase, not the snake_case this plugin and the model use.
        assertEquals("default", first["actionType"]?.jsonPrimitive?.content, "actionType missing")
        assertEquals(null, first["action_type"], "wrote the plugin-side field name")
    }

    @Test
    fun `selectors survive the translation verbatim`() {
        // Quotes in selectors are the norm, and the engine escapes them on the way into
        // JavaScript - so they must arrive here unmangled.
        val selector = "input[name='q']"
        val file = written("search", listOf(action("input", selector, "cats")))

        val first = Json.parseToJsonElement(file.readText()).jsonObject["actions"]!!
            .jsonArray.first().jsonObject

        assertEquals(selector, first["selector"]!!.jsonObject["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every action is carried through in order`() {
        val plan = listOf(
            action("navigate", null, "https://www.google.com"),
            action("input", "input[name='q']", "cat images"),
            action("keypress", "input[name='q']", "Enter"),
        )

        val file = written("cats", plan)

        val types = Json.parseToJsonElement(file.readText()).jsonObject["actions"]!!
            .jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("navigate", "input", "keypress"), types)
    }

    @Test
    fun `the filename cannot escape the config directory`() {
        // The name comes from a user instruction, so it must not be able to name a path.
        val file = written("../../etc/passwd and \"quotes\"", listOf(action("wait", null, "100")))

        assertTrue(file.name.startsWith("llm-rpa-"), "unexpected name: ${file.name}")
        assertTrue(!file.name.contains(".."), "traversal survived: ${file.name}")
        assertTrue(!file.name.contains('/'), "separator survived: ${file.name}")
        assertEquals(configDir.canonicalPath, file.parentFile.canonicalPath)
    }

    @Test
    fun `a blank instruction still yields a usable filename`() {
        val file = written("   ", listOf(action("wait", null, "100")))

        assertTrue(file.name.startsWith("llm-rpa-plan-"), "unusable name: ${file.name}")
        assertTrue(file.name.endsWith(".json"), "not a json file: ${file.name}")
    }

    @Test
    fun `two instructions that truncate alike get different files`() {
        // The slug is capped, so these share every character it keeps. Without the hash the
        // second write silently replaces the first plan in the user's engine list.
        val a = written(
            "open gmail and search for all the invoices from january last year",
            listOf(action("navigate", null, "https://mail.google.com")),
        )
        val b = written(
            "open gmail and search for all the invoices from february last year",
            listOf(action("navigate", null, "https://mail.google.com")),
        )

        assertTrue(a.name != b.name, "both instructions wrote to ${a.name}")
        assertTrue(a.exists() && b.exists(), "one file replaced the other")
    }

    @Test
    fun `re-running the same instruction overwrites its own file`() {
        val first = written("open gmail", listOf(action("navigate", null, "https://a.example")))
        val second = written("open gmail", listOf(action("navigate", null, "https://b.example")))

        assertEquals(first.name, second.name, "the same instruction should reuse its file")
        assertEquals(1, configDir.listFiles()?.size, "left a duplicate behind")
        assertTrue(second.readText().contains("b.example"), "did not overwrite the contents")
    }

    @Test
    fun `a truncated slug does not end on a separator`() {
        // Chosen so the 40-character cut lands exactly on a hyphen: the slug of this instruction
        // is "open-the-reporting-dashboard-and-export-|every". Trimming *before* truncating
        // leaves that hyphen in the filename.
        val file = written(
            "open the reporting dashboard and export every",
            listOf(action("navigate", null, "https://a.example")),
        )

        val slug = file.name.removePrefix("llm-rpa-").substringBeforeLast("-")
        assertTrue(!slug.endsWith("-"), "cut landed on a separator: ${file.name}")
    }

    @Test
    fun `the envelope carries the instruction as name and description`() {
        val file = written("search for cats", listOf(action("wait", null, "100")))

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("search for cats", root["name"]?.jsonPrimitive?.content)
        assertTrue(
            root["description"]?.jsonPrimitive?.content?.contains("search for cats") == true,
            "description does not name the instruction: ${root["description"]}",
        )
    }

    @Test
    fun `meta is passed through`() {
        val withMeta = action("click", "[name='q']").copy(meta = mapOf("note" to "kept"))
        val file = written("click something", listOf(withMeta))

        val first = Json.parseToJsonElement(file.readText()).jsonObject["actions"]!!
            .jsonArray.first().jsonObject
        assertEquals("kept", first["meta"]?.jsonObject?.get("note")?.jsonPrimitive?.content)
    }

    @Test
    fun `writeResult reports the reason instead of only null`() {
        // A file where the directory must go: mkdirs cannot create it, so the write must fail
        // with something the caller can show, not a bare null.
        val blocker = File(configDir, "blocked").apply { writeText("not a directory") }

        val result = RpaEngineHandoff.writeResult("anything", listOf(action("wait", null, "1")), blocker)

        assertTrue(result.isFailure, "writing into a file-as-directory should not succeed")
        assertNotNull(result.exceptionOrNull(), "no cause to report to the user")
        assertEquals(null, RpaEngineHandoff.write("anything", listOf(action("wait", null, "1")), blocker))
    }
}

/**
 * An unparseable reply must yield no actions.
 *
 * The parser used to fabricate a single `wait 1000` step on a parse failure. Every caller then
 * treated that as a plan: the panel showed READY and the handoff wrote it to disk as a
 * configuration the engine would run. A run of "your automation" that waits one second and
 * reports success is worse than a visible failure.
 */
class ParseFailureTest {

    @Test
    fun `an unparseable reply produces no actions`() {
        val response = LlmApiClient.parseForTest("this is not json at all")

        assertEquals("error", response.status)
        assertTrue(
            response.configuration.isEmpty(),
            "fabricated ${response.configuration.size} action(s): ${response.configuration}",
        )
    }

    @Test
    fun `a well-formed reply is parsed`() {
        val reply = """
            {"configuration":[{"name":"Go","action_type":"default","type":"navigate",
            "selector":{"type":"none","value":null,"isUnique":true},
            "value":"https://example.com","meta":{}}],"status":"success","message":"ok"}
        """.trimIndent()

        val response = LlmApiClient.parseForTest(reply)

        assertEquals("success", response.status)
        assertEquals(1, response.configuration.size)
        assertEquals("navigate", response.configuration.first().type)
    }
}
