package dev.soulacy.android

import dev.soulacy.android.api.GatewayJson
import dev.soulacy.android.api.WebsiteConnection
import dev.soulacy.android.data.AppSettings
import dev.soulacy.android.data.Appearance
import dev.soulacy.android.data.MapKeyValueStore
import dev.soulacy.android.ui.workspace.connectionHealth
import dev.soulacy.android.websites.SessionCollector
import dev.soulacy.android.websites.StorageItem
import dev.soulacy.android.websites.WebsiteBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WebsiteSessionTest {
    private val boundary = WebsiteBoundary(listOf("*.Example.com.", "notebook.google.com", "localhost", "192.168.1.5", "intranet"))

    @Test fun `boundary keeps only public domains, normalised`() {
        assertEquals(listOf("example.com", "notebook.google.com"), boundary.domains)
    }

    @Test fun `https inside the boundary only`() {
        assertTrue(boundary.allows("https://example.com/login"))
        assertTrue(boundary.allows("https://accounts.example.com:8443/x"))
        assertTrue(boundary.allows("https://notebook.google.com/"))
        assertFalse(boundary.allows("http://example.com/"))
        assertFalse(boundary.allows("https://evil-example.com/"))
        assertFalse(boundary.allows("https://google.com/")) // parent of an allowed host is outside
        assertFalse(boundary.allows("https://user:pw@example.com/"))
        assertFalse(boundary.allows("javascript:alert(1)"))
    }

    @Test fun `collector records cookies per visited host and latest local storage per origin`() {
        val c = SessionCollector(boundary)
        c.addCookies("https://accounts.example.com/done", "sid=abc; theme=dark; =skip")
        c.addCookies("https://tracker.net/", "x=1") // outside: ignored
        c.setLocalStorage("https://example.com/app", listOf(StorageItem("b", "2"), StorageItem("a", "1")))
        c.setLocalStorage("https://example.com/other", listOf(StorageItem("token", "t")))
        c.setLocalStorage("https://outside.org/", listOf(StorageItem("x", "y")))
        val s = c.build()
        assertEquals(listOf("sid", "theme"), s.cookies.map { it.name })
        assertTrue(s.cookies.all { it.domain == "accounts.example.com" && it.secure && it.expires == -1.0 })
        assertEquals(1, s.origins.size)
        assertEquals("https://example.com", s.origins.single().origin)
        assertEquals(listOf("token"), s.origins.single().localStorage.map { it.name })
    }

    @Test fun `nothing to save is refused before calling the gateway`() {
        assertTrue(SessionCollector(boundary).encode(GatewayJson).isFailure)
        val c = SessionCollector(boundary)
        c.addCookies("https://example.com/", "sid=1")
        val json = c.encode(GatewayJson).getOrThrow()
        assertTrue(json.contains("\"sameSite\":\"Lax\"") && json.contains("\"httpOnly\":false"))
    }

    @Test fun `connection health matches iOS`() {
        val now = Instant.parse("2026-10-29T12:00:00Z")
        fun c(kind: String = "browser_session", status: String = "ready", secret: Boolean = true, expires: String? = null) =
            WebsiteConnection("conn", kind = kind, status = status, hasSecret = secret, expiresAt = expires)
        assertEquals("Ready", connectionHealth(c(), now).first)
        assertEquals("Set up on desktop", connectionHealth(c(kind = "oauth"), now).first)
        assertEquals("Revoked", connectionHealth(c(status = "revoked"), now).first)
        assertEquals("Expired", connectionHealth(c(expires = "2026-10-01T00:00:00Z"), now).first)
        assertEquals("Sign in", connectionHealth(c(status = "pending", secret = false), now).first)
    }

    @Test fun `appearance survives unpairing`() {
        val s = AppSettings(MapKeyValueStore())
        assertEquals(Appearance.SYSTEM, s.appearance)
        s.appearance = Appearance.DARK
        s.clear()
        assertEquals(Appearance.DARK, s.appearance)
        assertNull(s.reachability)
    }
}
