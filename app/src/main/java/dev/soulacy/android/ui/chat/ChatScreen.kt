package dev.soulacy.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.ConfirmRequest
import dev.soulacy.android.inbox.ArgFormatter
import dev.soulacy.android.ui.ChatMessage
import dev.soulacy.android.ui.ChatState
import dev.soulacy.android.ui.components.AgentAvatar
import dev.soulacy.android.ui.components.MarkdownText
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.home.copy
import dev.soulacy.android.ui.home.share
import dev.soulacy.android.ui.theme.Soulacy
import kotlinx.coroutines.delay

/** The suggestions on an empty Genie conversation, as on iOS. */
private val GenieSuggestions = listOf("What needs my attention?", "Summarize today", "Every weekday at 7, brief me")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatState,
    genie: Boolean,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNew: (() -> Unit)?,
    onUndo: () -> Unit,
    onDecide: (ConfirmRequest, Boolean) -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    var draft by rememberSaveable(chat.sessionId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val extra = (if (chat.working != null) 1 else 0) + (if (chat.confirm != null) 1 else 0)
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text?.length, chat.confirm, chat.working) {
        val n = chat.messages.size + extra
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    // In the Genie tab the outer scaffold already pads for the system bars.
    val embedded = onBack == null
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = if (embedded) WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = if (embedded) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (genie) Icon(Icons.Default.AutoAwesome, null, tint = Soulacy.colors.brand) else AgentAvatar(chat.agent.id, chat.agent.title, 32)
                        Spacer(Modifier.width(10.dp))
                        Text(if (genie) "Genie" else chat.agent.title, maxLines = 1)
                    }
                },
                actions = {
                    if (onNew != null) IconButton(onClick = onNew, enabled = !chat.streaming) { Icon(Icons.Default.AddComment, "New conversation") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Safe Undo receipts") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menu = false; onUndo() })
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (chat.messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EmptyChat(genie, chat.agent.title, chat.agent.description) { onSend(it) }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(chat.messages.size) { i ->
                        val m = chat.messages[i]
                        if (!(m.text.isEmpty() && !m.fromUser)) Message(m)
                    }
                    chat.confirm?.let { c -> item { InlineApproval(chat.agent.title, c) { approve -> onDecide(c, approve) } } }
                    chat.working?.let { w -> item { Working(w, chat.messages.size) } }
                }
            }
            Composer(
                draft = draft, onDraft = { draft = it }, streaming = chat.streaming,
                placeholder = if (genie) "Ask Genie" else "Message ${chat.agent.title}",
                onSend = { onSend(draft); draft = "" }, onStop = onStop,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(genie: Boolean, name: String, description: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Soulacy.colors.brandGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text(if (genie) "Genie" else name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(6.dp))
        Text(
            if (genie) "Ask for something, or ask for it to happen on its own." else description.ifBlank { "Say hello, or ask $name to start on something." },
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (genie) {
            Spacer(Modifier.size(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GenieSuggestions.forEach { s ->
                    AssistChip(onClick = { onPick(s) }, label = { Text(s) }, colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Message(m: ChatMessage) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val c = Soulacy.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.widthIn(max = 340.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (m.fromUser) 18.dp else 6.dp, bottomEnd = if (m.fromUser) 6.dp else 18.dp))
                .background(
                    when {
                        m.error -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.errorContainer))
                        m.fromUser -> Brush.linearGradient(listOf(c.brandDeep, c.brand.copy(alpha = 0.9f)))
                        else -> Brush.linearGradient(listOf(if (c.dark) c.raised.copy(alpha = 0.85f) else Color(0xFFF0F5F4), if (c.dark) c.raised.copy(alpha = 0.85f) else Color(0xFFF0F5F4)))
                    }
                )
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.fromUser) Text(m.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            else MarkdownText(m.text, if (m.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; copy(context, m.text) })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; share(context, "", m.text) })
            }
        }
    }
}

/** "Thinking · 6s" while nothing is streaming; the dots pulse, the clock keeps it honest. */
@Composable
private fun Working(label: String, key: Int) {
    var seconds by remember(key, label) { mutableLongStateOf(0) }
    LaunchedEffect(key, label) { while (true) { delay(1000); seconds++ } }
    val pulse by rememberInfiniteTransition(label = "working").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
        repeat(3) { i -> Box(Modifier.padding(end = 4.dp).size(7.dp).alpha(if (i == 1) 1.3f - pulse else pulse).clip(CircleShape).background(Soulacy.colors.brand)) }
        Spacer(Modifier.width(6.dp))
        Text("$label" + if (seconds >= 2) " · ${seconds}s" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineApproval(agent: String, c: ConfirmRequest, onDecide: (Boolean) -> Unit) {
    SoulCard(accent = Soulacy.colors.coral) {
        Text("NEEDS YOU", style = MaterialTheme.typography.labelMedium, color = Soulacy.colors.coral, fontWeight = FontWeight.Bold)
        Text("$agent wants to run ${c.tool}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        if (c.reason.isNotBlank()) Text(c.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        ArgFormatter.rows(c.args).take(6).forEach { r ->
            Row(Modifier.padding(top = 6.dp)) {
                Text(r.key + ": ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(r.emphasis?.let { "$it · ${r.value}" } ?: r.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = { onDecide(true) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Soulacy.colors.brand)) { Text("Approve") }
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, streaming: Boolean, placeholder: String, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = draft, onValueChange = onDraft, placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f), maxLines = 6, shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = if (Soulacy.colors.dark) Soulacy.colors.raised.copy(alpha = 0.6f) else Color.White,
                focusedContainerColor = if (Soulacy.colors.dark) Soulacy.colors.raised.copy(alpha = 0.8f) else Color.White,
                focusedBorderColor = Soulacy.colors.brand,
            ),
        )
        Spacer(Modifier.width(8.dp))
        if (streaming) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Soulacy.colors.coral)) { Icon(Icons.Default.Stop, "Stop", tint = Color.White) }
        } else {
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Soulacy.colors.brand)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
            }
        }
    }
}
