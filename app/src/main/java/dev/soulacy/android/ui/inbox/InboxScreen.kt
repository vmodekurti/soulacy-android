package dev.soulacy.android.ui.inbox

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.inbox.ArgFormatter
import dev.soulacy.android.inbox.Urgency
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

/** Inbox: decisions with deadlines, banded by urgency rather than by type (iOS InboxListView). */
@Composable
fun InboxScreen(
    approvals: List<PendingApproval>,
    deadline: (PendingApproval) -> Instant,
    agentName: (String) -> String,
    updatedAt: Long?,
    connection: Connection,
    onOpen: (PendingApproval) -> Unit,
    onDeny: (PendingApproval) -> Unit,
) {
    val now = rememberNow()
    val sorted = approvals.sortedBy(deadline)
    val urgent = sorted.filter { ApprovalTiming.urgency(deadline(it), now) != Urgency.WAITING }
    val waiting = sorted - urgent.toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OfflineBanner(connection) }
        item { Text("Decisions from across your workspace, in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        if (sorted.isEmpty()) {
            item { EmptyState(Icons.Default.Inbox, "Nothing waiting", "When an agent needs your yes before it acts, it lands here, with how long it will wait.") }
        }
        if (urgent.isNotEmpty()) {
            item { SectionLabel("Expiring soon", Soulacy.colors.coral) }
            items(urgent, key = { it.callId }) { a -> SwipeRow(a, deadline(a), now, agentName(a.agentId), onOpen, onDeny) }
        }
        if (waiting.isNotEmpty()) {
            item { SectionLabel("Waiting on you", Soulacy.colors.mango) }
            items(waiting, key = { it.callId }) { a -> SwipeRow(a, deadline(a), now, agentName(a.agentId), onOpen, onDeny) }
        }
        item { StalenessFooter(updatedAt, connection) }
    }
}

/** Deny is a swipe (it still asks for the device lock); approve always goes through the detail view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(a: PendingApproval, deadline: Instant, now: Instant, agent: String, onOpen: (PendingApproval) -> Unit, onDeny: (PendingApproval) -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) onDeny(a)
        false // the row stays until the gateway confirms the decision
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Only while swiping: the cards are translucent, so a resting
            // background would tint them and show through.
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Soulacy.colors.coral).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Close, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Deny", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        },
    ) { ApprovalRow(a, deadline, now, agent) { onOpen(a) } }
}

@Composable
private fun ApprovalRow(a: PendingApproval, deadline: Instant, now: Instant, agent: String, onClick: () -> Unit) {
    val u = ApprovalTiming.urgency(deadline, now)
    val tint = when (u) { Urgency.URGENT -> Soulacy.colors.coral; Urgency.WAITING -> Soulacy.colors.mango; Urgency.EXPIRED -> Color.Gray }
    SoulCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(a.agentId, agent, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.tool.ifBlank { "Tool call" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(agent, a.reason).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (u == Urgency.EXPIRED) "expired" else ApprovalTiming.countdown(deadline, now),
                color = tint, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = ApprovalTiming.spoken(deadline, now) },
            )
        }
    }
}

/** One approval in full: what, who, why, the arguments as a person judges them, and the clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalDetailScreen(a: PendingApproval, deadline: Instant, agent: String, onBack: () -> Unit, onDecide: (Boolean) -> Unit) {
    BackHandler(onBack = onBack)
    val now = rememberNow()
    val u = ApprovalTiming.urgency(deadline, now)
    val rows = ArgFormatter.rows(a.args)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Approval") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f).height(52.dp), enabled = u != Urgency.EXPIRED) { Text("Deny") }
                Button(
                    onClick = { onDecide(true) }, modifier = Modifier.weight(1f).height(52.dp), enabled = u != Urgency.EXPIRED,
                    colors = ButtonDefaults.buttonColors(containerColor = Soulacy.colors.brand),
                ) { Text("Approve") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(a.agentId, agent, 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.tool.ifBlank { "Tool call" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Requested by $agent", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Pill(
                if (u == Urgency.EXPIRED) "Expired" else "Expires in " + ApprovalTiming.countdown(deadline, now),
                when (u) { Urgency.URGENT -> Soulacy.colors.coral; Urgency.WAITING -> Soulacy.colors.mango; Urgency.EXPIRED -> Color.Gray },
                Icons.Default.Timer,
            )
            if (a.reason.isNotBlank()) SoulCard {
                Text("Why", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a.reason, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            }
            SoulCard {
                Text("What it will run with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rows.isEmpty()) Text("No arguments.", modifier = Modifier.padding(top = 6.dp))
                rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(top = if (i == 0) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.key, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        r.emphasis?.let { Pill(it, Soulacy.colors.mango) }
                    }
                    Text(r.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Long values are shortened here and credential-like fields are hidden. The full request stays on your gateway. Deciding asks for your fingerprint, face or screen lock.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
