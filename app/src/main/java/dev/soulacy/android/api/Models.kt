package dev.soulacy.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire types for the gateway endpoints a paired phone uses. Field names
// follow the Go handlers in soulacy-personal/internal/gateway; unknown
// fields are ignored so a newer gateway never breaks an older app.

@Serializable
data class RedeemResponse(
    val paired: Boolean = false,
    val token: String = "",
    val fingerprint: String = "",
    @SerialName("key_id") val keyId: String = "",
    val scopes: List<String> = emptyList(),
    val subject: String = "",
    val role: String = "",
    @SerialName("display_name") val displayName: String = "",
    val reason: String = "",
)

@Serializable
data class Agent(
    val id: String,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
) {
    val title: String get() = name.ifBlank { id }
}

@Serializable
data class AgentInterface(
    val surfaces: List<String> = emptyList(),
    @SerialName("chat_eligible") val chatEligible: Boolean = true,
    @SerialName("disabled_reason") val disabledReason: String? = null,
)

@Serializable
data class AgentsResponse(
    val agents: List<Agent> = emptyList(),
    val interfaces: Map<String, AgentInterface> = emptyMap(),
)

@Serializable
data class PendingApproval(
    @SerialName("call_id") val callId: String,
    val tool: String = "",
    val args: JsonObject? = null,
    val reason: String = "",
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ApprovalsResponse(val approvals: List<PendingApproval> = emptyList())

/** The `tool_confirm` frame on the chat stream. */
@Serializable
data class ConfirmRequest(
    @SerialName("call_id") val callId: String,
    val tool: String = "",
    val args: JsonObject? = null,
    val reason: String = "",
)

@Serializable
data class ChatResult(
    val reply: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("run_id") val runId: String = "",
)

@Serializable
data class ChatRequest(
    @SerialName("agent_id") val agentId: String,
    @SerialName("session_id") val sessionId: String,
    val text: String,
)

@Serializable
data class DeviceRegistration(
    val id: String,
    val name: String,
    val platform: String = "android",
    // Left empty: a gateway without Android push support rejects any
    // bundle id but the iOS one, while an empty one is accepted by both.
    @SerialName("bundle_id") val bundleId: String = "",
    @SerialName("push_token") val pushToken: String = "",
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean,
)

@Serializable
data class Delivery(
    val id: String,
    @SerialName("agent_id") val agentId: String = "",
    val title: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class DeliveriesResponse(
    val deliveries: List<Delivery> = emptyList(),
    @SerialName("unread_count") val unreadCount: Int = 0,
)

@Serializable
data class SafeUndoJob(
    val id: String,
    @SerialName("agent_id") val agentId: String = "",
    val title: String = "",
    val status: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("action_count") val actionCount: Int = 0,
)

@Serializable
data class SafeUndoJobsResponse(val jobs: List<SafeUndoJob> = emptyList())

@Serializable
data class PairingStatus(val paired: Boolean = false, val subject: String = "", val device: JsonElement? = null)

/** One event read from `POST /api/v1/chat/stream`. */
sealed interface ChatEvent {
    data class Run(val runId: String) : ChatEvent
    data class Token(val text: String) : ChatEvent
    data class Confirm(val request: ConfirmRequest) : ChatEvent
    data class Result(val result: ChatResult) : ChatEvent
    data class Error(val message: String) : ChatEvent
    data object Done : ChatEvent
}
