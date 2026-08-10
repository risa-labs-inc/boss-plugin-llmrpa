package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import kotlinx.serialization.json.Json

/**
 * Turns a natural-language instruction into RPA actions.
 *
 * Prompt building and response parsing live here; the request itself goes through the
 * shared **AI Gateway** plugin, so this file names no provider, no endpoint and no wire
 * format. It used to carry a Ktor client and four per-format request builders - the third
 * copy of that code in the workspace - and each copy had to independently know that
 * `LlmApiFormat` is an open set needing an `else`.
 *
 * This plugin still owns no credentials. Before the provider stack existed it kept four
 * keys in plaintext in `~/.boss/config/llm-settings.json`, rewritten on every keystroke of
 * its own API-key field.
 *
 * [gateway] is a lambda, not a captured instance: plugin load order is not guaranteed, so
 * resolving the API once would cache a null forever.
 */
class LlmApiClient(
    // No default. A call site that forgets to pass the gateway would otherwise be
    // indistinguishable at runtime from "no gateway installed" - the panel would serve
    // example responses forever - so the compiler enforces the wiring instead.
    private val gateway: () -> AiGatewayAPI?,
) {

    /**
     * Generate actions for [request].
     *
     * No gateway or no configured provider yields the example response rather than an
     * error, so the panel stays usable while the user goes and sets a provider up.
     */
    suspend fun callLLMApi(request: LLMRpaRequest): LLMRpaResponse {
        val api = gateway() ?: return createUnconfiguredResponse(request)
        if (api.activeModel() == null) return createUnconfiguredResponse(request)

        return api
            .complete(
                AiRequest(
                    system = SYSTEM_PROMPT,
                    messages = listOf(AiMessage.user(buildPrompt(request))),
                ),
            ).fold(
                onSuccess = { reply -> parseRpaResponse(reply.text) },
                onFailure = { error ->
                    LLMRpaResponse(
                        configuration = emptyList(),
                        status = "error",
                        // The gateway's messages are already written for a person, so they
                        // are shown rather than wrapped in another layer of prose.
                        message = error.message ?: "The AI request failed.",
                    )
                },
            )
    }

    private fun buildPrompt(request: LLMRpaRequest): String {
        val instructions = request.actions.joinToString("\n") { "- ${it.instruction}" }

        return """
Generate RPA browser automation actions for the following instructions:

Instructions:
$instructions

Source URL: ${request.sourceUrl}

Return the response as a JSON object with the following structure:
{
    "configuration": [
        {
            "name": "Action description",
            "action_type": "default",
            "type": "action_type",
            "selector": {
                "type": "css|xpath|id|text|none",
                "value": "selector_value_or_null",
                "isUnique": true
            },
            "value": "value_if_needed",
            "meta": {}
        }
    ],
    "status": "success",
    "message": "Explanation of what the actions do"
}

Available action types: navigate, click, input, select, wait, scroll, keypress, submit, assert, run_script

Use ONLY those types. The RPA Engine that executes this plan implements exactly these; anything
else fails the step.

Selector guidelines:
- Selector "type" must be one of: css, xpath, id, text, none
- Use CSS selectors over XPath when possible
- Write attribute-only CSS, not tag-qualified: [name='q'], never input[name='q'].
  The tag is the part most often wrong (a search box may be a textarea, not an input),
  while the attribute holds.
- For a link, tab or button identified by its visible label, use a "text" selector with the
  label as the value. Do NOT match on URL query parameters (href*='...'): those change
  without notice, and the visible label does not.
- Never target a site's internal data-* attributes (data-ils, data-ved, data-atf and the like).
  They are build artifacts and change constantly. Prefer ARIA landmarks and roles: the first
  image in a results page is [role='main'] img, not a data-* guess.
- For search fields, prefer name or id attributes
- Use "input" type for typing text
- To run a search, use "keypress" with value "Enter" on the search field, or "submit" on it
- Follow any navigation or submit with a "wait" action before selecting from the new page

Provide only the JSON response without additional text.
        """.trimIndent()
    }

    private fun parseRpaResponse(content: String): LLMRpaResponse = parseReply(content)

    private fun createUnconfiguredResponse(request: LLMRpaRequest): LLMRpaResponse {
        val instruction = request.actions.firstOrNull()?.instruction ?: "wait"

        return LLMRpaResponse(
            configuration = listOf(
                RpaActionConfig(
                    name = "Example: $instruction",
                    action_type = "default",
                    type = "wait",
                    selector = SelectorInfo(type = "none", value = null),
                    value = "1000",
                    meta = mapOf("note" to "Configure a provider in Settings > AI Providers")
                )
            ),
            status = "success",
            message = "Example response — configure an AI provider to generate real actions"
        )
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /**
         * Parse an LLM reply into a response, yielding **no actions** when it cannot be read.
         *
         * On the companion so a test can reach it without constructing a client (which needs a
         * gateway). It used to fabricate a single `wait 1000` step on failure, and every caller
         * treated that as a plan: the panel showed READY and the handoff wrote it to disk as a
         * configuration the engine would run.
         */
        internal fun parseReply(content: String): LLMRpaResponse =
            try {
                val jsonMatch = Regex("""\{[\s\S]*\}""").find(content)
                json.decodeFromString<LLMRpaResponse>(jsonMatch?.value ?: content)
            } catch (e: Exception) {
                LLMRpaResponse(
                    configuration = emptyList(),
                    status = "error",
                    message = "Failed to parse LLM response: ${e.message}",
                )
            }

        /** Test seam for [parseReply]. */
        internal fun parseForTest(content: String): LLMRpaResponse = parseReply(content)

        const val SYSTEM_PROMPT =
            "You are an RPA assistant that generates browser automation actions."
    }
}
