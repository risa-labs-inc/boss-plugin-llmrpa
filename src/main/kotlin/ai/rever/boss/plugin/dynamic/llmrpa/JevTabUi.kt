package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.ui.BossPrimaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val JEV_EXAMPLES = listOf(
    "Research" to "Find the top 10 restaurants in Palo Alto and list their ratings",
    "Look up" to "What are the opening hours of the Computer History Museum?",
)

/** The Task / Jev switch above the panel. */
@Composable
internal fun PanelTabs(component: LlmrpaComponent, current: LlmrpaComponent.PanelTab, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(LlmrpaComponent.PanelTab.TASK to "Task", LlmrpaComponent.PanelTab.JEV to "Jev").forEach { (tab, label) ->
            val on = tab == current
            Text(
                label, color = if (on) RpaTokens.Text else RpaTokens.TextSecondary, fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RpaTokens.Shape).border(1.dp, if (on) RpaTokens.Accent else RpaTokens.Border, RpaTokens.Shape)
                    .clickable(enabled = enabled && !on, role = Role.Tab) { component.selectPanelTab(tab) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { contentDescription = "$label tab${if (on) ", selected" else ""}" },
            )
        }
    }
}

/** The Jev tab: a request box, the live run (plan, steps, report) and the activity log of every call. */
@Composable
internal fun JevTab(component: LlmrpaComponent) {
    val run by component.useRun.collectAsState()
    val input by component.useInput.collectAsState()
    val error by component.errorMessage.collectAsState()
    val current = run
    val active = current?.phase?.collectAsState()?.value?.let { it != UsePhase.FINISHED } == true
    Column(Modifier.fillMaxSize().background(RpaTokens.Content)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.widthIn(max = PaneMaxWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current == null) JevIntro(component) else key(current.startedAt) { JevRunView(component, current, active) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RpaTokens.Border))
        Composer(component, input, active, error)
    }
}

