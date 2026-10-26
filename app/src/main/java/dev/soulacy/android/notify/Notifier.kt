package dev.soulacy.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.soulacy.android.MainActivity
import dev.soulacy.android.R
import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.PendingApproval

/**
 * Local notifications. Text stays generic (agent and tool names only), like
 * the iOS lock-screen alerts; arguments are shown inside the app after the
 * person unlocks it.
 */
class Notifier(private val context: Context) : AttentionSync.Alerts {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent is waiting for your decision"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULTS, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Results from scheduled and long-running agent tasks"
            }
        )
    }

    fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun approval(a: PendingApproval) {
        val who = a.agentId.ifBlank { "An agent" }
        val tool = a.tool.ifBlank { "a tool" }
        post(
            id = a.callId.hashCode(),
            n = NotificationCompat.Builder(context, CH_APPROVALS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Approval needed")
                .setContentText("$who wants to use $tool")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open("soulacy://approval/${Uri.encode(a.callId)}"))
                .build(),
        )
    }

    override fun cancelApproval(callId: String) = NotificationManagerCompat.from(context).cancel(callId.hashCode())

    override fun delivery(d: Delivery) {
        post(
            id = d.id.hashCode(),
            n = NotificationCompat.Builder(context, CH_RESULTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(d.title.ifBlank { "New result" })
                .setContentText(d.agentId.ifBlank { "Soulacy" } + " finished a task")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open("soulacy://delivery/${Uri.encode(d.id)}"))
                .build(),
        )
    }

    private fun open(link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, link.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(id: Int, n: android.app.Notification) {
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    companion object {
        const val CH_APPROVALS = "approvals"
        const val CH_RESULTS = "results"
    }
}
