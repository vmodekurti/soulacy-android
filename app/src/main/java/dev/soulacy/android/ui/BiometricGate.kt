package dev.soulacy.android.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Every approval decision passes the device lock first (fingerprint, face or
 * the screen-lock PIN), like Face ID on iOS. A phone without a screen lock
 * cannot approve: anyone holding it could.
 */
object BiometricGate {
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    sealed interface Outcome {
        data object Passed : Outcome
        data class Refused(val message: String) : Outcome
    }

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, done: (Outcome) -> Unit) {
        when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                return done(Outcome.Refused("Set a screen lock on this phone to approve from it."))
            else -> return done(Outcome.Refused("This phone cannot confirm it is you, so approvals stay on the web."))
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(Outcome.Passed)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    done(Outcome.Refused(errString.toString()))
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }
}
