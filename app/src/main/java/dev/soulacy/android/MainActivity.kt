package dev.soulacy.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.push.PushSetup
import dev.soulacy.android.ui.AppViewModel
import dev.soulacy.android.ui.BiometricGate
import dev.soulacy.android.ui.ChatSlot
import dev.soulacy.android.ui.Connection
import dev.soulacy.android.ui.agents.AgentsScreen
import dev.soulacy.android.ui.chat.ChatScreen
import dev.soulacy.android.ui.components.ConnectionLine
import dev.soulacy.android.ui.components.FullScreenCenter
import dev.soulacy.android.ui.components.Wordmark
import dev.soulacy.android.ui.home.HomeScreen
import dev.soulacy.android.ui.inbox.ApprovalDetailScreen
import dev.soulacy.android.ui.inbox.InboxScreen
import dev.soulacy.android.ui.onboarding.Onboarding
import dev.soulacy.android.ui.settings.SettingsScreen
import dev.soulacy.android.ui.settings.UndoScreen
import dev.soulacy.android.ui.theme.Soulacy
import dev.soulacy.android.ui.theme.SoulacyBackground
import dev.soulacy.android.ui.theme.SoulacyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** FragmentActivity because BiometricPrompt needs one; Compose works the same. */
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    /** A notification asked for a place: an approval call id, or the Home feed. */
    private val linkRequest = MutableStateFlow<Pair<Tab, String?>?>(null)
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
        setContent { SoulacyTheme { SoulacyBackground { Root() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** soulacy://approval/<id> and soulacy://delivery/<id> from notifications. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data?.takeIf { it.scheme == "soulacy" } ?: return
        when (uri.host) {
            "approval" -> linkRequest.value = Tab.INBOX to uri.lastPathSegment
            "delivery" -> linkRequest.value = Tab.HOME to null
        }
    }

    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.reregister()
    }

    private fun confirmThen(title: String, subtitle: String, action: () -> Unit) {
        BiometricGate.authenticate(this, title, subtitle) { outcome ->
            when (outcome) {
                BiometricGate.Outcome.Passed -> action()
                is BiometricGate.Outcome.Refused -> vm.showMessage(outcome.message)
            }
        }
    }

    enum class Tab(val label: String) { HOME("Home"), INBOX("Inbox"), GENIE("Genie"), AGENTS("Agents"), SETTINGS("Settings") }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val s by vm.state.collectAsStateWithLifecycle()
        val link by linkRequest.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var openApproval by rememberSaveable { mutableStateOf<String?>(null) }
        var refreshing by remember { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }
        val app = LocalContext.current.applicationContext as SoulacyApp

        LaunchedEffect(link) {
            link?.let { (t, id) -> tab = t; openApproval = id; vm.closeChat(); linkRequest.value = null }
        }
        LaunchedEffect(s.notice) { s.notice?.let { snack.showSnackbar(it); vm.dismissMessages() } }
        LaunchedEffect(s.connection) { if (s.connection != Connection.Checking) refreshing = false }
        LaunchedEffect(tab, s.agents) { if (tab == Tab.GENIE) vm.ensureGenie() }

        val profile = s.profile
        if (profile == null || !s.onboardingDone) {
            Onboarding(
                profile = profile, pairing = s.pairing, pairError = s.pairError, agentCount = s.agents.size,
                canNotify = app.notifier.canNotify(),
                onPair = vm::pair, onClearError = vm::clearPairError, onReachability = vm::setReachability,
                onRequestNotifications = ::askForNotifications,
                onFinish = { vm.finishOnboarding(); tab = Tab.INBOX }, // like iOS, the first stop is the Inbox
            )
            return
        }

        val agentName: (String) -> String = { id -> s.agents.firstOrNull { it.id == id }?.title ?: id.ifBlank { "An agent" } }
        val deadline: (PendingApproval) -> Instant = { a -> ApprovalTiming.deadline(a, vm.firstSeen[a.callId] ?: Instant.now()) }
        val decide: (String, String, String, Boolean) -> Unit = { callId, agent, tool, approve ->
            confirmThen(if (approve) "Approve $tool?" else "Deny $tool?", "Requested by $agent") {
                vm.decide(callId, approve)
                if (openApproval == callId) openApproval = null
            }
        }

        // Full-screen layers over the tabs, in the order Back unwinds them.
        s.undoAgent?.let { a -> UndoScreen(a, s.undoJobs, s.undoMessage, onBack = vm::closeUndo); return }
        openApproval?.let { id ->
            val a = s.approvals.firstOrNull { it.callId == id }
            if (a != null) {
                ApprovalDetailScreen(a, deadline(a), agentName(a.agentId), onBack = { openApproval = null }) { approve ->
                    decide(a.callId, agentName(a.agentId), a.tool, approve)
                }
                return
            }
        }
        s.chat?.let { c ->
            ChatScreen(
                chat = c, genie = false, snackbar = snack, onBack = vm::closeChat,
                onSend = { vm.send(ChatSlot.AGENT, it) }, onStop = { vm.stop(ChatSlot.AGENT) }, onNew = null,
                onUndo = { vm.openUndo(c.agent) },
                onDecide = { req, approve -> decide(req.callId, c.agent.title, req.tool, approve) },
            )
            return
        }

        val pending = s.approvals.size
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                if (tab != Tab.GENIE) Column {
                    CenterAlignedTopAppBar(
                        title = {
                            if (tab == Tab.HOME) Wordmark() else Text(tab.label, fontWeight = FontWeight.SemiBold)
                        },
                        actions = {
                            if (tab == Tab.HOME || tab == Tab.INBOX || tab == Tab.AGENTS) IconButton(onClick = { refreshing = true; vm.refreshAll() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                    ConnectionLine(s.connection)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = if (Soulacy.colors.dark) Soulacy.colors.canvas.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.96f)) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = {
                                val icon = when (t) {
                                    Tab.HOME -> Icons.Default.Home; Tab.INBOX -> Icons.Default.Inbox; Tab.GENIE -> Icons.Default.AutoAwesome
                                    Tab.AGENTS -> Icons.Default.Groups; Tab.SETTINGS -> Icons.Default.Settings
                                }
                                // The badge counts pending approvals only: a badge that adds up
                                // everything trains people to ignore it (iOS screens doc).
                                if (t == Tab.INBOX && pending > 0) BadgedBox(badge = { Badge(containerColor = Soulacy.colors.coral) { Text("$pending") } }) { Icon(icon, null) }
                                else Icon(icon, null)
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Soulacy.colors.brand, selectedTextColor = Soulacy.colors.brand,
                                indicatorColor = Soulacy.colors.brand.copy(alpha = 0.16f),
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        approvals = s.approvals, deliveries = s.deliveries, agentName = agentName,
                        updatedAt = s.deliveriesUpdated, connection = s.connection, refreshing = refreshing,
                        onRefresh = { refreshing = true; vm.refreshAll() },
                        onOpenApproval = { openApproval = it.callId },
                        onDecide = { a, approve -> decide(a.callId, agentName(a.agentId), a.tool, approve) },
                        onRead = vm::markRead, onReply = { vm.markRead(it); vm.replyTo(it) },
                    )
                    Tab.INBOX -> InboxScreen(
                        approvals = s.approvals, deadline = deadline, agentName = agentName,
                        updatedAt = s.approvalsUpdated, connection = s.connection,
                        onOpen = { openApproval = it.callId },
                        onDeny = { a -> decide(a.callId, agentName(a.agentId), a.tool, false) },
                    )
                    Tab.GENIE -> s.genie?.let { g ->
                        ChatScreen(
                            chat = g, genie = true, snackbar = snack, onBack = null,
                            onSend = { vm.send(ChatSlot.GENIE, it) }, onStop = { vm.stop(ChatSlot.GENIE) },
                            onNew = vm::newGenieConversation, onUndo = { vm.openUndo(g.agent) },
                            onDecide = { req, approve -> decide(req.callId, "Genie", req.tool, approve) },
                        )
                    } ?: FullScreenCenter { Text(if (s.agents.isEmpty()) "Loading your agents…" else "No agents to talk to yet.") }
                    Tab.AGENTS -> AgentsScreen(
                        agents = s.agents, conversations = s.conversations, updatedAt = s.agentsUpdated, connection = s.connection,
                        onOpen = vm::openChat, onOpenConversation = vm::openConversation, onDeleteConversation = vm::deleteConversation,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        profile = profile, reachability = s.reachability, connection = s.connection,
                        pushEnabled = PushSetup.enabled, canNotify = app.notifier.canNotify(),
                        onEnableNotifications = ::askForNotifications, onUnpair = vm::unpair,
                    )
                }
            }
        }
    }
}
