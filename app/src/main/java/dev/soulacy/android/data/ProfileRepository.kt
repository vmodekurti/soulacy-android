package dev.soulacy.android.data

import android.content.Context
import dev.soulacy.android.api.GatewayClient
import dev.soulacy.android.security.SecretStore
import java.util.UUID

/** The gateway this phone is paired with. The credential lives in [SecretStore]. */
data class Profile(
    val baseUrl: String,
    val fingerprint: String,
    val subject: String,
    val displayName: String,
    val role: String,
    val keyId: String,
)

/** Plain, non-secret key/value storage (SharedPreferences on the device). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putStrings(values: Map<String, String?>)
}

class PrefsKeyValueStore(context: Context, name: String = "soulacy_profile") : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putStrings(values: Map<String, String?>) {
        val e = prefs.edit()
        values.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        e.apply()
    }
}

class MapKeyValueStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putStrings(values: Map<String, String?>) =
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
}

class ProfileRepository(private val kv: KeyValueStore, private val secrets: SecretStore) {

    fun load(): Profile? {
        val base = kv.getString(K_BASE) ?: return null
        return Profile(
            baseUrl = base,
            fingerprint = kv.getString(K_FP).orEmpty(),
            subject = kv.getString(K_SUBJECT).orEmpty(),
            displayName = kv.getString(K_DISPLAY).orEmpty(),
            role = kv.getString(K_ROLE).orEmpty(),
            keyId = kv.getString(K_KEY_ID).orEmpty(),
        )
    }

    fun token(): String = secrets.get(SECRET_TOKEN).orEmpty()

    fun save(profile: Profile, token: String) {
        secrets.put(SECRET_TOKEN, token)
        kv.putStrings(
            mapOf(
                K_BASE to profile.baseUrl, K_FP to profile.fingerprint, K_SUBJECT to profile.subject,
                K_DISPLAY to profile.displayName, K_ROLE to profile.role, K_KEY_ID to profile.keyId,
            )
        )
    }

    fun clear() {
        secrets.remove(SECRET_TOKEN)
        kv.putStrings(listOf(K_BASE, K_FP, K_SUBJECT, K_DISPLAY, K_ROLE, K_KEY_ID, K_PUSH).associateWith { null })
    }

    /** Stable per-install id used for the gateway's device registration. */
    fun deviceId(): String = kv.getString(K_DEVICE) ?: UUID.randomUUID().toString().also { kv.putStrings(mapOf(K_DEVICE to it)) }

    var pushToken: String
        get() = kv.getString(K_PUSH).orEmpty()
        set(value) = kv.putStrings(mapOf(K_PUSH to value.ifEmpty { null }))

    /** A client for the paired gateway, or null when not paired. */
    fun client(): GatewayClient? = load()?.let { GatewayClient(it.baseUrl, token(), it.fingerprint) }

    private companion object {
        const val SECRET_TOKEN = "gateway_token"
        const val K_BASE = "base_url"
        const val K_FP = "fingerprint"
        const val K_SUBJECT = "subject"
        const val K_DISPLAY = "display_name"
        const val K_ROLE = "role"
        const val K_KEY_ID = "key_id"
        const val K_DEVICE = "device_id"
        const val K_PUSH = "push_token"
    }
}
