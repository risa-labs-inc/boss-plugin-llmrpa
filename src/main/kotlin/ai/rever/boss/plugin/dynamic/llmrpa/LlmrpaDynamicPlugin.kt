package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * LLM RPA dynamic plugin - Loaded from external JAR.
 *
 * AI-powered robotic process automation
 */
class LlmrpaDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.llmrpa"
    override val displayName: String = "LLM RPA (Dynamic)"
    override val version: String = "1.0.0"
    override val description: String = "AI-powered robotic process automation"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-llmrpa"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(LlmrpaInfo) { ctx, panelInfo ->
            LlmrpaComponent(ctx, panelInfo)
        }
    }
}
