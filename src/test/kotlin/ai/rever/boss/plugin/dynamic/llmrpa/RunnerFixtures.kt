package ai.rever.boss.plugin.dynamic.llmrpa

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
    private val decide: (Map<String, String>, Int) -> Triple<String, Double, Int?>,
) : ToolInvoker {
    val steps = mutableListOf<JsonObject>()
    var decideCalls = 0
    var riskCalls = 0
    var verifyCalls = 0

    override fun has(toolName: String) = true
    override fun inputSchema(toolName: String): String? =
        if (toolName == ToolNames.JEV_DECIDE) """{"properties":{"model":{"type":"string","enum":["typesafe/jev-1.13","typesafe/jev-2"]}}}""" else null

    override suspend fun invoke(toolName: String, arguments: JsonElement): ToolReply {
        val args = arguments.jsonObject
        return when (toolName) {
            ToolNames.OBSERVE -> ToolReply(observeJson(page), false)
            ToolNames.STEP -> {
                steps += args["action"]!!.jsonObject
                val ok = stepOk(args["action"]!!.jsonObject)
                ToolReply("""{"ok":$ok,"error":${if (ok) "null" else "\"element not found\""}}""", false)
            }
            ToolNames.JEV_DECIDE -> {
                val questions = args["questions"]!!.jsonObject
                if ("complete" in questions) {
                    val p = complete[verifyCalls.coerceAtMost(complete.lastIndex)]
                    verifyCalls++
                    ToolReply("""{"response":{"answers":{"complete":{"type":"noul","noul":$p}},"usage":{"cost":0.00001}}}""", false)
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
            """{"id":"${e.id}","role":"${e.role}","tag":"${e.tag}","label":${e.label?.let { "\"$it\"" } ?: "null"},"options":$opts,"sensitive":${e.sensitive},"in_viewport":true,"selector":{"type":"${e.selector.type}","value":"${e.selector.value}"}}"""
        }
        return """{"tab_id":"t1","url":"${p.url}","title":"${p.title}","truncated":false,"elements":[$els]}"""
    }
}

internal val JEV = ModelOption(ModelOption.Kind.DECISION, "JEV", "Jev", "typesafe/jev-1.13", "jev-1.13")

internal fun json(text: String) = Json.parseToJsonElement(text).jsonObject
