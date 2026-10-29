package dev.soulacy.android.data

import dev.soulacy.android.pairing.Reachability

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

    fun clear() = kv.putStrings(mapOf(K_ONBOARDED to "0", K_REACH to null))

    private companion object {
        const val K_ONBOARDED = "onboarding_done"
        const val K_REACH = "reachability"
    }
}
