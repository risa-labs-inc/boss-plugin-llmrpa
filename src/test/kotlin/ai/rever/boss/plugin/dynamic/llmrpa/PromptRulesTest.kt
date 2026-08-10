package ai.rever.boss.plugin.dynamic.llmrpa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prompt's selector rules are load-bearing: each was added after watching a generated plan
 * fail on a live page, and a stray edit deleting one passes every other test in this suite while
 * quietly restoring the failure. This does not prove the rules *work* - only that they are still
 * being sent.
 */
class PromptRulesTest {

    private val prompt =
        LlmApiClient { null }.buildPrompt(
            LLMRpaRequest(
                actions = listOf(LLMAction("search for cats")),
                sourceUrl = "https://example.com",
            ),
        )

    @Test
    fun `the offered verbs are exactly the ones the engine implements`() {
        // Set equality on the "Available action types:" line, not `contains` over the whole
        // prompt. `contains("select")` matches "CSS selectors", `contains("submit")` matches the
        // submit rule, `contains("input")` matches the typing rule - so deleting those from the
        // verb list left the test green. It survived the mutation it was named for.
        val line =
            prompt.lineSequence()
                .first { it.startsWith("Available action types:") }
                .removePrefix("Available action types:")
        val offered = line.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        assertEquals(
            setOf(
                "navigate", "click", "input", "select", "wait", "scroll", "keypress", "submit",
                "assert", "run_script",
            ),
            offered,
            "the verb list no longer matches the engine's dispatch table",
        )
    }

    @Test
    fun `attribute-only selectors are demanded`() {
        // The model emitted input[name='q'] for a textarea.
        assertTrue(prompt.contains("[name='q']"), "no attribute-only example")
        assertTrue(prompt.contains("never input[name='q']"), "the tag-qualified form is not refused")
    }

    @Test
    fun `text selectors are demanded for labelled links`() {
        // The model emitted a[href*='tbm=isch'] for Google Images, which uses udm=2 now.
        assertTrue(prompt.contains("\"text\" selector"), "no text-selector rule")
        assertTrue(prompt.contains("href*="), "the query-parameter form is not refused")
    }

    @Test
    fun `internal data attributes are refused and ARIA preferred`() {
        // The model emitted [data-ils], which matched nothing.
        assertTrue(prompt.contains("data-*"), "no rule about internal data attributes")
        assertTrue(prompt.contains("[role='main']"), "no ARIA landmark example")
    }

    @Test
    fun `the instruction and source url reach the model`() {
        assertTrue(prompt.contains("search for cats"))
        assertTrue(prompt.contains("https://example.com"))
    }
}
