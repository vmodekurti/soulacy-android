package dev.soulacy.android.push

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.soulacy.android.BuildConfig
import dev.soulacy.android.SoulacyApp
import dev.soulacy.android.notify.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Optional Firebase Cloud Messaging. Firebase is initialised by hand, and
 * only when the build was given a Firebase project; the default build never
 * touches Google services and relies on [SyncWorker] polling instead.
 */
object PushSetup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val enabled: Boolean get() = BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_APPLICATION_ID.isNotBlank()

    fun start(app: SoulacyApp) {
        if (!enabled) return
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> onToken(app, token) }
        }
    }

    fun onToken(app: SoulacyApp, token: String) {
        if (token == app.profiles.pushToken) return
        app.profiles.pushToken = token
        if (app.profiles.load() == null) return
        scope.launch { app.pairer.register(app.deviceName, app.notifier.canNotify()) }
    }
}

/** Receives the gateway's data-only wake-ups and fetches the real content. */
class SoulacyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        SyncWorker.runNow(applicationContext)
    }

    override fun onNewToken(token: String) {
        PushSetup.onToken(application as SoulacyApp, token)
    }
}
