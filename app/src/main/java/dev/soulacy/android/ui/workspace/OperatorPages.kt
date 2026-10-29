package dev.soulacy.android.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.LedgerRun
import dev.soulacy.android.api.Lesson
import dev.soulacy.android.api.Proof
import dev.soulacy.android.api.Workspace
import dev.soulacy.android.ui.components.MarkdownText
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
private fun statusColor(status: String): Color = when (status.lowercase()) {
    "success", "succeeded", "ok", "pass", "ready", "done", "active", "accepted", "applied", "stable" -> Soulacy.colors.success
    "failed", "fail", "rejected", "needs_review", "blocked", "expired" -> Soulacy.colors.coral
    "running", "pending", "warn", "canary", "todo", "draft" -> Soulacy.colors.mango
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun duration(ms: Long?): String = when {
    ms == null || ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

private fun pct(x: Double?): String = x?.let { "${(it * 100).toInt()}%" } ?: "n/a"

// --- Diagnostics ---

@Composable
fun DiagnosticsPage(ws: Workspace, address: String, appVersion: String, onBack: () -> Unit) {
    val status = rememberLoadable(address) { ws.mobileStatus() }
    SubPage("Diagnostics", onBack, onRefresh = status::refresh) { pad ->
        LoadableList(status, pad) { s ->
            item {
                SoulCard(accent = statusColor(s.status)) {
                    Row { Text("Mobile readiness", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill("${s.score}/100", statusColor(s.status)) }
                    Field("Checks", "${s.ready} of ${s.total} ready")
                    Field("Approvals", "${s.pendingApprovals} waiting")
                    Field("Agents", "${s.chatAgents} chat, ${s.scheduledAgents} scheduled")
                    Field("Delivery", "${s.deliveryChannels} channel(s)")
                    Field("Run ledger", if (s.durableRunLedger) "durable, ${s.recentRuns} recent" else "not durable")
                    Field("Gateway", address)
                    Field("App", appVersion)
                }
            }
            if (s.nextActions.isNotEmpty()) {
                item { SectionLabel("Suggested next") }
                items(s.nextActions) { a -> Notice(a, MaterialTheme.colorScheme.onSurface) }
            }
            item { SectionLabel("Checks") }
            items(s.checks, key = { it.key }) { c ->
                SoulCard {
                    Row { Text(c.label.ifBlank { c.key }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(c.status, statusColor(c.status)) }
                    if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- Activity ---

private data class ActivityData(val running: List<dev.soulacy.android.api.RunningSession>, val runs: List<LedgerRun>, val suggestions: List<dev.soulacy.android.api.Suggestion>, val runsError: String?)

@Composable
fun ActivityPage(ws: Workspace, address: String, agentName: (String) -> String, onBack: () -> Unit) {
    var open by remember { mutableStateOf<LedgerRun?>(null) }
    val data = rememberLoadable(address) {
        coroutineScope {
            val running = async { runCatching { ws.running() }.getOrDefault(emptyList()) }
            val runs = async { runCatching { ws.runs() } }
            val sug = async { runCatching { ws.suggestions() }.getOrDefault(emptyList()) }
            val r = runs.await()
            ActivityData(running.await(), r.getOrDefault(emptyList()), sug.await(), r.exceptionOrNull()?.let { Loadable.describe(it, address) })
        }
    }
    open?.let { run -> RunDetail(run, agentName) { open = null }; return }
    SubPage("Activity", onBack, onRefresh = data::refresh) { pad ->
        LoadableList(data, pad) { d ->
            if (d.suggestions.isNotEmpty()) {
                item { SectionLabel("Suggestions") }
                items(d.suggestions.take(4)) { s -> SoulCard { Text(s.title, fontWeight = FontWeight.SemiBold); Text(s.detail, style = MaterialTheme.typography.bodySmall) } }
            }
            item { SectionLabel("Running now") }
            if (d.running.isEmpty()) item { Notice("Nothing is running.") }
            items(d.running, key = { it.sessionId }) { r ->
                SoulCard(accent = if (r.hung) Soulacy.colors.coral else null) {
                    Row { Text(agentName(r.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(if (r.hung) "stuck" else duration(r.elapsedSeconds * 1000), if (r.hung) Soulacy.colors.coral else Soulacy.colors.mango) }
                    r.lastEventType?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall) }
                    r.hungReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral) }
                }
            }
            item { SectionLabel("Recent runs") }
            d.runsError?.let { item { Notice(it) } }
            items(d.runs, key = { it.key }) { r ->
                SoulCard(Modifier.clickable { open = r }) {
                    Row {
                        Text(r.agentName ?: r.agentId?.let(agentName) ?: "Run", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill(r.status, statusColor(r.status))
                    }
                    Text(listOfNotNull(shortTime(r.startedAt).ifBlank { null }, r.trigger, duration(r.durationMs).ifBlank { null }, r.steps?.let { "$it steps" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (r.failure?.summary ?: r.error)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral, maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun RunDetail(r: LedgerRun, agentName: (String) -> String, onBack: () -> Unit) {
    SubPage("Run", onBack) { pad ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SoulCard {
                    Row { Text(r.agentName ?: r.agentId?.let(agentName) ?: "Run", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(r.status, statusColor(r.status)) }
                    Field("Started", shortTime(r.startedAt))
                    Field("Duration", duration(r.durationMs).ifBlank { "n/a" })
                    r.trigger?.let { Field("Trigger", it) }
                    r.steps?.let { Field("Steps", "$it") }
                    r.deliveryStatus?.let { Field("Delivery", listOfNotNull(it, r.deliveryChannel).joinToString(" via ")) }
                    Field("Run id", r.key)
                }
            }
            r.failure?.let { f ->
                item {
                    SoulCard(accent = Soulacy.colors.coral) {
                        Text("What went wrong", fontWeight = FontWeight.SemiBold)
                        Text(f.summary)
                        f.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (f.nextAction.isNotBlank()) Text("Next: ${f.nextAction}", style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.brand)
                    }
                }
            }
            r.output?.takeIf { it.isNotBlank() }?.let { out -> item { SoulCard { Text("Output", fontWeight = FontWeight.SemiBold); MarkdownText(out.take(4000)) } } }
        }
    }
}

// --- Autopilot ---

@Composable
fun AutopilotPage(ws: Workspace, address: String, agentName: (String) -> String, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("proofs") }
    var proof by remember { mutableStateOf<Proof?>(null) }
    val summary = rememberLoadable(address) { ws.autopilot() }
    val deployments = rememberLoadable(address, tab) { if (tab == "deployments") ws.deployments() else emptyList() }
    val goals = rememberLoadable(address, tab) { if (tab == "goals") ws.goals() else emptyList() }
    proof?.let { p -> ProofDetail(p, agentName) { proof = null }; return }
    SubPage("Autopilot", onBack, onRefresh = { summary.refresh(); deployments.refresh(); goals.refresh() }) { pad ->
        LoadableList(summary, pad, header = {
            item { Text("What your agents proved, how reliable they are, and checks waiting for review. Changes are made on the web.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("proofs" to "Proofs", "reliability" to "Reliability", "checks" to "Regression checks", "deployments" to "Deployments", "goals" to "Goals")
                        .forEach { (k, l) -> FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(l) }) }
                }
            }
        }) { s ->
            when (tab) {
                "proofs" -> {
                    val list = s.proofs.orEmpty()
                    if (list.isEmpty()) item { Notice("No proofs yet. Agents with a mission record one each run.") }
                    items(list, key = { it.id }) { p ->
                        SoulCard(Modifier.clickable { proof = p }) {
                            Row { Text(agentName(p.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(p.outcome, statusColor(p.outcome)) }
                            Text("Verification ${p.verification} · ${p.checks.size} check(s) · ${shortTime(p.completedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                "reliability" -> {
                    val list = s.reliability.orEmpty()
                    if (list.isEmpty()) item { Notice("No reliability data yet.") }
                    items(list, key = { it.agentId }) { r ->
                        SoulCard {
                            Row { Text(agentName(r.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); r.score?.let { Pill(pct(it), if (it >= 0.8) Soulacy.colors.success else Soulacy.colors.mango) } }
                            Text("${r.samples} runs · success ${pct(r.successRate)} · verified ${pct(r.verificationRate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                "checks" -> {
                    val list = s.proposals.orEmpty()
                    if (list.isEmpty()) item { Notice("No regression checks waiting.") }
                    items(list, key = { it.id }) { p ->
                        SoulCard {
                            Row { Text(agentName(p.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(p.status, statusColor(p.status)) }
                            Text(p.failureSummary, style = MaterialTheme.typography.bodyMedium)
                            Text("Baseline ${p.baseline?.status ?: "not run"} · candidate ${p.candidate?.status ?: "not run"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                "deployments" -> {
                    deployments.error?.let { item { Notice(it) } }
                    val list = deployments.data.orEmpty()
                    if (list.isEmpty() && deployments.error == null) item { Notice(if (deployments.loading) "Loading…" else "No deployment channels yet.") }
                    items(list, key = { it.agentId }) { d ->
                        SoulCard(accent = if (d.frozen) Soulacy.colors.coral else null) {
                            Row { Text(agentName(d.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (d.frozen) Pill("frozen", Soulacy.colors.coral) }
                            d.channels.forEach { c -> Field(c.channel, (c.currentVersionId ?: "none") + if (c.trafficPercent > 0) " · ${c.trafficPercent}%" else "") }
                            d.freezeReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral) }
                        }
                    }
                }
                else -> {
                    goals.error?.let { item { Notice(it) } }
                    val list = goals.data.orEmpty()
                    if (list.isEmpty() && goals.error == null) item { Notice(if (goals.loading) "Loading…" else "No goals yet.") }
                    items(list, key = { it.id }) { g ->
                        SoulCard {
                            Row { Text(g.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(g.status, statusColor(g.status)) }
                            if (g.objective.isNotBlank()) Text(g.objective, style = MaterialTheme.typography.bodySmall)
                            g.tasks.forEach { t -> Field(t.status, "${t.title} (${agentName(t.agentId)})") }
                            g.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProofDetail(p: Proof, agentName: (String) -> String, onBack: () -> Unit) {
    SubPage("Proof", onBack) { pad ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SoulCard {
                    Row { Text(agentName(p.agentId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(p.outcome, statusColor(p.outcome)) }
                    Field("Verification", p.verification)
                    Field("Completed", shortTime(p.completedAt))
                    Field("Duration", duration(p.durationMs).ifBlank { "n/a" })
                    p.costUsd?.let { Field("Cost", "$" + "%.4f".format(it)) }
                    if (p.simulation) Field("Mode", "simulation")
                    Field("Run", p.runId)
                    Field("Proof hash", p.proofHash.take(23) + "…")
                }
            }
            if (p.checks.isNotEmpty()) {
                item { SectionLabel("Checks") }
                items(p.checks, key = { it.id }) { c ->
                    SoulCard { Row { Text(c.description ?: c.type, modifier = Modifier.weight(1f)); Pill(c.status, statusColor(c.status)) }; c.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            if (p.externalChanges.isNotEmpty()) {
                item { SectionLabel("What it changed outside") }
                items(p.externalChanges) { c -> SoulCard { Text(c.summary, fontWeight = FontWeight.SemiBold); Text("${c.kind} · ${c.target}" + if (c.reversible) " · reversible" else "", style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

// --- Board ---

@Composable
fun BoardPage(ws: Workspace, address: String, agentName: (String) -> String, onBack: () -> Unit) {
    var status by rememberSaveable { mutableStateOf("needs_review") }
    val tasks = rememberLoadable(address, status) { ws.board(status) }
    SubPage("Board", onBack, onRefresh = tasks::refresh) { pad ->
        LoadableList(tasks, pad, header = {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("needs_review" to "Needs review", "running" to "Running", "todo" to "To do", "done" to "Done", "failed" to "Failed")
                        .forEach { (k, l) -> FilterChip(selected = status == k, onClick = { status = k }, label = { Text(l) }) }
                }
            }
        }) { list ->
            if (list.isEmpty()) item { Notice("Nothing here.") }
            items(list, key = { it.id }) { t ->
                SoulCard {
                    Row { Text(t.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (t.priority != "normal") Pill(t.priority, if (t.priority == "urgent" || t.priority == "high") Soulacy.colors.coral else Soulacy.colors.mango) }
                    Text(listOfNotNull(agentName(t.agentId), t.dueAt?.let { "due " + shortTime(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    if (t.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) { t.tags.take(4).forEach { Pill(it, Soulacy.colors.brand) } }
                }
            }
        }
    }
}

// --- Learning notebook ---

@Composable
fun LearningPage(ws: Workspace, address: String, agents: List<Agent>, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var agentId by rememberSaveable { mutableStateOf(agents.firstOrNull()?.id ?: "") }
    var status by rememberSaveable { mutableStateOf("active") }
    val lessons = rememberLoadable(address, agentId, status) { ws.lessons(agentId, status) }
    var teaching by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<Pair<Lesson, String>?>(null) }

    fun act(block: suspend () -> String) = scope.launch {
        try { snack.showSnackbar(block()); lessons.refresh() } catch (e: Exception) { snack.showSnackbar(Loadable.describe(e, address)) }
    }

    SubPage("Learning notebook", onBack, snack, onRefresh = lessons::refresh) { pad ->
        LoadableList(lessons, pad, header = {
            item { Text("What each agent has learned, kept as reviewed lessons. New lessons wait for your approval.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    agents.forEach { a -> FilterChip(selected = agentId == a.id, onClick = { agentId = a.id }, label = { Text(a.title) }) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("active" to "Active", "pending" to "Waiting", "archived" to "Disabled", "rejected" to "Rejected")
                        .forEach { (k, l) -> FilterChip(selected = status == k, onClick = { status = k }, label = { Text(l) }) }
                }
            }
        }) { res ->
            if (!res.enabled) item { Notice("Learning is off for this agent.") }
            item { OutlinedButton(onClick = { teaching = true }, modifier = Modifier.fillMaxWidth(), enabled = res.enabled) { Text("Teach this agent something") } }
            val list = res.lessons.orEmpty()
            if (list.isEmpty()) item { Notice("No lessons here.") }
            items(list, key = { it.id }) { l ->
                SoulCard {
                    Row { Text(l.title.ifBlank { l.key }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(l.kind, Soulacy.colors.brand) }
                    if (l.trigger.isNotBlank()) Text("When: ${l.trigger}", style = MaterialTheme.typography.bodySmall)
                    Text(l.content, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    Text("v${l.version} · used ${l.uses} · 👍 ${l.helpful} 👎 ${l.unhelpful}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (l.status) {
                            "pending" -> { TextButton(onClick = { reviewing = l to "approve" }) { Text("Approve") }; TextButton(onClick = { reviewing = l to "reject" }) { Text("Reject") } }
                            "active" -> {
                                TextButton(onClick = { act { ws.rateLesson(agentId, l.id, true); "Marked helpful" } }) { Text("Helpful") }
                                TextButton(onClick = { act { ws.rateLesson(agentId, l.id, false); "Marked as needing work" } }) { Text("Needs work") }
                                TextButton(onClick = { reviewing = l to "archive" }) { Text("Disable") }
                            }
                            "archived", "superseded" -> TextButton(onClick = { reviewing = l to "restore" }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }

    reviewing?.let { (l, action) ->
        val verb = mapOf("approve" to "Approve", "reject" to "Reject", "archive" to "Disable", "restore" to "Restore")[action] ?: action
        AlertDialog(
            onDismissRequest = { reviewing = null },
            title = { Text("$verb this lesson?") },
            text = {
                Text(
                    when (action) {
                        "approve" -> "The agent starts using it in its runs. A lesson with the same key is replaced."
                        "archive" -> "The agent stops using it. You can restore it later."
                        "restore" -> "A new copy goes back to waiting for approval."
                        else -> "It won't be used."
                    } + "\n\n" + l.title.ifBlank { l.key },
                )
            },
            confirmButton = { TextButton(onClick = { reviewing = null; act { ws.reviewLesson(agentId, l.id, action); "$verb done" } }) { Text(verb) } },
            dismissButton = { TextButton(onClick = { reviewing = null }) { Text("Cancel") } },
        )
    }
    if (teaching) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { teaching = false },
            title = { Text("Teach ${agents.firstOrNull { it.id == agentId }?.title ?: "this agent"}") },
            text = { OutlinedTextField(value = note, onValueChange = { if (it.length <= 6000) note = it }, label = { Text("What should it learn?") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = note.isNotBlank(), onClick = {
                    teaching = false
                    act { val r = ws.teach(agentId, note.trim()); if (r.created) "Lesson drafted; it's waiting for your approval" else "Nothing new to learn from that" }
                }) { Text("Teach") }
            },
            dismissButton = { TextButton(onClick = { teaching = false }) { Text("Cancel") } },
        )
    }
}
