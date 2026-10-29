package dev.soulacy.android

import dev.soulacy.android.api.GatewayException
import dev.soulacy.android.data.AppSettings
import dev.soulacy.android.data.Conversation
import dev.soulacy.android.data.ConversationStore
import dev.soulacy.android.data.MapKeyValueStore
import dev.soulacy.android.data.ResponseCache
import dev.soulacy.android.data.StoredMessage
import dev.soulacy.android.pairing.ConnectionProblem
import dev.soulacy.android.pairing.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class LocalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun conv(id: String, at: Long, vararg texts: String) =
        Conversation(id, "genie", "Genie", at, texts.mapIndexed { i, t -> StoredMessage(i % 2 == 0, t) })

    @Test fun `conversations round trip newest first`() {
        val store = ConversationStore(tmp.newFolder("c"))
        store.save(conv("s/1", 1, "hi", "hello"))
        store.save(conv("s2", 2, "plan my day", "Here is your day:\n- standup"))
        assertEquals(listOf("s2", "s/1"), store.list().map { it.sessionId })
        assertEquals("Here is your day:", store.load("s2")!!.preview)
        store.delete("s/1")
        assertNull(store.load("s/1"))
    }

    @Test fun `an empty conversation is not kept, long ones are trimmed`() {
        val store = ConversationStore(tmp.newFolder("c"))
        store.save(Conversation("empty", "a", "A", 1, emptyList()))
        assertTrue(store.list().isEmpty())
        store.save(conv("long", 1, *Array(ConversationStore.MAX_MESSAGES + 10) { "m$it" }))
        assertEquals(ConversationStore.MAX_MESSAGES, store.load("long")!!.messages.size)
    }

    @Test fun `history is capped`() {
        val store = ConversationStore(tmp.newFolder("c"))
        repeat(ConversationStore.MAX_CONVERSATIONS + 3) { store.save(conv("s$it", it.toLong(), "q")) }
        assertEquals(ConversationStore.MAX_CONVERSATIONS, store.list().size)
        assertNull(store.load("s0"))
    }

    @Test fun `cache remembers body and time`() {
        val cache = ResponseCache(tmp.newFolder("cache"))
        assertNull(cache.get("agents"))
        cache.put("agents", "{\"agents\":[]}")
        assertEquals("{\"agents\":[]}", cache.get("agents")!!.first)
        cache.clear()
        assertNull(cache.get("agents"))
    }

    @Test fun `settings keep reachability and treat older installs as onboarded`() {
        val kv = MapKeyValueStore()
        val s = AppSettings(kv)
        assertTrue(s.onboardingDone(paired = true))
        assertFalse(s.onboardingDone(paired = false))
        s.setOnboardingDone(false)
        assertFalse(s.onboardingDone(paired = true))
        s.reachability = Reachability.VPN
        assertEquals(Reachability.VPN, AppSettings(kv).reachability)
        s.clear()
        assertNull(s.reachability)
    }

    @Test fun `engine errors lead with plain words and keep the detail`() {
        val msg = ConnectionProblem.agentError("engine: llm call: ollama: stream unexpected status 400: {\"error\":\"model is required\"}")
        assertTrue(msg.startsWith("The agent couldn't get an answer from its model."))
        assertTrue(msg.endsWith("> llm call: ollama: stream unexpected status 400: {\"error\":\"model is required\"}"))
        assertEquals("something odd", ConnectionProblem.agentError("something odd"))
    }

    @Test fun `connection problems are named`() {
        val addr = "http://192.168.1.20:18789"
        assertTrue(ConnectionProblem.describe(UnknownHostException("x"), addr).startsWith("Can't find 192.168.1.20"))
        assertTrue(ConnectionProblem.describe(SocketTimeoutException(), addr).contains("didn't answer in time"))
        assertTrue(ConnectionProblem.describe(ConnectException(), addr).startsWith("Nothing answered at $addr"))
        assertTrue(ConnectionProblem.describe(GatewayException(401, "invalid or expired pairing code"), addr).startsWith("That pairing code is invalid"))
        assertTrue(ConnectionProblem.describe(GatewayException(401, "The gateway did not accept this phone's credential."), addr).contains("Pair it again"))
        assertEquals("Your phone is already paired.", ConnectionProblem.describe(GatewayException(409, "Your phone is already paired."), addr))
        val pin = SSLHandshakeException("handshake").apply { initCause(CertificateException("gateway key does not match the paired fingerprint")) }
        assertTrue(ConnectionProblem.describe(pin, addr).contains("doesn't match"))
        assertTrue(ConnectionProblem.describe(ConnectException(), addr, Reachability.HOME).endsWith("is this phone on it?"))
        assertFalse(ConnectionProblem.describe(GatewayException(500, "x"), addr, Reachability.HOME).contains("is this phone on it"))
    }
}
