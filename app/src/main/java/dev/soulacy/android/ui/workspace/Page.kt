package dev.soulacy.android.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soulacy.android.api.featureUnavailable
import dev.soulacy.android.pairing.ConnectionProblem
import dev.soulacy.android.ui.components.SoulCard
import dev.soulacy.android.ui.theme.Soulacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a screen knows about its data: loading, a reason it can't load, or the data. */
class Loadable<T>(private val scope: CoroutineScope, private val address: String, private val load: suspend () -> T) {
    var data by mutableStateOf<T?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(true); private set
    private var generation by mutableIntStateOf(0)

    fun refresh() {
        val mine = ++generation
        loading = true
        scope.launch {
            try {
                val v = load()
                if (mine == generation) { data = v; error = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) error = describe(e, address)
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    companion object {
        fun describe(e: Throwable, address: String): String = featureUnavailable(e) ?: ConnectionProblem.describe(e, address)
    }
}

@Composable
fun <T> rememberLoadable(address: String, vararg keys: Any?, load: suspend () -> T): Loadable<T> {
    val scope = rememberCoroutineScope()
    val l = remember(*keys) { Loadable(scope, address, load) }
    LaunchedEffect(l) { l.refresh() }
    return l
}

/** A full-screen page under Settings: title, Back, optional refresh, then content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    actions()
                    if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad -> content(pad) }
}

/** A list body that shows loading, the reason it can't load, or [items]. */
@Composable
fun <T> LoadableList(
    state: Loadable<T>,
    pad: PaddingValues,
    header: LazyListScope.() -> Unit = {},
    items: LazyListScope.(T) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()
        val data = state.data
        when {
            data != null -> items(data)
            state.error != null -> item { Notice(state.error!!) }
            else -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Soulacy.colors.brand) } }
        }
        if (data != null && state.error != null) item { Notice(state.error!!) }
    }
}

@Composable
fun Notice(text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    SoulCard { Text(text, color = tint, style = MaterialTheme.typography.bodyMedium) }
}

/** A tappable row: icon, title, optional detail, chevron. */
@Composable
fun NavRow(icon: ImageVector, title: String, detail: String? = null, tint: Color = Soulacy.colors.brand, onClick: () -> Unit) {
    SoulCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label: value lines inside a card. */
@Composable
fun ColumnScope.Field(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** "2026-10-29T15:04:05.123Z" → "Oct 29, 15:04" in the phone's zone; blank or zero times → "". */
fun shortTime(iso: String?): String {
    if (iso.isNullOrBlank() || iso.startsWith("0001-")) return ""
    return runCatching {
        val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }.getOrDefault("")
}
