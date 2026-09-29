package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabsProvider
import java.net.URI
import java.net.URLEncoder

/**
 * Why the host could not drive a tab. BossConsole resolves browsers only from the space on screen,
 * so a tab in another running space is listed but has no browser a plugin can reach.
 */
internal const val NO_BROWSER_HINT = "This tab is in another space or its browser is not loaded. Switch to its space, or use a new tab."

/** Where the address of a new tab came from. */
enum class StartSource(val label: String) {
    INSTRUCTION("instruction"),
    CALLER("start_url"),
    MODEL("model"),
    SEARCH("search fallback"),
}

/** The page a run opened in a new tab. [note] says why a search was used, when it was. */
data class OpenedPage(val url: String, val source: StartSource, val note: String? = null) {
    val description: String get() = "Opened $url (chosen by ${source.label})"
}

/**
 * How a run gets a tab of its own: [open] is the host's `createBrowserTab` (the active space, so
 * it can be driven), [claim] takes the run lock on it and returns a refusal or null.
 * [startUrl] is an address the caller already chose; null means pick one.
 */
class NewTab(
    val open: suspend (url: String, title: String) -> String?,
    val claim: (tabId: String) -> String?,
    val startUrl: String? = null,
)

internal object StartPages {
    const val TAB_TITLE = "LLM RPA"

    /** The first usable http(s) address in the instruction. */
    fun fromInstruction(instruction: String): String? = Candidates.urls(instruction).firstNotNullOfOrNull { usable(it, httpsOnly = false) }

    /**
     * [raw] as an address safe to open, or null: parseable, http(s) (https only when [httpsOnly]),
     * a real host, no credentials. javascript:, data: and file: fail the scheme check.
     */
    fun usable(raw: String?, httpsOnly: Boolean): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 } ?: return null
        if (s.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && (httpsOnly || scheme != "http")) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        // A model's pick is a public site: no localhost, intranet names or bare IPs.
        if (httpsOnly && (!host.contains('.') || host.startsWith("[") || host.all { it.isDigit() || it == '.' })) return null
        return uri.toString()
    }

    fun searchUrl(instruction: String): String =
        "https://duckduckgo.com/?q=" + URLEncoder.encode(instruction.trim().take(500), Charsets.UTF_8)

    /** The chosen start page, plus the cost and number of model calls it took. */
    data class Choice(val page: OpenedPage, val calls: Int, val costUsd: Double)

    /** Instruction first, then the model (https only), then a web search for the instruction. */
    suspend fun choose(instruction: String, decider: StepDecider): Choice {
        fromInstruction(instruction)?.let { return Choice(OpenedPage(it, StartSource.INSTRUCTION), 0, 0.0) }
        val reply = decider.startUrl(instruction)
        val suggested = reply.getOrNull()
        usable(suggested?.url, httpsOnly = true)?.let { return Choice(OpenedPage(it, StartSource.MODEL), 1, suggested!!.costUsd) }
        val why = reply.exceptionOrNull()?.message
            ?: suggested?.url?.let { "the model's address '${it.take(80)}' is not a safe https address" }
            ?: "the model named no address"
        return Choice(OpenedPage(searchUrl(instruction), StartSource.SEARCH, why), if (suggested != null) 1 else 0, suggested?.costUsd ?: 0.0)
    }

    /** Whether the host can drive [tabId] now, as RPA Engine resolves it. */
    fun drivable(provider: ActiveTabsProvider, tabId: String): Boolean =
        runCatching { provider.getBrowserIntegration(tabId)?.isBrowserAvailable() == true }.getOrDefault(false)
}
