package ai.rever.boss.plugin.dynamic.llmrpa

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
 * LLM API Client for making calls to various LLM providers
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

    suspend fun callLLMApi(request: LLMRpaRequest): LLMRpaResponse {
        // Load settings
        LLMSettings.loadSettings()

        // Check if we have a valid API key
        if (!LLMSettings.hasValidApiKey()) {
            return createMockResponse(request)
        }

        val provider = LLMSettings.selectedProvider
        val modelId = LLMSettings.selectedModelId
        val apiKey = LLMSettings.getApiKey(provider) ?: return createMockResponse(request)

        return try {
            withContext(Dispatchers.IO) {
                when (provider) {
                    LLMProvider.ANTHROPIC -> callAnthropicApi(request, apiKey, modelId)
                    LLMProvider.OPENAI -> callOpenAIApi(request, apiKey, modelId)
                    LLMProvider.TOGETHER -> callTogetherApi(request, apiKey, modelId)
                    LLMProvider.CUSTOM -> callCustomApi(request, apiKey)
                }
            }
        } catch (e: Exception) {
            LLMRpaResponse(
                configuration = emptyList(),
                status = "error",
                message = "API call failed: ${e.message}"
            )
        }
    }

    private suspend fun callAnthropicApi(
        request: LLMRpaRequest,
        apiKey: String,
        modelId: String
    ): LLMRpaResponse {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("max_tokens", JsonPrimitive(LLMSettings.maxTokens))
            put("temperature", JsonPrimitive(LLMSettings.temperature))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(buildPrompt(request)))
                }
            }
        }

        val response = httpClient.post("https://api.anthropic.com/v1/messages") {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", "2023-06-01")
            }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Anthropic API error: ${response.status} - $errorBody")
        }

        val responseBody = response.body<JsonObject>()
        val content = responseBody["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: throw Exception("Invalid response format")

        return parseRpaResponse(content)
    }

    private suspend fun callOpenAIApi(
        request: LLMRpaRequest,
        apiKey: String,
        modelId: String
    ): LLMRpaResponse {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("temperature", JsonPrimitive(LLMSettings.temperature))
            put("max_tokens", JsonPrimitive(LLMSettings.maxTokens))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive("You are an RPA assistant that generates browser automation actions."))
                }
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(buildPrompt(request)))
                }
            }
        }

        val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
            headers {
                append("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            throw Exception("OpenAI API error: ${response.status}")
        }

        val responseBody = response.body<JsonObject>()
        val content = responseBody["choices"]?.jsonArray?.get(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw Exception("Invalid response format")

        return parseRpaResponse(content)
    }

    private suspend fun callTogetherApi(
        request: LLMRpaRequest,
        apiKey: String,
        modelId: String
    ): LLMRpaResponse {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("temperature", JsonPrimitive(LLMSettings.temperature))
            put("max_tokens", JsonPrimitive(LLMSettings.maxTokens))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive("You are an RPA assistant that generates browser automation actions."))
                }
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(buildPrompt(request)))
                }
            }
        }

        val response = httpClient.post("https://api.together.xyz/v1/chat/completions") {
            headers {
                append("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            throw Exception("Together AI API error: ${response.status}")
        }

        val responseBody = response.body<JsonObject>()
        val content = responseBody["choices"]?.jsonArray?.get(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw Exception("Invalid response format")

        return parseRpaResponse(content)
    }

    private suspend fun callCustomApi(
        request: LLMRpaRequest,
        apiKey: String
    ): LLMRpaResponse {
        val endpoint = LLMSettings.getCustomEndpoint()
        if (endpoint.isBlank()) {
            throw Exception("Custom endpoint not configured")
        }

        val response = httpClient.post(endpoint) {
            headers {
                append("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw Exception("Custom API error: ${response.status}")
        }

        return response.body<LLMRpaResponse>()
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

    private fun createMockResponse(request: LLMRpaRequest): LLMRpaResponse {
        val instruction = request.actions.firstOrNull()?.instruction ?: "wait"
        
        return LLMRpaResponse(
            configuration = listOf(
                RpaActionConfig(
                    name = "Example: $instruction",
                    action_type = "default",
                    type = "wait",
                    selector = SelectorInfo(type = "none", value = null),
                    value = "1000",
                    meta = mapOf("note" to "Configure API key in Settings > LLM Providers")
                )
            ),
            status = "success",
            message = "Mock response - configure an LLM API key to generate real actions"
        )
    }

    fun dispose() {
        httpClient.close()
    }
}
