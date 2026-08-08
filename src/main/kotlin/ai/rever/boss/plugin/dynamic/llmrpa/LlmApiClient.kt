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
    private val gateway: () -> AiGatewayAPI? = { null },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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

Available action types: navigate, click, input, wait, scroll, screenshot, extract, select, hover, rightClick, keypress, submit

Selector guidelines:
- For search fields, prefer name or id attributes
- Use CSS selectors over XPath when possible
- Use "input" type for typing text
- Use "keypress" with value "Enter" for form submission

Provide only the JSON response without additional text.
        """.trimIndent()
    }

    private fun parseRpaResponse(content: String): LLMRpaResponse {
        return try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(content)
            val jsonString = jsonMatch?.value ?: content
            json.decodeFromString<LLMRpaResponse>(jsonString)
        } catch (e: Exception) {
            LLMRpaResponse(
                configuration = listOf(
                    RpaActionConfig(
                        name = "Wait",
                        action_type = "default",
                        type = "wait",
                        selector = SelectorInfo(type = "none", value = null),
                        value = "1000"
                    )
                ),
                status = "error",
                message = "Failed to parse LLM response: ${e.message}"
            )
        }
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
            status = "success",
            message = "Example response — configure an AI provider to generate real actions"
        )
    }


    private companion object {
        const val SYSTEM_PROMPT =
            "You are an RPA assistant that generates browser automation actions."
    }
}
