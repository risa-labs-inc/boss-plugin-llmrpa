package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
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
    // From the manifest, which processResources syncs from build.gradle.kts. Hardcoded, this said
    // 1.0.5 while the build said 1.2.0 - the resource filter does not touch Kotlin sources - and
    // this is the value the class reports at runtime. Only the manifest naming this plugin id is
    // accepted: every BOSS plugin ships plugin.json at the same resource path.
    override val version: String = manifestVersion()
    override val description: String = "AI-powered robotic process automation"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-llmrpa"

    // Most recently created panel component, so MCP tools can drive generation.
    @Volatile
    private var lastComponent: LlmrpaComponent? = null

    override fun register(context: PluginContext) {
        val activeTabsProvider = context.activeTabsProvider
        // AI goes through the shared AI Gateway plugin, which owns the wire formats and
        // resolves the active provider itself. A lambda, not a resolved instance: plugin
        // load order is not guaranteed, so reading it here would cache whatever was
        // registered at this moment - usually null. Also null under BOSS_MODE=KERNEL,
        // where the microkernel's RemotePluginContext has no plugin-API proxy yet.
        val aiGateway = { context.getPluginAPI(AiGatewayAPI::class.java) }
        // So the panel can send the user straight to where keys now live. Both may be null;
        // the panel falls back to naming the path in text.
        val settingsProvider = context.settingsProvider
        val windowId = context.windowId

        context.panelRegistry.registerPanel(LlmrpaInfo) { ctx, panelInfo ->
            LlmrpaComponent(
                ctx,
                panelInfo,
                activeTabsProvider,
                aiGateway,
                // The dialog that names whichever thing is missing and opens the fix.
                { feature ->
                    ai.rever.boss.plugin.api.AiAvailability.promptToFix(context, feature) ==
                        ai.rever.boss.plugin.api.AiReadiness.READY
                },
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

    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
