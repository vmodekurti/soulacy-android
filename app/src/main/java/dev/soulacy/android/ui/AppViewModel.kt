package dev.soulacy.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.soulacy.android.SoulacyApp
import dev.soulacy.android.api.Agent
import dev.soulacy.android.api.AgentsResponse
import dev.soulacy.android.api.ChatEvent
import dev.soulacy.android.api.ChatRequest
import dev.soulacy.android.api.ConfirmRequest
import dev.soulacy.android.api.DeliveriesResponse
import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.GatewayException
import dev.soulacy.android.api.GatewayJson
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.api.ApprovalsResponse
import dev.soulacy.android.data.Conversation
import dev.soulacy.android.data.Profile
import dev.soulacy.android.data.StoredMessage
import dev.soulacy.android.notify.SyncWorker
import dev.soulacy.android.pairing.ConnectionProblem
import dev.soulacy.android.pairing.PairingLink
import dev.soulacy.android.pairing.PairingOutcome
import dev.soulacy.android.pairing.Reachability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

data class ChatMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)

/** Which conversation a call is about: the Genie tab, or one opened from Agents / Home. */
enum class ChatSlot { GENIE, AGENT }

data class ChatState(
    val agent: Agent,
    val sessionId: String = "android-" + UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val runId: String = "",
    val confirm: ConfirmRequest? = null,
    /** What the agent is doing while no text is arriving ("Thinking", "Running list_dir"). */
    val working: String? = null,
)

/** Connection as a quiet line under the top bar, never a dialog (iOS rule). */
sealed interface Connection {
    data object Connected : Connection
    data object Checking : Connection
    data class Offline(val message: String) : Connection
}

