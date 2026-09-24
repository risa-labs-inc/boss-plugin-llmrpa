package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class StepContext(
    val instruction: String,
    val page: PageSnapshot,
    val candidates: List<Candidate>,
    val values: List<String>,
    val history: List<String>,
)

/**
 * The model's pick for one step. [confidence] is a probability for Jev and the model's own
 * estimate for a chat model; [alternatives] are the runners-up, best first.
 */
data class Decision(
    val key: String,
    val confidence: Double,
    val alternatives: List<Pair<String, Double>> = emptyList(),
    val value: String? = null,
    /** True when [value] came from the model rather than the instruction. */
    val valueWritten: Boolean = false,
    /** Chance the chosen action submits, pays, sends or deletes; null when not assessed. */
    val risk: Double? = null,
    val reason: String? = null,
    val costUsd: Double = 0.0,
)

interface StepDecider {
    val option: ModelOption

    suspend fun decide(ctx: StepContext): Result<Decision>

    /** Chance that [action] cannot be undone. Called only for actions that could commit. Returns (risk, cost). */
    suspend fun risk(ctx: StepContext, action: Candidate): Result<Pair<Double, Double>>

    /** Chance the instruction is complete on the current page, asked when the model picks done. Returns (p, cost). */
    suspend fun verifyDone(ctx: StepContext): Result<Pair<Double, Double>>
}

/**
 * Jev picks each step with one `jev_decide` call: which action, and which instruction value to
 * type. It cannot write text, so a typed value is always one the instruction contains.
 */
class JevDecider(private val tools: ToolInvoker, override val option: ModelOption) : StepDecider {
    override suspend fun decide(ctx: StepContext): Result<Decision> {
        val reply = tools.invoke(ToolNames.JEV_DECIDE, decideArgs(ctx, option.modelId))
        if (reply.isError) return Result.failure(IllegalStateException(reply.errorMessage))
        return runCatching { parseDecision(reply.json ?: error("Jev returned no JSON"), ctx) }
    }

    override suspend fun risk(ctx: StepContext, action: Candidate): Result<Pair<Double, Double>> {
        val reply = tools.invoke(ToolNames.JEV_DECIDE, riskArgs(ctx, action, option.modelId))
        if (reply.isError) return Result.failure(IllegalStateException(reply.errorMessage))
        return runCatching {
            val response = reply.json!!["response"]!!.jsonObject
            val p = (response["answers"]!!.jsonObject["irreversible"]!!.jsonObject["noul"] as JsonPrimitive).doubleOrNull ?: 0.0
            p to cost(response)
        }
    }

    override suspend fun verifyDone(ctx: StepContext): Result<Pair<Double, Double>> {
        val reply = tools.invoke(ToolNames.JEV_DECIDE, verifyArgs(ctx, option.modelId))
        if (reply.isError) return Result.failure(IllegalStateException(reply.errorMessage))
        return runCatching {
            val response = reply.json!!["response"]!!.jsonObject
            val p = (response["answers"]!!.jsonObject["complete"]!!.jsonObject["noul"] as JsonPrimitive).doubleOrNull ?: 0.0
            p to cost(response)
        }
    }

