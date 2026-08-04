package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Turns a natural-language instruction into RPA actions using whichever AI provider the user
 * configured in **Settings → AI Providers**.
 *
 * This plugin owns no credentials. Every call is driven by an [LlmConfig] resolved by the
 * secret-manager plugin through `PluginContext.llmProvider`, which supplies the key, the
 * endpoint, the model and the sampling parameters. Before this, llmrpa kept four provider keys
 * in plaintext in `~/.boss/config/llm-settings.json` and rewrote that file on every keystroke
 * of its own API-key field.
 *
 * [LlmConfig.apiFormat] selects the wire format. Note that `CUSTOM` is now an
 * OpenAI-compatible chat endpoint (that is what the provider registry defines it as), not the
 * bespoke "POST an LLMRpaRequest, get an LLMRpaResponse back" protocol the old custom branch
 * spoke.
 */
class LlmApiClient {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Generate actions for [request] using [config].
     *
     * A null [config] means no provider is configured (or the provider plugin has not finished
     * loading credentials yet) — the caller gets the example response rather than an error, so
     * the panel stays usable while the user goes and sets a key up.
     */
    suspend fun callLLMApi(config: LlmConfig?, request: LLMRpaRequest): LLMRpaResponse {
        if (config == null || config.apiKey.isBlank()) return createUnconfiguredResponse(request)

        return try {
            withContext(Dispatchers.IO) {
                val reply = callProvider(config, buildPrompt(request))
                    ?: return@withContext LLMRpaResponse(
                        configuration = emptyList(),
                        status = "error",
                        message =
                            "${config.displayName} speaks a wire format this plugin does not " +
                                "support (${config.apiFormat}). Update the LLM RPA plugin.",
                    )
                parseRpaResponse(reply)
            }
        } catch (e: Exception) {
            LLMRpaResponse(
                configuration = emptyList(),
                status = "error",
                message = "API call failed: ${e.message}"
            )
        }
    }

    /**
     * Single dispatch point for the wire format; returns the model's reply text, or null when
     * the format is one this plugin cannot speak.
     *
     * [LlmApiFormat] is an **open set** — the host may serve a constant newer than the one this
     * plugin compiled against, so this `when` must keep its `else`. Made exhaustive without one,
     * a new provider format throws `NoWhenBranchMatchedException` mid-request (exactly the bug
     * that shipped in the jupyter-notebook plugin's v1.0.12).
     */
    private suspend fun callProvider(config: LlmConfig, prompt: String): String? =
        when (config.apiFormat) {
            LlmApiFormat.ANTHROPIC_MESSAGES -> callAnthropicApi(config, prompt)
            LlmApiFormat.OPENAI_CHAT -> callOpenAiCompatibleApi(config, prompt)
            LlmApiFormat.GOOGLE_GENERATIVE -> callGoogleApi(config, prompt)
            else -> null
        }

    private suspend fun callAnthropicApi(config: LlmConfig, prompt: String): String {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.modelId))
            put("max_tokens", JsonPrimitive(config.maxTokens))
            put("temperature", JsonPrimitive(config.temperature))
            put("system", JsonPrimitive(SYSTEM_PROMPT))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                }
            }
        }

        val response = httpClient.post(config.baseUrl) {
            headers {
                append("x-api-key", config.apiKey)
                append("anthropic-version", ANTHROPIC_VERSION)
            }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        requireSuccess(response, config)

        val body = response.body<JsonObject>()
        return body["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: throw Exception("Invalid response format from ${config.displayName}")
    }

    /** OpenAI, Together, xAI, Moonshot and any custom OpenAI-compatible endpoint. */
    private suspend fun callOpenAiCompatibleApi(config: LlmConfig, prompt: String): String {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.modelId))
            put("temperature", JsonPrimitive(config.temperature))
            put("max_tokens", JsonPrimitive(config.maxTokens))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(SYSTEM_PROMPT))
                }
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                }
            }
        }

        val response = httpClient.post(config.baseUrl) {
            headers { append("Authorization", "Bearer ${config.apiKey}") }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        requireSuccess(response, config)

        val body = response.body<JsonObject>()
        return body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw Exception("Invalid response format from ${config.displayName}")
    }

    /**
     * Google's `generateContent`. [LlmConfig.baseUrl] already carries the model in its path, so
     * it is not repeated in the payload. The key goes in the `x-goog-api-key` header rather than
     * the `?key=` query parameter Google also accepts, so it cannot reach a log line or an
     * error message.
     */
    private suspend fun callGoogleApi(config: LlmConfig, prompt: String): String {
        val requestBody = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", JsonPrimitive(SYSTEM_PROMPT)) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    putJsonArray("parts") { addJsonObject { put("text", JsonPrimitive(prompt)) } }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", JsonPrimitive(config.temperature))
                put("maxOutputTokens", JsonPrimitive(config.maxTokens))
            }
        }

        val response = httpClient.post(config.baseUrl) {
            headers { append("x-goog-api-key", config.apiKey) }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        requireSuccess(response, config)

        val body = response.body<JsonObject>()
        val parts = body["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: throw Exception("Invalid response format from ${config.displayName}")
        return parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("")
    }

    /**
     * Fail with the status and the provider's own message.
     *
     * The response body is included because a 400 from any of these providers explains what is
     * wrong with the request, and without it the panel could only say "error 400". Provider
     * error envelopes do not echo the credential.
     */
    private suspend fun requireSuccess(response: HttpResponse, config: LlmConfig) {
        if (response.status.isSuccess()) return
        throw Exception("${config.displayName} API error: ${response.status} - ${response.bodyAsText()}")
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

    fun dispose() {
        httpClient.close()
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val SYSTEM_PROMPT =
            "You are an RPA assistant that generates browser automation actions."
    }
}
