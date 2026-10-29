package dev.soulacy.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

// Wire types for the Settings › workspace tools, matching the gateway
// handlers (adaptive_memory.go, person.go, mobilestatus.go,
// authenticated_connections.go, runledger.go, autopilot*.go, workboard.go,
// learning_notebook.go, safe_undo.go). A paired phone's credential
// (chat, agents:read, memory, config, runs:read, website_access:*) can call
// every read here plus the memory, person, learning and website-session writes.

// --- adaptive memory ---

@Serializable
data class MemoryStatus(
    val enabled: Boolean = false,
    val provider: String = "",
    val categories: List<String> = emptyList(),
    val active: Int? = null,
    val superseded: Int? = null,
    val retracted: Int? = null,
    val relations: Int? = null,
    @SerialName("graph_enabled") val graphEnabled: Boolean = false,
)

@Serializable
data class Fact(
    val id: String,
    @SerialName("agent_id") val agentId: String = "",
    val category: String = "",
    val content: String = "",
    val confidence: Double = 0.0,
    val status: String = "active",
    val source: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val score: Double? = null,
)

@Serializable
data class FactsResponse(val facts: List<Fact>? = null, val provider: String = "")

@Serializable
data class FactEvent(
    val id: Long = 0,
    @SerialName("fact_id") val factId: String = "",
    val event: String = "",
    val actor: String = "",
    val content: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class FactHistory(val events: List<FactEvent>? = null)

@Serializable
data class Relation(
    val id: String,
    val subject: String = "",
    val predicate: String = "",
    @SerialName("object") val obj: String = "",
    val status: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class RelationsResponse(val relations: List<Relation>? = null)

// --- person model ---

@Serializable
data class PersonEntry(
    val section: String,
    val key: String,
    val summary: String = "",
    val source: String = "manual",
    val confidence: Double = 1.0,
    @SerialName("observed_at") val observedAt: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    /** "You told us", "Guessed by planner", "Noticed by routine": the iOS provenance labels. */
    val origin: String get() = when {
        source == "manual" -> "You told us"
        source.startsWith("agent:") -> "Guessed by " + source.removePrefix("agent:")
        source.startsWith("sense:") -> "Noticed by " + source.removePrefix("sense:")
        else -> source
    }
    val isGuess: Boolean get() = source != "manual" && confidence < 0.7
}

@Serializable
data class PersonModel(
    val owner: String = "",
    val entries: List<PersonEntry>? = null,
    val summary: String = "",
)

@Serializable
data class PersonWrite(val entry: PersonEntry? = null, val applied: Boolean = false, val reason: String? = null)

@Serializable
data class Sense(val sense: String, val enabled: Boolean = false, val kinds: List<String> = emptyList(), val purpose: String = "")

@Serializable
data class SensesResponse(val senses: List<Sense>? = null)

@Serializable
data class SenseToggle(val sense: String = "", val enabled: Boolean = false, val forgotten: Int = 0)

// --- diagnostics ---

@Serializable
data class StatusCheck(val key: String = "", val label: String = "", val status: String = "", val detail: String = "")

@Serializable
data class MobileStatus(
    val status: String = "",
    val score: Int = 0,
    val ready: Int = 0,
    val total: Int = 0,
    @SerialName("pending_approvals") val pendingApprovals: Int = 0,
    @SerialName("chat_agents") val chatAgents: Int = 0,
    @SerialName("scheduled_agents") val scheduledAgents: Int = 0,
    @SerialName("delivery_channels") val deliveryChannels: Int = 0,
    @SerialName("recent_runs") val recentRuns: Int = 0,
    @SerialName("durable_run_ledger") val durableRunLedger: Boolean = false,
    val checks: List<StatusCheck> = emptyList(),
    @SerialName("next_actions") val nextActions: List<String> = emptyList(),
)

// --- website access ---

@Serializable
data class WebsiteConnection(
    val id: String,
    val name: String = "",
    val kind: String = "",
    val scope: String = "",
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("allowed_domains") val allowedDomains: List<String> = emptyList(),
    val status: String = "",
    @SerialName("has_secret") val hasSecret: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("agent_ids") val agentIds: List<String> = emptyList(),
)

@Serializable
data class ConnectionsResponse(val connections: List<WebsiteConnection>? = null)

// --- activity ---

@Serializable
data class RunningSession(
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("elapsed_seconds") val elapsedSeconds: Long = 0,
    @SerialName("last_event_type") val lastEventType: String? = null,
    val hung: Boolean = false,
    @SerialName("hung_reason") val hungReason: String? = null,
)

@Serializable
data class RunningResponse(val sessions: List<RunningSession>? = null)

@Serializable
data class RunFailure(val summary: String = "", val step: String? = null, val detail: String? = null, val nextAction: String = "")

@Serializable
data class LedgerRun(
    val runId: String = "",
    val id: String = "",
    val agentId: String? = null,
    val agentName: String? = null,
    val trigger: String? = null,
    val startedAt: String = "",
    val updatedAt: String = "",
    val steps: Int? = null,
    val status: String = "",
    val error: String? = null,
    val output: String? = null,
    val durationMs: Long? = null,
    val deliveryStatus: String? = null,
    val deliveryChannel: String? = null,
    val failure: RunFailure? = null,
) {
    val key: String get() = runId.ifBlank { id }
}

@Serializable
data class LedgerResponse(val runs: List<LedgerRun>? = null, @SerialName("has_more") val hasMore: Boolean = false)

@Serializable
data class Suggestion(
    val kind: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    val title: String = "",
    val detail: String = "",
    val action: String = "",
)

@Serializable
data class SuggestionsResponse(val enabled: Boolean = false, val suggestions: List<Suggestion>? = null)

// --- autopilot (read-only on a phone) ---

@Serializable
data class ProofCheck(val id: String = "", val type: String = "", val description: String? = null, val status: String = "", val detail: String? = null)

@Serializable
data class ProofChange(val kind: String = "", val target: String = "", val summary: String = "", val reversible: Boolean = false)

@Serializable
data class Proof(
    val id: String,
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("run_id") val runId: String = "",
    val outcome: String = "",
    val verification: String = "",
    val simulation: Boolean = false,
    val checks: List<ProofCheck> = emptyList(),
    @SerialName("external_changes") val externalChanges: List<ProofChange> = emptyList(),
    @SerialName("cost_usd") val costUsd: Double? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("completed_at") val completedAt: String = "",
    @SerialName("proof_hash") val proofHash: String = "",
)

@Serializable
data class Reliability(
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("sample_count") val samples: Long = 0,
    @SerialName("success_rate") val successRate: Double? = null,
    @SerialName("verification_rate") val verificationRate: Double? = null,
    val score: Double? = null,
)

@Serializable
data class ProposalRun(val status: String = "", val detail: String? = null)

@Serializable
data class Proposal(
    val id: String,
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("failure_summary") val failureSummary: String = "",
    val status: String = "",
    val baseline: ProposalRun? = null,
    val candidate: ProposalRun? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class GoalTask(val id: String = "", val title: String = "", @SerialName("agent_id") val agentId: String = "", val status: String = "")

@Serializable
data class Goal(
    val id: String,
    val title: String = "",
    val objective: String = "",
    val status: String = "",
    val error: String? = null,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String = "",
    val tasks: List<GoalTask> = emptyList(),
)

@Serializable
data class AutopilotSummary(
    val proofs: List<Proof>? = null,
    val reliability: List<Reliability>? = null,
    val proposals: List<Proposal>? = null,
)

@Serializable
data class DeploymentChannel(
    val channel: String = "",
    @SerialName("current_version_id") val currentVersionId: String? = null,
    @SerialName("traffic_percent") val trafficPercent: Int = 0,
)

@Serializable
data class DeploymentState(
    @SerialName("agent_id") val agentId: String = "",
    val frozen: Boolean = false,
    @SerialName("freeze_reason") val freezeReason: String? = null,
    val channels: List<DeploymentChannel> = emptyList(),
)

@Serializable
data class DeploymentsResponse(val states: List<DeploymentState>? = null)

@Serializable
data class GoalsResponse(val goals: List<Goal>? = null)

// --- safe undo ---

@Serializable
data class UndoValue(val exists: Boolean = false, val display: String? = null)

@Serializable
data class UndoField(val name: String = "", val before: UndoValue = UndoValue(), val after: UndoValue = UndoValue())

@Serializable
data class UndoAction(
    @SerialName("resource_id") val resourceId: String = "",
    val name: String = "",
    val kind: String = "",
    val status: String = "",
    val fields: List<UndoField> = emptyList(),
    @SerialName("before_text") val beforeText: String? = null,
    @SerialName("after_text") val afterText: String? = null,
)

@Serializable
data class UndoJob(
    val id: String,
    val title: String = "",
    val status: String = "",
    val notice: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val actions: List<UndoAction> = emptyList(),
)

// --- workboard ---

@Serializable
data class BoardTask(
    val id: Long,
    val title: String = "",
    val description: String = "",
    @SerialName("agent_id") val agentId: String = "",
    val status: String = "",
    val priority: String = "normal",
    val tags: List<String> = emptyList(),
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class BoardResponse(val tasks: List<BoardTask>? = null)

// --- learning notebook ---

@Serializable
data class Lesson(
    val id: String,
    val key: String = "",
    val kind: String = "",
    val title: String = "",
    val trigger: String = "",
    val content: String = "",
    val verification: String = "",
    val pitfalls: String? = null,
    val status: String = "",
    val version: Int = 1,
    val uses: Int = 0,
    val helpful: Int = 0,
    val unhelpful: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class LessonsResponse(
    val lessons: List<Lesson>? = null,
    val enabled: Boolean = false,
    val total: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class TeachResponse(val lesson: Lesson? = null, val created: Boolean = false)

/**
 * The gateway's workspace tools, typed. Every path is URL-encoded per
 * segment; the gateway decodes ids once.
 */
class Workspace(val client: GatewayClient) {
    private fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun body(o: JsonObject) = GatewayJson.encodeToString(JsonObject.serializer(), o)

    // memory
    suspend fun memoryStatus(): MemoryStatus = client.fetch("/api/v1/memory/facts/status")
    suspend fun facts(status: String?, query: String): List<Fact> = client.fetch<FactsResponse>(
        if (query.isNotBlank()) "/api/v1/memory/facts?q=${e(query)}&limit=50"
        else "/api/v1/memory/facts?limit=200" + (status?.let { "&status=$it" } ?: "")
    ).facts.orEmpty()
    suspend fun relations(): List<Relation> = client.fetch<RelationsResponse>("/api/v1/memory/facts/relations").relations.orEmpty()
    suspend fun factHistory(id: String): List<FactEvent> = client.fetch<FactHistory>("/api/v1/memory/facts/${e(id)}/history").events.orEmpty()
    suspend fun addFact(content: String, category: String) {
        client.send("POST", "/api/v1/memory/facts", body(buildJsonObject { put("content", content); put("category", category) }))
    }
    suspend fun deleteFact(f: Fact) {
        client.send("DELETE", "/api/v1/memory/facts/${e(f.id)}" + if (f.agentId.isNotBlank()) "?agent_id=${e(f.agentId)}" else "")
    }
    suspend fun purgeFacts() { client.send("DELETE", "/api/v1/memory/facts?confirm=true") }

    // person model
    suspend fun person(): PersonModel = client.fetch("/api/v1/person/model")
    suspend fun senses(): List<Sense> = client.fetch<SensesResponse>("/api/v1/person/senses").senses.orEmpty()
    /** 409 means a higher-ranked source holds the key; the reply says which, and the write did not apply. */
    suspend fun putEntry(section: String, key: String, summary: String): PersonWrite {
        val (code, text) = client.send(
            "PUT", "/api/v1/person/model/entries",
            body(buildJsonObject { put("section", section); put("key", key); put("summary", summary) }), allowError = true,
        )
        if (code != 200 && code != 409) throw GatewayException(code, GatewayClient.errorMessage(code, text))
        return GatewayJson.decodeFromString(text)
    }
    suspend fun deleteEntry(entry: PersonEntry) {
        client.send("DELETE", "/api/v1/person/model/entries/${e(entry.section)}/${e(entry.key)}")
    }
    suspend fun forgetPerson() { client.send("DELETE", "/api/v1/person/model?confirm=true") }
    suspend fun setSense(sense: String, enabled: Boolean): SenseToggle = GatewayJson.decodeFromString(
        client.send("PUT", "/api/v1/person/senses/${e(sense)}", body(buildJsonObject { put("enabled", enabled) })).second
    )

    // diagnostics
    suspend fun mobileStatus(): MobileStatus = client.fetch("/api/v1/mobile/status")

    // website access
    suspend fun connections(): List<WebsiteConnection> = client.fetch<ConnectionsResponse>("/api/v1/authenticated-connections").connections.orEmpty()
    suspend fun saveSession(id: String, storageStateJson: String) {
        client.send("PUT", "/api/v1/authenticated-connections/${e(id)}/session", "{\"storage_state\":$storageStateJson}")
    }

    // activity
    suspend fun running(): List<RunningSession> = client.fetch<RunningResponse>("/api/v1/activity/running").sessions.orEmpty()
    suspend fun runs(limit: Int = 60): List<LedgerRun> = client.fetch<LedgerResponse>("/api/v1/runs/ledger?limit=$limit").runs.orEmpty()
    /** There is no GET runs/:id; the ledger's run_id filter is the per-run read. */
    suspend fun run(runId: String): LedgerRun? = client.fetch<LedgerResponse>("/api/v1/runs/ledger?run_id=${e(runId)}&limit=1").runs?.firstOrNull()
    suspend fun suggestions(): List<Suggestion> = client.fetch<SuggestionsResponse>("/api/v1/proactive/suggestions").suggestions.orEmpty()

    // autopilot
    suspend fun autopilot(): AutopilotSummary = client.fetch("/api/v1/autopilot/summary")
    suspend fun proof(id: String): Proof = client.fetch("/api/v1/autopilot/proofs/${e(id)}")
    suspend fun deployments(): List<DeploymentState> = client.fetch<DeploymentsResponse>("/api/v1/autopilot/deployments").states.orEmpty()
    suspend fun goals(): List<Goal> = client.fetch<GoalsResponse>("/api/v1/autopilot/goals").goals.orEmpty()

    // safe undo
    suspend fun undoJob(agentId: String, jobId: String): UndoJob = client.fetch("/api/v1/agents/${e(agentId)}/safe-undo/jobs/${e(jobId)}")

    // workboard
    suspend fun board(status: String?): List<BoardTask> =
        client.fetch<BoardResponse>("/api/v1/workboard/tasks" + (status?.let { "?status=$it" } ?: "")).tasks.orEmpty()

    // learning notebook
    suspend fun lessons(agentId: String, status: String): LessonsResponse =
        client.fetch("/api/v1/agents/${e(agentId)}/learning/lessons?status=$status")
    suspend fun teach(agentId: String, note: String): TeachResponse = GatewayJson.decodeFromString(
        client.send("POST", "/api/v1/agents/${e(agentId)}/learning/teach", body(buildJsonObject { put("note", note) })).second
    )
    suspend fun rateLesson(agentId: String, lessonId: String, helpful: Boolean): Lesson = GatewayJson.decodeFromString(
        client.send("POST", "/api/v1/agents/${e(agentId)}/learning/lessons/${e(lessonId)}/feedback", body(buildJsonObject { put("rating", if (helpful) 1 else -1) })).second
    )
    /** approve, reject, archive ("disable") or restore. */
    suspend fun reviewLesson(agentId: String, lessonId: String, action: String): Lesson = GatewayJson.decodeFromString(
        client.send("POST", "/api/v1/agents/${e(agentId)}/learning/lessons/${e(lessonId)}/review", body(buildJsonObject { put("action", action); put("confirmed", true) })).second
    )
}

/** Why a workspace tool can't be shown, in words; null when the error should surface as-is. */
fun featureUnavailable(e: Throwable): String? = when {
    e is GatewayException && e.status == 503 -> "This gateway hasn't turned this feature on."
    e is GatewayException && e.status == 403 -> "This phone's credential can't open this. Use the web workspace."
    e is GatewayException && e.status == 404 -> "This gateway version doesn't have this yet."
    e is GatewayException && e.status == 501 -> "This gateway's storage doesn't support this."
    e is kotlinx.serialization.SerializationException -> "This gateway answered in a form the app can't read. Update the gateway or the app."
    else -> null
}
