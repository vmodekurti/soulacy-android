package dev.soulacy.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.soulacy.android.SoulacyApp
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.ChatEvent
import dev.soulacy.android.api.ChatRequest
import dev.soulacy.android.api.ConfirmRequest
import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.GatewayException
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.api.SafeUndoJob
import dev.soulacy.android.data.Profile
import dev.soulacy.android.notify.SyncWorker
import dev.soulacy.android.pairing.PairingLink
import dev.soulacy.android.pairing.PairingOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)

data class ChatState(
    val agent: Agent,
    val sessionId: String = "android-" + UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val runId: String = "",
    val confirm: ConfirmRequest? = null,
)

data class UiState(
    val profile: Profile? = null,
    val agents: List<Agent> = emptyList(),
    val agentsLoading: Boolean = false,
    val approvals: List<PendingApproval> = emptyList(),
    val deliveries: List<Delivery> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val pairing: Boolean = false,
    val chat: ChatState? = null,
    val undoAgent: Agent? = null,
    val undoJobs: List<SafeUndoJob> = emptyList(),
    val undoMessage: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SoulacyApp
    private val _state = MutableStateFlow(UiState(profile = app.profiles.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var poller: Job? = null
    private var stream: Job? = null

    // --- pairing ---

    fun pair(link: PairingLink) {
        _state.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            when (val out = app.pairer.pair(link, app.deviceName, app.notifier.canNotify())) {
                is PairingOutcome.Paired -> {
                    SyncWorker.schedule(app)
                    _state.update {
                        it.copy(
                            profile = out.profile, pairing = false,
                            notice = out.registrationError?.let { e -> "Paired, but notifications are not set up: $e" },
                        )
                    }
                    refreshAll()
                }
                is PairingOutcome.Failed -> _state.update { it.copy(pairing = false, error = out.message) }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            runCatching { app.profiles.client()?.unpair() }
            app.profiles.clear()
            SyncWorker.cancel(app)
            stream?.cancel()
            _state.value = UiState()
        }
    }

    /** Re-sends the device registration, e.g. after notification permission changed. */
    fun reregister() {
        viewModelScope.launch { app.pairer.register(app.deviceName, app.notifier.canNotify()) }
    }

    // --- foreground refresh ---

    fun onForeground() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            refreshAgents()
            while (isActive) {
                if (_state.value.profile != null) {
                    app.sync.syncOnce()
                    refreshApprovals()
                    refreshDeliveries()
                }
                delay(5_000)
            }
        }
    }

    fun onBackground() {
        poller?.cancel()
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshAgents()
            refreshApprovals()
            refreshDeliveries()
        }
    }

    private suspend fun refreshAgents() {
        val client = app.profiles.client() ?: return
        _state.update { it.copy(agentsLoading = true) }
        try {
            val res = client.agents()
            val visible = res.agents.filter { a -> a.enabled && res.interfaces[a.id]?.chatEligible != false }
            _state.update { it.copy(agents = visible, agentsLoading = false, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(agentsLoading = false, error = describe(e)) }
        }
    }

    private suspend fun refreshApprovals() {
        val client = app.profiles.client() ?: return
        runCatching { client.approvals() }.onSuccess { list -> _state.update { it.copy(approvals = list) } }
    }

    private suspend fun refreshDeliveries() {
        val client = app.profiles.client() ?: return
        runCatching { client.deliveries(app.profiles.deviceId()).deliveries }
            .onSuccess { list -> _state.update { it.copy(deliveries = list) } }
    }

    fun markRead(d: Delivery) {
        viewModelScope.launch {
            app.profiles.client()?.markDeliveryRead(d.id, app.profiles.deviceId())
            refreshDeliveries()
        }
    }

    fun showMessage(message: String) = _state.update { it.copy(notice = message) }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    // --- approvals ---

    /** Call only after [BiometricGate] passed. */
    fun decide(callId: String, approve: Boolean) {
        viewModelScope.launch {
            val client = app.profiles.client() ?: return@launch
            try {
                client.decide(callId, approve)
                _state.update { it.copy(notice = if (approve) "Approved" else "Denied") }
            } catch (e: GatewayException) {
                // 404: decided elsewhere or timed out. Either way it is gone.
                _state.update { it.copy(notice = if (e.status == 404) "Already decided or expired" else describe(e)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
            }
            app.notifier.cancelApproval(callId)
            _state.update { s ->
                s.copy(
                    approvals = s.approvals.filterNot { it.callId == callId },
                    chat = s.chat?.let { c -> if (c.confirm?.callId == callId) c.copy(confirm = null) else c },
                )
            }
            refreshApprovals()
        }
    }

    // --- chat ---

    fun openChat(agent: Agent) {
        val current = _state.value.chat
        if (current?.agent?.id != agent.id) _state.update { it.copy(chat = ChatState(agent)) }
    }

    fun closeChat() {
        _state.update { it.copy(chat = null) }
    }

    fun send(text: String) {
        val chat = _state.value.chat ?: return
        if (text.isBlank() || chat.streaming) return
        val client = app.profiles.client() ?: return
        updateChat { it.copy(messages = it.messages + ChatMessage(true, text.trim()) + ChatMessage(false, ""), streaming = true) }
        stream = viewModelScope.launch {
            try {
                client.streamChat(ChatRequest(chat.agent.id, chat.sessionId, text.trim())).collect { ev ->
                    when (ev) {
                        is ChatEvent.Run -> updateChat { it.copy(runId = ev.runId) }
                        is ChatEvent.Token -> updateLast { it.copy(text = it.text + ev.text) }
                        is ChatEvent.Confirm -> updateChat { it.copy(confirm = ev.request) }
                        is ChatEvent.Result -> if (ev.result.reply.isNotBlank()) updateLast { it.copy(text = ev.result.reply) }
                        is ChatEvent.Error -> updateLast { it.copy(text = it.text.ifBlank { ev.message }, error = it.text.isBlank()) }
                        ChatEvent.Done -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) updateLast { it.copy(text = describe(e), error = true) }
            } finally {
                updateChat { it.copy(streaming = false, confirm = null) }
            }
        }
    }

    fun stop() {
        val chat = _state.value.chat ?: return
        stream?.cancel()
        if (chat.runId.isNotBlank()) viewModelScope.launch { app.profiles.client()?.cancelRun(chat.runId) }
    }

    private fun updateChat(f: (ChatState) -> ChatState) = _state.update { s -> s.copy(chat = s.chat?.let(f)) }

    private fun updateLast(f: (ChatMessage) -> ChatMessage) = updateChat { c ->
        if (c.messages.isEmpty()) c else c.copy(messages = c.messages.dropLast(1) + f(c.messages.last()))
    }

    // --- Safe Undo (read-only on the phone) ---

    fun openUndo(agent: Agent) {
        _state.update { it.copy(undoAgent = agent, undoJobs = emptyList(), undoMessage = "Loading receipts") }
        viewModelScope.launch {
            val client = app.profiles.client() ?: return@launch
            try {
                val jobs = client.safeUndoJobs(agent.id)
                _state.update { it.copy(undoJobs = jobs, undoMessage = if (jobs.isEmpty()) "No Safe Undo receipts for this agent." else null) }
            } catch (e: GatewayException) {
                val msg = when (e.status) {
                    403 -> "This phone's credential can't open Safe Undo. Review and undo changes in the web workspace."
                    503 -> "Safe Undo is not enabled on this gateway."
                    else -> describe(e)
                }
                _state.update { it.copy(undoMessage = msg) }
            } catch (e: Exception) {
                _state.update { it.copy(undoMessage = describe(e)) }
            }
        }
    }

    fun closeUndo() = _state.update { it.copy(undoAgent = null) }

    private fun describe(e: Exception): String = when (e) {
        is GatewayException -> e.message ?: "Gateway error ${e.status}"
        is java.net.UnknownHostException -> "Can't find the gateway. Check the network or VPN."
        is java.net.ConnectException -> "Can't reach the gateway."
        is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
