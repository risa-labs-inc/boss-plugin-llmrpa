package ai.rever.boss.plugin.dynamic.llmrpa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Options shown per question before "+N more"; the rest are in `llmrpa_status` with include_calls. */
private const val SHOWN_OPTIONS = 6

/** Characters of a chat reply or prompt shown; the stored text is capped at 8 KB. */
private const val SHOWN_CHARS = 1_200

internal fun usd(cost: Double): String = "$%.5f".format(cost)

/** "N model calls · $cost", collapsed; expanded, each call with its question, options or reply, pick and latency. */
@Composable
internal fun ModelCallsRow(calls: List<ModelCall>, modifier: Modifier = Modifier, label: String? = null, initiallyExpanded: Boolean = false) {
    if (calls.isEmpty()) return
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val cost = calls.sumOf { it.costUsd ?: 0.0 }
    val summary = listOfNotNull(label, "${calls.size} model ${if (calls.size == 1) "call" else "calls"}", cost.takeIf { it > 0 }?.let(::usd)).joinToString(" · ")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { expanded = !expanded }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp, vertical = 2.dp)
                .semantics { contentDescription = "$summary. ${if (expanded) "Hide" else "Show"} them" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SmallIcon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Text(summary, color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
        if (expanded) calls.forEach { CallCard(it) }
    }
}

@Composable
private fun CallCard(call: ModelCall) {
    Column(
        Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Content).border(1.dp, RpaTokens.Border, RpaTokens.Shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(call.kind.label, if (call.error != null) Tone.ERROR else Tone.NEUTRAL)
            Text("${call.tool} · ${call.model}", color = RpaTokens.TextMuted, fontSize = 11.sp, fontFamily = RpaTokens.Mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(listOfNotNull("${call.latencyMs} ms", call.costUsd?.takeIf { it > 0 }?.let(::usd)).joinToString(" · "), color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
        call.error?.let { Text(it, color = RpaTokens.Error, fontSize = 12.sp) }
        if (call.questions.isNotEmpty()) {
            call.questions.forEach { q ->
                Text(q.text, color = RpaTokens.TextSecondary, fontSize = 12.sp)
                val ranked = q.options.sortedByDescending { it.probability ?: -1.0 }
                ranked.take(SHOWN_OPTIONS).forEach { OptionBar(it, picked = it.key == q.pick) }
                if (ranked.size > SHOWN_OPTIONS) Text("+${ranked.size - SHOWN_OPTIONS} more", color = RpaTokens.TextMuted, fontSize = 11.sp)
            }
        } else if (call.request.dropped) {
            Text("${ModelCall.TEXT_DROPPED}.", color = RpaTokens.TextMuted, fontSize = 11.sp)
        } else {
            call.response?.let { r ->
                Text("Reply", color = RpaTokens.TextMuted, fontSize = 11.sp)
                Text(r.text.take(SHOWN_CHARS) + if (r.text.length > SHOWN_CHARS || r.truncated) "…" else "",
                    color = RpaTokens.Text, fontSize = 11.sp, fontFamily = RpaTokens.Mono,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(RpaTokens.Panel).padding(6.dp))
            }
        }
        val parsed = listOfNotNull(
            call.pick?.let { "Picked: $it" },
            call.confidence?.let { "${TaskRunner.pct(it)} sure" },
            call.risk?.let { "risk ${TaskRunner.pct(it)}" },
            listOfNotNull(call.inputTokens, call.outputTokens).takeIf { it.isNotEmpty() }?.let { "${it.sum()} tokens" },
        )
        if (parsed.isNotEmpty()) Text(parsed.joinToString(" · "), color = RpaTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OptionBar(option: CallOption, picked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (picked) "✓ " else "") + option.label, color = if (picked) RpaTokens.Text else RpaTokens.TextSecondary, fontSize = 12.sp,
            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        option.probability?.let { p ->
            Box(Modifier.width(60.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(RpaTokens.Raised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).background(if (picked) RpaTokens.Accent else RpaTokens.TextMuted))
            }
            Text(TaskRunner.pct(p), color = RpaTokens.TextMuted, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        }
    }
}