    companion object {
        internal fun verifyArgs(ctx: StepContext, model: String) = buildJsonObject {
            put("model", model)
            put("state", state(ctx))
            putJsonObject("questions") {
                putJsonObject("complete") {
                    put("type", "noul")
                    put("instructions", "Judging only by the current page title and address and what was done, is the instruction fully complete?")
                    putJsonObject("criteria") {
                        put("true", "The current page is the result the instruction asked for")
                        put("false", "The page is an intermediate step, a search or error page, or unrelated")
                    }
                }
            }
            put("timeout_ms", 30_000)
        }

        internal fun state(ctx: StepContext) = buildJsonObject {
            put("instruction", ctx.instruction)
            putJsonObject("page") { put("url", ctx.page.url); put("title", ctx.page.title) }
            put("done_so_far", buildJsonArray { ctx.history.forEach { add(JsonPrimitive(it)) } })
        }

        internal fun decideArgs(ctx: StepContext, model: String) = buildJsonObject {
            put("model", model)
            put("state", state(ctx))
            putJsonObject("questions") {
                putJsonObject("next") {
                    put("type", "choice")
                    put(
                        "instructions",
                        "Pick the single action on this page that best moves the instruction forward from what is done so far. " +
                            "Pick 'The task is complete' only if the instruction is fully done. Pick 'None of these' if no action helps.",
                    )
                    putJsonObject("criteria") { ctx.candidates.forEach { put(it.key, it.description) } }
                }
                if (ctx.values.isNotEmpty() && ctx.candidates.any { it.needsValue }) {
                    putJsonObject("value") {
                        put("type", "choice")
                        put("instructions", "If the next action types text, which of these values from the instruction should it type?")
                        putJsonObject("criteria") { ctx.values.forEachIndexed { i, v -> put("v${i + 1}", v) } }
                    }
                }
            }
            put("timeout_ms", 30_000)
        }

        internal fun riskArgs(ctx: StepContext, action: Candidate, model: String) = buildJsonObject {
            put("model", model)
            put("state", buildJsonObject {
                put("instruction", ctx.instruction)
                putJsonObject("page") { put("url", ctx.page.url); put("title", ctx.page.title) }
                put("next_action", action.description)
            })
            putJsonObject("questions") {
                putJsonObject("irreversible") {
                    put("type", "noul")
                    put("instructions", "Would the next action submit, pay, send, publish, or delete something that cannot be undone?")
                    putJsonObject("criteria") {
                        put("true", "It commits: an order, payment, message, submission, or deletion")
                        put("false", "It only navigates, searches, filters, opens, or edits a draft")
                    }
                }
            }
            put("timeout_ms", 30_000)
        }

        internal fun parseDecision(root: JsonObject, ctx: StepContext): Decision {
            val response = root["response"]!!.jsonObject
            val answers = response["answers"]!!.jsonObject
            val next = answers["next"]!!.jsonObject
            val key = (next["choice"] as JsonPrimitive).content
            val probs = (next["probabilities"] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).doubleOrNull ?: 0.0 }
            val ranked = probs.entries.sortedByDescending { it.value }
            val value = (answers["value"] as? JsonObject)?.let { v ->
                val pick = (v["choice"] as JsonPrimitive).content
                pick.removePrefix("v").toIntOrNull()?.let { ctx.values.getOrNull(it - 1) }
            }
            return Decision(
                key = key,
                confidence = probs[key] ?: 0.0,
                alternatives = ranked.filter { it.key != key }.take(2).map { it.key to it.value },
                value = value,
                costUsd = cost(response),
            )
        }

        private fun cost(response: JsonObject): Double =
            ((response["usage"] as? JsonObject)?.get("cost") as? JsonPrimitive)?.doubleOrNull ?: 0.0
    }
}

/**
 * Any chat model configured in Settings → AI Providers, reached through the AI Gateway with the
 * chosen provider and model. Unlike Jev it can write the text it types; the timeline marks
 * those values so the user can tell them from values in the instruction.
 */
