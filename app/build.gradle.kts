plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional Firebase Cloud Messaging wiring. Without these values the app
// never initialises Firebase and falls back to polling the gateway, so the
// default build talks to nothing but the paired gateway.
fun fcmValue(name: String): String =
    (findProperty("soulacy.fcm.$name") as String?) ?: System.getenv("SOULACY_FCM_${name.uppercase()}") ?: ""

android {
    namespace = "dev.soulacy.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.soulacy.android"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("soulacy.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("soulacy.versionName") as String?) ?: "0.1.0"

        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcmValue("projectId")}\"")
        buildConfigField("String", "FCM_APPLICATION_ID", "\"${fcmValue("applicationId")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${fcmValue("apiKey")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcmValue("senderId")}\"")
    }

    // Release signing comes only from the environment (CI secrets). The
    // keystore itself is never committed.
    val keystorePath = System.getenv("SOULACY_KEYSTORE_FILE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SOULACY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SOULACY_KEY_ALIAS")
                keyPassword = System.getenv("SOULACY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    // biometric 1.1 still pulls Fragment 1.2, which mishandles ActivityResult requests.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
