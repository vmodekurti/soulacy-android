package dev.soulacy.android.security

import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.X509TrustManager

/**
 * The gateway's TLS pin, computed exactly like `publicKeyFingerprint` in
 * internal/gateway/autotls.go (and the iOS PinningDelegate): lowercase hex
 * SHA-256 of the leaf's raw public key. EC keys hash the uncompressed X9.63
 * point (0x04 || X || Y), RSA keys hash the PKCS#1 RSAPublicKey DER. This is
 * deliberately not an SPKI pin, so OkHttp's CertificatePinner cannot be used.
 */
object KeyPinning {
    fun fingerprint(key: PublicKey): String {
        val raw = when (key) {
            is ECPublicKey -> {
                val size = (key.params.curve.field.fieldSize + 7) / 8
                byteArrayOf(0x04) + fixed(key.w.affineX, size) + fixed(key.w.affineY, size)
            }
            is RSAPublicKey -> derSequence(derInteger(key.modulus) + derInteger(key.publicExponent))
            else -> throw IllegalArgumentException("unsupported key type ${key.algorithm}")
        }
        return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }

    private fun fixed(n: BigInteger, size: Int): ByteArray {
        val b = n.toByteArray().let { if (it.size > size && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(b.size <= size) { "coordinate larger than field" }
        return ByteArray(size - b.size) + b
    }

    private fun derInteger(n: BigInteger): ByteArray = byteArrayOf(0x02) + derLength(n.toByteArray().size) + n.toByteArray()
    private fun derSequence(body: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(body.size) + body

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
    }
}

/**
 * Trusts exactly one server key. The gateway's own certificate is
 * self-signed, so chain building would fail; the pin replaces it.
 */
class PinnedKeyTrustManager(private val expected: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val actual = KeyPinning.fingerprint(leaf.publicKey)
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.lowercase().toByteArray())) {
            throw CertificateException("gateway key does not match the paired fingerprint")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
