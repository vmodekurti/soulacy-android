package dev.soulacy.android

import dev.soulacy.android.api.ChatEvent
import dev.soulacy.android.api.ChatRequest
import dev.soulacy.android.api.DeviceRegistration
import dev.soulacy.android.api.GatewayClient
import dev.soulacy.android.api.GatewayException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GatewayClientTest {
    private val server = MockWebServer()
    private lateinit var client: GatewayClient

    @Before fun start() {
        server.start()
        client = GatewayClient(server.url("/").toString(), token = "tok-123")
    }

    @After fun stop() = server.close()

    private fun reply(code: Int, body: String, type: String = "application/json") =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", type).body(body).build())

    @Test fun `redeem is unauthenticated and returns the scoped key`() = runTest {
        reply(200, """{"paired":true,"token":"sk-new","fingerprint":"","key_id":"k1","scopes":["chat"],"subject":"admin","role":"operator","display_name":"","message":"x"}""")
        val r = client.redeem("ABCDEFGHIJKLMNOP")
        assertEquals("sk-new", r.token)
        assertEquals("admin", r.subject)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/pairing/redeem", req.url.encodedPath)
        assertNull(req.headers["Authorization"])
        assertEquals("ABCDEFGHIJKLMNOP", Json.parseToJsonElement(req.body!!.utf8()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun `redeem surfaces the gateway's reason`() = runTest {
        reply(401, """{"paired":false,"reason":"invalid or expired pairing code"}""")
        try { client.redeem("X"); fail() } catch (e: GatewayException) {
            assertEquals(401, e.status)
            assertEquals("invalid or expired pairing code", e.message)
        }
        reply(409, """{"paired":false,"reason":"Your phone is already paired (since Oct 1). Unpair it first to pair a different device."}""")
        try { client.redeem("X"); fail() } catch (e: GatewayException) {
            assertEquals(409, e.status)
            assertTrue(e.message!!.startsWith("Your phone is already paired"))
        }
    }

    @Test fun `authenticated calls send the bearer token`() = runTest {
        reply(200, """{"agents":[{"id":"genie","name":"Genie","description":"helper","enabled":true},{"id":"cron","enabled":true}],"count":2,"interfaces":{"cron":{"chat_eligible":false}}}""")
        val res = client.agents()
        assertEquals(listOf("genie", "cron"), res.agents.map { it.id })
        assertEquals(false, res.interfaces["cron"]?.chatEligible)
        val req = server.takeRequest()
        assertEquals("Bearer tok-123", req.headers["Authorization"])
        assertEquals("chat", req.url.queryParameter("surface"))
    }

    @Test fun `an agent disabled with a structured reason does not break the list`() = runTest {
        reply(200, """{"agents":[{"id":"a","name":"A","enabled":false},{"id":"b","enabled":true}],"interfaces":{"a":{"chat_eligible":true,"surfaces":["chat"],"disabled_reason":{"field":"llm.model","problem":"model not found","fix":"pull it"}}}}""")
        val res = client.agents()
        assertEquals(listOf("a", "b"), res.agents.map { it.id })
        assertEquals("llm.model", res.interfaces["a"]!!.disabledReason!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test fun `approvals list and decisions`() = runTest {
        reply(200, """{"approvals":[{"call_id":"c/1","tool":"files.delete","args":{"path":"/tmp/x"},"agent_id":"genie","session_id":"s","created_at":"2026-10-26T10:00:00Z"}]}""")
        val list = client.approvals()
        assertEquals("c/1", list.single().callId)
        assertEquals("/tmp/x", list.single().args!!["path"]!!.jsonPrimitive.content)
        server.takeRequest()

        reply(200, """{"ok":true,"approved":true}""")
        client.decide("c/1", approve = true)
        assertEquals("/api/v1/approvals/c%2F1/approve", server.takeRequest().url.encodedPath)

        reply(404, """{"error":"call_id not found — it may have already been resolved or timed out"}""")
        try { client.decide("gone", approve = false); fail() } catch (e: GatewayException) { assertEquals(404, e.status) }
        assertEquals("/api/v1/approvals/gone/deny", server.takeRequest().url.encodedPath)
    }

    @Test fun `device registration body matches the gateway contract`() = runTest {
        reply(201, """{"registered":true,"device_id":"d1"}""")
        client.registerDevice(DeviceRegistration(id = "d1", name = "Pixel", pushToken = "fcm:abc", notificationsEnabled = true))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("fcm:abc", body["push_token"]!!.jsonPrimitive.content)
        assertEquals("", body["bundle_id"]!!.jsonPrimitive.content)
        assertEquals("true", body["notifications_enabled"]!!.jsonPrimitive.content)
    }

    @Test fun `streams a chat reply`() = runTest {
        reply(
            200,
            "event: run\ndata: {\"run_id\":\"r1\"}\n\ndata: Hi\n\ndata:  there\n\n" +
                "event: result\ndata: {\"reply\":\"Hi there\",\"session_id\":\"s1\",\"run_id\":\"r1\"}\n\ndata: [DONE]\n\n",
            "text/event-stream",
        )
        val events = client.streamChat(ChatRequest("genie", "s1", "hello")).toList()
        assertEquals(ChatEvent.Run("r1"), events.first())
        assertEquals("Hi there", events.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text })
        assertEquals(ChatEvent.Done, events.last())
        val req = server.takeRequest()
        assertEquals("/api/v1/chat/stream", req.url.encodedPath)
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("genie", body["agent_id"]!!.jsonPrimitive.content)
        assertEquals("s1", body["session_id"]!!.jsonPrimitive.content)
    }

    @Test fun `stream refusal is an exception with the gateway message`() = runTest {
        reply(403, """{"error":"agent not allowed"}""")
        try { client.streamChat(ChatRequest("x", "s", "hi")).toList(); fail() } catch (e: GatewayException) {
            assertEquals("agent not allowed", e.message)
        }
    }

    @Test fun `redirects are not followed with the credential`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/").build())
        try { client.approvals(); fail() } catch (e: GatewayException) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
}
