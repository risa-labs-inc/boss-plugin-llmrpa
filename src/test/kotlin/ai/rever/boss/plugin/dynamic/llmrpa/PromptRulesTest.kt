package ai.rever.boss.plugin.dynamic.llmrpa

import kotlin.test.Test
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
    fun `every verb the engine implements is offered`() {
        listOf(
            "navigate", "click", "input", "select", "wait", "scroll", "keypress", "submit",
            "assert", "run_script",
        ).forEach {
            assertTrue(prompt.contains(it), "verb '$it' is not offered to the model")
        }
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
