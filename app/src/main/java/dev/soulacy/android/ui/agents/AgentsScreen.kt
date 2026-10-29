package dev.soulacy.android.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.Agent
import dev.soulacy.android.data.Conversation
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.components.AgentAvatar
import dev.soulacy.android.ui.components.EmptyState
import dev.soulacy.android.ui.components.OfflineBanner
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.components.StalenessFooter
import dev.soulacy.android.ui.components.rememberNow
import dev.soulacy.android.ui.theme.Soulacy
import java.time.Instant

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentsScreen(
    agents: List<Agent>,
    conversations: List<Conversation>,
    updatedAt: Long?,
    connection: Connection,
    onOpen: (Agent) -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
) {
    val now = rememberNow(60_000)
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your agent team", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Specialists that work when you need them", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OfflineBanner(connection) }
        item { Summary(agents.size, connection) }
        if (agents.isEmpty()) item { EmptyState(Icons.Default.Groups, "No chat agents yet", "Agents you can chat with appear here. Create one in the web workspace, or ask Genie to build one.") }
        items(agents, key = { it.id }) { a -> AgentCard(a) { onOpen(a) } }
        if (conversations.isNotEmpty()) {
            item { SectionLabel("Recent conversations") }
            items(conversations.take(15), key = { it.sessionId }) { c ->
                SoulCard(Modifier.combinedClickable(onClick = { onOpenConversation(c) }, onLongClick = { deleting = c })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(c.agentId, c.agentName, 36)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(c.agentName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(ApprovalTiming.ago(Instant.ofEpochMilli(c.updatedAt), now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(c.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item { StalenessFooter(updatedAt, connection) }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this conversation?") },
            text = { Text("It is removed from this phone. The agent's memory on the gateway is not changed.") },
            confirmButton = { TextButton(onClick = { onDeleteConversation(c); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Summary(count: Int, connection: Connection) {
    val ok = connection !is Connection.Offline
    SoulCard(accent = if (ok) Soulacy.colors.brand else Soulacy.colors.coral) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$count available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    when (connection) { Connection.Connected -> "All systems ready"; Connection.Checking -> "Checking your gateway"; is Connection.Offline -> "Can't reach your gateway" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val tint = if (ok) Soulacy.colors.success else Soulacy.colors.coral
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Default.VerifiedUser else Icons.Default.CloudOff, null, tint = tint)
            }
        }
    }
}

@Composable
private fun AgentCard(a: Agent, onOpen: () -> Unit) {
    SoulCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(a.id, a.title, 56)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (a.description.isNotBlank()) Text(a.description.lineSequence().first(), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill("Available", Soulacy.colors.success, Icons.Default.CheckCircle)
                    Pill("Chat", Soulacy.colors.brand, Icons.AutoMirrored.Filled.Chat)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
