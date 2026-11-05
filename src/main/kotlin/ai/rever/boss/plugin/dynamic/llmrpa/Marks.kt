package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * One interactive element of the viewport as RPA Engine's `look` lists it (`look.elements`). [id] ("m12") is what `click_at` takes as `element`;
 * [rect] is in CSS px of the viewport. Its text is page data and is only ever shown to Jev as such.
 */
internal data class Mark(
    val id: String,
    val role: String,
    val tag: String,
    val label: String,
    /** text | empty | checked | unchecked | selected:<option label>; never a typed value. */
    val valueKind: String?,
    val rect: Cu.Rect,
    val disabled: Boolean,
    val covered: Boolean,
    val context: String,
    val editable: Boolean,
    val hrefHost: String?,
) {
    /** The number drawn on the image: "12" for "m12". */
    val number: String get() = id.removePrefix("m")
    val centreX: Double get() = (rect.left + rect.right) / 2
    val centreY: Double get() = (rect.top + rect.bottom) / 2

    /** "button 'ADD'", the short name used in notes and recent actions. */
    val short: String get() = "$role '${label.take(40)}'"
}

/** What one `look` returned: the screenshot, where the goal's phrases are, the interactive elements and, when asked, the page text. */
internal data class Look(val shot: Shot, val located: Map<String, Cu.Located>, val marks: List<Mark>, val pageText: String?)

/** Set-of-marks: the elements Jev may pick by number, how they read as options, and what to do when its box and its pick disagree. */
internal object Marks {
    const val NONE = "NONE"

    /** Jev takes at most this many options in a choice; NONE is one of them. */
    const val MAX_OPTIONS = 255

    /** A box centre this far (a share of the element's size, each side) outside the picked element still counts as pointing at it. */
    const val BOX_SLACK = 0.10

    /** Confidence is multiplied by this when the box points elsewhere than the pick. */
    const val DISAGREE_FACTOR = 0.8

    /** The `look` result with its `look` object's fields lifted next to the top-level `ok`, `error`, `dialog`...; a plain result is returned as it is. */
    fun body(json: JsonObject?): JsonObject? {
        json ?: return null
        val look = json["look"] as? JsonObject ?: return json
        return JsonObject(json.filterKeys { it != "look" } + look)
    }

