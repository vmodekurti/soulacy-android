package dev.soulacy.android.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.components.AgentAvatar
import dev.soulacy.android.ui.components.EmptyState
import dev.soulacy.android.ui.components.MarkdownText
import dev.soulacy.android.ui.components.OfflineBanner
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.components.StalenessFooter
import dev.soulacy.android.ui.components.rememberNow
import dev.soulacy.android.ui.theme.Soulacy
import java.time.Instant

/** Home: what your agents did, with anything that needs your yes on top (iOS FeedView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    approvals: List<PendingApproval>,
    deliveries: List<Delivery>,
    agentName: (String) -> String,
    updatedAt: Long?,
    connection: Connection,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenApproval: (PendingApproval) -> Unit,
    onDecide: (PendingApproval, Boolean) -> Unit,
    onRead: (Delivery) -> Unit,
    onReply: (Delivery) -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OfflineBanner(connection) }
            if (approvals.isNotEmpty()) {
                item { SectionLabel("Needs you", Soulacy.colors.coral) }
                items(approvals, key = { "a-" + it.callId }) { a ->
                    NeedsYouCard(a, agentName(a.agentId), onOpen = { onOpenApproval(a) }, onDecide = { approve -> onDecide(a, approve) })
                }
            }
            if (deliveries.isEmpty() && approvals.isEmpty()) {
                item {
                    EmptyState(Icons.Default.AutoAwesome, "Nothing yet", "When an agent finishes something or sends you a result, it shows up here.")
                }
            } else if (deliveries.isNotEmpty()) {
                item { SectionLabel("From your agents") }
                items(deliveries, key = { "d-" + it.id }) { d -> DeliveryCard(d, agentName(d.agentId), onRead = { onRead(d) }, onReply = { onReply(d) }) }
            }
            item { StalenessFooter(updatedAt, connection) }
        }
    }
}

@Composable
private fun NeedsYouCard(a: PendingApproval, agent: String, onOpen: () -> Unit, onDecide: (Boolean) -> Unit) {
    SoulCard(Modifier.clickable(onClick = onOpen), accent = Soulacy.colors.coral) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(a.agentId, agent, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Wants to run ${a.tool}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (a.reason.isNotBlank()) Text(a.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = { onDecide(true) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Soulacy.colors.brand)) { Text("Approve") }
        }
    }
}

@Composable
private fun DeliveryCard(d: Delivery, agent: String, onRead: () -> Unit, onReply: () -> Unit) {
    val context = LocalContext.current
    val now = rememberNow(60_000)
    var expanded by rememberSaveable(d.id) { mutableStateOf(false) }
    val unread = d.readAt == null
    val long = d.body.length > 600
    SoulCard(Modifier.clickable { expanded = !expanded; onRead() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(d.agentId, agent, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val at = runCatching { Instant.parse(d.createdAt) }.getOrNull()
                if (at != null) Text(ApprovalTiming.ago(at, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unread) Box(Modifier.size(9.dp).clip(CircleShape).background(Soulacy.colors.brand))
        }
        if (d.title.isNotBlank()) Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        MarkdownText(if (long && !expanded) d.body.take(600).substringBeforeLast(' ') + " …" else d.body)
        if (long) TextButton(onClick = { expanded = !expanded; onRead() }) { Text(if (expanded) "Show less" else "Show more", color = Soulacy.colors.brand) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onReply) { Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reply in Chat") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { copy(context, d.body); onRead() }) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(18.dp)) }
            TextButton(onClick = { share(context, d.title, d.body); onRead() }) { Icon(Icons.Default.Share, "Share", Modifier.size(18.dp)) }
        }
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Soulacy", text))
}

fun share(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) text else "$title\n\n$text")
    context.startActivity(Intent.createChooser(send, "Share"))
}
