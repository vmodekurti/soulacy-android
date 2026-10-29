package dev.soulacy.android.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.Fact
import dev.soulacy.android.api.FactEvent
import dev.soulacy.android.api.PersonEntry
import dev.soulacy.android.api.Workspace
import dev.soulacy.android.ui.components.Pill
import dev.soulacy.android.ui.components.SectionLabel
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FACT_TABS = listOf("active" to "Active", "superseded" to "Superseded", "retracted" to "Retracted", "relations" to "Relationships")

/** What it remembers: the adaptive-memory facts agents keep about you (iOS MemoryView). */
@Composable
fun MemoryPage(ws: Workspace, address: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf("active") }
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(350); debounced = query.trim() }
    val status = rememberLoadable(address) { ws.memoryStatus() }
    val facts = rememberLoadable(address, tab, debounced) {
        if (tab == "relations") emptyList() else ws.facts(tab, debounced)
    }
    val relations = rememberLoadable(address, tab) { if (tab == "relations") ws.relations() else emptyList() }
    var history by remember { mutableStateOf<Pair<Fact, List<FactEvent>?>?>(null) }
    var deleting by remember { mutableStateOf<Fact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var purging by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun act(done: String, block: suspend () -> Unit) = scope.launch {
        try { block(); snack.showSnackbar(done); facts.refresh(); status.refresh() }
        catch (e: Exception) { snack.showSnackbar(Loadable.describe(e, address)) }
    }

    SubPage("What it remembers", onBack, snack, onRefresh = { status.refresh(); facts.refresh(); relations.refresh() }, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add a fact") }
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Forget all memory", color = Soulacy.colors.coral) }, onClick = { menu = false; purging = true })
        }
    }) { pad ->
        LoadableList(if (tab == "relations") relations else facts, pad, header = {
            item {
                val s = status.data
                Text(
                    when {
                        s == null -> "Facts your agents learned about you, and where they came from."
                        !s.enabled -> "Adaptive memory is off on this gateway."
                        else -> listOfNotNull(
                            s.active?.let { "$it active" }, s.superseded?.let { "$it replaced" },
                            s.relations?.let { "$it relationships" }, s.provider.takeIf { it.isNotBlank() }?.let { "provider $it" },
                        ).joinToString(" · ").ifBlank { "Facts your agents learned about you." }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    FACT_TABS.forEach { (k, label) -> FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(label) }) }
                }
            }
            if (tab != "relations") item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search memory") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        }) { data ->
            if (tab == "relations") {
                val rel = relations.data.orEmpty()
                if (rel.isEmpty()) item { Notice("No relationships yet.") }
                items(rel, key = { it.id }) { r ->
                    SoulCard { Text("${r.subject} · ${r.predicate} · ${r.obj}", fontWeight = FontWeight.SemiBold); Text(shortTime(r.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                @Suppress("UNCHECKED_CAST") val list = data as List<Fact>
                if (list.isEmpty()) item { Notice(if (debounced.isBlank()) "Nothing here yet." else "Nothing matches \"$debounced\".") }
                items(list, key = { it.id }) { f ->
                    SoulCard(Modifier.clickable {
                        history = f to null
                        scope.launch { history = f to runCatching { ws.factHistory(f.id) }.getOrDefault(emptyList()) }
                    }) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(f.content)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Pill(f.category, Soulacy.colors.brand)
                                    if (f.agentId.isNotBlank()) Pill(f.agentId, Soulacy.colors.mango)
                                }
                                Text(listOf(f.source, shortTime(f.updatedAt)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (f.status == "active") IconButton(onClick = { deleting = f }) { Icon(Icons.Default.DeleteOutline, "Forget this") }
                        }
                    }
                }
            }
        }
    }

    history?.let { (f, events) ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("History") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.content, fontWeight = FontWeight.SemiBold)
                    when {
                        events == null -> Text("Loading…")
                        events.isEmpty() -> Text("No history recorded.")
                        else -> events.take(12).forEach { e -> Text("${shortTime(e.createdAt)}  ${e.event} by ${e.actor}" + if (e.content.isNotBlank() && e.content != f.content) "\n${e.content}" else "", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text("Close") } },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Forget this?") },
            text = { Text(f.content) },
            confirmButton = { TextButton(onClick = { deleting = null; act("Forgotten") { ws.deleteFact(f) } }) { Text("Forget", color = Soulacy.colors.coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (purging) AlertDialog(
        onDismissRequest = { purging = false },
        title = { Text("Forget all memory?") },
        text = { Text("Every fact and relationship your agents remember about you is deleted from the gateway. This can't be undone.") },
        confirmButton = { TextButton(onClick = { purging = false; act("All memory forgotten") { ws.purgeFacts() } }) { Text("Forget everything", color = Soulacy.colors.coral) } },
        dismissButton = { TextButton(onClick = { purging = false }) { Text("Cancel") } },
    )
    if (adding) {
        val cats = status.data?.categories?.takeIf { it.isNotEmpty() } ?: listOf("preference", "identity", "constraint", "entity")
        AddFactDialog(cats, onDismiss = { adding = false }) { content, cat -> adding = false; act("Remembered") { ws.addFact(content, cat) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFactDialog(categories: List<String>, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(categories.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remember a fact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 400) text = it }, label = { Text("What should agents remember?") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { categories.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) }) } }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text.trim(), cat) }, enabled = text.isNotBlank()) { Text("Remember") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val SECTIONS = listOf(
    "identity" to "Who you are", "routine" to "Your routine", "state" to "Right now", "relationships" to "People",
    "commitments" to "Commitments", "preferences" to "Preferences",
)

/** About You: what Soulacy believes about you, each line with its source (iOS PersonView). */
@Composable
fun AboutYouPage(ws: Workspace, address: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val model = rememberLoadable(address) { ws.person() }
    val senses = rememberLoadable(address) { runCatching { ws.senses() }.getOrDefault(emptyList()) }
    var deleting by remember { mutableStateOf<PersonEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }

    fun act(block: suspend () -> String) = scope.launch {
        try { snack.showSnackbar(block()); model.refresh(); senses.refresh() }
        catch (e: Exception) { snack.showSnackbar(Loadable.describe(e, address)) }
    }

    SubPage("About You", onBack, snack, onRefresh = { model.refresh(); senses.refresh() }, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add a line") }
    }) { pad ->
        LoadableList(model, pad) { m ->
            val entries = m.entries.orEmpty()
            val filled = SECTIONS.count { (s, _) -> entries.any { it.section == s } }
            item {
                Text(
                    if (entries.isEmpty()) "Nothing yet. Add a line yourself, or let an agent like Getting to Know You ask."
                    else "$filled of ${SECTIONS.size} areas filled in. Every line says where it came from.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SECTIONS.forEach { (section, label) ->
                val rows = entries.filter { it.section == section }
                if (rows.isNotEmpty()) {
                    item { SectionLabel(label) }
                    items(rows, key = { "${it.section}/${it.key}" }) { e ->
                        SoulCard {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.key.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(e.summary, fontStyle = if (e.isGuess) FontStyle.Italic else null)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                        Pill(e.origin, if (e.source == "manual") Soulacy.colors.success else Soulacy.colors.brand)
                                        if (e.isGuess) Pill("a guess", Soulacy.colors.mango)
                                        e.expiresAt?.let { shortTime(it) }?.takeIf { it.isNotBlank() }?.let { Pill("until $it", Soulacy.colors.mango) }
                                    }
                                }
                                IconButton(onClick = { deleting = e }) { Icon(Icons.Default.DeleteOutline, "Delete this line") }
                            }
                        }
                    }
                }
            }
            val s = senses.data.orEmpty()
            if (s.isNotEmpty()) {
                item { SectionLabel("What may Soulacy notice?") }
                item { Text("Switching one off deletes what it worked out and the signals behind it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(s, key = { it.sense }) { sense ->
                    SoulCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sense.sense.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                Text(sense.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = sense.enabled, onCheckedChange = { on ->
                                act { val r = ws.setSense(sense.sense, on); if (on) "${sense.sense} is on" else "${sense.sense} is off; forgot ${r.forgotten} line(s)" }
                            })
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth()) { Text("Forget everything about me", color = Soulacy.colors.coral) }
            }
        }
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete this line?") }, text = { Text(e.summary) },
            confirmButton = { TextButton(onClick = { deleting = null; act { ws.deleteEntry(e); "Deleted" } }) { Text("Delete", color = Soulacy.colors.coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (forgetting) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text("Forget everything about you?") },
        text = { Text("Every line, and the signals behind them, is deleted from the gateway. Agents start from nothing.") },
        confirmButton = { TextButton(onClick = { forgetting = false; act { ws.forgetPerson(); "Forgotten" } }) { Text("Forget everything", color = Soulacy.colors.coral) } },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
    )
    if (adding) AddLineDialog(onDismiss = { adding = false }) { section, key, summary ->
        adding = false
        act {
            val r = ws.putEntry(section, key, summary)
            if (r.applied) "Added. Your own lines outrank anything an agent guessed." else r.reason ?: "Not applied"
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddLineDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var section by remember { mutableStateOf("preferences") }
    var key by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Area", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SECTIONS.forEach { (s, l) -> FilterChip(selected = section == s, onClick = { section = s }, label = { Text(l) }) }
                }
                OutlinedTextField(value = key, onValueChange = { if (it.length <= 120) key = it }, label = { Text("Topic, e.g. coffee") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = summary, onValueChange = { if (it.length <= 400) summary = it }, label = { Text("What's true") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(section, key.trim(), summary.trim()) }, enabled = key.isNotBlank() && summary.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
