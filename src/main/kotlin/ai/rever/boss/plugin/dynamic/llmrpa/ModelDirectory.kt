package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One model the runner can use. A DECISION model (Jev) only picks among options; a CHAT model
 * is any provider configured in Settings → AI Providers, reached through the AI Gateway.
 */
data class ModelOption(
    val kind: Kind,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val label: String = modelId,
) {
    enum class Kind { DECISION, CHAT }

    val key: String get() = "${kind.name}:$providerId:$modelId"
}

data class ModelGroup(val providerName: String, val kind: ModelOption.Kind, val models: List<ModelOption>, val note: String? = null)

/**
 * Lists every model the user can pick: Jev's models (from the `jev_decide` tool's own schema,
 * so a new Jev model shows up without a change here) and each configured AI provider's models
 * (from the AI Gateway's catalog).
 */
class ModelDirectory(
    private val tools: ToolInvoker,
    private val llmProvider: () -> LlmProvider?,
    private val gateway: () -> AiGatewayAPI?,
) {
    fun load(): List<ModelGroup> = buildList {
        jevGroup()?.let(::add)
        addAll(chatGroups())
    }

    private fun jevGroup(): ModelGroup? {
        if (!tools.has(ToolNames.JEV_DECIDE)) return null
        val ids = jevModelIds(tools.inputSchema(ToolNames.JEV_DECIDE)).ifEmpty { listOf(JEV_DEFAULT_MODEL) }
        return ModelGroup(
            providerName = "Jev",
            kind = ModelOption.Kind.DECISION,
            models = ids.map { ModelOption(ModelOption.Kind.DECISION, JEV_PROVIDER, "Jev", it, it.substringAfter('/')) },
            note = "Decision model · picks each step",
        )
    }

    /**
     * Chat models from the gateway's own catalog ([AiGatewayAPI.availableModels], credential-free),
     * then the provider plugin's, then each configured provider's selected model. Each call is
     * guarded: on a host older than the api that added it, crossing the boundary throws
     * `NoSuchMethodError` rather than returning empty.
     */
    private fun chatGroups(): List<ModelGroup> {
        val catalog = runCatching { gateway()?.availableModels().orEmpty() }.getOrDefault(emptyList())
            .ifEmpty { runCatching { llmProvider()?.availableModels().orEmpty() }.getOrDefault(emptyList()) }
        val selected = runCatching { llmProvider()?.configuredProviders().orEmpty() }.getOrDefault(emptyList())
            .associate { it.providerId to it.modelId }
        if (catalog.isNotEmpty()) {
            return catalog.mapNotNull { p ->
                val chosen = selected[p.providerId]
                val listed = p.models.map { ModelOption(ModelOption.Kind.CHAT, p.providerId, p.providerName, it.id, it.displayName.ifBlank { it.id }) }
                // The model already selected for this provider in Settings goes first.
                val models = (listed.filter { it.modelId == chosen } + listed.filter { it.modelId != chosen })
                    .ifEmpty { chosen?.let { listOf(ModelOption(ModelOption.Kind.CHAT, p.providerId, p.providerName, it)) }.orEmpty() }
                models.takeIf { it.isNotEmpty() }?.let { ModelGroup(p.providerName, ModelOption.Kind.CHAT, it) }
            }
        }
        val configured = runCatching { llmProvider()?.configuredProviders().orEmpty() }.getOrDefault(emptyList())
        if (configured.isNotEmpty()) {
            return configured.map { cfg ->
                ModelGroup(cfg.displayName, ModelOption.Kind.CHAT, listOf(ModelOption(ModelOption.Kind.CHAT, cfg.providerId, cfg.displayName, cfg.modelId)))
            }
        }
        val active = runCatching { gateway()?.activeModel() }.getOrNull() ?: return emptyList()
        return listOf(
            ModelGroup(active.providerName, ModelOption.Kind.CHAT, listOf(ModelOption(ModelOption.Kind.CHAT, active.providerId, active.providerName, active.modelId))),
        )
    }

    companion object {
        const val JEV_PROVIDER = "JEV"
        const val JEV_DEFAULT_MODEL = "typesafe/jev-1.13"

        /** The `model` enum from `jev_decide`'s input schema. */
        internal fun jevModelIds(schema: String?): List<String> = runCatching {
            val root = Json.parseToJsonElement(schema ?: return emptyList()) as JsonObject
            val model = ((root["properties"] as JsonObject)["model"] as JsonObject)
            (model["enum"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())

    }
}
