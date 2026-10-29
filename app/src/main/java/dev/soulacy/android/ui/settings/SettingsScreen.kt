package dev.soulacy.android.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.BuildConfig
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.SafeUndoJob
import dev.soulacy.android.data.Profile
import dev.soulacy.android.pairing.Reachability
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy

@Composable
fun SettingsScreen(
    profile: Profile,
    reachability: Reachability?,
    connection: Connection,
    pushEnabled: Boolean,
    canNotify: Boolean,
    onEnableNotifications: () -> Unit,
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
                }
                Spacer(Modifier.height(10.dp))
                Text("Signed in as ${profile.displayName.ifBlank { profile.subject.ifBlank { "owner" } }} · ${profile.role.ifBlank { "operator" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                (connection as? Connection.Offline)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = Soulacy.colors.coral, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            Item(Icons.Default.Wifi, "Reachable", reachability?.label ?: "Not set", reachability?.detail)
        }
        item { SectionLabel("Notifications") }
        item {
            Item(
                Icons.Default.Notifications, if (canNotify) "On" else "Off",
                when {
                    !canNotify -> "Approvals still show in the app."
                    pushEnabled -> "Woken by push, and checked every 15 minutes."
                    else -> "Checked every 15 minutes, and every few seconds while the app is open. This build has no push service."
                },
                action = if (!canNotify) ("Turn on" to onEnableNotifications) else null,
            )
        }
        item { SectionLabel("Privacy") }
        item {
            Item(Icons.Default.Lock, "Only your gateway", "This phone talks only to the gateway above. There is no analytics or tracking. Conversations are kept on this phone and are not backed up.")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UndoScreen(agent: Agent, jobs: List<SafeUndoJob>, message: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Safe Undo") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Receipts for ${agent.title}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            message?.let { item { Item(Icons.Default.History, it, null) } }
            items(jobs, key = { it.id }) { j ->
                SoulCard {
                    Text(j.title.ifBlank { j.id }, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill(j.status.replace('_', ' '), if (j.status == "needs_review") Soulacy.colors.coral else Soulacy.colors.brand)
                        Pill("${j.actionCount} change${if (j.actionCount == 1) "" else "s"}", Soulacy.colors.mango)
                    }
                    Text(j.updatedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (jobs.isNotEmpty()) item {
                Text("Receipts are read-only on the phone. Preview, apply and undo from the web workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
