package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.EncodedImageFormat

/** Renders the real panel in each state at sidebar and wide widths, for visual review. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PanelRenderTest {
    private val tab = ActiveTabData("t1", "fluck", "Shop · Wireless keyboards", "w", "Work", "p", "win", url = "https://shop.example/search")

    private fun tabs(list: List<ActiveTabData>) = object : ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(list)
        override suspend fun refreshTabs() {}
        override fun selectTab(tabId: String, panelId: String) {}
        override fun getTabUrl(tabId: String): String? = list.firstOrNull { it.tabId == tabId }?.url
        override fun getFaviconCacheKey(tabId: String): String? = null
        @androidx.compose.runtime.Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String): BrowserIntegration? = null
        override fun createBrowserTab(url: String, title: String): String? = null
        override fun closeTab(tabId: String): Boolean = false
    }

    private fun component(tools: ToolInvoker, openTabs: List<ActiveTabData> = listOf(tab)) =
        LlmrpaComponent(DefaultComponentContext(LifecycleRegistry()), LlmrpaInfo, tabs(openTabs), { null }, tools = tools)

    @Test
    fun `renders every state at every width`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TaskRunner.NAV_SETTLE_MS = 0
        TaskRunner.STEP_SETTLE_MS = 0
        try {
            val instruction = "Search for 'wireless keyboard' and open the first result"

            val idle = component(FakeTools { _, _ -> Triple("The task is complete", 0.9, null) })
            listOf(280, 360, 520).forEach { render(idle, it, 900, "idle-$it") }
            idle.updateInstruction(instruction)
            render(idle, 360, 900, "ready-360")

            val asking = component(FakeTools { _, call ->
                if (call == 0) Triple("Type into 'Search shop'", 0.94, 1) else Triple("Open 'Sign in' link", 0.46, null)
            })
            asking.updateInstruction(instruction)
            assertEquals(null, asking.startRun())
            assertEquals(RunStatus.WAITING, asking.run.value?.status)
            listOf(280, 360).forEach { render(asking, it, 1000, "asking-$it") }
            render(asking, 960, 760, "asking-wide-960")

            val done = component(FakeTools { _, call ->
                when (call) {
                    0 -> Triple("Type into 'Search shop'", 0.94, 1)
                    1 -> Triple("Press Enter", 0.88, null)
                    else -> Triple("The task is complete", 0.91, null)
                }
            })
            done.updateInstruction(instruction)
            done.startRun()
            assertEquals(RunStatus.DONE, done.run.value?.status)
            render(done, 360, 1000, "done-360")
            render(done, 1440, 800, "done-wide-1440")

            val noTab = component(FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }, openTabs = emptyList())
            noTab.updateInstruction(instruction)
            render(noTab, 360, 800, "no-tab-360")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `readiness recovers when Jev registers after the panel opened`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val tools = FakeTools { _, _ -> Triple("The task is complete", 0.9, null) }
            tools.registered = setOf(ToolNames.OBSERVE, ToolNames.STEP)
            val c = component(tools)
            // Plugins load in any order: at creation there is no model to pick.
            assertEquals(LlmrpaComponent.Blocker.MODEL, c.readiness.value)
            assertEquals(null, c.selectedModel.value)

            tools.registered = null
            c.recheck()
            assertEquals("typesafe/jev-1.13", c.selectedModel.value?.modelId)
            assertEquals(null, c.readiness.value)

            // Jev briefly unregistering (a hot reload) must not wipe the pick.
            tools.registered = setOf(ToolNames.OBSERVE, ToolNames.STEP)
            c.recheck()
            assertEquals("typesafe/jev-1.13", c.selectedModel.value?.modelId)
            assertEquals(LlmrpaComponent.Blocker.JEV, c.readiness.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun render(component: LlmrpaComponent, width: Int, height: Int, name: String) {
        val out = Path.of("build/reports/visual/llmrpa-$name.png")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { LlmrpaContent(component) }
        try {
            val png = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            Files.createDirectories(out.parent)
            Files.write(out, png.bytes)
            assertTrue(Files.size(out) > 0)
        } finally {
            scene.close()
        }
    }
}
