package dev.soulacy.android

import dev.soulacy.android.security.SealedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.KeyGenerator

class SealedBoxTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SealedBox { key }

    @Test fun `round trip`() = assertEquals("sk-secret", box.open(box.seal("sk-secret", "gateway_token"), "gateway_token"))

    @Test fun `fresh iv every time, plaintext never visible`() {
        val a = box.seal("sk-secret", "n")
        assertNotEquals(a, box.seal("sk-secret", "n"))
        assertFalse(String(java.util.Base64.getDecoder().decode(a)).contains("sk-secret"))
    }

    @Test(expected = Exception::class)
    fun `value moved to another name does not open`() {
        box.open(box.seal("sk-secret", "gateway_token"), "other")
    }

    @Test(expected = Exception::class)
    fun `tampering is detected`() {
        val raw = java.util.Base64.getDecoder().decode(box.seal("sk-secret", "n"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        box.open(java.util.Base64.getEncoder().encodeToString(raw), "n")
    }
}
