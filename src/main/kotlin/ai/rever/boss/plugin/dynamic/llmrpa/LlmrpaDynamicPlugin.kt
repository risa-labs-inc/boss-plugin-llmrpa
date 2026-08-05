package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

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
        // Credentials, endpoint and model all come from the secret-manager plugin. Captured
        // here rather than per-panel because the relay itself is stable; what changes is the
        // config behind it, which is why every caller re-reads activeConfig() instead of
        // caching an LlmConfig. Null when the provider plugin is absent or disabled, and under
        // BOSS_MODE=KERNEL, where the microkernel's RemotePluginContext has no llmProvider
        // proxy yet.
        val llmProvider = context.llmProvider
        // So the panel can send the user straight to where keys now live. Both may be null;
        // the panel falls back to naming the path in text.
        val settingsProvider = context.settingsProvider
        val windowId = context.windowId

        context.panelRegistry.registerPanel(LlmrpaInfo) { ctx, panelInfo ->
            LlmrpaComponent(
                ctx,
                panelInfo,
                activeTabsProvider,
                llmProvider,
                settingsProvider,
                windowId,
            ).also { comp ->
                lastComponent = comp
                // Clear on panel close: a destroyed component's scope is cancelled,
                // so MCP tools driving it would silently no-op with false success.
                ctx.lifecycle.doOnDestroy { if (lastComponent === comp) lastComponent = null }
            }
        }

        // Contribute llmrpa_status/run MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(LlmrpaMcpToolProvider(pluginId) { lastComponent })
    }

    override fun dispose() {
        lastComponent = null
    }
}
