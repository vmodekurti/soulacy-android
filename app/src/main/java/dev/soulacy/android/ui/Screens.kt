package dev.soulacy.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.ConfirmRequest
import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.api.SafeUndoJob
import dev.soulacy.android.data.Profile
import kotlinx.serialization.json.JsonObject

@Composable
fun AgentsScreen(agents: List<Agent>, loading: Boolean, onOpen: (Agent) -> Unit) {
    if (agents.isEmpty()) {
        Empty(if (loading) "Loading agents" else "No chat agents on this gateway yet.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(agents, key = { it.id }) { a ->
            ListItem(
                headlineContent = { Text(a.title) },
                supportingContent = { if (a.description.isNotBlank()) Text(a.description, maxLines = 2) },
                modifier = Modifier.clickable { onOpen(a) }.testTag("agent-${a.id}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ApprovalsScreen(approvals: List<PendingApproval>, onDecide: (PendingApproval, Boolean) -> Unit) {
    if (approvals.isEmpty()) {
        Empty("Nothing is waiting for you.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(approvals, key = { it.callId }) { a ->
            ApprovalCard(
                agent = a.agentId, tool = a.tool, reason = a.reason, args = a.args,
                inAppOnly = a.tool == "commit_website_action",
                onDecide = { approve -> onDecide(a, approve) },
            )
        }
    }
}

@Composable
fun ApprovalCard(agent: String, tool: String, reason: String, args: JsonObject?, inAppOnly: Boolean = false, onDecide: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${agent.ifBlank { "An agent" }} wants to use ${tool.ifBlank { "a tool" }}", style = MaterialTheme.typography.titleMedium)
            if (reason.isNotBlank()) Text(reason)
            if (args != null && args.isNotEmpty()) {
                Text(
                    args.entries.joinToString("\n") { (k, v) -> "$k: $v" }.take(1200),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (inAppOnly) Text("Check the provider, item, terms and total above before approving.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDecide(true) }, modifier = Modifier.testTag("approve")) { Text("Approve") }
                OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.testTag("deny")) { Text("Deny") }
            }
        }
    }
}

@Composable
fun InboxScreen(deliveries: List<Delivery>, onOpen: (Delivery) -> Unit) {
    if (deliveries.isEmpty()) {
        Empty("Results from scheduled agents arrive here.")
        return
    }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(deliveries, key = { it.id }) { d ->
            ListItem(
                headlineContent = { Text(d.title.ifBlank { d.agentId }) },
                overlineContent = { Text(if (d.readAt == null) "New from ${d.agentId}" else d.agentId) },
                supportingContent = { Text(d.body, maxLines = if (open == d.id) Int.MAX_VALUE else 3) },
                modifier = Modifier.clickable { open = if (open == d.id) null else d.id; onOpen(d) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun SettingsScreen(profile: Profile, pushEnabled: Boolean, canNotify: Boolean, onEnableNotifications: () -> Unit, onUnpair: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Gateway", style = MaterialTheme.typography.titleMedium)
        Text(profile.baseUrl, modifier = Modifier.testTag("gatewayUrl"))
        Text("Signed in as ${profile.displayName.ifBlank { profile.subject.ifBlank { "owner" } }} (${profile.role.ifBlank { "operator" }})")
        if (profile.fingerprint.isNotEmpty()) Text("Pinned key ${profile.fingerprint.take(16)}...", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                !canNotify -> "Off. Approvals still show in the app."
                pushEnabled -> "On, woken by push and checked every 15 minutes."
                else -> "On, checked every 15 minutes and every few seconds while the app is open. This build has no push service."
            }
        )
        if (!canNotify) OutlinedButton(onClick = onEnableNotifications) { Text("Turn on notifications") }
        HorizontalDivider()
        Text("This phone talks only to the gateway above. There is no analytics or tracking.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) { Text("Unpair this phone") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onUndo: () -> Unit,
    onDecide: (ConfirmRequest, Boolean) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    BackHandler(onBack = onBack)
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text?.length, chat.confirm) {
        val n = chat.messages.size + (if (chat.confirm != null) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chat.agent.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onUndo) { Icon(Icons.Default.History, "Safe Undo") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chat.messages.size) { i ->
                    val m = chat.messages[i]
                    Bubble(m, typing = chat.streaming && i == chat.messages.lastIndex && m.text.isEmpty())
                }
                chat.confirm?.let { c ->
                    item {
                        ApprovalCard(agent = chat.agent.title, tool = c.tool, reason = c.reason, args = c.args) { approve -> onDecide(c, approve) }
                    }
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Message ${chat.agent.title}") },
                    modifier = Modifier.weight(1f).testTag("chatInput"),
                    maxLines = 5,
                )
                if (chat.streaming) {
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
                } else {
                    IconButton(onClick = { onSend(draft); draft = "" }, enabled = draft.isNotBlank(), modifier = Modifier.testTag("send")) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, typing: Boolean) {
    val bg = when {
        m.error -> MaterialTheme.colorScheme.errorContainer
        m.fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            if (typing) "..." else m.text,
            modifier = Modifier.widthIn(max = 320.dp).background(bg, RoundedCornerShape(14.dp)).padding(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UndoScreen(agent: Agent, jobs: List<SafeUndoJob>, message: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safe Undo: ${agent.title}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            message?.let { Text(it, Modifier.padding(16.dp)) }
            LazyColumn {
                items(jobs, key = { it.id }) { j ->
                    ListItem(
                        headlineContent = { Text(j.title.ifBlank { j.id }) },
                        supportingContent = { Text("${j.status} · ${j.actionCount} change(s) · ${j.updatedAt.take(16).replace('T', ' ')}") },
                    )
                    HorizontalDivider()
                }
            }
            if (jobs.isNotEmpty()) Text(
                "Receipts are read-only here. Preview, apply and undo from the web workspace.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
