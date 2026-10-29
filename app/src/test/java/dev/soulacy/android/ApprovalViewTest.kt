package dev.soulacy.android

import dev.soulacy.android.api.PendingApproval
import dev.soulacy.android.inbox.ApprovalTiming
import dev.soulacy.android.inbox.ArgFormatter
import dev.soulacy.android.inbox.Urgency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApprovalViewTest {
    private val t0 = Instant.parse("2026-10-29T10:00:00Z")

    @Test fun `deadline prefers expires_at, then created_at plus 15 minutes, then first seen`() {
        assertEquals(Instant.parse("2026-10-29T10:05:00Z"), ApprovalTiming.deadline(PendingApproval("a", createdAt = "2026-10-29T09:00:00Z", expiresAt = "2026-10-29T10:05:00Z"), t0))
        assertEquals(Instant.parse("2026-10-29T09:15:00Z"), ApprovalTiming.deadline(PendingApproval("a", createdAt = "2026-10-29T09:00:00Z"), t0))
        assertEquals(t0.plusSeconds(900), ApprovalTiming.deadline(PendingApproval("a"), t0))
        // The gateway's Go timestamps carry nanoseconds; they must still parse.
        assertEquals(Instant.parse("2026-10-29T09:15:00.123456789Z"), ApprovalTiming.deadline(PendingApproval("a", createdAt = "2026-10-29T09:00:00.123456789Z"), t0))
    }

    @Test fun `urgency bands match iOS`() {
        assertEquals(Urgency.WAITING, ApprovalTiming.urgency(t0.plusSeconds(181), t0))
        assertEquals(Urgency.URGENT, ApprovalTiming.urgency(t0.plusSeconds(179), t0))
        assertEquals(Urgency.EXPIRED, ApprovalTiming.urgency(t0, t0))
    }

    @Test fun `countdown and spoken forms`() {
        assertEquals("4:07", ApprovalTiming.countdown(t0.plusSeconds(247), t0))
        assertEquals("0:00", ApprovalTiming.countdown(t0.minusSeconds(5), t0))
        assertEquals("expires in 4 minutes", ApprovalTiming.spoken(t0.plusSeconds(247), t0))
        assertEquals("expires in less than a minute", ApprovalTiming.spoken(t0.plusSeconds(20), t0))
        assertEquals("expired", ApprovalTiming.spoken(t0, t0))
        assertEquals("just now", ApprovalTiming.ago(t0, t0.plusSeconds(10)))
        assertEquals("5 min ago", ApprovalTiming.ago(t0, t0.plusSeconds(290)))
        assertEquals("2 h ago", ApprovalTiming.ago(t0, t0.plusSeconds(7300)))
    }

    @Test fun `arguments lead with blast radius and hide secrets`() {
        val args = Json.parseToJsonElement(
            """{"paths":["/a","/b","/c","/d"],"api_key":"sk-live-123","options":{"force":true},"note":"${"x".repeat(300)}","dry_run":false,"one":["x"]}"""
        ).jsonObject
        val rows = ArgFormatter.rows(args).associateBy { it.key }
        assertEquals("4 items", rows["paths"]!!.emphasis)
        assertEquals("/a, /b, /c, …", rows["paths"]!!.value)
        assertEquals("1 item", rows["one"]!!.emphasis)
        assertEquals("[redacted]", rows["api_key"]!!.value)
        assertEquals("1 field", rows["options"]!!.emphasis)
        assertTrue(rows["note"]!!.value.endsWith("… (20 more characters)"))
        assertEquals("false", rows["dry_run"]!!.value)
        assertEquals(emptyList<ArgFormatter.Row>(), ArgFormatter.rows(null))
    }
}
