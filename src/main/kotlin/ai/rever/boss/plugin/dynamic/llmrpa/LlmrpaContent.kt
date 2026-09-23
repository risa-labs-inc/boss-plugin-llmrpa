package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** At and above this width, compose and the run sit side by side. */
internal val TwoPaneMinWidth: Dp = 640.dp

/** Below this width the header drops secondary text and rows stack. */
internal val CompactWidth: Dp = 360.dp

/** The tab's address shows beside its title only when both fit. */
internal val HostVisibleWidth: Dp = 420.dp

/** Readable measure for pane content on very wide windows. */
internal val PaneMaxWidth: Dp = 760.dp

private val EXAMPLES = listOf(
    "Fill a form" to "Put the name \"Ada Lovelace\" and email \"ada@example.com\" in the contact form",
    "Find something" to "Search for \"noise cancelling headphones\" and open the top-rated result",
    "Go somewhere" to "Open the Orders page",
)

@Composable
fun LlmrpaContent(component: LlmrpaComponent) {
    val instruction by component.currentInstruction.collectAsState()
    val tabs by component.availableTabs.collectAsState()
    val selectedTab by component.selectedTab.collectAsState()
    val groups by component.modelGroups.collectAsState()
    val model by component.selectedModel.collectAsState()
    val run by component.run.collectAsState()
    val pastRuns by component.pastRuns.collectAsState()
    val maxSteps by component.maxSteps.collectAsState()
    val drafting by component.isGenerating.collectAsState()
    val draftPath by component.handoffPath.collectAsState()
    val errorMessage by component.errorMessage.collectAsState()
    val blocker by component.readiness.collectAsState()
    val active = run?.status == RunStatus.RUNNING || run?.status == RunStatus.WAITING

    BossTheme {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(RpaTokens.Panel).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = e.isMetaPressed || e.isCtrlPressed
                val question = run?.question
                when {
                    mod && e.key == Key.Enter -> { if (!active) component.startRun(); true }
                    e.key == Key.Escape && active -> { component.stopRun(); true }
                    question is PendingQuestion.Choose && e.key in NUMBER_KEYS -> {
                        question.options.getOrNull(NUMBER_KEYS.indexOf(e.key))?.let { component.answer(Answer.Pick(it.first)) }
                        true
                    }
                    else -> false
                }
            },
        ) {
            val twoPane = maxWidth >= TwoPaneMinWidth
            val compact = maxWidth < CompactWidth
            val showHost = maxWidth >= HostVisibleWidth
            Column(Modifier.fillMaxSize()) {
                Header(component, tabs, selectedTab, groups, model, blocker, compact, showHost, active)
                Box(Modifier.fillMaxWidth().height(1.dp).background(RpaTokens.Border))
                if (twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            Compose(component, instruction, model, blocker, active, drafting, draftPath, errorMessage, maxSteps, collapsed = false)
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(RpaTokens.Border))
                        Box(Modifier.weight(1.1f).fillMaxHeight()) { RunPane(component, run, pastRuns, active) }
                    }
                } else {
                    // Sidebar: compose above the run; while a run is going the box shrinks so
                    // the timeline gets the height.
                    Compose(component, instruction, model, blocker, active, drafting, draftPath, errorMessage, maxSteps, collapsed = active)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(RpaTokens.Border))
                    Box(Modifier.weight(1f).fillMaxWidth()) { RunPane(component, run, pastRuns, active) }
                }
            }
        }
    }
}

private val NUMBER_KEYS = listOf(Key.One, Key.Two, Key.Three)

@Composable
private fun Header(
    component: LlmrpaComponent,
    tabs: List<ActiveTabData>,
    selected: ActiveTabData?,
    groups: List<ModelGroup>,
    model: ModelOption?,
    blocker: LlmrpaComponent.Blocker?,
    compact: Boolean,
    showHost: Boolean,
    active: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) { TabPicker(component, tabs, selected, showHost, enabled = !active) }
        ModelPicker(component, groups, model, blocker, compact, enabled = !active)
    }
}

