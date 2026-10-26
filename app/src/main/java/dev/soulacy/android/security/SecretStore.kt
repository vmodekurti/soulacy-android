package dev.soulacy.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small string secrets (the paired gateway credential). */
interface SecretStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)
}

/**
 * Seals values with AES-256-GCM. The ciphertext layout is
 * `version(1) || iv(12) || ciphertext+tag`, base64 encoded, so the codec is
 * testable on the JVM with any [SecretKey].
 */
class SealedBox(private val key: () -> SecretKey) {
    fun seal(plain: String, aad: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad.toByteArray())
        val ct = c.doFinal(plain.toByteArray())
        return Base64Codec.encode(byteArrayOf(VERSION) + c.iv + ct)
    }

    fun open(sealed: String, aad: String): String {
        val raw = Base64Codec.decode(sealed)
        require(raw.size > 1 + IV && raw[0] == VERSION) { "unknown secret format" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 1, IV))
        c.updateAAD(aad.toByteArray())
        return String(c.doFinal(raw, 1 + IV, raw.size - 1 - IV))
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV = 12
    }
}

/** java.util.Base64 is API 26+, which is our floor; android.util.Base64 is not on the JVM. */
internal object Base64Codec {
    fun encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}

/**
 * Values sealed with a non-exportable Android Keystore key and kept in a
 * private, backup-excluded preferences file. Uninstalling the app or clearing
 * its data destroys the key, so a copied preferences file is useless.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("soulacy_secrets", Context.MODE_PRIVATE)
    private val box = SealedBox(::keystoreKey)

    override fun put(name: String, value: String) {
        prefs.edit().putString(name, box.seal(value, name)).apply()
    }

    override fun get(name: String): String? = prefs.getString(name, null)?.let { sealed ->
        runCatching { box.open(sealed, name) }.getOrNull()
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "soulacy.gateway.credential"
    }
}

/** For tests and previews. */
class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun put(name: String, value: String) { map[name] = value }
    override fun get(name: String) = map[name]
    override fun remove(name: String) { map.remove(name) }
}
