package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * LLM RPA dynamic plugin - Loaded from external JAR.
 *
 * AI-powered robotic process automation with LLM integration.
 */
class LlmrpaDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.llmrpa"
    override val displayName: String = "LLM RPA (Dynamic)"
    override val version: String = "1.0.5"
    override val description: String = "AI-powered robotic process automation"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-llmrpa"

    // Most recently created panel component, so MCP tools can drive generation.
    @Volatile
    private var lastComponent: LlmrpaComponent? = null

    override fun register(context: PluginContext) {
        val activeTabsProvider = context.activeTabsProvider

        context.panelRegistry.registerPanel(LlmrpaInfo) { ctx, panelInfo ->
            LlmrpaComponent(ctx, panelInfo, activeTabsProvider).also { lastComponent = it }
        }

        // Contribute llmrpa_status/run MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(LlmrpaMcpToolProvider(pluginId) { lastComponent })
    }

    override fun dispose() {
        lastComponent = null
    }
}