/** The tab every action lands on. Always visible, because it is where clicks will happen. */
@Composable
private fun TabPicker(component: LlmrpaComponent, tabs: List<ActiveTabData>, selected: ActiveTabData?, showHost: Boolean, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RpaTokens.Shape).clickable(enabled = enabled, role = Role.Button) { open = true }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 4.dp)
                .semantics { contentDescription = "Target tab: ${selected?.title ?: "none"}. Change" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallIcon(Icons.Outlined.Public, null, if (selected == null) RpaTokens.Warning else RpaTokens.TextSecondary)
            Text(selected?.title?.ifBlank { null } ?: if (tabs.isEmpty()) "No web page open" else "Pick a tab",
                color = if (selected == null) RpaTokens.Warning else RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (showHost && selected != null) {
                Text(host(selected.url), color = RpaTokens.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
            SmallIcon(Icons.Outlined.ExpandMore, null)
        }
        if (open) {
            RpaMenu(onDismiss = { open = false }, width = 300) {
                MenuHeading("Run on")
                if (tabs.isEmpty()) {
                    Text("Open a web page in a browser tab, then pick it here.", color = RpaTokens.TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                tabs.forEach { t ->
                    MenuRow(t.title.ifBlank { host(t.url) }, onClick = { open = false; component.selectTab(t) }, detail = host(t.url),
                        selected = t.tabId == selected?.tabId)
                }
            }
        }
    }
}

/** Which model picks the steps, and whether everything needed to run is in place. */
@Composable
private fun ModelPicker(
    component: LlmrpaComponent,
    groups: List<ModelGroup>,
    model: ModelOption?,
    blocker: LlmrpaComponent.Blocker?,
    compact: Boolean,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val ready = blocker == null || blocker == LlmrpaComponent.Blocker.TAB
    val label = model?.label ?: "Pick a model"
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .border(1.dp, if (ready) RpaTokens.Border else RpaTokens.Warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button) { open = true; component.refreshModels() }
                .pointerHoverIcon(PointerIcon.Hand).padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp)
                .semantics { contentDescription = "Model: $label. ${if (ready) "Ready" else blocker!!.short}. Change" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ready) RpaTokens.Success else RpaTokens.Warning))
            Text(if (compact) label.take(14) else label, color = if (ready) RpaTokens.TextSecondary else RpaTokens.Warning,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            SmallIcon(Icons.Outlined.ExpandMore, null)
        }
        if (open) {
            RpaMenu(onDismiss = { open = false; query = "" }, width = 320) {
                MenuSearch(query, { query = it }, "Search models")
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (groups.isEmpty()) {
                        Text(LlmrpaComponent.Blocker.MODEL.detail, color = RpaTokens.TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    groups.forEach { g ->
                        val shown = g.models.filter { query.isBlank() || it.label.contains(query, true) || it.modelId.contains(query, true) || g.providerName.contains(query, true) }
                        if (shown.isNotEmpty()) {
                            MenuHeading(g.providerName, g.note ?: if (g.kind == ModelOption.Kind.CHAT) "Chat model" else null)
                            shown.take(200).forEach { m ->
                                MenuRow(m.label, onClick = { open = false; query = ""; component.selectModel(m) },
                                    detail = if (m.label != m.modelId) m.modelId else null, selected = m.key == model?.key)
                            }
                        }
                    }
                }
                if (blocker != null && blocker != LlmrpaComponent.Blocker.TAB) {
                    MenuDivider()
                    Text(blocker.detail, color = RpaTokens.Warning, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                if (component.canOpenProviderSettings()) {
                    MenuDivider()
                    MenuRow("Open AI Providers settings", onClick = { open = false; component.openProviderSettings() })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Compose(
    component: LlmrpaComponent,
    instruction: String,
    model: ModelOption?,
    blocker: LlmrpaComponent.Blocker?,
    active: Boolean,
    drafting: Boolean,
    draftPath: String?,
    errorMessage: String?,
    maxSteps: Int,
    collapsed: Boolean,
) {
    val values = remember(instruction) { Candidates.values(instruction) }
    val decision = model?.kind == ModelOption.Kind.DECISION
    Column(
        Modifier.fillMaxWidth().widthIn(max = PaneMaxWidth).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("What should happen?", Modifier.semantics { heading() })
        RpaTextArea(
            instruction, component::updateInstruction, Modifier.fillMaxWidth(),
            placeholder = "e.g. Search for \"wireless keyboard\", sort by price low to high, and open the first result",
            label = "Instruction", minHeight = if (collapsed) 44 else 92, readOnly = active,
        )
        if (!collapsed && instruction.isNotBlank()) {
            // What can be typed, shown before the run: a decision model types only these.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (decision) "Jev can type:" else "From your instruction:", color = RpaTokens.TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterVertically))
                values.forEach { Pill(it, Tone.ACCENT, mono = true) }
                if (values.isEmpty()) {
                    Text(if (decision) "nothing yet. Put text to type in quotes." else "no quoted values; the model may write its own.",
                        color = if (decision) RpaTokens.Warning else RpaTokens.TextMuted, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        // Hidden while a run is going: Stop lives in the run header, one place only.
        if (!active) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val center = Modifier.align(Alignment.CenterVertically)
                BossPrimaryButton("Run", onClick = { component.startRun() }, modifier = center.height(34.dp),
                    enabled = instruction.isNotBlank() && blocker == null, icon = Icons.Outlined.PlayArrow)
                OutlineButton(if (drafting) "Drafting…" else "Draft steps", { component.generateActions() }, center,
                    enabled = instruction.isNotBlank() && !drafting && component.aiAvailable() && blocker != LlmrpaComponent.Blocker.TAB)
                Box(center) { StepLimit(component, maxSteps, enabled = true) }
            }
        }
        if (!active && blocker != null && instruction.isNotBlank()) Notice(blocker.detail, Tone.WARNING)
        errorMessage?.let { Notice(it, Tone.ERROR, onDismiss = component::clearError) }
        draftPath?.let { Notice("Draft saved for RPA Engine as ${it.substringAfterLast('/')}. Load it there to run the whole plan.", Tone.SUCCESS) }
        if (!collapsed && model != null) {
            val where = if (decision) "OpenRouter" else model.providerName
            Text("Each step sends element labels, the page address and your instruction to $where. Never field values or passwords.",
                color = RpaTokens.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StepLimit(component: LlmrpaComponent, maxSteps: Int, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlineButton("$maxSteps steps", { open = true }, enabled = enabled, tone = Tone.NEUTRAL, trailing = "▾")
        if (open) {
            RpaMenu(onDismiss = { open = false }, width = 200) {
                MenuHeading("Stop after")
                listOf(5, 12, 25, 50).forEach { n ->
                    MenuRow("$n steps", onClick = { open = false; component.setMaxSteps(n) }, selected = n == maxSteps)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, tone: Tone, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Content)
            .border(1.dp, tone.color().copy(alpha = 0.55f), RpaTokens.Shape).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = RpaTokens.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        onDismiss?.let { LinkText("Dismiss", it, color = RpaTokens.TextSecondary) }
    }
}

@Composable
private fun RunPane(component: LlmrpaComponent, run: RunState?, pastRuns: List<RunState>, active: Boolean) {
    Column(Modifier.fillMaxSize().background(RpaTokens.Content)) {
        if (run != null) RunHeader(component, run, active)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.widthIn(max = PaneMaxWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run == null) {
                    Empty(component)
                } else {
                    run.steps.forEach { StepRow(it, current = active && it == run.steps.last() && it.outcome == StepRecord.Outcome.RUNNING) }
                    if (active && run.question == null && run.steps.lastOrNull()?.outcome != StepRecord.Outcome.RUNNING) {
                        Text("Reading the page…", color = RpaTokens.TextSecondary, fontSize = 12.sp)
                    }
                    run.question?.let { QuestionCard(component, it) }
                    if (!active) ResultCard(component, run)
                }
            }
        }
        if (pastRuns.isNotEmpty()) PastRuns(component, pastRuns)
    }
}

@Composable
private fun RunHeader(component: LlmrpaComponent, run: RunState, active: Boolean) {
    val status = when (run.status) {
        RunStatus.RUNNING -> "Running"
        RunStatus.WAITING -> "Waiting for you"
        RunStatus.DONE -> "Done"
        RunStatus.STOPPED -> "Stopped"
        RunStatus.FAILED -> "Failed"
    }
    Row(
        Modifier.fillMaxWidth().background(RpaTokens.Panel).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(status, color = RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Step ${run.steps.size} of up to ${run.maxSteps} · ${run.calls} calls" + if (run.costUsd > 0) " · $%.5f".format(run.costUsd) else "",
            color = RpaTokens.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (active) OutlineButton("Stop", component::stopRun, tone = Tone.ERROR)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(RpaTokens.Border))
}

@Composable
private fun StepRow(step: StepRecord, current: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val (mark, tone) = when (step.outcome) {
        StepRecord.Outcome.OK -> "✓" to Tone.SUCCESS
        StepRecord.Outcome.FAILED -> "!" to Tone.ERROR
        StepRecord.Outcome.RUNNING -> "${step.index}" to Tone.ACCENT
    }
    val outcomeWords = when (step.outcome) {
        StepRecord.Outcome.OK -> "done"
        StepRecord.Outcome.FAILED -> "failed"
        StepRecord.Outcome.RUNNING -> "in progress"
    }
    Row(
        Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape)
            .clickable(role = Role.Button) { expanded = !expanded }.padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Step ${step.index} $outcomeWords: ${step.description}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, tone.color(), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(mark, color = tone.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(step.description, color = RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val notes = buildList {
                if (step.chosenBy == StepRecord.ChosenBy.USER) add("you picked this")
                if (step.valueWritten) add("text written by the model")
                step.detail?.let { add(it) }
            }
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = if (step.outcome == StepRecord.Outcome.FAILED) RpaTokens.Error else RpaTokens.TextSecondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${TaskRunner.pct(step.confidence)} sure", color = RpaTokens.TextSecondary, fontSize = 11.sp)
                Box(Modifier.width(120.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(RpaTokens.Raised)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(step.confidence.toFloat().coerceIn(0f, 1f)).background(RpaTokens.Accent))
                }
            }
            if ((current || expanded) && step.alternatives.isNotEmpty()) {
                step.alternatives.forEach { (d, p) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Next choice: $d", color = RpaTokens.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TaskRunner.pct(p), color = RpaTokens.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** A question takes focus and is announced, so it is never missed. Keys 1–3 answer a choice. */
@Composable
private fun QuestionCard(component: LlmrpaComponent, q: PendingQuestion) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(q) { runCatching { focus.requestFocus() } }
    val risk = q is PendingQuestion.Confirm
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RpaTokens.Panel)
            .border(1.dp, (if (risk) RpaTokens.Error else RpaTokens.Warning).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .focusRequester(focus).focusable().padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (q) {
            is PendingQuestion.Choose -> {
                Text("Which should I do?", color = RpaTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("${q.reason}. Pick one, or stop here.", color = RpaTokens.TextSecondary, fontSize = 12.sp)
                q.options.forEachIndexed { i, (c, p) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RpaTokens.Shape).background(RpaTokens.Content)
                            .border(1.dp, RpaTokens.Border, RpaTokens.Shape)
                            .clickable(role = Role.Button) { component.answer(Answer.Pick(c)) }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${i + 1}", color = RpaTokens.TextMuted, fontSize = 11.sp, fontFamily = RpaTokens.Mono)
                        Text(c.description, color = RpaTokens.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(TaskRunner.pct(p), color = RpaTokens.TextSecondary, fontSize = 12.sp)
                    }
                }
                OutlineButton("Stop here", { component.answer(Answer.Stop) }, tone = Tone.ERROR)
            }
            is PendingQuestion.Confirm -> {
                Text("This looks like it can't be undone", color = RpaTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("Next: ${q.action.description}. ${TaskRunner.pct(q.risk)} likely to submit, pay, send, or delete something.",
                    color = RpaTokens.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BossPrimaryButton("Continue", onClick = { component.answer(Answer.Proceed) }, modifier = Modifier.height(34.dp))
                    OutlineButton("Stop", { component.answer(Answer.Stop) }, tone = Tone.ERROR)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(component: LlmrpaComponent, run: RunState) {
    val tone = if (run.status == RunStatus.DONE) Tone.SUCCESS else Tone.ERROR
    val title = when (run.status) {
        RunStatus.DONE -> "Done"
        RunStatus.FAILED -> "Could not finish"
        else -> "Stopped"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RpaTokens.Panel)
            .border(1.dp, tone.color().copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = RpaTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        run.summary?.let { Text(it, color = RpaTokens.TextSecondary, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Run again", { component.reuse(run); component.startRun() })
        }
    }
}

@Composable
private fun Empty(component: LlmrpaComponent) {
    Text("Steps show up here as the model works through the task, each with how sure it was. You can stop at any time.",
        color = RpaTokens.TextSecondary, fontSize = 12.sp)
    EXAMPLES.forEach { (title, text) ->
        Column(
            Modifier.fillMaxWidth().clip(RpaTokens.Shape).background(RpaTokens.Panel).border(1.dp, RpaTokens.Border, RpaTokens.Shape)
                .clickable(role = Role.Button) { component.applyQuickExample(text) }.pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = RpaTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = RpaTokens.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PastRuns(component: LlmrpaComponent, runs: List<RunState>) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(RpaTokens.Border))
    Row(
        Modifier.fillMaxWidth().background(RpaTokens.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Earlier:", color = RpaTokens.TextMuted, fontSize = 12.sp)
        runs.forEach { r ->
            val mark = if (r.status == RunStatus.DONE) "✓" else "·"
            LinkText("$mark ${r.instruction.take(36)}${if (r.instruction.length > 36) "…" else ""}", { component.reuse(r) })
        }
    }
}

