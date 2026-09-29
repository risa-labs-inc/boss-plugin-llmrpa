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
        // macOS smart quotes turn 'x' into ‘x’.
        Regex("‘([^’]{1,200})’"),
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

    private val quotedAll = listOf(Regex("\"([^\"]+)\""), Regex("“([^”]+)”"), Regex("‘([^’]+)’"), Regex("""(?<!\w)'([^']+)'(?!\w)"""))

    /**
     * Every quoted phrase, email and address in the instruction, uncapped: what must not leave in a
     * search or a model-chosen address. [values] is capped for the list a model types from.
     */
    fun scrubbable(instruction: String): List<String> {
        val found = LinkedHashSet<String>()
        quotedAll.forEach { re -> re.findAll(instruction).forEach { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(found::add) } }
        email.findAll(instruction).forEach { found += it.value }
        url.findAll(instruction).forEach { found += it.value.trimEnd('.', ',', ')') }
        return found.toList()
    }

    private val SECRET_AFTER = Regex(
        """\b(?:passwords?|passcode|passwd|pwd|pin|otp|token|secret|ssn|cvv|cvc|card(?: number)?|account(?: number)?|api key|user ?name)\b(?:\s*(?:is\b|=|:))?\s*("[^"]*"|“[^”]*”|‘[^’]*’|'[^']*'|[^\s,;]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The unquoted word after a secret-sounding keyword ("password hunter2", "pin: 1234"), with the
     * keyword. Crude, and it over-cuts ("pin the tab"), which only costs a search some words.
     */
    fun keywordSecrets(instruction: String): List<String> =
        SECRET_AFTER.findAll(instruction).flatMap { listOf(it.value, it.groupValues[1]) }.distinct().toList()

    /** Whether [value] is the word after a secret keyword, as quoted or bare. */
    fun isKeywordSecret(instruction: String, value: String): Boolean =
        SECRET_AFTER.findAll(instruction).any { it.groupValues[1].trim('"', '\'', '“', '”', '‘', '’') == value }

    /**
     * [instruction] with each secret keyword and its word, then its values ([scrubbable]), replaced
     * by [with]. Keywords go in one regex pass, so a username that prefixes the password cannot
     * shield it; values go longest first and only as whole words, so "cat" leaves "category" alone.
     */
    fun scrub(instruction: String, with: String): String {
        // Keywords first, on the untouched text, so a quoted secret goes with its quotes.
        var text = SECRET_AFTER.replace(instruction, Regex.escapeReplacement(with))
        scrubbable(instruction).sortedByDescending { it.length }.forEach { v ->
            text = text.replace(Regex("(?<![\\p{L}\\p{N}])${Regex.escape(v)}(?![\\p{L}\\p{N}])"), Regex.escapeReplacement(with))
        }
        return text
    }

    /** Quoted phrases alone: text meant to be typed, not a place to go. */
    fun quotedPhrases(instruction: String): List<String> =
        quotedAll.flatMap { re -> re.findAll(instruction).map { it.groupValues[1] }.toList() }

    fun urls(instruction: String): List<String> = url.findAll(instruction).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()

    private const val MAX_PHRASES = 8
    private const val MAX_PHRASE_WORDS = 4

    // A span may not start or end on one of these: they say what to do, not what to type.
    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "of", "on", "in", "at", "to", "into", "onto", "for", "from", "by", "with",
        "about", "as", "is", "are", "be", "it", "its", "this", "that", "these", "those", "there", "here", "then", "than",
        "so", "if", "when", "where", "what", "which", "who", "how", "i", "me", "my", "we", "us", "our", "you", "your",
        "please", "can", "could", "would", "should", "will", "must", "want", "need", "let", "just", "now", "up", "down",
        "log", "login", "logout", "sign", "signin", "open", "go", "goto", "visit", "navigate", "follow", "link", "links", "click", "press", "tap", "type", "enter",
        "search", "find", "look", "show", "get", "till", "until", "reach", "arrive", "keep", "start", "stop", "use",
        "page", "pages", "home", "homepage", "site", "website", "tab", "result", "results", "first", "next", "select",
        "choose", "pick", "download", "save", "image", "picture", "article",
        "let's", "don't", "doesn't", "didn't", "can't", "won't", "isn't", "it's", "that's", "i'm", "i'd", "i'll", "you're",
    )
    private val SITE_SUFFIX = Regex("""^(home ?page|homepage|website|site|main page)\b""", RegexOption.IGNORE_CASE)
    private val word = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’-]*""")

    /**
     * Words from the instruction that could be typed when nothing is quoted: contiguous spans of up
     * to four words, never starting or ending on a stop or task word or on the site's name (a word
     * before "home page", X in "search X for", or a label of [addresses]' hosts or the instruction's own). Quoted
     * text, emails and addresses are left out (they are [values] already), and so is [keywordSecrets].
     * Spans with no inner stop word first, then longest; capped.
     */
    fun phrases(instruction: String, addresses: List<String> = emptyList()): List<String> {
        // The word after "password" and the like goes too: before 1.4 unquoted text was never typed.
        val text = scrub(instruction, " , ")
        val sites = (addresses + urls(instruction)).flatMap { hostLabels(it) }.toMutableSet()
        // Clauses break on punctuation, so a span never runs across "page, then".
        // ’ between letters is an apostrophe (macOS "Let’s"), not a quote.
        val clauses = text.split(Regex("""[.,;:!?()\[\]{}"“”‘]|(?<!\p{L})’|’(?!\p{L})|\s'|'\s""")).map { c -> word.findAll(c).map { it.value }.toList() }
        clauses.forEach { words ->
            words.forEachIndexed { i, w ->
                if (i + 1 < words.size && SITE_SUFFIX.containsMatchIn(words.drop(i + 1).joinToString(" "))) sites += w.lowercase()
                // "search google for cats": the word between is where, not what.
                if (i in 1 until words.lastIndex && words[i - 1].equals("search", true) && words[i + 1].equals("for", true)) sites += w.lowercase()
            }
        }
        val found = mutableListOf<Pair<String, Int>>()
        clauses.forEach { words ->
            for (start in words.indices) for (len in 1..MAX_PHRASE_WORDS) {
                val span = words.subList(start, (start + len).coerceAtMost(words.size)).takeIf { it.size == len } ?: break
                val edge = listOf(span.first(), span.last()).map { it.lowercase().replace('’', '\'') }
                if (edge.any { it in STOP_WORDS || it in sites }) continue
                if (len == 1 && span[0].length < 2) continue
                found += span.joinToString(" ") to len
            }
        }
        // Spans with no inner stop word first ("cats" over "google for cats"), then longest. The sort
        // is stable, so ties keep the instruction's order.
        fun clean(p: String) = p.split(' ').none { it.lowercase().replace('’', '\'') in STOP_WORDS }
        return found.sortedWith(compareByDescending<Pair<String, Int>> { clean(it.first) }.thenByDescending { it.second })
            .map { it.first }.distinctBy { it.lowercase() }.take(MAX_PHRASES)
    }

    /** The name-like labels of [address]'s host: `en.wikipedia.org` gives `wikipedia` (and `en`). */
    private fun hostLabels(address: String): List<String> {
        val host = runCatching { java.net.URI(address.trim()).host }.getOrNull()?.lowercase() ?: return emptyList()
        return host.split('.').dropLast(1).filter { it != "www" }
    }

    /** A field whose Enter runs a search rather than posting what was typed. */
    fun isSearchField(el: PageElement): Boolean =
        el.role == "searchbox" || el.type == "search" || el.label?.contains("search", ignoreCase = true) == true

    /**
     * [writes] is whether the decider can write text itself (a chat model). A field is offered only
     * when something can go in it: a private field takes quoted values alone, any other field also
     * words from the instruction ([phrases]).
     */
    fun build(page: PageSnapshot, instruction: String, writes: Boolean, phrases: List<String> = phrases(instruction, listOf(page.url))): List<Candidate> {
        val quotedValues = values(instruction).isNotEmpty()
        val canType = writes || quotedValues || phrases.isNotEmpty()
        val canTypePrivate = writes || quotedValues
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
                isTextField(el) -> if (if (el.sensitive) canTypePrivate else canType) out += Candidate(
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
