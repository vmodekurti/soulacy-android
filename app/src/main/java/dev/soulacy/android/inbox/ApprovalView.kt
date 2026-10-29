package dev.soulacy.android.inbox

import dev.soulacy.android.api.PendingApproval
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

/** How soon a decision is needed, the iOS Inbox's urgency bands. */
enum class Urgency { WAITING, URGENT, EXPIRED }

object ApprovalTiming {
    /** iOS Approval.defaultTTL: the gateway's personal edition sends no expires_at. */
    val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
    /** iOS Theme.urgentThreshold. */
    val URGENT: Duration = Duration.ofMinutes(3)

    fun deadline(a: PendingApproval, firstSeen: Instant): Instant {
        val explicit = a.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (explicit != null) return explicit
        val created = runCatching { Instant.parse(a.createdAt) }.getOrNull() ?: firstSeen
        return created.plus(DEFAULT_TTL)
    }

    fun urgency(deadline: Instant, now: Instant): Urgency {
        val left = Duration.between(now, deadline)
        return when {
            left.isNegative || left.isZero -> Urgency.EXPIRED
            left < URGENT -> Urgency.URGENT
            else -> Urgency.WAITING
        }
    }

    /** "4:07" on screen. */
    fun countdown(deadline: Instant, now: Instant): String {
        val s = Duration.between(now, deadline).seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** "expires in 4 minutes" for TalkBack, never "4:07". */
    fun spoken(deadline: Instant, now: Instant): String {
        val s = Duration.between(now, deadline).seconds
        return when {
            s <= 0 -> "expired"
            s < 60 -> "expires in less than a minute"
            s < 120 -> "expires in 1 minute"
            else -> "expires in ${s / 60} minutes"
        }
    }

    fun ago(then: Instant, now: Instant): String {
        val s = Duration.between(then, now).seconds.coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}

/**
 * Tool arguments as a person judges them. Lists lead with their size,
 * because the count is the blast radius ("14 items"), long text is cut with
 * a marker, and credential-shaped keys are never shown.
 */
object ArgFormatter {
    data class Row(val key: String, val value: String, val emphasis: String? = null)

    private val secret = Regex("(?i)(pass(word)?|secret|token|api[_-]?key|authorization|cookie|credential|private[_-]?key)")
    const val MAX_TEXT = 280

    fun rows(args: JsonObject?): List<Row> =
        args.orEmpty().entries.map { (k, v) -> row(k, v) }

    private fun row(key: String, v: JsonElement): Row = when {
        secret.containsMatchIn(key) -> Row(key, "[redacted]")
        v is JsonArray -> Row(key, v.take(3).joinToString(", ") { brief(it) } + if (v.size > 3) ", …" else "", "${v.size} ${if (v.size == 1) "item" else "items"}")
        v is JsonObject -> Row(key, v.entries.take(4).joinToString(", ") { (k2, v2) -> "$k2: ${brief(v2)}" } + if (v.size > 4) ", …" else "", "${v.size} ${if (v.size == 1) "field" else "fields"}")
        else -> Row(key, cut(scalar(v)))
    }

    private fun brief(v: JsonElement): String = when (v) {
        is JsonArray -> "[${v.size}]"
        is JsonObject -> "{${v.size}}"
        else -> cut(scalar(v), 60)
    }

    private fun scalar(v: JsonElement): String = when (v) {
        JsonNull -> "none"
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    fun cut(s: String, max: Int = MAX_TEXT): String =
        if (s.length <= max) s else s.take(max) + "… (${s.length - max} more characters)"
}
