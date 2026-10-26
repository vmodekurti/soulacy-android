package dev.soulacy.android.notify

import dev.soulacy.android.api.GatewayException
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.data.ProfileRepository

/** One pass of "what needs the person now", shared by the worker, push and the open app. */
class AttentionSync(
    private val profiles: ProfileRepository,
    private val tracker: AttentionTracker,
    private val alert: Alerts,
) {
    /** Where alerts go; [Notifier] on a device, a fake in tests. */
    interface Alerts {
        fun approval(a: PendingApproval)
        fun cancelApproval(callId: String)
        fun delivery(d: dev.soulacy.android.api.Delivery)
    }

    /** Returns false when the gateway could not be reached (worth a retry). */
    suspend fun syncOnce(): Boolean {
        val client = profiles.client() ?: return true
        return try {
            val before = tracker.knownApprovals()
            val approvals = client.approvals()
            tracker.newApprovals(approvals).forEach(alert::approval)
            tracker.resolvedSince(before, approvals).forEach(alert::cancelApproval)
            val deliveries = runCatching { client.deliveries(profiles.deviceId()).deliveries }.getOrDefault(emptyList())
            tracker.newDeliveries(deliveries).forEach(alert::delivery)
            true
        } catch (e: GatewayException) {
            // 401/403 will not fix themselves by retrying.
            e.status == 401 || e.status == 403
        } catch (_: Exception) {
            false
        }
    }
}
