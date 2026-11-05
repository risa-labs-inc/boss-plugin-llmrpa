package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Every computer-use run as a file, `<dir>/<run_id>.json`, rewritten after each step while it runs and at the end,
 * so a run can be read afterwards (or while it goes on) by `browser_use_status`. Private text is masked before it
 * is written; the newest [keep] files stay. Writing never fails a run.
 */
class RunLogStore(private val dir: File, private val keep: Int = 50) {
    private val live = ConcurrentHashMap<String, UseRun>()
    private val json = Json { prettyPrint = false }

    internal fun begin(run: UseRun) { live[run.id] = run; write(run) }
    internal fun update(run: UseRun) = write(run)
    internal fun end(run: UseRun) { write(run); live.remove(run.id); prune() }
    internal fun liveRuns(): List<UseRun> = live.values.sortedBy { it.startedAt }

    /** A run's record: the live one built fresh, else the file. Null for an unknown or malformed id. */
    internal fun read(id: String): JsonObject? {
        if (!VALID_ID.matches(id)) return null
        live[id]?.let { return record(it) }
        val f = File(dir, "$id.json")
        return runCatching { json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
    }

    /** The newest [limit] finished or live runs, one summary line each, newest first. */
    internal fun list(limit: Int): List<JsonObject> {
        val files = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.lastModified() }
        val ids = (live.keys.toList() + files.map { it.name.removeSuffix(".json") }).distinct()
        return ids.mapNotNull { id -> read(id) }
            .sortedByDescending { (it["started_at_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L }
            .take(limit).map(::summary)
    }

    /** Writes a record that is not a [UseRun], such as a DOM-mode run. */
    internal fun writeRaw(id: String, record: JsonObject) {
        runCatching { atomic(id, record.toString()); prune() }
    }

    private fun write(run: UseRun) {
        runCatching { atomic(run.id, Secrets.mask(record(run).toString(), run.secrets)) }
    }

    private fun atomic(id: String, text: String) {
        Files.createDirectories(dir.toPath())
        val tmp = Files.createTempFile(dir.toPath(), "$id-", ".part")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, File(dir, "$id.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, File(dir, "$id.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun prune() {
        runCatching {
            val files = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.lastModified() }
            files.drop(keep).filter { it.name.removeSuffix(".json") !in live.keys }.forEach { it.delete() }
        }
    }

    internal fun summary(r: JsonObject): JsonObject = buildJsonObject {
        for (k in listOf("run_id", "mode", "task", "status", "started_at", "seconds", "cost_usd", "steps_count", "tab_id")) r[k]?.let { put(k, it) }
    }

    /** The record of [run] as it stands: no image data, private text masked by the writer, inputs by name only. */
    internal fun record(run: UseRun): JsonObject {
        val report = run.report.value
        val steps = run.steps.value
        val now = System.currentTimeMillis()
        val end = run.endedAt ?: now
        val status = when {
            report != null -> report.status.name.lowercase()
            run.question.value != null -> "waiting"
            run.phase.value == UsePhase.FINISHED -> "stopped"
            else -> "running"
        }
        return buildJsonObject {
            put("run_id", run.id)
            put("mode", run.mode)
            put("task", run.task)
            put("status", status)
            put("phase", run.phase.value.name.lowercase())
            put("started_at", Instant.ofEpochMilli(run.startedAt).toString())
            put("started_at_ms", run.startedAt)
            put("updated_at", Instant.ofEpochMilli(now).toString())
            put("seconds", report?.seconds ?: ((run.priorMs + (now - run.segmentStart.coerceAtLeast(run.startedAt)).coerceAtLeast(0)) / 1000.0))
            put("jev_model", run.jevModel)
            put("llm_model", run.llmModel)
            run.consultModel?.let { put("consult_model", it) }
            run.tabId?.let { put("tab_id", it) }
            putJsonArray("inputs") { run.inputNames.forEach { add(JsonPrimitive(it)) } }
            run.plan.value?.let { p ->
                putJsonObject("plan") {
                    putJsonArray("open") { p.open.forEach { add(JsonPrimitive(it)) } }
                    put("goal", p.goal)
                    putJsonArray("type_values") { p.typeValues.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("milestones") { p.milestones.forEachIndexed { i, m -> add(buildJsonObject { put("text", m); put("done", i < run.doneMilestones.size) }) } }
                }
            }
            put("steps_count", steps.size)
            putJsonArray("steps") { steps.forEach { add(stepJson(it)) } }
            putJsonArray("tabs") { run.tabsUsed.forEach { t -> add(buildJsonObject { put("tab_id", t.tabId); put("title", t.title); put("kind", t.kind) }) } }
            put("jev_calls", run.log.jevCalls)
            put("llm_calls", run.log.llmCalls)
            put("tool_calls", run.log.toolCalls)
            if (run.log.jevCalls > 0) put("cost_usd", run.log.jevCost) else put("cost_usd", JsonNull)
            report?.let { put("report", it.toJson(includeText = true)) }
            putJsonArray("activity") {
                run.log.entries.value.forEach { e ->
                    add(buildJsonObject {
                        put("n", e.n)
                        put("at", Instant.ofEpochMilli(e.atMs).toString())
                        put("kind", e.kind.name.lowercase())
                        put("name", e.name)
                        put("args", e.args)
                        put("result", e.result)
                        put("duration_ms", e.durationMs)
                        e.confidence?.let { put("confidence", it) }
                        e.costUsd?.let { put("cost_usd", it) }
                        e.tokens?.let { put("tokens", it) }
                        if (e.failed) put("failed", true)
                        e.image?.let { put("image", it) }
                    })
                }
            }
            put("ended", run.endedAt != null)
            put("elapsed_ms", end - run.startedAt)
        }
    }

    private fun stepJson(s: UseStep): JsonObject = buildJsonObject {
        put("index", s.index)
        put("op", s.op.name)
        put("description", s.description)
        s.x?.let { put("x", it) }; s.y?.let { put("y", it) }
        s.xPct?.let { put("x_pct", it) }; s.yPct?.let { put("y_pct", it) }
        s.box?.let { b -> putJsonObject("box") { put("top", b.top); put("bottom", b.bottom); put("left", b.left); put("right", b.right) } }
        s.text?.let { put("text", it) }
        s.textSource?.let { put("text_source", it) }
        put("confidence", s.confidence)
        s.before?.scrollY?.let { put("scroll_y", it) }
        s.before?.url?.let { put("url", it) }
        s.tabId?.let { put("tab_id", it) }
        s.screenshotPath?.let { put("screenshot_path", it) }
        s.changed?.let { put("changed", it) }
        put("ms", s.ms)
        s.note?.let { put("note", it) }
        s.consult?.let { put("consult", it) }
        s.dialog?.let { put("dialog", it) }
        s.popup?.let { put("popup", it) }
        s.snapped?.let { n -> putJsonObject("snapped") { put("from", n.from); put("to", n.to); put("tag", n.tag); put("label", n.label) } }
        if (s.failed) put("failed", true)
    }

    companion object {
        val VALID_ID = Regex("[A-Za-z0-9_-]{1,80}")

        val defaultDir: File get() = File(System.getProperty("user.home"), ".boss/cache/llmrpa/runs")

        fun newId(): String =
            "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC).format(Instant.now()) +
                "-" + java.util.UUID.randomUUID().toString().take(4)
    }
}
