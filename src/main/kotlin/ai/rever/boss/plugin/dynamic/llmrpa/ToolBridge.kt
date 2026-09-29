package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.McpToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Result of calling another plugin's MCP tool. [json] is the parsed text when it is JSON. */
data class ToolReply(val text: String, val isError: Boolean) {
    val json: JsonObject? by lazy { runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() }

    /** The `{"error":{"message"}}` convention Jev and RPA Engine use, or the raw text. */
    val errorMessage: String
        get() = ((json?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: text
}

/**
 * Calls tools other plugins contribute to the BOSS MCP server: RPA Engine acts on pages, Jev
 * decides, Secret Manager lists models. A seam so the runner can be tested without a host.
 */
interface ToolInvoker {
    fun has(toolName: String): Boolean
    fun inputSchema(toolName: String): String?
    suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply
}

/**
 * [ToolInvoker] over the host registry. The registry is read lazily and every access is
 * guarded: it is absent on hosts older than the api that added it, and crossing the plugin
 * boundary on such a host throws `NoSuchMethodError` rather than returning null.
 */
class RegistryToolInvoker(private val registry: () -> McpToolRegistry?) : ToolInvoker {
    override fun has(toolName: String): Boolean =
        runCatching { registry()?.tools?.value?.any { it.definition.name == toolName } == true }.getOrDefault(false)

    override fun inputSchema(toolName: String): String? =
        runCatching { registry()?.tools?.value?.firstOrNull { it.definition.name == toolName }?.definition?.inputSchema }
            .getOrNull()

    override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
        val r = runCatching { registry() }.getOrNull()
            ?: return ToolReply("This BOSS version does not let plugins call each other's tools", isError = true)
        return try {
            val result = r.invoke(toolName, arguments.toString())
            ToolReply(result.text, result.isError)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            ToolReply("$toolName failed: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }
}

internal object ToolNames {
    const val OBSERVE = "rpa_observe"
    const val STEP = "rpa_step"
    const val JEV_DECIDE = "jev_decide"
}