data class UiState(
    val profile: Profile? = null,
    val onboardingDone: Boolean = true,
    val reachability: Reachability? = null,
    val agents: List<Agent> = emptyList(),
    val agentsUpdated: Long? = null,
    val approvals: List<PendingApproval> = emptyList(),
    val approvalsUpdated: Long? = null,
    val deliveries: List<Delivery> = emptyList(),
    val deliveriesUpdated: Long? = null,
    val conversations: List<Conversation> = emptyList(),
    val connection: Connection = Connection.Checking,
    val notice: String? = null,
    val pairing: Boolean = false,
    val pairError: String? = null,
    val genie: ChatState? = null,
    val chat: ChatState? = null,
    val undoAgent: Agent? = null,
    val appearance: dev.soulacy.android.data.Appearance = dev.soulacy.android.data.Appearance.SYSTEM,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SoulacyApp
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** When the phone first saw each approval: the fallback clock for a missing created_at. */
    val firstSeen = mutableMapOf<String, Instant>()

    private var poller: Job? = null

    private fun initialState(): UiState {
        val profile = app.profiles.load()
        val cache = app.cache
        fun <T> cached(key: String, decode: (String) -> T): Pair<T, Long>? =
            cache.get(key)?.let { (body, at) -> runCatching { decode(body) to at }.getOrNull() }
        val agents = cached("agents") { GatewayJson.decodeFromString<AgentsResponse>(it) }
        val approvals = cached("approvals") { GatewayJson.decodeFromString<ApprovalsResponse>(it).approvals }
        val deliveries = cached("deliveries") { GatewayJson.decodeFromString<DeliveriesResponse>(it).deliveries }
        return UiState(
            profile = profile,
            onboardingDone = app.settings.onboardingDone(profile != null),
            reachability = app.settings.reachability,
            appearance = app.settings.appearance,
            agents = agents?.first?.let(::chatAgents).orEmpty(),
            agentsUpdated = agents?.second,
            approvals = approvals?.first.orEmpty(),
            approvalsUpdated = approvals?.second,
            deliveries = deliveries?.first.orEmpty(),
            deliveriesUpdated = deliveries?.second,
            conversations = app.conversations.list(),
        )
    }

    // --- onboarding and pairing ---

    fun pair(link: PairingLink) {
        _state.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            app.settings.setOnboardingDone(false)
            when (val out = app.pairer.pair(link, app.deviceName, app.notifier.canNotify())) {
                is PairingOutcome.Paired -> {
                    SyncWorker.schedule(app)
                    _state.update { it.copy(profile = out.profile, pairing = false, onboardingDone = false) }
                    refreshAll()
                }
                is PairingOutcome.Failed -> _state.update { it.copy(pairing = false, pairError = out.message) }
            }
        }
    }

    fun clearPairError() = _state.update { it.copy(pairError = null) }

    fun setReachability(r: Reachability) {
        app.settings.reachability = r
        _state.update { it.copy(reachability = r) }
    }

    fun finishOnboarding() {
        app.settings.setOnboardingDone(true)
        reregister()
        _state.update { it.copy(onboardingDone = true) }
    }

    fun unpair() {
        viewModelScope.launch {
            runCatching { app.profiles.client()?.unpair() }
            app.profiles.clear()
            app.cache.clear()
            app.conversations.clear()
            app.settings.clear()
            SyncWorker.cancel(app)
            streams.values.forEach { it.cancel() }
            live.clear()
            _state.value = UiState(onboardingDone = false)
        }
    }

    /** Re-sends the device registration, e.g. after notification permission changed. */
    fun reregister() {
        viewModelScope.launch { app.pairer.register(app.deviceName, app.notifier.canNotify()) }
    }

    // --- refresh ---

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

    fun onBackground() { poller?.cancel() }

    fun refreshAll() {
        viewModelScope.launch {
            _state.update { it.copy(connection = Connection.Checking) }
            refreshAgents(); refreshApprovals(); refreshDeliveries()
        }
    }

    private suspend fun <T> fetch(key: String, call: suspend () -> Pair<T, String>, apply: (UiState, T, Long) -> UiState) {
        val client = app.profiles.client() ?: return
        try {
            val (value, raw) = call()
            app.cache.put(key, raw)
            val now = System.currentTimeMillis()
            _state.update { apply(it, value, now).copy(connection = Connection.Connected) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            // The gateway answered but the app couldn't read it: not a
            // connection problem, and not something a later success should hide.
            _state.update { it.copy(notice = "Couldn't read $key from the gateway. Update the app if this keeps happening.") }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(notice = "Couldn't read $key from the gateway. Update the app if this keeps happening.") }
        } catch (e: Exception) {
            val msg = ConnectionProblem.describe(e, client.base.toString().trimEnd('/'), _state.value.reachability)
            _state.update { it.copy(connection = Connection.Offline(msg)) }
        }
    }

    private suspend fun refreshAgents() = fetch("agents", {
        val res = app.profiles.client()!!.agents()
        res to GatewayJson.encodeToString(AgentsResponse.serializer(), res)
    }) { s, res, at -> s.copy(agents = chatAgents(res), agentsUpdated = at) }

    private suspend fun refreshApprovals() = fetch("approvals", {
        val list = app.profiles.client()!!.approvals()
        list to GatewayJson.encodeToString(ApprovalsResponse.serializer(), ApprovalsResponse(list))
    }) { s, list, at ->
        val now = Instant.now()
        list.forEach { firstSeen.putIfAbsent(it.callId, now) }
        s.copy(approvals = list, approvalsUpdated = at)
    }

    private suspend fun refreshDeliveries() = fetch("deliveries", {
        val res = app.profiles.client()!!.deliveries(app.profiles.deviceId())
        res.deliveries to GatewayJson.encodeToString(DeliveriesResponse.serializer(), res)
    }) { s, list, at -> s.copy(deliveries = list, deliveriesUpdated = at) }

    private fun chatAgents(res: AgentsResponse): List<Agent> =
        res.agents.filter { a -> a.enabled && res.interfaces[a.id]?.chatEligible != false }

    fun markRead(d: Delivery) {
        if (d.readAt != null) return
        viewModelScope.launch {
            app.profiles.client()?.markDeliveryRead(d.id, app.profiles.deviceId())
            refreshDeliveries()
        }
    }

    fun showMessage(message: String) = _state.update { it.copy(notice = message) }
    fun dismissMessages() = _state.update { it.copy(notice = null) }

    // --- approvals ---

    /** Call only after [BiometricGate] passed. */
    fun decide(callId: String, approve: Boolean) {
        val pending = _state.value.approvals.firstOrNull { it.callId == callId }
        viewModelScope.launch {
            val client = app.profiles.client() ?: return@launch
            try {
                client.decide(callId, approve)
                _state.update { it.copy(notice = if (approve) "Approved" else "Denied") }
                // The conversation that asked may be on screen or running in the background.
                live.values.filter { it.confirm?.callId == callId || (pending != null && it.sessionId == pending.sessionId) }.forEach { c ->
                    val tool = c.confirm?.tool ?: pending?.tool.orEmpty()
                    updateSession(c.sessionId) { it.copy(confirm = null, working = if (approve) "Running $tool" else "Continuing without $tool") }
                }
            } catch (e: GatewayException) {
                _state.update { it.copy(notice = if (e.status == 404) "Already decided, or it expired." else e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(notice = ConnectionProblem.describe(e, client.base.toString())) }
            }
            app.notifier.cancelApproval(callId)
            _state.update { s -> s.copy(approvals = s.approvals.filterNot { it.callId == callId }) }
            refreshApprovals()
        }
    }

    // --- chat ---

    /**
     * Conversations with a reply in flight, by session. A stream keeps going
     * when its screen is closed; the screens mirror these while open.
     */
    private val live = mutableMapOf<String, ChatState>()
    private val streams = mutableMapOf<String, Job>()

    fun slotState(slot: ChatSlot): ChatState? = when (slot) {
        ChatSlot.GENIE -> _state.value.genie
        ChatSlot.AGENT -> _state.value.chat
    }

    private fun show(slot: ChatSlot, c: ChatState?) = _state.update { s ->
        when (slot) {
            ChatSlot.GENIE -> s.copy(genie = c)
            ChatSlot.AGENT -> s.copy(chat = c)
        }
    }

    private fun updateSession(id: String, f: (ChatState) -> ChatState) {
        val current = live[id] ?: listOfNotNull(_state.value.genie, _state.value.chat).firstOrNull { it.sessionId == id } ?: return
        val next = f(current)
        if (id in live) live[id] = next
        _state.update { s ->
            s.copy(
                genie = if (s.genie?.sessionId == id) next else s.genie,
                chat = if (s.chat?.sessionId == id) next else s.chat,
            )
        }
    }

    private fun updateLast(id: String, f: (ChatMessage) -> ChatMessage) = updateSession(id) { c ->
        if (c.messages.isEmpty()) c else c.copy(messages = c.messages.dropLast(1) + f(c.messages.last()))
    }

    /** The Genie tab talks to the gateway's "genie" agent, or the first agent when there is none. */
    fun ensureGenie() {
        if (_state.value.genie != null) return
        val agents = _state.value.agents
        val genie = agents.firstOrNull { it.id == "genie" } ?: agents.firstOrNull() ?: return
        val last = _state.value.conversations.firstOrNull { it.agentId == genie.id && it.sessionId.startsWith("genie-") }
        show(ChatSlot.GENIE, last?.let { c -> live[c.sessionId] ?: restore(genie, c) } ?: ChatState(genie, sessionId = "genie-" + UUID.randomUUID()))
    }

    fun newGenieConversation() {
        val g = _state.value.genie ?: return
        show(ChatSlot.GENIE, ChatState(g.agent, sessionId = "genie-" + UUID.randomUUID()))
    }

    fun openChat(agent: Agent) {
        if (_state.value.chat?.agent?.id != agent.id) show(ChatSlot.AGENT, ChatState(agent))
    }

    fun openConversation(c: Conversation) {
        val agent = _state.value.agents.firstOrNull { it.id == c.agentId } ?: Agent(c.agentId, c.agentName)
        show(ChatSlot.AGENT, live[c.sessionId] ?: restore(agent, c))
    }

    /** "Reply in Chat" on a delivered result: a new conversation that starts from it. */
    fun replyTo(d: Delivery) {
        val agent = _state.value.agents.firstOrNull { it.id == d.agentId } ?: Agent(d.agentId, d.agentId)
        show(ChatSlot.AGENT, ChatState(agent, messages = listOf(ChatMessage(false, "**${d.title.ifBlank { "Result" }}**\n\n${d.body}"))))
    }

    fun deleteConversation(c: Conversation) {
        streams[c.sessionId]?.cancel()
        app.conversations.delete(c.sessionId)
        _state.update { it.copy(conversations = app.conversations.list()) }
    }

    private fun restore(agent: Agent, c: Conversation) =
        ChatState(agent, sessionId = c.sessionId, messages = c.messages.map { ChatMessage(it.fromUser, it.text, it.error) })

    /** Sending and each finished reply already saved it; closing must not bump it to the top. */
    fun closeChat() = show(ChatSlot.AGENT, null)

    fun send(slot: ChatSlot, text: String) {
        val chat = slotState(slot) ?: return
        if (text.isBlank() || chat.streaming) return
        val client = app.profiles.client() ?: return
        val id = chat.sessionId
        live[id] = chat.copy(
            messages = chat.messages + ChatMessage(true, text.trim()) + ChatMessage(false, ""), streaming = true, working = "Thinking",
        )
        updateSession(id) { live.getValue(id) }
        persist(live.getValue(id))
        streams[id] = viewModelScope.launch {
            try {
                client.streamChat(ChatRequest(chat.agent.id, id, text.trim())).collect { ev ->
                    when (ev) {
                        is ChatEvent.Run -> updateSession(id) { it.copy(runId = ev.runId) }
                        is ChatEvent.Token -> {
                            updateLast(id) { it.copy(text = it.text + ev.text) }
                            updateSession(id) { it.copy(working = null) }
                        }
                        is ChatEvent.Confirm -> updateSession(id) { it.copy(confirm = ev.request, working = "Waiting for your approval") }
                        is ChatEvent.Result -> if (ev.result.reply.isNotBlank()) updateLast(id) { it.copy(text = ev.result.reply) }
                        is ChatEvent.Error -> updateLast(id) { it.copy(text = it.text.ifBlank { ConnectionProblem.agentError(ev.message) }, error = it.text.isBlank()) }
                        ChatEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                updateLast(id) { if (it.text.isBlank()) it.copy(text = "Stopped.", error = true) else it }
            } catch (e: Exception) {
                updateLast(id) { it.copy(text = ConnectionProblem.describe(e, client.base.toString().trimEnd('/'), _state.value.reachability), error = true) }
            } finally {
                updateSession(id) { it.copy(streaming = false, confirm = null, working = null) }
                live.remove(id)?.let(::persist)
                streams.remove(id)
            }
        }
    }

    fun stop(slot: ChatSlot) {
        val chat = slotState(slot) ?: return
        streams[chat.sessionId]?.cancel()
        val runId = live[chat.sessionId]?.runId ?: chat.runId
        if (runId.isNotBlank()) viewModelScope.launch { app.profiles.client()?.cancelRun(runId) }
    }

    private fun persist(c: ChatState) {
        app.conversations.save(
            Conversation(
                sessionId = c.sessionId, agentId = c.agent.id, agentName = c.agent.title,
                updatedAt = System.currentTimeMillis(),
                messages = c.messages.filter { it.text.isNotBlank() }.map { StoredMessage(it.fromUser, it.text, it.error) },
            )
        )
        _state.update { it.copy(conversations = app.conversations.list()) }
    }

    // --- Safe Undo (read-only on the phone) ---

    /** Safe Undo for [agent], opened from a chat's menu; Settings › Safe Undo opens it too. */
    fun openUndo(agent: Agent) = _state.update { it.copy(undoAgent = agent) }

    fun closeUndo() = _state.update { it.copy(undoAgent = null) }

    fun setAppearance(a: dev.soulacy.android.data.Appearance) {
        app.settings.appearance = a
        _state.update { it.copy(appearance = a) }
    }
}
