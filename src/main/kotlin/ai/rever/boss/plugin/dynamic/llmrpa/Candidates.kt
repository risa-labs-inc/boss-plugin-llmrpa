package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One interactive element as RPA Engine's `rpa_observe` reports it. Never carries field values. */
data class PageElement(
    val id: String,
    val role: String,
    val tag: String,
    val label: String?,
    val type: String? = null,
    val href: String? = null,
    val options: List<String> = emptyList(),
    val checked: Boolean? = null,
    val sensitive: Boolean = false,
    val inViewport: Boolean = true,
    val selector: SelectorInfo,
    /** The image this element is or wraps (RPA Engine 1.3+), when it is big enough to matter. */
    val imageSrc: String? = null,
)

data class PageSnapshot(val url: String, val title: String, val elements: List<PageElement>, val truncated: Boolean) {
    companion object {
        fun parse(root: JsonObject): PageSnapshot = PageSnapshot(
            url = root.str("url").orEmpty(),
            title = root.str("title").orEmpty(),
            truncated = (root["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            elements = (root["elements"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val sel = o["selector"] as? JsonObject ?: return@mapNotNull null
                PageElement(
                    id = o.str("id") ?: return@mapNotNull null,
                    role = o.str("role").orEmpty(),
                    tag = o.str("tag").orEmpty(),
                    label = o.str("label")?.takeIf { it.isNotBlank() },
                    type = o.str("type"),
                    href = o.str("href"),
                    options = (o["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    checked = (o["checked"] as? JsonPrimitive)?.booleanOrNull,
                    sensitive = (o["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    inViewport = (o["in_viewport"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    selector = SelectorInfo(sel.str("type") ?: "css", sel.str("value")),
                    imageSrc = (o["image"] as? JsonObject)?.str("src"),
                )
            },
        )

        private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** An action the model can pick, phrased as a plain sentence. [action] is what `rpa_step` runs. */
data class Candidate(
    val key: String,
    val kind: Kind,
    val description: String,
    val action: StepAction? = null,
    val element: PageElement? = null,
) {
    enum class Kind { CLICK, TYPE, SELECT, KEY, NAVIGATE, DOWNLOAD, DONE, STUCK }

    val needsValue: Boolean get() = kind == Kind.TYPE

    /**
     * Whether this action could commit something on the page and so deserves a risk check. A select
     * counts when its label says so (some sites submit on change), and so does an address that does
     * (one-click unsubscribe, confirm and approve links act on a GET in a logged-in session).
     */
    val canCommit: Boolean get() = kind == Kind.CLICK || kind == Kind.KEY ||
        ((kind == Kind.SELECT || kind == Kind.NAVIGATE) && Candidates.soundsCommitting(this))
}

data class StepAction(val type: String, val selector: SelectorInfo? = null, val value: String? = null)

internal object Candidates {
    const val DONE = "done"
    const val STUCK = "stuck"

    /**
     * Cap on page-derived candidates plus Enter; done and stuck come on top, so a list is at most
     * MAX + 2. Jev's choice questions take at most 255 options, but a decision over ~200 links is
     * both slow and diluted. RPA Engine lists in-viewport elements first, so the cut drops what is
     * off screen.
     */
    const val MAX = 150
    private const val MAX_SELECT_OPTIONS = 12

    // Single quotes count only outside words, so the apostrophe in "don't" opens nothing.
    private val quoted = listOf(
        Regex("\"([^\"]{1,200})\""),
        Regex("“([^”]{1,200})”"),
        Regex("""(?<!\w)'([^']{1,200})'(?!\w)"""),
    )
    private val email = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val url = Regex("""https?://[^\s"'<>]+""")

    /**
     * Text the runner may type: quoted phrases, emails and web addresses found in the instruction.
     * A decision model cannot write text, so this is the whole vocabulary it types from, and the
     * panel shows it before the run starts.
     */
    fun values(instruction: String): List<String> {
        val found = LinkedHashSet<String>()
        quoted.forEach { re -> re.findAll(instruction).forEach { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(found::add) } }
        email.findAll(instruction).forEach { found += it.value }
        url.findAll(instruction).forEach { found += it.value.trimEnd('.', ',', ')') }
        return found.toList().take(20)
    }

    fun urls(instruction: String): List<String> = url.findAll(instruction).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()

    fun build(page: PageSnapshot, instruction: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        var n = 0
        var firstImage = true
        fun next() = "a${++n}"
        urls(instruction).filter { it.trimEnd('/') != page.url.trimEnd('/') }.forEach { u ->
            out += Candidate(next(), Candidate.Kind.NAVIGATE, "Go to $u", StepAction("navigate", value = u))
        }
        for (el in page.elements) {
            if (out.size >= MAX - 1) break
            val label = el.label ?: continue
            val quotedLabel = "'${label.take(60)}'"
            // An image (or a link wrapping one) can be saved; RPA Engine's download action fetches it.
            if (el.imageSrc != null) {
                // "the image" on an article almost always means its lead image, which RPA Engine
                // lists first; saying so lets a model tell it from the gallery further down.
                val lead = if (firstImage) " (first image on the page)" else ""
                firstImage = false
                out += Candidate(next(), Candidate.Kind.DOWNLOAD, "Download image $quotedLabel$lead", StepAction("download", el.selector), el)
                if (el.role == "img") continue
            }
            when {
                isTextField(el) -> out += Candidate(
                    next(), Candidate.Kind.TYPE, "Type into $quotedLabel${if (el.sensitive) " (private field)" else ""}",
                    StepAction("input", el.selector), el,
                )
                el.tag == "select" || (el.role == "combobox" && el.options.isNotEmpty()) -> el.options.take(MAX_SELECT_OPTIONS).forEach { opt ->
                    out += Candidate(next(), Candidate.Kind.SELECT, "Select '${opt.take(60)}' in $quotedLabel", StepAction("select", el.selector, opt), el)
                }
                el.role == "checkbox" || el.role == "switch" -> out += Candidate(
                    next(), Candidate.Kind.CLICK, "${if (el.checked == true) "Uncheck" else "Check"} $quotedLabel",
                    StepAction("click", el.selector), el,
                )
                el.role == "radio" -> out += Candidate(next(), Candidate.Kind.CLICK, "Choose $quotedLabel", StepAction("click", el.selector), el)
                el.role == "link" -> out += Candidate(next(), Candidate.Kind.CLICK, "Open $quotedLabel link", StepAction("click", el.selector), el)
                else -> out += Candidate(next(), Candidate.Kind.CLICK, "Click $quotedLabel ${roleNoun(el)}".trimEnd(), StepAction("click", el.selector), el)
            }
        }
        // After the cut, so a page with hundreds of links still offers Enter after typing a search.
        return out.take(MAX - 1) +
            Candidate(next(), Candidate.Kind.KEY, "Press Enter", StepAction("keypress", value = "Enter")) +
            Candidate(DONE, Candidate.Kind.DONE, "The task is complete") +
            Candidate(STUCK, Candidate.Kind.STUCK, "None of these moves the task forward")
    }

    private val COMMIT_WORDS = Regex(
        """\b(submit|pay|buy|purchase|order|send|delete|remove|confirm|check ?out|transfer|publish|post|sign ?up|register|save|accept|agree|book|reserve|donate|unsubscribe|cancel|approve|verify|activate)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A cheap label check, used only when the model could not say whether [c] can be undone. */
    fun soundsCommitting(c: Candidate): Boolean = COMMIT_WORDS.containsMatchIn("${c.description} ${c.element?.label.orEmpty()}")

    private val TEXT_ROLES = setOf("textbox", "searchbox")
    private val TEXT_INPUT_TYPES = setOf(null, "", "text", "search", "email", "url", "tel", "number", "password")

    /**
     * A field that takes typed text. An autocomplete box reports role `combobox` but is still a text
     * input: Wikipedia's search upgrades itself to one a moment after load, so keying on role alone
     * offered "Type into" only when the page happened to be observed early.
     */
    internal fun isTextField(el: PageElement): Boolean =
        el.role in TEXT_ROLES || el.tag == "textarea" ||
            (el.tag == "input" && el.role == "combobox" && el.options.isEmpty() && el.type in TEXT_INPUT_TYPES)

    private fun roleNoun(el: PageElement): String = when (el.role) {
        "button" -> "button"
        "tab" -> "tab"
        "menuitem" -> "menu item"
        "option" -> "option"
        else -> ""
    }
}