class ChatDecider(
    private val gateway: () -> AiGatewayAPI?,
    override val option: ModelOption,
) : StepDecider {
    override suspend fun decide(ctx: StepContext): Result<Decision> {
        val api = runCatching { gateway() }.getOrNull() ?: return Result.failure(IllegalStateException("The AI Gateway plugin is not available"))
        routingProblem(api, option)?.let { return Result.failure(IllegalStateException(it)) }
        val first = request(prompt(ctx))
        val reply = api.complete(first).getOrElse { return Result.failure(it) }
        runCatching { parseReply(reply.text, ctx) }.onSuccess { return Result.success(it) }
        // Smaller and "free" routed models sometimes answer in prose. Ask once more, showing them
        // their own reply, before giving up on the step.
        val retry = first.copy(
            messages = first.messages + AiMessage.assistant(reply.text.take(2000)) +
                AiMessage.user("That was not the JSON object. Reply with only the JSON object described, using a key from the list."),
        )
        val second = api.complete(retry).getOrElse { return Result.failure(it) }
        return runCatching { parseReply(second.text, ctx) }.recoverCatching { e ->
            // Quote the reply: "did not reply with JSON" alone left nothing to act on, and an empty
            // reply (a reasoning model spending its budget thinking) looks different from prose.
            val said = second.text.trim().replace(Regex("\\s+"), " ").take(160)
            throw IllegalStateException("${e.message}. It replied: ${if (said.isEmpty()) "(nothing)" else "\"$said\""}")
        }
    }

    // The chat decision already carries the irreversible flag, so no second call.
    override suspend fun risk(ctx: StepContext, action: Candidate): Result<Pair<Double, Double>> =
        Result.failure(UnsupportedOperationException("assessed in decide"))

    override suspend fun verifyDone(ctx: StepContext): Result<Pair<Double, Double>> {
        val api = runCatching { gateway() }.getOrNull() ?: return Result.failure(IllegalStateException("The AI Gateway plugin is not available"))
        val user = buildString {
            appendLine("Instruction: ${ctx.instruction}")
            appendLine("Done so far: ${ctx.history.ifEmpty { listOf("nothing") }.joinToString("; ")}")
            appendLine("The browser is now on: \"${ctx.page.title}\" (${ctx.page.url})")
            append("Judging only by that page, is the instruction fully complete? Reply with only JSON: {\"complete\": true|false, \"confidence\": 0..1}")
        }
        val reply = api.complete(
            AiRequest(system = "You check whether a browser task is finished. Be strict: a search or results page is not an opened article.",
                messages = listOf(AiMessage.user(user)), temperature = 0f, maxTokens = 1_000, timeoutMs = 90_000, extras = routingExtras(option)),
        ).getOrElse { return Result.failure(it) }
        return runCatching {
            val obj = Json.parseToJsonElement(LlmApiClient.firstJsonObject(reply.text) ?: error("no JSON")).jsonObject
            val complete = (obj["complete"] as JsonPrimitive).booleanOrNull ?: false
            val confidence = ((obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.8).coerceIn(0.0, 1.0)
            (if (complete) confidence else 1 - confidence) to 0.0
        }
    }

    private fun request(user: String) = AiRequest(
        system = SYSTEM,
        messages = listOf(AiMessage.user(user)),
        temperature = 0f,
        // Room for reasoning models, which spend part of the budget thinking before the JSON.
        maxTokens = 2_000,
        timeoutMs = 90_000,
        extras = routingExtras(option),
    )

    companion object {
        /**
         * Extras that send one request to [option]'s provider and model. The keys are the api's
         * own constants (inlined at compile time, so safe on any host).
         */
        fun routingExtras(option: ModelOption): Map<String, String> = mapOf(
            AiRequest.EXTRAS_KEY_PROVIDER_ID to option.providerId,
            AiRequest.EXTRAS_KEY_MODEL_OVERRIDE to option.modelId,
        )

        /**
         * Why [option] cannot be reached, or null. A gateway without provider override would
         * silently answer with the active model instead, which would misreport who decided.
         */
        fun routingProblem(api: AiGatewayAPI, option: ModelOption): String? {
            val caps = runCatching { api.capabilities() }.getOrDefault(emptySet())
            if (AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE in caps) return null
            val active = runCatching { api.activeModel() }.getOrNull()
            return if (active != null && active.providerId == option.providerId && active.modelId == option.modelId) null
            else "This AI Gateway can only use the model selected in Settings → AI Providers (${active?.modelId ?: "none"}). Update AI Gateway, or pick that model."
        }

        private val SYSTEM = """
You operate a web browser one step at a time to complete the user's instruction.
Each turn you get the instruction, the current page, what is already done, and a list of actions.
Reply with only a JSON object:
{"action": "<key from the list>", "value": "<text to type, only for a Type action, else null>",
 "confidence": <0..1>, "irreversible": <true if the action submits, pays, sends, publishes or deletes>,
 "reason": "<one short sentence>"}
Use "done" when the instruction is complete and "stuck" when no action helps. Never invent keys.
"Download image" saves a picture to the user's Downloads folder.
Prefer values the instruction states. Never type passwords or payment details unless the instruction gives them.
        """.trimIndent()

        internal fun prompt(ctx: StepContext): String = buildString {
            appendLine("Instruction: ${ctx.instruction}")
            appendLine("Page: ${ctx.page.title} (${ctx.page.url})")
            appendLine("Done so far: ${ctx.history.ifEmpty { listOf("nothing") }.joinToString("; ")}")
            if (ctx.values.isNotEmpty()) appendLine("Values in the instruction: ${ctx.values.joinToString(" | ")}")
            appendLine("Actions:")
            ctx.candidates.forEach { appendLine("${it.key}: ${it.description}") }
        }

        internal fun parseReply(text: String, ctx: StepContext): Decision {
            val obj = LlmApiClient.firstJsonObject(text)?.let { Json.parseToJsonElement(it).jsonObject }
                ?: error("The model did not reply with JSON")
            val key = (obj["action"] as? JsonPrimitive)?.content?.trim() ?: error("The model named no action")
            require(ctx.candidates.any { it.key == key }) { "The model picked '$key', which is not one of the actions" }
            val value = (obj["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return Decision(
                key = key,
                confidence = ((obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.5).coerceIn(0.0, 1.0),
                value = value,
                valueWritten = value != null && value !in ctx.values,
                risk = (obj["irreversible"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) 1.0 else 0.0 },
                reason = (obj["reason"] as? JsonPrimitive)?.content,
            )
        }
    }
}
