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
                onSuccess = { reply -> parseReply(reply.text) },
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

    /**
     * Build the generation prompt.
     *
     * **The verb list and the selector-type set below are a cross-repo contract.** They mirror
     * `ActionTypes` and `SelectorTypes` in boss-plugin-rpaengine
     * (`RpaEngineTypes.kt`, read at rpaengine 1.2.0) and its `executeRealAction` dispatch. Nothing
     * here can pin them: when the engine gains a verb this prompt silently keeps withholding it,
     * and when it renames one, plans fail that step inside the *other* plugin. Check that file
     * when either list looks wrong.
     */
    internal fun buildPrompt(request: LLMRpaRequest): String {
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
            // NOT "success": runnablePlan() is the sole authority for "write this to the engine's
            // directory and tell the user it is ready to run", and this satisfied it. A plan named
            // after the user's instruction landed on disk, the green card said "open it to load and
            // run this plan", and running it waited one second and reported success - the same
            // failure the fabricated parse-error action was removed for. Reaching it needs the
            // gateway or provider to disappear between generateActions' aiAvailable() check and
            // this call, which is a TOCTOU window rather than an impossibility.
            status = STATUS_EXAMPLE,
            message = "Example response - configure an AI provider to generate real actions"
        )
    }

    companion object {
        /** Status of the placeholder response served when no provider is configured. */
        internal const val STATUS_EXAMPLE = "example"

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
        /**
         * The first complete JSON object in [content], or null if there is none.
         *
         * The previous `\{[\s\S]*\}` ran from the first brace to the **last** one in the whole
         * reply. Reasoning models routinely close the JSON, close a code fence, and keep talking -
         * GLM produced `...}\n```\n\nHmm, wait. Let me reconsider...` - so the greedy match
         * swallowed the trailing prose and the parse died at the far end of it. Every action was
         * present and correct and the whole generation was thrown away.
         *
         * Counts braces at depth, skipping anything inside a string literal (and whatever follows a
         * backslash there), so a brace in a selector value cannot end the object early.
         */
        internal fun firstJsonObject(content: String): String? {
            val start = content.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until content.length) {
                val c = content[i]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) return content.substring(start, i + 1)
                    }
                }
            }
            return null
        }

        internal fun parseReply(content: String): LLMRpaResponse =
            try {
                json.decodeFromString<LLMRpaResponse>(firstJsonObject(content) ?: content)
            } catch (e: Exception) {
                LLMRpaResponse(
                    configuration = emptyList(),
                    status = "error",
                    message = "Failed to parse LLM response: ${e.message}",
                )
            }

        const val SYSTEM_PROMPT =
            "You are an RPA assistant that generates browser automation actions."
    }
}