    fun parseLook(body: JsonObject?): Look? {
        body ?: return null
        val shot = Cu.parseShot(body) ?: return null
        val located = (body["located"] as? JsonObject).orEmpty().mapNotNull { (phrase, v) ->
            Cu.parseLocated(buildJsonObject { put("located", v) })?.let { phrase to it }
        }.toMap()
        return Look(shot, located, parseMarks(body["elements"] as? JsonArray), (body["page_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }

    fun parseMarks(elements: JsonArray?): List<Mark> = elements.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
        val r = o["rect"] as? JsonObject ?: return@mapNotNull null
        fun n(k: String) = (r[k] as? JsonPrimitive)?.doubleOrNull
        val x = n("x") ?: n("left") ?: return@mapNotNull null
        val y = n("y") ?: n("top") ?: return@mapNotNull null
        val w = n("w") ?: n("width") ?: return@mapNotNull null
        val h = n("h") ?: n("height") ?: return@mapNotNull null
        Mark(
            id = s("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            role = s("role").orEmpty().ifBlank { s("tag").orEmpty() },
            tag = s("tag").orEmpty(),
            label = s("label").orEmpty(),
            valueKind = s("value_kind")?.takeIf { it.isNotBlank() },
            rect = Cu.Rect(x, y, x + w, y + h),
            disabled = b("disabled"), covered = b("covered"),
            context = s("context").orEmpty(), editable = b("editable"),
            hrefHost = s("href_host")?.takeIf { it.isNotBlank() },
        )
    }

    private fun clean(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)

    /** The option text: `[12] button 'ADD' — Eggless Classic Tres Leches ₹470 · disabled`. Context and state ride in the text. */
    fun optionText(m: Mark): String = buildString {
        append('[').append(m.number).append("] ").append(m.role.ifBlank { m.tag })
        clean(m.label, 80).takeIf { it.isNotEmpty() }?.let { append(" '").append(it).append('\'') }
        clean(m.context, 80).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
        val state = listOfNotNull(
            when (val v = m.valueKind) {
                null -> null
                "text" -> "has text"
                "empty" -> "empty"
                else -> if (v.startsWith("selected:")) "selected ${clean(v.removePrefix("selected:"), 60)}" else v
            },
            if (m.disabled) "disabled" else null,
            m.hrefHost?.let { "link to $it" },
        )
        if (state.isNotEmpty()) append(" · ").append(state.joinToString(" · "))
    }

    /** The marks Jev sees: covered ones are left out (they cannot be reached); at most [limit] (each question also carries NONE). Nav and footer links go first. */
    fun visible(marks: List<Mark>, limit: Int = MAX_OPTIONS - 1): List<Mark> {
        val open = marks.filter { !it.covered }
        if (open.size <= limit) return open
        // A link with no row or card around it is navigation or footer chrome: the first to drop.
        val (chrome, rest) = open.partition { it.role == "link" && it.context.isBlank() }
        val keep = if (rest.size >= limit) rest.take(limit) else rest + chrome.take(limit - rest.size)
        return open.filter { it in keep }
    }

    private fun typeable(m: Mark) = m.editable || m.tag.equals("select", ignoreCase = true) || m.role.equals("combobox", ignoreCase = true)

    /** Marks a CLICK can act on: everything that is not a field (fields are for TYPE_TEXT). Disabled ones stay, labelled. */
    fun clickable(marks: List<Mark>): List<Mark> = marks.filter { !typeable(it) }

    /** Marks TYPE_TEXT can act on: text fields and dropdowns that are not disabled. */
    fun typeables(marks: List<Mark>): List<Mark> = marks.filter { typeable(it) && !it.disabled }

    /** Whether [x], [y] (CSS px) is inside [m]'s rect grown by [BOX_SLACK] on each side. */
    fun near(m: Mark, x: Double, y: Double): Boolean {
        val dw = (m.rect.right - m.rect.left) * BOX_SLACK
        val dh = (m.rect.bottom - m.rect.top) * BOX_SLACK
        return x >= m.rect.left - dw && x <= m.rect.right + dw && y >= m.rect.top - dh && y <= m.rect.bottom + dh
    }

    /**
     * Fixes the pick of the click or type question for [d]: the chosen mark (when it is one of [marks] and reachable) becomes the target; no mark, NONE or
     * an unknown id leaves the box. If the box centre falls outside the mark (grown by [BOX_SLACK]) the mark is still used, with confidence times
     * [DISAGREE_FACTOR] and the decision flagged.
     */
    fun reconcile(d: VisionDecision, marks: List<Mark>, cssWidth: Int, cssHeight: Int): VisionDecision {
        val id = when (d.op) { VisionOp.CLICK -> d.clickChoice; VisionOp.TYPE_TEXT -> d.typeChoice; else -> null } ?: return d
        val mark = marks.firstOrNull { it.id == id && !it.covered } ?: return d
        val box = d.box?.takeIf { it.valid && cssWidth > 0 && cssHeight > 0 }
        val disagree = box != null && !near(mark, box.centreX * cssWidth, box.centreY * cssHeight)
        return d.copy(mark = mark, boxDisagreed = disagree, confidence = if (disagree) d.confidence * DISAGREE_FACTOR else d.confidence)
    }

    /** Jev's pick is "low" below this: its click_target probability or its operation confidence. */
    const val AMBIGUOUS_BELOW = 0.6
    const val MAX_CHOICES = 6

    /** What an element is called to a person: the card or row around it, else its own label, at most 60 characters. */
    fun display(m: Mark): String = clean(m.context.ifBlank { m.label }, 60)

    /** [display] without a price: "Classic Tres Leches ₹450" is "Classic Tres Leches". */
    fun nameOf(m: Mark): String = display(m).replace(Regex("[\\u20B9$\\u20AC\\u00A3]\\s?\\d[\\d.,]*"), "").replace(Regex("\\s+"), " ").trim().trim('-', '\u2014', '\u00B7', ' ')

    private fun pool(marks: List<Mark>, op: VisionOp) = (if (op == VisionOp.TYPE_TEXT) typeables(marks) else clickable(marks)).filter { !it.covered && !it.disabled }

    /**
     * Marks that name the same [entities] entity and are alike (the same kind of element with the same label, such as the ADD buttons of three items),
     * distinct by what they are called: two or more means the goal does not say which. Alike to [picked] when it is one of them, else the largest such group.
     */
    fun alike(candidates: List<Mark>, entities: List<String>, picked: Mark?): Pair<String, List<Mark>>? {
        for (e in entities) {
            val hit = candidates.filter { "${it.label} ${it.context}".contains(e, ignoreCase = true) }
            val group = if (picked != null && hit.any { it.id == picked.id }) hit.filter { it.role == picked.role && it.label.equals(picked.label, true) }
            else hit.groupBy { it.role to it.label.lowercase() }.values.maxByOrNull { it.size }.orEmpty()
            val distinct = group.distinctBy { display(it).lowercase() }
            if (distinct.size >= 2) return e to distinct.take(MAX_CHOICES)
        }
        return null
    }

    /** What to do about a hesitant CLICK or TYPE_TEXT: [Chosen] when the goal's own words name exactly one candidate, [Ask] when several fit equally. */
    sealed interface Disambiguation {
        data class Chosen(val mark: Mark) : Disambiguation
        data class Ask(val entity: String, val options: List<Mark>) : Disambiguation
    }

    /**
     * Jev picked CLICK or TYPE_TEXT with low confidence (its target probability or its operation confidence under [AMBIGUOUS_BELOW]) and two or more marks
     * that name the same entity look alike: ask the person, unless one of [phrases] (the goal's own words) is exactly one candidate's name.
     */
    fun disambiguate(d: VisionDecision, marks: List<Mark>, entities: List<String>, phrases: List<String>): Disambiguation? {
        if (d.op != VisionOp.CLICK && d.op != VisionOp.TYPE_TEXT) return null
        if ((d.markP ?: 1.0) >= AMBIGUOUS_BELOW && d.confidence >= AMBIGUOUS_BELOW) return null
        val (entity, group) = alike(pool(marks, d.op), entities, d.mark) ?: return null
        val named = group.filter { m -> nameOf(m).let { n -> n.isNotEmpty() && phrases.any { it.trim().equals(n, ignoreCase = true) } } }
        named.singleOrNull()?.let { return Disambiguation.Chosen(it) }
        return Disambiguation.Ask(entity, group)
    }

    /** "Which one do you want? 1) Classic Tres Leches ₹450 2) ..." */
    fun choiceQuestion(options: List<Mark>): String =
        "Which one do you want? " + options.mapIndexed { i, m -> "${i + 1}) ${display(m)}" }.joinToString(" ") + " (reply with the number or the name)"

    /**
     * The mark a reply to that question means, found again in a fresh look: a number counts into [pending] (the labels asked about), or into the
     * options as they look now; a name is an exact label or product name, else the one candidate that holds it. Null when it names none or several.
     */
    fun resolveChoice(reply: String, marks: List<Mark>, pending: List<String>, entities: List<String>): Mark? {
        val cands = pool(marks, VisionOp.CLICK)
        val text = reply.trim().trimEnd('.', ')').let { r ->
            r.toIntOrNull()?.let { n -> pending.getOrNull(n - 1) ?: alike(cands, entities, null)?.second?.getOrNull(n - 1)?.let(::display) ?: return null } ?: r
        }
        if (text.isBlank()) return null
        // Elements inside a card or row (the ADD of an item) before bare links to the same name.
        fun find(pool: List<Mark>): Mark? {
            pool.filter { display(it).equals(text, true) }.distinctBy { display(it) }.singleOrNull()?.let { return it }
            pool.filter { nameOf(it).equals(text, true) }.singleOrNull()?.let { return it }
            return pool.filter { display(it).contains(text, true) || (nameOf(it).length >= 3 && text.contains(nameOf(it), true)) }.distinctBy { display(it) }.singleOrNull()
        }
        return find(cands.filter { it.context.isNotBlank() }) ?: find(cands)
    }

    private val REFUSAL = Regex("""(?i)\bis (disabled|covered by\b.*)""")

    /** "ADD is disabled" / "ADD is covered by Login dialog" from the engine's click error, or null for any other failure. */
    fun refusal(error: String?): String? =
        error?.takeIf { REFUSAL.containsMatchIn(it) }?.replace("'", "")?.replace(Regex("\\s+"), " ")?.trim()?.take(160)
}

/** Draws the numbered marks on a copy of the screenshot for Jev: a thin outline and a small numbered pill at each element's top-left corner. */
internal object MarkImage {
    private val OUTLINES = listOf(Color(0xE5, 0x39, 0x35), Color(0x1E, 0x88, 0xE5), Color(0x43, 0xA0, 0x47), Color(0xF5, 0x7C, 0x00), Color(0x8E, 0x24, 0xAA))
    private val GREY = Color(0x9E, 0x9E, 0x9E)

    /** The marked copy saved as `<name>-marks.jpg` beside [shot]'s file, or null when nothing could be drawn or written. */
    fun render(shot: Shot, marks: List<Mark>): String? = runCatching {
        if (marks.isEmpty() || shot.path.isEmpty() || shot.cssWidth <= 0 || shot.cssHeight <= 0) return@runCatching null
        val src = ImageIO.read(File(shot.path)) ?: return@runCatching null
        val img = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, null)
            val sx = src.width / shot.cssWidth.toDouble()
            val sy = src.height / shot.cssHeight.toDouble()
            val font = Font(Font.SANS_SERIF, Font.BOLD, (src.width / 110).coerceIn(9, 14))
            g.font = font
            val fm = g.fontMetrics
            marks.forEachIndexed { i, m ->
                val x = m.rect.left * sx
                val y = m.rect.top * sy
                val w = (m.rect.right - m.rect.left) * sx
                val h = (m.rect.bottom - m.rect.top) * sy
                val colour = if (m.disabled) GREY else OUTLINES[i % OUTLINES.size]
                g.color = colour
                g.stroke = BasicStroke(if (m.disabled) 1f else 1.5f)
                g.draw(java.awt.geom.Rectangle2D.Double(x, y, w, h))
                // The number in a pill on the top-left corner, white on the outline's colour, kept inside the image.
                val text = m.number
                val pw = fm.stringWidth(text) + 6
                val ph = fm.height
                val px = x.coerceIn(0.0, (src.width - pw).toDouble().coerceAtLeast(0.0))
                val py = (y - ph + 2).coerceIn(0.0, (src.height - ph).toDouble().coerceAtLeast(0.0))
                g.fill(RoundRectangle2D.Double(px, py, pw.toDouble(), ph.toDouble(), 6.0, 6.0))
                g.color = Color.WHITE
                g.drawString(text, (px + 3).toFloat(), (py + fm.ascent - 1).toFloat())
            }
        } finally {
            g.dispose()
        }
        val f = File(shot.path)
        val out = File(f.parentFile ?: File(System.getProperty("java.io.tmpdir")), f.nameWithoutExtension + "-marks.jpg")
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.8f }
        ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, IIOImage(img, null, null), param) }
        writer.dispose()
        out.absolutePath
    }.getOrNull()
}
