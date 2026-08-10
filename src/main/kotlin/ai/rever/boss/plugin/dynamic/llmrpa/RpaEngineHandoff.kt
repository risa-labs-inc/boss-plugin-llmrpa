package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes a generated plan where the RPA Engine will find it.
 *
 * This plugin generated action lists that nothing could run: the engine loads configurations from
 * disk (`RpaEngineSettings` scans `~/.boss/config/rpaengine`, the recorder's directory, and
 * Downloads) and there was no path from here into any of them. So "Execute Instruction" produced a
 * plan and stopped, with a ▶ icon promising otherwise.
 *
 * A file rather than an API call, because that is the interface the engine already has - the RPA
 * Recorder hands off the same way. No new cross-plugin surface, and the plan is inspectable and
 * re-runnable afterwards.
 *
 * **Deliberately does not run anything.** Driving a browser is not something to start without a
 * person deciding to: the engine's own Run button stays the trigger. This gets the plan in front
 * of it.
 */
internal object RpaEngineHandoff {
    // Lazy: an api-provided symbol resolved in an object initializer fails with NoSuchMethodError
    // on a host built against a different api revision, and an Error slips straight past the
    // `catch (e: Exception)` around the caller, taking the coroutine down.
    private val logger by lazy { BossLogger.forComponent("LlmRpaHandoff") }

    // explicitNulls = false because another plugin parses this. kotlinx throws on an explicit
    // null for a non-nullable-with-default field, so if the engine ever gives `value` a default,
    // every action without a value (submit, a bare wait) would make the whole file unreadable.
    // It cuts the other way too: omitting a key is equally fatal for a nullable field with no
    // default. The engine's SelectorInfo.value is exactly that shape, and it tolerates the
    // omission today - a generated `navigate` has no selector value and loads fine - so this is
    // the exercised direction, not an assumption.
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Where the engine looks first. Created on demand, as the engine itself does.
     *
     * A parameter on [writeResult] rather than only this default, because the first version of the
     * tests wrote into the real directory and left four junk configurations in the user's engine
     * list. A path read from `user.home` with no seam is not testable without side effects.
     */
    private val defaultConfigDir: File
        get() = File(System.getProperty("user.home"), ".boss/config/rpaengine")

    /**
     * The engine's own on-disk shape.
     *
     * A separate model from [LLMRpaResponse] because the two disagree in ways that would silently
     * produce a config the engine cannot read: it wraps actions in `{name, description, actions}`
     * rather than `{configuration, status, message}`, and it names the field `actionType` where
     * this plugin (and its prompt) say `action_type`. Mapping in one place is what keeps that from
     * being discovered at run time.
     */
    @Serializable
    private data class EngineConfiguration(
        val name: String,
        val description: String = "",
        val actions: List<EngineAction>,
    )

    @Serializable
    private data class EngineAction(
        val name: String = "",
        // Pinned explicitly: this is the *engine's* field name, and renaming the property to
        // match this plugin's own model would silently change the on-disk format. Note it carries
        // the constant "default" - the verb that decides what runs travels in `type` below, whose
        // name happens to agree on both sides. This one has to be right for the file to parse;
        // `type` has to be right for the plan to do anything.
        @SerialName("actionType") val actionType: String = "default",
        val type: String,
        // SelectorInfo is shared with this plugin's own model on purpose: both sides agree on
        // type/value/isUnique, and mirroring it would mean maintaining two identical shapes.
        // A rename there does change the on-disk format, so it is a deliberate coupling.
        val selector: SelectorInfo,
        val value: String? = null,
        // Never omitted. explicitNulls = false drops a null key entirely, and if the engine ever
        // declares `meta` without a default, an action the model left it off would make the whole
        // configuration unreadable - the user sees an empty config rather than an error.
        val meta: Map<String, String> = emptyMap(),
    )

    /**
     * Writes [actions] as a configuration named after [instruction], keeping any failure so the
     * caller can name it to the user.
     *
     * `runCatching` here catches [Throwable], which is deliberate for a best-effort side channel:
     * a `NoClassDefFoundError` from a host/api mismatch should degrade to "could not save" rather
     * than take the generating coroutine down with it.
     */
    fun writeResult(
        instruction: String,
        actions: List<RpaActionConfig>,
        configDir: File = defaultConfigDir,
    ): Result<File> =
        runCatching {
            val dir = configDir.apply { mkdirs() }
            val file = File(dir, "${safeName(instruction)}.json")
            val config =
                EngineConfiguration(
                    name = instruction.trim().take(MAX_NAME_CHARS).ifBlank { "LLM RPA plan" },
                    description = "Generated by LLM RPA from: $instruction",
                    actions = actions.map { it.toEngineAction() },
                )
            // Write to a sibling and move atomically. The engine scans this directory on its own
            // schedule, so a plain writeText - which truncates first - lets a scan landing mid-write
            // read half a file, and re-running the same instruction overwrites in place by design,
            // which is exactly when that collision is likely.
            // A unique staging file per write. Derived from the instruction it was the *same*
            // path for two concurrent runs of the same instruction, so one could move a
            // half-written file into place - exactly the interleaving the atomic move rules out,
            // in the case named right above as the likely one.
            val staging = Files.createTempFile(dir.toPath(), file.name, ".part").toFile()
            // finally, not just a catch around the move: encodeToString and writeText can throw
            // too (full disk, quota, permissions) and those are exactly the paths that repeat, so
            // the .part sibling would accumulate in the user's engine directory. After a successful
            // move the delete is a no-op.
            try {
                staging.writeText(json.encodeToString(EngineConfiguration.serializer(), config))
                Files.move(
                    staging.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Real on some macOS and Windows volumes. A non-atomic replace is worse than an
                // atomic one and far better than "could not save" forever on that machine.
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (staging.exists()) staging.delete()
            }
            file
        }.onFailure { error ->
            // Inside its own guard: `logger` is lazy precisely so an api mismatch degrades rather
            // than propagating, and this is where the initialiser first runs - outside the
            // runCatching above, a NoSuchMethodError here would defeat the whole arrangement and
            // slip past the caller's `catch (e: Exception)`.
            runCatching {
                logger.warn(
                    LogCategory.GENERAL,
                    "Could not write the generated plan for the RPA Engine",
                    mapOf("error" to (error.message ?: "unknown")),
                )
            }
        }

    private fun RpaActionConfig.toEngineAction() =
        EngineAction(
            name = name,
            actionType = action_type,
            type = type,
            selector = selector,
            value = value,
            meta = meta ?: emptyMap(),
        )

    /**
     * A filename for [instruction]: a readable slug plus a hash of the whole instruction.
     *
     * Word characters only, so nothing in a sentence can name a path or collide with the
     * engine's own `settings.json`.
     *
     * `take` runs *before* `trim`, so a cut landing on a separator does not leave a trailing
     * hyphen. The hash is what keeps the name honest: the slug is capped at
     * [MAX_SLUG_CHARS], so "...invoices from january" and "...invoices from february" truncate
     * to the same characters and the second write would silently replace the first plan in the
     * user's engine list. Re-running the *same* instruction still overwrites itself, which is
     * what you want.
     */
    private fun safeName(instruction: String): String {
        val slug =
            instruction
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .take(MAX_SLUG_CHARS)
                .trim('-')
                .ifBlank { "plan" }
        return "llm-rpa-$slug-${instruction.hashCode().toUInt().toString(radix = 16)}"
    }

    private const val MAX_SLUG_CHARS = 40
    private const val MAX_NAME_CHARS = 80
}