@Composable
private fun JevIntro(component: LlmrpaComponent) {
    Text(
        "Ask for something on the web. A chat model picks the page, then Jev works the page from screenshots with a visible pointer, and a report with what happened and the final page's text lands here.",
        color = RpaTokens.TextSecondary, fontSize = 12.sp,
    )
    JEV_EXAMPLES.forEach { (title, text) ->
        Column(
            Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape)
                .clickable(role = Role.Button) { component.updateUseInput(text) }.pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = RpaTokens.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Composer(component: LlmrpaComponent, input: String, active: Boolean, error: String?) {
    val blocker = component.useBlocker()
    Column(Modifier.fillMaxWidth().background(RpaTokens.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RpaTextArea(
            input, component::updateUseInput, Modifier.fillMaxWidth(),
            placeholder = "e.g. Find the top 10 restaurants in Palo Alto", label = "Request for Jev", minHeight = 44, readOnly = active,
        )
        if (!active) ModelRow(component, enabled = true)
        if (blocker != null && input.isNotBlank() && !active) Notice(blocker, Tone.WARNING)
        error?.let { Notice(it, Tone.ERROR, onDismiss = component::clearError) }
        if (active) OutlineButton("Stop", component::stopUse, tone = Tone.ERROR)
        else {
            BossPrimaryButton("Send", onClick = { component.startUse()?.let { component.showError(it) } },
                modifier = Modifier.height(34.dp), enabled = input.isNotBlank() && blocker == null, icon = Icons.Outlined.PlayArrow)
            Text("Every step sends a screenshot of the page to the Jev model. The chat model sees your request, and the field and recent steps when it writes text.",
                color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
    }
}

/** Jev model, planner model and pointer label: the three switches of a request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(component: LlmrpaComponent, enabled: Boolean) {
    val jev by component.jevModelId.collectAsState()
    val planner by component.plannerModel.collectAsState()
    val groups by component.modelGroups.collectAsState()
    val pointer by component.pointerLabel.collectAsState()
    val llmTyping by component.llmTyping.collectAsState()
    var menu by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlineButton("Jev: ${jev.substringAfter('/')}", { menu = "jev" }, enabled = enabled, tone = Tone.NEUTRAL, trailing = "▾")
            if (menu == "jev") RpaMenu(onDismiss = { menu = null }, width = 260) {
                MenuHeading("Jev model", "needs images and a box")
                component.jevModels().forEach { id ->
                    val ok = Cu.supportsVision(id)
                    MenuRow(id.substringAfter('/'), onClick = { menu = null; component.selectJevModel(id) }, selected = id == jev,
                        detail = if (ok) null else "cannot read screenshots", enabled = ok)
                }
            }
        }
        Box {
            OutlineButton("Planner: ${planner?.label ?: "selected model"}", { menu = "planner"; component.refreshModels() }, enabled = enabled, tone = Tone.NEUTRAL, trailing = "▾")
            if (menu == "planner") RpaMenu(onDismiss = { menu = null }, width = 300) {
                MenuHeading("Chat model", "plans the page, writes the report")
                MenuRow("Selected in Settings", onClick = { menu = null; component.selectPlanner(null) }, selected = planner == null)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    groups.filter { it.kind == ModelOption.Kind.CHAT }.forEach { g ->
                        MenuHeading(g.providerName)
                        g.models.take(100).forEach { m ->
                            MenuRow(m.label, onClick = { menu = null; component.selectPlanner(m) }, selected = m.key == planner?.key,
                                detail = if (m.label != m.modelId) m.modelId else null)
                        }
                    }
                }
            }
        }
        OutlineButton("LLM typing: ${if (llmTyping) "on" else "off"}", { component.setLlmTyping(!llmTyping) }, enabled = enabled, tone = Tone.NEUTRAL)
        Box {
            OutlineButton("Pointer: ${pointer.ifBlank { "Jev" }}", { menu = "pointer" }, enabled = enabled, tone = Tone.NEUTRAL, trailing = "▾")
            if (menu == "pointer") RpaMenu(onDismiss = { menu = null }, width = 240) {
                MenuHeading("Label on the page pointer", "both modes")
                MenuSearch(pointer, component::setPointerLabel, "Pointer label")
            }
        }
    }
}

@Composable
private fun JevRunView(component: LlmrpaComponent, run: UseRun, active: Boolean) {
    val phase by run.phase.collectAsState()
    val plan by run.plan.collectAsState()
    val steps by run.steps.collectAsState()
    val question by run.question.collectAsState()
    val report by run.report.collectAsState()
    val activity by run.log.entries.collectAsState()

    Text(run.task, color = RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape).padding(10.dp))
    if (active) {
        Text(
            "${phase.label}…" + if (steps.isNotEmpty()) " · step ${steps.size}" else "",
            color = RpaTokens.TextSecondary, fontSize = 12.sp,
        )
    }
    plan?.let { p ->
        Column(
            Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SectionLabel("Plan")
            Text(p.goal, color = RpaTokens.Text, fontSize = 13.sp)
            p.open.firstOrNull()?.let { Text("Opens $it", color = RpaTokens.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
    steps.forEach { s -> key(s.index) { UseStepRow(s) } }
    question?.let { q ->
        Column(
            Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Warning.copy(alpha = 0.6f), RpaTokens.Shape).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(q.reason, color = RpaTokens.Text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Proceed", { component.answerUse(true) })
                OutlineButton("Stop", { component.answerUse(false) }, tone = Tone.ERROR)
            }
        }
    }
    if (!active) {
        report?.let { ReportCard(it) } ?: Text("Stopped before a report was written.", color = RpaTokens.TextSecondary, fontSize = 12.sp)
    }
    ActivitySection(activity, active)
}

@Composable
private fun UseStepRow(s: UseStep) {
    val tone = if (s.failed) Tone.ERROR else Tone.SUCCESS
    Column(
        Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Step ${s.index}: ${s.description}" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.index}", color = tone.color(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(s.description, color = RpaTokens.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${TaskRunner.pct(s.confidence)} sure", color = RpaTokens.TextSecondary, fontSize = 11.sp)
        }
        val notes = listOfNotNull(
            s.note?.takeIf { !it.startsWith("amount") },
            s.textSource?.let { "text $it" },
            if (s.changed == false) "the screen did not change" else null,
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = if (s.failed) RpaTokens.Error else RpaTokens.TextSecondary, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(r: UseReport) {
    val tone = when (r.status) { UseStatus.DONE -> Tone.SUCCESS; UseStatus.BLOCKED, UseStatus.BUDGET -> Tone.WARNING; UseStatus.ERROR -> Tone.ERROR }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RpaTokens.Panel).border(1.dp, tone.color().copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Report", color = RpaTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Pill(r.status.name.lowercase(), tone)
        }
        Text(r.summary, color = RpaTokens.TextSecondary, fontSize = 12.sp)
        r.pageText?.takeIf { it.isNotBlank() }?.let { PageTextBlock(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("%.1f s".format(r.seconds))
            Pill("${r.steps.size} steps")
            Pill("${r.jevCalls} Jev calls")
            Pill("${r.llmCalls} LLM calls")
            Pill("${r.toolCalls} tool calls")
            Pill("Jev " + (r.costUsd?.let(::usd) ?: "n/a") + " · LLM " + (r.llmTokens?.let { "$it tokens, cost n/a" } ?: "n/a"))
        }
        if (r.confidences.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SectionLabel("Confidence")
                r.confidences.forEach { c ->
                    Text("${c.op.name.lowercase().replace('_', ' ')}: ${c.n}x, min ${TaskRunner.pct(c.min)}, median ${TaskRunner.pct(c.median)}",
                        color = RpaTokens.TextSecondary, fontSize = 11.sp, fontFamily = RpaTokens.Mono)
                }
            }
        }
        Text("${r.jevModel} · ${r.llmModel}", color = RpaTokens.TextMuted, fontSize = 11.sp)
        r.screenshotPath?.let { Text(it, color = RpaTokens.TextMuted, fontSize = 11.sp, fontFamily = RpaTokens.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Every tool call of the request, newest last, live. */
@Composable
private fun ActivitySection(entries: List<ActivityEntry>, active: Boolean) {
    // Open while the request runs; folded once the report is in, where the answer matters more.
    var open by remember { mutableStateOf(active) }
    androidx.compose.runtime.LaunchedEffect(active) { if (!active) open = false }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("Activity")
            Text("${entries.size} calls ${if (open) "▾" else "▸"}", color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
        if (open) entries.forEach { e -> key(e.n) { ActivityRow(e) } }
    }
}

@Composable
private fun ActivityRow(e: ActivityEntry) {
    Column(
        Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(e.kind.label, when (e.kind) { ActivityKind.JEV -> Tone.ACCENT; else -> Tone.NEUTRAL })
            Text(e.name, color = RpaTokens.Text, fontSize = 12.sp, fontFamily = RpaTokens.Mono, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.durationMs} ms", color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
        Text(e.args, color = RpaTokens.TextSecondary, fontSize = 11.sp, fontFamily = RpaTokens.Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val tail = buildList {
            add(e.result)
            e.confidence?.let { add("${TaskRunner.pct(it)} sure") }
            e.costUsd?.let { add(usd(it)) }
        }.joinToString(" · ")
        Text(tail, color = if (e.failed) RpaTokens.Error else RpaTokens.TextSecondary, fontSize = 11.sp, fontFamily = RpaTokens.Mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** The final page's text: the first [EXCERPT_CHARS] characters, expandable to a scrollable view of all of it. */
@Composable
private fun PageTextBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val more = text.length > EXCERPT_CHARS
    Column(Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Content).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Final page text")
        val shown = if (expanded || !more) text else text.take(EXCERPT_CHARS) + "…"
        Box(if (expanded) Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()) else Modifier) {
            Text(shown, color = RpaTokens.Text, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (more) LinkText(if (expanded) "Show less" else "Show all ${text.length} characters", { expanded = !expanded })
    }
}

private const val EXCERPT_CHARS = 2_000
