package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import java.net.URI
import java.net.URLDecoder
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

    /** The first usable http(s) address in the instruction, preferring one outside quotes (quoted text is for typing). */
    fun fromInstruction(instruction: String): String? {
        val quoted = Candidates.quotedPhrases(instruction)
        val (inQuotes, bare) = Candidates.urls(instruction).partition { u -> quoted.any { u in it } }
        return (bare + inQuotes).firstNotNullOfOrNull { usable(it, httpsOnly = false) }
    }

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
        val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
        if (httpsOnly && !publicHost(host)) return null
        return uri.toString()
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa", ".intranet", ".corp")
    private val TLD = Regex("[a-z]{2,63}|xn--[a-z0-9-]{1,59}")
    private val HEX_LABEL = Regex("0x[0-9a-f]*")

    /**
     * A model's pick is a public site: a dotted name with an alphabetic TLD, so no localhost,
     * intranet suffixes, trailing dots, or IP literals in any spelling (`0x7f.1`, `127.1`, `[::1]`).
     * A public name can still resolve privately (wildcard DNS such as nip.io); nothing here resolves.
     */
    private fun publicHost(host: String): Boolean {
        if (host.endsWith(".") || host.startsWith("[") || !host.contains('.')) return false
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || HEX_LABEL.matches(it) }) return false
        return TLD.matches(labels.last())
    }

    /**
     * A web search for [instruction] without its quoted text, emails or addresses: those are the
     * values it may type (passwords included) and must not reach a search engine. Null when
     * nothing is left to search for.
     */
    fun searchUrl(instruction: String): String? {
        var q = instruction
        Candidates.scrubbable(instruction).sortedByDescending { it.length }.forEach { q = q.replace(it, " ") }
        q = q.replace(Regex("[\"“”']\\s*[\"“”']"), " ").replace(Regex("\\s+"), " ").trim().take(200)
        if (q.count { it.isLetterOrDigit() } < 3) return null
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(q, Charsets.UTF_8)
    }

    /** The chosen start page, plus the cost and number of model calls it took. */
    data class Choice(val page: OpenedPage, val calls: Int, val costUsd: Double)

    /** Instruction first, then the model (https only), then a web search for the instruction. */
    suspend fun choose(instruction: String, decider: StepDecider): Choice {
        fromInstruction(instruction)?.let { return Choice(OpenedPage(it, StartSource.INSTRUCTION), 0, 0.0) }
        val reply = decider.startUrl(instruction)
        val suggested = reply.getOrNull()
        // A call that went out and failed may still have been billed.
        val calls = if (suggested != null || reply.exceptionOrNull() is StartUrlCallFailed) 1 else 0
        val cost = suggested?.costUsd ?: 0.0
        usable(suggested?.url, httpsOnly = true)?.let { url ->
            // The model saw the whole instruction, secrets included: an address carrying any of its
            // values (the user's own addresses aside) keeps only its origin.
            val own = Candidates.urls(instruction).toSet()
            // Decoded once, so %20, + and any hex case compare as the raw value.
            val secrets = Candidates.scrubbable(instruction).filter { it !in own && it.length >= 3 }
            fun leaks(u: String): Boolean {
                val plain = runCatching { URLDecoder.decode(u, Charsets.UTF_8) }.getOrDefault(u)
                return secrets.any { v -> u.contains(v, ignoreCase = true) || plain.contains(v, ignoreCase = true) }
            }
            if (!leaks(url)) return Choice(OpenedPage(url, StartSource.MODEL), calls, cost)
            val origin = URI(url).let { "${it.scheme}://${it.rawAuthority}/" }
            // The host itself can carry the value (hunter2.shop.example).
            if (!leaks(origin)) return Choice(OpenedPage(origin, StartSource.MODEL, "its path carried text from your instruction, so only the site was opened"), calls, cost)
        }
        val why = reply.exceptionOrNull()?.message
            ?: suggested?.url?.takeIf { usable(it, httpsOnly = true) != null }?.let { "the model's address carried text from your instruction" }
            ?: suggested?.url?.let { "the model's address '${it.take(80)}' is not a safe https address" }
            ?: "the model named no address"
        val search = searchUrl(instruction)
            ?: throw NoStartPage("Could not tell which page to start on ($why). Put the site's address in the instruction, or pick a tab.")
        return Choice(OpenedPage(search, StartSource.SEARCH, why), calls, cost)
    }

    class NoStartPage(message: String) : Exception(message)

    /** Whether [tabId] is drivable per the probe results; a tab not probed yet reads as drivable. */
    fun drivableIn(probed: Map<String, Boolean>?, tabId: String): Boolean = probed?.get(tabId) ?: true

    /**
     * Why a tab cannot be driven, for the picker and the headless listing. A tab whose space also
     * has a drivable tab is on screen, so its browser is just not loaded.
     */
    fun awayReason(tab: ActiveTabData, tabs: List<ActiveTabData>, probed: Map<String, Boolean>?): String =
        if (tabs.any { it.workspaceId == tab.workspaceId && probed?.get(it.tabId) == true }) "Not loaded yet — open it once to use this tab"
        else "In another space (${tab.workspaceName.take(40)}) — switch to it to use this tab"

    /**
     * Whether the host can drive [tabId] now, as RPA Engine resolves it. A throw is unknown, which
     * reads as drivable: the NO_BROWSER mapping then explains a failure.
     */
    fun drivable(provider: ActiveTabsProvider, tabId: String): Boolean =
        runCatching { provider.getBrowserIntegration(tabId)?.isBrowserAvailable() == true }.getOrDefault(true)
}
