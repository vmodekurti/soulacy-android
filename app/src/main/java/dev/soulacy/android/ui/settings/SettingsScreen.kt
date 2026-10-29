package dev.soulacy.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.BuildConfig
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.UndoJob
import dev.soulacy.android.api.Workspace
import dev.soulacy.android.data.Appearance
import dev.soulacy.android.data.Profile
import dev.soulacy.android.pairing.Reachability
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy
import dev.soulacy.android.ui.workspace.Field
import dev.soulacy.android.ui.workspace.LoadableList
import dev.soulacy.android.ui.workspace.NavRow
import dev.soulacy.android.ui.workspace.Notice
import dev.soulacy.android.ui.workspace.SubPage
import dev.soulacy.android.ui.workspace.rememberLoadable
import dev.soulacy.android.ui.workspace.shortTime

/** Where a Settings row leads. */
enum class SettingsPage { DIAGNOSTICS, ABOUT_YOU, MEMORY, WEBSITES, ACTIVITY, AUTOPILOT, SAFE_UNDO, BOARD, LEARNING }

/** Settings, laid out like iOS: gateway, look, notifications, workspace tools, privacy, unpair. */
@Composable
fun SettingsScreen(
    profile: Profile,
    reachability: Reachability?,
    connection: Connection,
    pushEnabled: Boolean,
    canNotify: Boolean,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onEnableNotifications: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionLabel("Gateway") }
        item {
            SoulCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, tint = Soulacy.colors.brand)
                    Spacer(Modifier.width(12.dp))
                    Text(profile.baseUrl, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (connection) {
                        Connection.Connected -> Pill("Connected", Soulacy.colors.success)
                        Connection.Checking -> Pill("Checking", Soulacy.colors.mango)
                        is Connection.Offline -> Pill("Offline", Soulacy.colors.coral)
                    }
                    if (profile.fingerprint.isNotEmpty()) Pill("Key pinned", Soulacy.colors.brand, Icons.Default.Lock)
                    if (pushEnabled) Pill("Push", Soulacy.colors.brand)
                }
                Spacer(Modifier.height(10.dp))
                Text("Signed in as ${profile.displayName.ifBlank { profile.subject.ifBlank { "owner" } }} · ${profile.role.ifBlank { "operator" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                (connection as? Connection.Offline)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        item { Item(Icons.Default.Wifi, "Reachable", reachability?.label ?: "Not set", reachability?.detail) }
        item { NavRow(Icons.Default.MonitorHeart, "Diagnostics", "What the gateway reports, and what to fix next") { onOpen(SettingsPage.DIAGNOSTICS) } }

        item { SectionLabel("Appearance") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Appearance.values().forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = appearance == a, onClick = { onAppearance(a) },
                        shape = SegmentedButtonDefaults.itemShape(i, Appearance.values().size),
                    ) { Text(a.label) }
                }
            }
        }

        item { SectionLabel("Notifications") }
        item {
            Item(
                Icons.Default.Notifications, if (canNotify) "On" else "Off",
                when {
                    !canNotify -> "Approvals still show in the app."
                    pushEnabled -> "Woken by push when your gateway supports it, and checked every 15 minutes."
                    else -> "Checked every 15 minutes, and every few seconds while the app is open."
                },
                action = if (!canNotify) ("Turn on" to onEnableNotifications) else null,
            )
        }

        item { SectionLabel("About you") }
        item { NavRow(Icons.Default.Face, "About You", "What Soulacy believes about you, and where each line came from") { onOpen(SettingsPage.ABOUT_YOU) } }
        item { NavRow(Icons.Default.Psychology, "What it remembers", "Facts your agents keep, with history") { onOpen(SettingsPage.MEMORY) } }
        item { NavRow(Icons.Default.Language, "Website access", "Sites agents may use while signed in as you") { onOpen(SettingsPage.WEBSITES) } }

        item { SectionLabel("Workspace tools") }
        item { NavRow(Icons.Default.Timeline, "Activity", "Running now and recent runs") { onOpen(SettingsPage.ACTIVITY) } }
        item { NavRow(Icons.Default.Verified, "Autopilot", "Proofs, reliability, regression checks") { onOpen(SettingsPage.AUTOPILOT) } }
        item { NavRow(Icons.Default.History, "Safe Undo", "Receipts for changes agents made") { onOpen(SettingsPage.SAFE_UNDO) } }
        item { NavRow(Icons.Default.TaskAlt, "Board", "Tasks your agents are working through") { onOpen(SettingsPage.BOARD) } }
        item { NavRow(Icons.Default.School, "Learning notebook", "Lessons agents learned, waiting for your review") { onOpen(SettingsPage.LEARNING) } }

        item { SectionLabel("Privacy") }
        item {
            Item(Icons.Default.Lock, "Only your gateway", "This phone talks only to the gateway above. There is no analytics or tracking. Conversations are kept on this phone and are not backed up. Every approval asks for your fingerprint, face or screen lock.")
        }
        item {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Unpair this phone", color = Soulacy.colors.coral) }
            Text("Soulacy ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Unpair this phone?") },
        text = { Text("Its credential is revoked on the gateway, and conversations on this phone are deleted. You can pair again with a new code.") },
        confirmButton = { TextButton(onClick = { confirm = false; onUnpair() }) { Text("Unpair", color = Soulacy.colors.coral) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Item(icon: ImageVector, title: String, body: String?, detail: String? = null, action: Pair<String, () -> Unit>? = null) {
    SoulCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = Soulacy.colors.brand)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (body != null) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            action?.let { (label, run) -> TextButton(onClick = run) { Text(label, color = Soulacy.colors.brand) } }
        }
    }
}

