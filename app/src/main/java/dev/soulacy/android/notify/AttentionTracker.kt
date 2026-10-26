package dev.soulacy.android.notify

import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.data.KeyValueStore

/**
 * Remembers which approvals and deliveries the phone has already alerted
 * about, so a poll or a push wake-up only notifies about new ones.
 */
class AttentionTracker(private val kv: KeyValueStore) {

    /** Approvals not alerted before. Resolved ones drop out of the memory. */
    fun newApprovals(current: List<PendingApproval>): List<PendingApproval> {
        val seen = read(K_APPROVALS)
        val fresh = current.filter { it.callId !in seen }
        write(K_APPROVALS, current.map { it.callId })
        return fresh
    }

    /** Approvals that were pending last time and are not any more. */
    fun resolvedSince(previous: Set<String>, current: List<PendingApproval>): Set<String> =
        previous - current.map { it.callId }.toSet()

    fun knownApprovals(): Set<String> = read(K_APPROVALS)

    /** Unread deliveries not alerted before. */
    fun newDeliveries(current: List<Delivery>): List<Delivery> {
        val seen = read(K_DELIVERIES)
        val unread = current.filter { it.readAt == null }
        val fresh = unread.filter { it.id !in seen }
        write(K_DELIVERIES, (seen + fresh.map { it.id }).toList().takeLast(MAX))
        return fresh
    }

    private fun read(key: String): Set<String> =
        kv.getString(key)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun write(key: String, ids: Collection<String>) {
        kv.putStrings(mapOf(key to ids.joinToString(",")))
    }

    private companion object {
        const val K_APPROVALS = "seen_approvals"
        const val K_DELIVERIES = "seen_deliveries"
        const val MAX = 200
    }
}
