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

        assertEquals("llm-rpa-plan.json", file.name)
    }
}
