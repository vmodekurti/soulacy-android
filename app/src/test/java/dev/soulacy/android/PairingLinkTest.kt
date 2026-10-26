package dev.soulacy.android

import dev.soulacy.android.pairing.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    private val code = "ABCDEFGHIJKLMNOP"
    private val fp = "b2b0bc1fae233604fc90cdd053b7248761a581ec00000000000000000000beef"

    @Test fun `plain http pair url`() {
        assertEquals(PairingLink("http://192.168.1.20:18789", code), PairingLink.parse("http://192.168.1.20:18789/mobile?pair=$code"))
    }

    @Test fun `https with pinned fingerprint, lowercase code accepted`() {
        val link = PairingLink.parse(" https://box.tail1234.ts.net:18789/mobile?pair=${code.lowercase()}&fp=${fp.uppercase()} ")
        assertEquals(PairingLink("https://box.tail1234.ts.net:18789", code, fp), link)
    }

    @Test fun `gateway served under a path prefix`() {
        assertEquals("https://example.com/soulacy", PairingLink.parse("https://example.com/soulacy/mobile/?pair=$code")?.baseUrl)
    }

    @Test fun `ipv6 host keeps brackets`() {
        assertEquals("http://[fd7a::1]:18789", PairingLink.parse("http://[fd7a::1]:18789/mobile?pair=$code")?.baseUrl)
    }

    @Test fun `rejects anything that is not a pairing code`() {
        listOf(
            "",
            "hello",
            "soulacy://approval/x",
            "ftp://host/mobile?pair=$code",
            "https://host/other?pair=$code",
            "https://host/mobile",
            "https://host/mobile?pair=abc", // too short
            "https://host/mobile?pair=ABCDEFGH01", // 0 and 1 are not base32
            "https://host/mobile?pair=$code&fp=nothex",
            "http://host/mobile?pair=$code&fp=$fp", // a pin over http is meaningless
            "https://user:pass@host/mobile?pair=$code",
        ).forEach { assertNull(it, PairingLink.parse(it)) }
    }

    @Test fun `manual entry`() {
        assertEquals(PairingLink("http://10.0.2.2:18789", code), PairingLink.manual("http://10.0.2.2:18789/", code.lowercase()))
    }
}
