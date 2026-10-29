package dev.soulacy.android

import dev.soulacy.android.api.GatewayClient
import dev.soulacy.android.api.GatewayException
import dev.soulacy.android.api.Workspace
import dev.soulacy.android.api.featureUnavailable
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Settings › workspace tools against the gateway's documented contract. */
class WorkspaceTest {
    private val server = MockWebServer().apply { start() }
    private val ws = Workspace(GatewayClient(server.url("/").toString(), token = "tok"))

    @After fun stop() = server.close()

    private fun reply(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    private fun next() = server.takeRequest()

    @Test fun `memory list, search and purge`() = runTest {
        reply(200, """{"facts":[{"id":"f1","agent_id":"genie","category":"preference","content":"Likes tea","confidence":0.9,"status":"active","source":"manual","created_at":"2026-10-29T10:00:00.123456Z","updated_at":"2026-10-29T10:00:00.123456Z"}],"provider":"local","owner":"admin"}""")
        val facts = ws.facts("active", "")
        assertEquals("Likes tea", facts.single().content)
        assertEquals("/api/v1/memory/facts?limit=200&status=active", next().target)

        reply(200, """{"facts":[{"id":"f1","content":"Likes tea","score":0.8}],"query":"tea"}""")
        assertEquals(0.8, ws.facts("active", "green tea").single().score)
        assertEquals("/api/v1/memory/facts?q=green%20tea&limit=50", next().target)

        server.enqueue(MockResponse.Builder().code(204).build())
        ws.deleteFact(facts.single())
        next().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/memory/facts/f1?agent_id=genie", it.target) }

        reply(200, """{"ok":true,"removed":3}""")
        ws.purgeFacts()
        assertEquals("/api/v1/memory/facts?confirm=true", next().target)
    }

    @Test fun `null lists decode as empty`() = runTest {
        reply(200, """{"facts":null,"provider":"local"}""")
        assertTrue(ws.facts(null, "").isEmpty())
        next()
        reply(200, """{"owner":"admin","entries":null,"summary":"","updated_at":"0001-01-01T00:00:00Z"}""")
        assertNull(ws.person().entries)
        next()
        reply(200, """{"enabled":false,"suggestions":null}""")
        assertTrue(ws.suggestions().isEmpty())
    }

    @Test fun `person entry write reports a higher-ranked source instead of failing`() = runTest {
        reply(409, """{"entry":{"section":"identity","key":"home","summary":"Oak Street","source":"manual","confidence":1},"applied":false,"reason":"a manual entry already holds this key"}""")
        val r = ws.putEntry("identity", "home", "Elm Street")
        assertFalse(r.applied)
        assertEquals("a manual entry already holds this key", r.reason)
        val body = Json.parseToJsonElement(next().body!!.utf8()).jsonObject
        assertEquals(setOf("section", "key", "summary"), body.keys)

        reply(400, """{"error":"summary is required"}""")
        try { ws.putEntry("identity", "x", ""); fail() } catch (e: GatewayException) { assertEquals(400, e.status) }
    }

    @Test fun `person entry provenance labels`() = runTest {
        reply(200, """{"entries":[
            {"section":"identity","key":"home","summary":"Oak St","source":"manual","confidence":1},
            {"section":"routine","key":"wake","summary":"7:00","source":"sense:routine","confidence":0.5},
            {"section":"preferences","key":"tea","summary":"green","source":"agent:planner","confidence":0.9}]}""")
        val e = ws.person().entries!!
        assertEquals(listOf("You told us", "Noticed by routine", "Guessed by planner"), e.map { it.origin })
        assertEquals(listOf(false, true, false), e.map { it.isGuess })
    }

    @Test fun `sense toggle and delete paths are encoded`() = runTest {
        reply(200, """{"sense":"routine","enabled":false,"forgotten":4}""")
        assertEquals(4, ws.setSense("routine", false).forgotten)
        next().let { assertEquals("PUT", it.method); assertFalse(Json.parseToJsonElement(it.body!!.utf8()).jsonObject["enabled"]!!.jsonPrimitive.boolean) }
        reply(200, """{"deleted":true}""")
        ws.deleteEntry(dev.soulacy.android.api.PersonEntry("preferences", "morning coffee", "x"))
        assertEquals("/api/v1/person/model/entries/preferences/morning%20coffee", next().target)
    }

    @Test fun `run ledger is camelCase and single runs come from the run_id filter`() = runTest {
        reply(200, """{"runs":[{"runId":"r1","id":"r1","agentId":"genie","agentName":"Genie","startedAt":"2026-10-29T10:00:00Z","updatedAt":"2026-10-29T10:01:00Z","ok":false,"status":"failed","durationMs":61000,"failure":{"summary":"Model refused","nextAction":"Pick a bigger model"}}],"matched":1,"has_more":false}""")
        val run = ws.run("r1")!!
        assertEquals("Genie", run.agentName)
        assertEquals("Pick a bigger model", run.failure!!.nextAction)
        assertEquals("/api/v1/runs/ledger?run_id=r1&limit=1", next().target)
    }

    @Test fun `lesson review always confirms`() = runTest {
        reply(200, """{"id":"l1","key":"k","kind":"skill","title":"T","status":"archived"}""")
        assertEquals("archived", ws.reviewLesson("genie", "l1", "archive").status)
        next().let {
            assertEquals("/api/v1/agents/genie/learning/lessons/l1/review", it.target)
            val b = Json.parseToJsonElement(it.body!!.utf8()).jsonObject
            assertEquals("archive", b["action"]!!.jsonPrimitive.content)
            assertTrue(b["confirmed"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun `safe undo receipt detail`() = runTest {
        reply(200, """{"id":"j1","agent_id":"a","title":"Update owner","status":"applied","created_at":"2026-10-29T10:00:00Z","updated_at":"2026-10-29T10:00:00Z","revision":2,"undo_started":false,
            "actions":[{"resource_id":"customer","name":"Customer record","kind":"json_record","status":"applied","reconciled":false,
            "fields":[{"name":"owner","before":{"exists":true,"display":"\"alice\""},"after":{"exists":true,"display":"\"bob\""}}]}]}""")
        val j = ws.undoJob("a", "j1")
        assertEquals("\"bob\"", j.actions.single().fields.single().after.display)
    }

    @Test fun `website session is wrapped in storage_state`() = runTest {
        reply(200, """{"connection":{"id":"conn_1","status":"ready","has_secret":true},"message":"saved"}""")
        ws.saveSession("conn_1", """{"cookies":[],"origins":[]}""")
        next().let {
            assertEquals("PUT", it.method)
            assertEquals("/api/v1/authenticated-connections/conn_1/session", it.target)
            assertEquals("""{"storage_state":{"cookies":[],"origins":[]}}""", it.body!!.utf8())
        }
    }

    @Test fun `unavailable features are explained`() = runTest {
        assertEquals("This gateway hasn't turned this feature on.", featureUnavailable(GatewayException(503, "x")))
        assertTrue(featureUnavailable(GatewayException(403, "insufficient permissions"))!!.contains("credential"))
        // An unknown route falls through to the web app's HTML page with 200.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<!doctype html><html></html>").build())
        try { ws.mobileStatus(); fail() } catch (e: Exception) { assertTrue(featureUnavailable(e)!!.contains("can't read")) }
    }
}
