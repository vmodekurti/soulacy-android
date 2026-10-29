package dev.soulacy.android.data

import dev.soulacy.android.api.GatewayJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class StoredMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)

@Serializable
data class Conversation(
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
) {
    val preview: String get() = messages.lastOrNull { it.text.isNotBlank() }?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
}

/**
 * Conversations kept on this phone only, in app-private storage that is
 * excluded from backup. The gateway session id is reused when a
 * conversation is reopened, so the agent keeps its context.
 */
class ConversationStore(private val dir: File) {
    init { dir.mkdirs() }

    fun list(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { GatewayJson.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(sessionId: String): Conversation? =
        file(sessionId).takeIf { it.exists() }?.let { runCatching { GatewayJson.decodeFromString<Conversation>(it.readText()) }.getOrNull() }

    fun save(c: Conversation) {
        if (c.messages.none { it.fromUser }) return
        val trimmed = c.copy(messages = c.messages.takeLast(MAX_MESSAGES))
        val tmp = File(dir, "${safe(c.sessionId)}.tmp")
        tmp.writeText(GatewayJson.encodeToString(Conversation.serializer(), trimmed))
        tmp.renameTo(file(c.sessionId))
        list().drop(MAX_CONVERSATIONS).forEach { delete(it.sessionId) }
    }

    fun delete(sessionId: String) { file(sessionId).delete() }

    fun clear() { dir.listFiles().orEmpty().forEach { it.delete() } }

    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val MAX_CONVERSATIONS = 50
        const val MAX_MESSAGES = 200
    }
}

/** Last good copy of each list, so screens render at once and offline. */
class ResponseCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { dir.mkdirs() }

    fun put(key: String, body: String) {
        File(dir, "$key.json").writeText(body)
    }

    /** The cached body and when it was saved, or null. */
    fun get(key: String): Pair<String, Long>? =
        File(dir, "$key.json").takeIf { it.exists() }?.let { it.readText() to it.lastModified() }

    fun clear() { dir.listFiles().orEmpty().forEach { it.delete() } }

    fun now(): Long = clock()
}
