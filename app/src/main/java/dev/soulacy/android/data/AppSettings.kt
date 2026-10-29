package dev.soulacy.android.data

import dev.soulacy.android.pairing.Reachability

/** System follows the phone; the others pin a theme (iOS Settings › Appearance). */
enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Small per-install preferences that are not secret. */
class AppSettings(private val kv: KeyValueStore) {

    /**
     * Whether onboarding finished. A phone paired before onboarding existed
     * has no flag; it counts as done so an update never sends anyone back.
     */
    fun onboardingDone(paired: Boolean): Boolean = when (kv.getString(K_ONBOARDED)) {
        "1" -> true
        "0" -> false
        else -> paired
    }

    fun setOnboardingDone(done: Boolean) = kv.putStrings(mapOf(K_ONBOARDED to if (done) "1" else "0"))

    var reachability: Reachability?
        get() = kv.getString(K_REACH)?.let { runCatching { Reachability.valueOf(it) }.getOrNull() }
        set(value) = kv.putStrings(mapOf(K_REACH to value?.name))

    var appearance: Appearance
        get() = kv.getString(K_APPEARANCE)?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: Appearance.SYSTEM
        set(value) = kv.putStrings(mapOf(K_APPEARANCE to value.name))

    /** Unpairing forgets the gateway, not the person's look. */
    fun clear() = kv.putStrings(mapOf(K_ONBOARDED to "0", K_REACH to null))

    private companion object {
        const val K_ONBOARDED = "onboarding_done"
        const val K_REACH = "reachability"
        const val K_APPEARANCE = "appearance"
    }
}
