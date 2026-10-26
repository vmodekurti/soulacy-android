package dev.soulacy.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soulacy.android.push.PushSetup
import dev.soulacy.android.ui.AgentsScreen
import dev.soulacy.android.ui.AppViewModel
import dev.soulacy.android.ui.ApprovalsScreen
import dev.soulacy.android.ui.BiometricGate
import dev.soulacy.android.ui.ChatScreen
import dev.soulacy.android.ui.InboxScreen
import dev.soulacy.android.ui.PairScreen
import dev.soulacy.android.ui.SettingsScreen
import dev.soulacy.android.ui.UndoScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** FragmentActivity because BiometricPrompt needs one; Compose works the same. */
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private val tabRequest = MutableStateFlow<Int?>(null)
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.reregister() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.onForeground()
            override fun onStop(owner: LifecycleOwner) = vm.onBackground()
        })
        setContent { SoulacyTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** soulacy://approval/<id> and soulacy://delivery/<id> from notifications. */
    private fun handleLink(intent: Intent?) {
        when (intent?.data?.takeIf { it.scheme == "soulacy" }?.host) {
            "approval" -> tabRequest.value = TAB_APPROVALS
            "delivery" -> tabRequest.value = TAB_INBOX
        }
    }

    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun confirmThen(title: String, subtitle: String, action: () -> Unit, onRefused: (String) -> Unit) {
        BiometricGate.authenticate(this, title, subtitle) { outcome ->
            when (outcome) {
                BiometricGate.Outcome.Passed -> action()
                is BiometricGate.Outcome.Refused -> onRefused(outcome.message)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val s by vm.state.collectAsStateWithLifecycle()
        val requested by tabRequest.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(TAB_AGENTS) }
        val snack = remember { SnackbarHostState() }
        val app = LocalContext.current.applicationContext as SoulacyApp

        LaunchedEffect(requested) { requested?.let { tab = it; vm.closeChat(); tabRequest.value = null } }
        LaunchedEffect(s.error, s.notice) {
            (s.error ?: s.notice)?.let { snack.showSnackbar(it); vm.dismissMessages() }
        }
        LaunchedEffect(s.profile) { if (s.profile != null && !app.notifier.canNotify()) askForNotifications() }

        val profile = s.profile
        if (profile == null) {
            Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                    PairScreen(busy = s.pairing, error = s.error) { vm.pair(it) }
                }
            }
            return
        }

        val decide: (String, String, String, Boolean) -> Unit = { callId, agent, tool, approve ->
            confirmThen(
                if (approve) "Approve $tool?" else "Deny $tool?", "Requested by ${agent.ifBlank { "an agent" }}",
                action = { vm.decide(callId, approve) },
                onRefused = vm::showMessage,
            )
        }

        s.undoAgent?.let { a -> UndoScreen(a, s.undoJobs, s.undoMessage, onBack = vm::closeUndo); return }
        s.chat?.let { c ->
            ChatScreen(
                chat = c, onBack = vm::closeChat, onSend = vm::send, onStop = vm::stop,
                onUndo = { vm.openUndo(c.agent) },
                onDecide = { req, approve -> decide(req.callId, c.agent.title, req.tool, approve) },
                snackbar = snack,
            )
            return
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text(TITLES[tab]) }) },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == TAB_AGENTS, { tab = TAB_AGENTS }, { Icon(Icons.Default.SmartToy, null) }, label = { Text("Agents") })
                    NavigationBarItem(tab == TAB_APPROVALS, { tab = TAB_APPROVALS }, {
                        BadgedBox(badge = { if (s.approvals.isNotEmpty()) Badge { Text("${s.approvals.size}") } }) { Icon(Icons.Default.VerifiedUser, null) }
                    }, label = { Text("Approvals") })
                    NavigationBarItem(tab == TAB_INBOX, { tab = TAB_INBOX }, {
                        val unread = s.deliveries.count { it.readAt == null }
                        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(Icons.Default.Inbox, null) }
                    }, label = { Text("Inbox") })
                    NavigationBarItem(tab == TAB_SETTINGS, { tab = TAB_SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
        ) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                when (tab) {
                    TAB_AGENTS -> AgentsScreen(s.agents, s.agentsLoading, vm::openChat)
                    TAB_APPROVALS -> ApprovalsScreen(s.approvals) { a, approve -> decide(a.callId, a.agentId, a.tool, approve) }
                    TAB_INBOX -> InboxScreen(s.deliveries) { if (it.readAt == null) vm.markRead(it) }
                    else -> SettingsScreen(profile, PushSetup.enabled, app.notifier.canNotify(), ::askForNotifications, vm::unpair)
                }
            }
        }
    }

    private companion object {
        const val TAB_AGENTS = 0
        const val TAB_APPROVALS = 1
        const val TAB_INBOX = 2
        const val TAB_SETTINGS = 3
        val TITLES = listOf("Agents", "Approvals", "Inbox", "Settings")
    }
}

@Composable
fun SoulacyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
