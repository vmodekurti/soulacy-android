package dev.soulacy.android

import dev.soulacy.android.api.Delivery
import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.data.MapKeyValueStore
import dev.soulacy.android.data.Profile
import dev.soulacy.android.data.ProfileRepository
import dev.soulacy.android.notify.AttentionSync
import dev.soulacy.android.notify.AttentionTracker
import dev.soulacy.android.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSyncTest {
    private val server = MockWebServer().apply { start() }
    private val profiles = ProfileRepository(MapKeyValueStore(), InMemorySecretStore()).apply {
        save(Profile(server.url("/").toString().trimEnd('/'), "", "admin", "", "operator", "k"), "sk")
    }
    private val alerts = object : AttentionSync.Alerts {
        val approvals = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val deliveries = mutableListOf<String>()
        override fun approval(a: PendingApproval) { approvals += a.callId }
        override fun cancelApproval(callId: String) { cancelled += callId }
        override fun delivery(d: Delivery) { deliveries += d.id }
    }
    private val sync = AttentionSync(profiles, AttentionTracker(MapKeyValueStore()), alerts)

    @After fun stop() = server.close()

    private fun round(approvals: String, deliveries: String) {
        server.enqueue(MockResponse.Builder().code(200).body("""{"approvals":[$approvals]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"deliveries":[$deliveries],"unread_count":0}""").build())
    }

    @Test fun `alerts once per approval and clears resolved ones`() = runTest {
        round("""{"call_id":"a"},{"call_id":"b"}""", """{"id":"d1","title":"Brief"}""")
        assertTrue(sync.syncOnce())
        round("""{"call_id":"b"},{"call_id":"c"}""", """{"id":"d1","title":"Brief"},{"id":"d2","read_at":"2026-10-26T00:00:00Z"}""")
        assertTrue(sync.syncOnce())
        assertEquals(listOf("a", "b", "c"), alerts.approvals)
        assertEquals(listOf("a"), alerts.cancelled)
        assertEquals(listOf("d1"), alerts.deliveries) // d1 only once, d2 already read
    }

    @Test fun `server errors ask for a retry, auth errors do not`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("{}").build())
        assertFalse(sync.syncOnce())
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        assertTrue(sync.syncOnce())
    }
}
