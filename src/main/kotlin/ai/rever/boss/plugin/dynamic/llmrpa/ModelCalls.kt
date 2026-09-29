package ai.rever.boss.plugin.dynamic.llmrpa

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** What a model call was for. */
enum class CallKind(val label: String) {
    DECIDE("decide"),
    RISK("risk"),
    VERIFY_DONE("done check"),
    TEXT("text"),
    START_URL("start page"),
}

/** Text as stored in a [ModelCall]: masked and capped at [ModelCall.MAX_TEXT_BYTES] of UTF-8. */
data class CappedText(val text: String, val truncated: Boolean = false)

/** One option in a Jev question: its key, its label, and the probability Jev gave it (null when not reported). */
data class CallOption(val key: String, val label: String, val probability: Double?)

/** One question in a `jev_decide` request, with the key Jev picked. */
data class CallQuestion(val id: String, val text: String, val options: List<CallOption>, val pick: String?)

/**
 * One decider call, as sent and as answered. [step] is the timeline step it led to (0 = choosing
 * the start page). Chat models report tokens, not money, so their [costUsd] is null.
 */
data class ModelCall(
    val step: Int,
    val kind: CallKind,
    /** `jev_decide`, or [GATEWAY] for a chat model. */
    val tool: String,
    val model: String,
    /** Jev's questions and options; empty for a chat model, whose request is its prompt. */
    val questions: List<CallQuestion> = emptyList(),
    val request: CappedText,
    val response: CappedText? = null,
    val pick: String? = null,
    val confidence: Double? = null,
    val risk: Double? = null,
    val latencyMs: Long,
    val costUsd: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val error: String? = null,
) {
    /** A copy with every [secrets] occurrence masked, in the text and in the parsed fields. */
    fun masked(secrets: Collection<String>): ModelCall {
        if (secrets.isEmpty()) return this
        fun m(s: String) = Secrets.mask(s, secrets)
        return copy(
            questions = questions.map { q -> q.copy(text = m(q.text), options = q.options.map { it.copy(label = m(it.label)) }) },
            request = request.copy(text = m(request.text)),
            response = response?.let { it.copy(text = m(it.text)) },
            pick = pick?.let(::m),
            error = error?.let(::m),
        )
    }

    /**
     * As kept in a run: masked first, then capped, so a cut cannot split a secret the mask would
     * have caught. The cut also never splits one of [avoid], which may turn out to be private later.
     */
    fun stored(secrets: Collection<String>, avoid: Collection<String>): ModelCall = masked(secrets).let { c ->
        c.copy(request = cap(c.request.text, avoid), response = c.response?.let { cap(it.text, avoid) })
    }

    companion object {
        const val GATEWAY = "ai_gateway"
        const val MAX_TEXT_BYTES = 8 * 1024

        /** [text] cut to [MAX_TEXT_BYTES] of UTF-8 on a character boundary, and before any of [avoid] it would split. */
        fun cap(text: String, avoid: Collection<String> = emptyList()): CappedText {
            if (text.length * 3 <= MAX_TEXT_BYTES || text.toByteArray().size <= MAX_TEXT_BYTES) return CappedText(text)
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > MAX_TEXT_BYTES) break
                bytes += n
                end += Character.charCount(cp)
            }
            val cut = avoid.filter { it.length > 1 }.mapNotNull { v ->
                (1 until v.length).lastOrNull { k -> end - k >= 0 && text.startsWith(v, end - k) }?.let { end - it }
            }.minOrNull() ?: end
            return CappedText(text.substring(0, cut), truncated = true)
        }
    }
}

/**
 * Where deciders report their calls. The runner installs one around each decider call with the
 * step it belongs to, so a decider needs no run-specific state; outside a run nothing is recorded.
 */
internal class CallRecorder(val step: Int, private val sink: (ModelCall) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallRecorder>

    fun record(call: ModelCall) = sink(call.copy(step = step))
}

/** Reports [call] to the run's recorder, if any. */
internal suspend fun recordCall(call: ModelCall) {
    currentCoroutineContext()[CallRecorder]?.record(call)
}

internal object Secrets {
    const val MASK = "••••••"

    /** Text that must never be shown or written: the values [Candidates.isKeywordSecret] masks. */
    fun of(instruction: String): Set<String> = Candidates.keywordPrivate(instruction).filter { it.isNotBlank() }.toSet()

    /**
     * [text] with each secret replaced by [MASK], as a whole word so "pin the tab" cannot blank
     * "there", and in its JSON-escaped spelling too, since requests are JSON.
     */
    fun mask(text: String, secrets: Collection<String>): String {
        var out = text
        secrets.flatMap { listOf(it, jsonEscaped(it)) }.distinct().filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { s ->
            out = out.replace(Regex("(?<![\\p{L}\\p{N}])${Regex.escape(s)}(?![\\p{L}\\p{N}])"), Regex.escapeReplacement(MASK))
        }
        return out
    }

    private fun jsonEscaped(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString().removeSurrounding("\"")
}
