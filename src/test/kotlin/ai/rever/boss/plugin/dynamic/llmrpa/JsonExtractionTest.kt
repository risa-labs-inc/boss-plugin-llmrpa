package ai.rever.boss.plugin.dynamic.llmrpa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LlmApiClient.firstJsonObject] is what makes generation survive a reasoning model.
 *
 * The previous extraction ran from the first brace to the **last** one anywhere in the reply, so a
 * model that closed the JSON, closed a code fence and kept thinking took the whole generation down
 * with it - every action present and correct, and discarded.
 */
class JsonExtractionTest {

    @Test
    fun `stops at the end of the first object, not the last brace in the reply`() {
        // The exact shape GLM produced, which failed with "Expected EOF after parsing".
        val reply = """
            ```json
            {"configuration":[],"status":"success","message":"ok"}
            ```

            Hmm, wait. Let me reconsider {this} part.
        """.trimIndent()

        assertEquals(
            """{"configuration":[],"status":"success","message":"ok"}""",
            LlmApiClient.firstJsonObject(reply),
        )
    }

    @Test
    fun `keeps nested objects whole`() {
        val reply = """prose {"a":{"b":{"c":1}},"d":2} more prose"""

        assertEquals("""{"a":{"b":{"c":1}},"d":2}""", LlmApiClient.firstJsonObject(reply))
    }

    @Test
    fun `a brace inside a string does not end the object`() {
        // A real selector value: [role='main'] img is fine, but a plan can carry braces too.
        val reply = """{"selector":"div{a}","status":"success"}"""

        assertEquals(reply, LlmApiClient.firstJsonObject(reply))
    }

    @Test
    fun `an escaped quote does not open a string`() {
        val reply = """{"message":"he said \"}\" and stopped","status":"success"}"""

        assertEquals(reply, LlmApiClient.firstJsonObject(reply))
    }

    @Test
    fun `no object yields null`() {
        assertNull(LlmApiClient.firstJsonObject("no json here at all"))
        assertNull(LlmApiClient.firstJsonObject(""))
    }

    @Test
    fun `an unterminated object yields null rather than a truncated one`() {
        assertNull(LlmApiClient.firstJsonObject("""{"configuration":[ """))
    }

    @Test
    fun `a full reply with trailing reasoning still parses into actions`() {
        val reply = """
            {"configuration":[{"name":"Go","action_type":"default","type":"navigate",
            "selector":{"type":"none","value":null,"isUnique":true},
            "value":"https://example.com","meta":{}}],"status":"success","message":"ok"}

            Actually, let me reconsider whether {role='main'} is right.
        """.trimIndent()

        val response = LlmApiClient.parseReply(reply)

        assertEquals("success", response.status)
        assertEquals(1, response.configuration.size)
        assertTrue(response.runnablePlan() != null, "the plan should be runnable")
    }
}

/**
 * Two more ways a correct plan used to be discarded.
 *
 * Both are the same shape as the greedy-brace bug: the reply contains everything needed, and a
 * detail of how it is read throws it away.
 */
class ParseRobustnessTest {

    private fun reply(status: String, before: String = "") = """
        $before{"configuration":[{"name":"Go","action_type":"default","type":"navigate",
        "selector":{"type":"none","value":null,"isUnique":true},
        "value":"https://example.com","meta":{}}],"status":"$status","message":"ok"}
    """.trimIndent()

    @Test
    fun `a capitalised status is still a success`() {
        // The prompt asks for "success", but a reasoning model drifts - which is the premise of
        // the extraction fix. Downstream compares exactly, so "Success" discarded the whole plan.
        listOf("success", "Success", "SUCCESS", " success ").forEach { spelling ->
            val response = LlmApiClient.parseReply(reply(spelling))
            assertEquals("success", response.status, "status '$spelling' was not normalised")
            assertTrue(response.runnablePlan() != null, "plan lost for status '$spelling'")
        }
    }

    @Test
    fun `braced prose before the json does not lose the plan`() {
        // firstJsonObject anchors on the first brace anywhere, so an aside like this used to hand
        // back a slice that cannot decode.
        val response = LlmApiClient.parseReply(reply("success", "Let me think about {this} first.\n\n"))

        assertEquals("success", response.status)
        assertEquals(1, response.configuration.size)
    }

    @Test
    fun `an unparseable reply still reports a parse error`() {
        val response = LlmApiClient.parseReply("{not json} and {also not json}")

        assertEquals("error", response.status)
        assertTrue(response.configuration.isEmpty())
    }
}

/**
 * Fields a model plausibly omits must not discard the plan.
 *
 * kotlinx treats a nullable field with no default as **required**, so `"selector":{"type":"none"}`
 * or an action with no `selector` at all threw `MissingFieldException` and the reply - carrying a
 * complete, correct plan - was reported as a parse error.
 */
class MissingFieldToleranceTest {

    private fun parse(actionJson: String) =
        LlmApiClient.parseReply("""{"configuration":[$actionJson],"status":"success","message":"ok"}""")

    @Test
    fun `a selector with only a type is accepted`() {
        val response = parse("""{"name":"Wait","type":"wait","selector":{"type":"none"},"value":"1000"}""")

        assertEquals("success", response.status)
        assertEquals(1, response.configuration.size)
        assertTrue(response.runnablePlan() != null)
    }

    @Test
    fun `an action with no selector at all is accepted`() {
        // Normal output for navigate, wait and submit.
        val response = parse("""{"name":"Go","type":"navigate","value":"https://example.com"}""")

        assertEquals(1, response.configuration.size)
        assertEquals("none", response.configuration.first().selector.type)
    }

    @Test
    fun `an action with no name is accepted`() {
        val response = parse("""{"type":"wait","value":"500"}""")

        assertEquals(1, response.configuration.size)
    }

    @Test
    fun `an action with no type is still a parse error`() {
        // The verb is the one field an action cannot do without.
        val response = parse("""{"name":"Mystery","value":"1000"}""")

        assertEquals("error", response.status)
        assertTrue(response.configuration.isEmpty())
    }

    @Test
    fun `an unrecognised success-ish status is runnable`() {
        // "ok", "completed", "done" are all plausible from a model told to write "success".
        listOf("ok", "completed", "done").forEach { status ->
            val response = LlmApiClient.parseReply(
                """{"configuration":[{"name":"Go","type":"navigate","value":"https://a.example"}],""" +
                    """"status":"$status","message":"m"}""",
            )
            assertTrue(response.runnablePlan() != null, "status '$status' lost the plan")
        }
    }

    @Test
    fun `a failure status is still not runnable`() {
        val response = LlmApiClient.parseReply(
            """{"configuration":[{"name":"Go","type":"navigate","value":"https://a.example"}],""" +
                """"status":"error","message":"m"}""",
        )
        assertEquals(null, response.runnablePlan())
    }
}
