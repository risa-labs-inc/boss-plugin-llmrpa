package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun element(
    id: String,
    role: String,
    label: String?,
    tag: String = if (role == "link") "a" else if (role == "button") "button" else "input",
    options: List<String> = emptyList(),
    selector: String = "#$id",
) = PageElement(id, role, tag, label, options = options, selector = SelectorInfo("css", selector))

internal val SEARCH_PAGE = PageSnapshot(
    url = "https://shop.example/",
    title = "Shop",
    truncated = false,
    elements = listOf(
        element("e1", "searchbox", "Search shop", selector = "[name='q']"),
        element("e2", "link", "Sign in"),
        element("e3", "button", "Place your order"),
    ),
)

/**
 * Stands in for RPA Engine and Jev. [decide] receives the `next` criteria (key → description)
 * and returns the chosen description, its probability, and optionally the value index.
 */
internal class FakeTools(
    var page: PageSnapshot = SEARCH_PAGE,
    private val stepOk: (JsonObject) -> Boolean = { true },
    private val risk: Double = 0.1,
    /** Answers "is the task complete?" in order; the last value repeats. */
    private val complete: List<Double> = listOf(0.9),
    /** Answers "which text goes into the field?" given its options: the chosen text (null = none) and probability. */
    private val chooseText: (List<String>) -> Pair<String?, Double> = { null to 0.9 },
    private val decide: (Map<String, String>, Int) -> Triple<String, Double, Int?>,
) : ToolInvoker {
    var textCalls = 0
    val textOptions = mutableListOf<List<String>>()
    val steps = mutableListOf<JsonObject>()
    /** Full `rpa_step` arguments, for what travels beside the action. */
    val stepArgs = mutableListOf<JsonObject>()
    /** Thrown from every call to this tool, standing in for an api mismatch across the boundary. */
    var throwOn: String? = null
    var decideCalls = 0
    var riskCalls = 0
    var verifyCalls = 0

    /** Replaces `rpa_observe`'s reply when it returns non-null, given the tab id. */
    var observeHook: ((String) -> ToolReply?)? = null
    /** Replaces every `rpa_step` reply when set. */
    var stepReply: ToolReply? = null
    val observedTabs = mutableListOf<String>()

    /** Tools currently registered; null means all of them. Lets a test register Jev late. */
    var registered: Set<String>? = null

    override fun has(toolName: String) = registered?.contains(toolName) ?: true
    override fun inputSchema(toolName: String): String? =
        if (toolName == ToolNames.JEV_DECIDE) """{"properties":{"model":{"type":"string","enum":["typesafe/jev-1.13","typesafe/jev-2"]}}}""" else null

    override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
        val args = arguments.jsonObject
        if (toolName == throwOn) throw NoSuchMethodError("$toolName: no such method")
        return when (toolName) {
            ToolNames.OBSERVE -> {
                val tab = (args["tab_id"] as JsonPrimitive).content
                observedTabs += tab
                observeHook?.invoke(tab) ?: ToolReply(observeJson(page), false)
            }
            ToolNames.STEP -> {
                stepReply?.let { steps += args["action"]!!.jsonObject; return it }
                steps += args["action"]!!.jsonObject
                stepArgs += args
                val ok = stepOk(args["action"]!!.jsonObject)
                val download = if (ok && (args["action"]!!.jsonObject["type"] as JsonPrimitive).content == "download")
                    ""","download":{"url":"https://upload.example/cat.jpg","file":"cat.jpg","bytes":1234,"method":"blob"}""" else ""
                ToolReply("""{"ok":$ok,"error":${if (ok) "null" else "\"element not found\""}$download}""", false)
            }
            ToolNames.JEV_DECIDE -> {
                val questions = args["questions"]!!.jsonObject
                if ("complete" in questions) {
                    val p = complete[verifyCalls.coerceAtMost(complete.lastIndex)]
                    verifyCalls++
                    ToolReply("""{"response":{"answers":{"complete":{"type":"noul","noul":$p}},"usage":{"cost":0.00001}}}""", false)
                } else if ("text" in questions) {
                    textCalls++
                    val criteria = questions["text"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { (it.value as JsonPrimitive).content }
                    val options = criteria.filterKeys { it != JevDecider.NONE_OF_THESE }.values.toList()
                    textOptions += options
                    val (text, p) = chooseText(options)
                    val key = text?.let { t -> criteria.entries.first { it.value == t }.key } ?: JevDecider.NONE_OF_THESE
                    ToolReply("""{"response":{"answers":{"text":{"type":"choice","choice":"$key","probabilities":{"$key":$p},"confidence":$p}},"usage":{"cost":0.00003}}}""", false)
                } else if ("irreversible" in questions) {
                    riskCalls++
                    ToolReply("""{"response":{"answers":{"irreversible":{"type":"noul","noul":$risk}},"usage":{"cost":0.00001}}}""", false)
                } else {
                    val criteria = questions["next"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { (it.value as JsonPrimitive).content }
                    val (desc, p, valueIdx) = decide(criteria, decideCalls++)
                    val key = criteria.entries.first { it.value == desc }.key
                    val others = criteria.keys.filter { it != key }
                    val rest = (1 - p) / others.size.coerceAtLeast(1)
                    val probs = (listOf(key to p) + others.map { it to rest }).joinToString(",") { "\"${it.first}\":${it.second}" }
                    val value = valueIdx?.let { ""","value":{"type":"choice","choice":"v$it","probabilities":{"v$it":1.0},"confidence":0.9}""" }.orEmpty()
                    ToolReply(
                        """{"response":{"model":"typesafe/jev-1.13","answers":{"next":{"type":"choice","choice":"$key","probabilities":{$probs},"confidence":$p}$value},"usage":{"input_tokens":1,"output_tokens":1,"cost":0.00002}},"latency_ms":5}""",
                        false,
                    )
                }
            }
            else -> ToolReply("unknown tool", true)
        }
    }

    private fun observeJson(p: PageSnapshot): String {
        val els = p.elements.joinToString(",") { e ->
            val opts = if (e.options.isEmpty()) "null" else e.options.joinToString(",", "[", "]") { "\"$it\"" }
            val image = e.imageSrc?.let { ""","image":{"src":"$it","alt":null,"width":300,"height":200}""" }.orEmpty()
            """{"id":"${e.id}","role":"${e.role}","tag":"${e.tag}","label":${e.label?.let { "\"$it\"" } ?: "null"},"options":$opts,"sensitive":${e.sensitive},"in_viewport":true,"selector":{"type":"${e.selector.type}","value":"${e.selector.value}"}$image}"""
        }
        return """{"tab_id":"t1","url":"${p.url}","title":"${p.title}","truncated":false,"elements":[$els]}"""
    }
}

internal val JEV = ModelOption(ModelOption.Kind.DECISION, "JEV", "Jev", "typesafe/jev-1.13", "jev-1.13")

internal fun json(text: String) = Json.parseToJsonElement(text).jsonObject

/**
 * The host's tab list. [drivable] are the tabs whose browser resolves (the space on screen), all by
 * default; [create] stands in for createBrowserTab and [created] records what it was asked to open.
 */
internal class FakeTabs(
    list: List<ActiveTabData>,
    private val focused: () -> String? = { null },
    var drivable: Set<String>? = null,
    private val create: (String) -> String? = { null },
) : ActiveTabsProvider {
    val tabs = MutableStateFlow(list)
    val created = mutableListOf<String>()
    var probes = 0
    /** Runs inside each probe, e.g. to ask for another probe mid-probe. */
    var onProbe: (() -> Unit)? = null
    override val activeTabs: StateFlow<List<ActiveTabData>> = tabs
    override val activePanelId: String? get() = focused()?.let { id -> tabs.value.firstOrNull { it.tabId == id }?.panelId }
    override fun selectedTabId(workspaceId: String, panelId: String): String? =
        focused()?.takeIf { id -> tabs.value.any { it.tabId == id && it.workspaceId == workspaceId && it.panelId == panelId } }
    override suspend fun refreshTabs() {}
    override fun selectTab(tabId: String, panelId: String) {}
    override fun getTabUrl(tabId: String): String? = tabs.value.firstOrNull { it.tabId == tabId }?.url
    override fun getFaviconCacheKey(tabId: String): String? = null
    @androidx.compose.runtime.Composable override fun loadFavicon(cacheKey: String?): Painter? = null
    override fun getFallbackIcon(typeId: String): ImageVector? = null
    override fun getBrowserIntegration(tabId: String): BrowserIntegration? {
        probes++
        onProbe?.invoke()
        return if (tabs.value.any { it.tabId == tabId } && drivable?.contains(tabId) != false) FakeBrowser else null
    }
    override fun createBrowserTab(url: String, title: String): String? { created += url; return create(url) }
    override fun closeTab(tabId: String): Boolean = false
}

internal object FakeBrowser : BrowserIntegration {
    override suspend fun executeJavaScript(script: String): Any? = null
    override fun isBrowserAvailable(): Boolean = true
    override suspend fun getCurrentUrl(): String? = null
}

internal fun noBrowser(tab: String) =
    ToolReply("""{"error":{"code":"NO_BROWSER","message":"Tab '$tab' is not a browser tab"}}""", isError = true)
