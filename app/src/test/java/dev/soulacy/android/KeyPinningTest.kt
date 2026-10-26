package dev.soulacy.android

import dev.soulacy.android.security.KeyPinning
import dev.soulacy.android.security.PinnedKeyTrustManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Vectors produced by the gateway's own publicKeyFingerprint
 * (internal/gateway/autotls.go), so Android and Go must agree byte for byte.
 */
class KeyPinningTest {
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/pin/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun expected(name: String) = javaClass.getResource("/pin/$name.fp")!!.readText().trim()

    @Test fun `EC key matches the gateway`() = assertEquals(expected("ec"), KeyPinning.fingerprint(cert("ec").publicKey))

    @Test fun `RSA key matches the gateway`() = assertEquals(expected("rsa"), KeyPinning.fingerprint(cert("rsa").publicKey))

    @Test fun `trust manager accepts only the pinned key`() {
        PinnedKeyTrustManager(expected("ec").uppercase()).checkServerTrusted(arrayOf(cert("ec")), "ECDHE_ECDSA")
        try {
            PinnedKeyTrustManager(expected("ec")).checkServerTrusted(arrayOf(cert("rsa")), "ECDHE_RSA")
            throw AssertionError("a different key was trusted")
        } catch (_: CertificateException) {
        }
    }
}
