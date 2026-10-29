package dev.soulacy.android

import android.app.Application
import android.os.Build
import dev.soulacy.android.data.AppSettings
import dev.soulacy.android.data.ConversationStore
import dev.soulacy.android.data.PrefsKeyValueStore
import dev.soulacy.android.data.ResponseCache
import dev.soulacy.android.data.ProfileRepository
import dev.soulacy.android.notify.AttentionSync
import dev.soulacy.android.notify.AttentionTracker
import dev.soulacy.android.notify.Notifier
import dev.soulacy.android.notify.SyncWorker
import dev.soulacy.android.pairing.Pairer
import dev.soulacy.android.push.PushSetup
import dev.soulacy.android.security.KeystoreSecretStore

class SoulacyApp : Application() {
    lateinit var profiles: ProfileRepository
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var sync: AttentionSync
        private set
    lateinit var pairer: Pairer
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var cache: ResponseCache
        private set
    lateinit var conversations: ConversationStore
        private set

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileRepository(PrefsKeyValueStore(this), KeystoreSecretStore(this))
        notifier = Notifier(this).also { it.ensureChannels() }
        sync = AttentionSync(profiles, AttentionTracker(PrefsKeyValueStore(this, "soulacy_attention")), notifier)
        pairer = Pairer(profiles)
        settings = AppSettings(PrefsKeyValueStore(this, "soulacy_settings"))
        // noBackupFilesDir: never copied by Auto Backup or device transfer.
        cache = ResponseCache(java.io.File(noBackupFilesDir, "cache"))
        conversations = ConversationStore(java.io.File(noBackupFilesDir, "conversations"))
        if (profiles.load() != null) SyncWorker.schedule(this)
        PushSetup.start(this)
    }

    val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }
}
