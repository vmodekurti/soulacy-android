package dev.soulacy.android.pairing

import dev.soulacy.android.api.DeviceRegistration
import dev.soulacy.android.api.GatewayClient
import dev.soulacy.android.data.Profile
import dev.soulacy.android.data.ProfileRepository
import okhttp3.OkHttpClient

sealed interface PairingOutcome {
    /** Paired; [registrationError] is set when the push registration failed (pairing still stands). */
    data class Paired(val profile: Profile, val registrationError: String? = null) : PairingOutcome
    data class Failed(val message: String) : PairingOutcome
}

/**
 * Turns a scanned [PairingLink] into a stored credential, the same handshake
 * as the iOS app: redeem the single-use code, keep the scoped key, then
 * register this installation for deliveries and push.
 */
class Pairer(
    private val profiles: ProfileRepository,
    private val http: OkHttpClient = GatewayClient.defaultHttp(),
) {
    suspend fun pair(link: PairingLink, deviceName: String, notificationsEnabled: Boolean): PairingOutcome {
        val redeemed = try {
            GatewayClient(link.baseUrl, fingerprint = link.fingerprint, http = http).redeem(link.code)
        } catch (e: Exception) {
            return PairingOutcome.Failed(ConnectionProblem.describe(e, link.baseUrl))
        }
        // The QR's pin wins; the redeem reply's pin is a fallback for QR codes
        // without one. They must never disagree.
        if (link.fingerprint.isNotEmpty() && redeemed.fingerprint.isNotEmpty() &&
            !link.fingerprint.equals(redeemed.fingerprint, ignoreCase = true)
        ) {
            return PairingOutcome.Failed("The gateway's key does not match the pairing code. Generate a new code and try again.")
        }
        val fingerprint = link.fingerprint.ifEmpty { redeemed.fingerprint }.lowercase()
        val profile = Profile(
            baseUrl = link.baseUrl,
            fingerprint = if (link.baseUrl.startsWith("https://")) fingerprint else "",
            subject = redeemed.subject,
            displayName = redeemed.displayName,
            role = redeemed.role,
            keyId = redeemed.keyId,
        )
        profiles.save(profile, redeemed.token)
        val regError = register(deviceName, notificationsEnabled)
        return PairingOutcome.Paired(profile, regError)
    }

    /** (Re)registers this installation. Returns an error message, or null on success. */
    suspend fun register(deviceName: String, notificationsEnabled: Boolean): String? {
        val profile = profiles.load() ?: return "Not paired"
        val client = GatewayClient(profile.baseUrl, profiles.token(), profile.fingerprint, http)
        return try {
            client.registerDevice(
                DeviceRegistration(
                    id = profiles.deviceId(),
                    name = deviceName,
                    pushToken = profiles.pushToken,
                    notificationsEnabled = notificationsEnabled,
                )
            )
            null
        } catch (e: Exception) {
            e.message ?: "Device registration failed"
        }
    }
}
