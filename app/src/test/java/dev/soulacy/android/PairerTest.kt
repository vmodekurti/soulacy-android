package dev.soulacy.android

import dev.soulacy.android.data.MapKeyValueStore
import dev.soulacy.android.data.ProfileRepository
import dev.soulacy.android.pairing.Pairer
import dev.soulacy.android.pairing.PairingLink
import dev.soulacy.android.pairing.PairingOutcome
import dev.soulacy.android.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairerTest {
    private val server = MockWebServer().apply { start() }
    private val kv = MapKeyValueStore()
    private val secrets = InMemorySecretStore()
    private val profiles = ProfileRepository(kv, secrets)
    private val base get() = server.url("/").toString().trimEnd('/')

    @After fun stop() = server.close()

    private fun json(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    @Test fun `pairs, keeps the credential out of plain prefs, registers the device`() = runTest {
        json(200, """{"paired":true,"token":"sk-phone","key_id":"k9","subject":"priya","role":"viewer","display_name":"Priya"}""")
        json(201, """{"registered":true}""")
        val out = Pairer(profiles).pair(PairingLink(base, "ABCDEFGHIJKLMNOP"), "Pixel 9", notificationsEnabled = true)

        out as PairingOutcome.Paired
        assertNull(out.registrationError)
        assertEquals("priya", out.profile.subject)
        assertEquals("Priya", out.profile.displayName)
        assertEquals("sk-phone", profiles.token())
        assertFalse("token leaked into plain storage", kv.map.values.any { it.contains("sk-phone") })
        assertEquals("sk-phone", secrets.get("gateway_token"))

        server.takeRequest() // redeem
        val reg = server.takeRequest()
        assertEquals("/api/v1/mobile/devices", reg.url.encodedPath)
        assertEquals("Bearer sk-phone", reg.headers["Authorization"])
        assertTrue(reg.body!!.utf8().contains(profiles.deviceId()))
    }

    @Test fun `registration failure keeps the pairing and says why`() = runTest {
        json(200, """{"paired":true,"token":"sk","subject":"admin","role":"operator"}""")
        json(400, """{"error":"bundle_id must identify the Soulacy iOS app"}""")
        val out = Pairer(profiles).pair(PairingLink(base, "ABCDEFGHIJKLMNOP"), "Pixel", true) as PairingOutcome.Paired
        assertEquals("bundle_id must identify the Soulacy iOS app", out.registrationError)
        assertEquals(base, profiles.load()?.baseUrl)
    }

    @Test fun `expired code stores nothing`() = runTest {
        json(401, """{"paired":false,"reason":"invalid or expired pairing code"}""")
        val out = Pairer(profiles).pair(PairingLink(base, "ABCDEFGHIJKLMNOP"), "Pixel", true)
        assertEquals(PairingOutcome.Failed("invalid or expired pairing code"), out)
        assertNull(profiles.load())
        assertEquals("", profiles.token())
    }

    @Test fun `unreachable gateway fails cleanly`() = runTest {
        server.close()
        val out = Pairer(profiles).pair(PairingLink("http://127.0.0.1:1", "ABCDEFGHIJKLMNOP"), "Pixel", true)
        assertTrue(out is PairingOutcome.Failed)
        assertNull(profiles.load())
    }

    @Test fun `clear forgets credential and profile but keeps the install id`() {
        profiles.save(dev.soulacy.android.data.Profile(base, "", "admin", "", "operator", "k"), "sk")
        val id = profiles.deviceId()
        profiles.clear()
        assertNull(profiles.load())
        assertEquals("", profiles.token())
        assertEquals(id, profiles.deviceId())
    }
}