/**
 * Safe Undo receipts per agent, with the exact before and after of each
 * change. Preview, apply and undo need agents:write, which a paired phone
 * does not have, so those stay on the web.
 */
@Composable
fun SafeUndoPage(ws: Workspace, address: String, agents: List<Agent>, initialAgentId: String?, onBack: () -> Unit) {
    var agentId by rememberSaveable { mutableStateOf(initialAgentId ?: agents.firstOrNull()?.id.orEmpty()) }
    var open by remember { mutableStateOf<String?>(null) }
    val jobs = rememberLoadable(address, agentId) { ws.client.safeUndoJobs(agentId) }
    open?.let { id -> UndoJobPage(ws, address, agentId, id) { open = null }; return }
    SubPage("Safe Undo", onBack, onRefresh = jobs::refresh) { pad ->
        LoadableList(jobs, pad, header = {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    agents.forEach { a -> FilterChip(selected = agentId == a.id, onClick = { agentId = a.id }, label = { Text(a.title) }) }
                }
            }
        }) { list ->
            if (list.isEmpty()) item { Notice("No Safe Undo receipts for this agent.") }
            items(list, key = { it.id }) { j ->
                SoulCard(Modifier.clickable { open = j.id }) {
                    Text(j.title.ifBlank { j.id }, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill(j.status.replace('_', ' '), if (j.status == "needs_review") Soulacy.colors.coral else Soulacy.colors.brand)
                        Pill("${j.actionCount} change${if (j.actionCount == 1) "" else "s"}", Soulacy.colors.mango)
                    }
                    Text(shortTime(j.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item { Text("Receipts are read-only on the phone. Preview, apply and undo from the web workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun UndoJobPage(ws: Workspace, address: String, agentId: String, jobId: String, onBack: () -> Unit) {
    val job = rememberLoadable<UndoJob>(address, jobId) { ws.undoJob(agentId, jobId) }
    SubPage("Receipt", onBack, onRefresh = job::refresh) { pad ->
        LoadableList(job, pad) { j ->
            item {
                SoulCard {
                    Text(j.title.ifBlank { j.id }, fontWeight = FontWeight.SemiBold)
                    Field("Status", j.status.replace('_', ' '))
                    Field("Updated", shortTime(j.updatedAt))
                    j.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.mango) }
                }
            }
            items(j.actions) { a ->
                SoulCard {
                    Row { Text(a.name.ifBlank { a.resourceId }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(a.status.replace('_', ' '), Soulacy.colors.brand) }
                    a.fields.forEach { f ->
                        Text(f.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        Text("Before: " + (if (f.before.exists) f.before.display ?: "(set)" else "(not set)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("After:  " + (if (f.after.exists) f.after.display ?: "(set)" else "(not set)"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (a.beforeText != null || a.afterText != null) {
                        Text("Text changed (${a.beforeText?.length ?: 0} → ${a.afterText?.length ?: 0} characters)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
