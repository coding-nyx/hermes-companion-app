import java.util.Properties
import groovy.json.JsonSlurper

// Opt-in, separately sandboxed preview. The default application ID stays compatible
// with existing stable builds; preview configuration and permissions never migrate.
val hermesPreview = providers.gradleProperty("hermesPreview").map(String::toBooleanStrict).getOrElse(false)
val hermesApplicationId = "app.hermes.companion" + if (hermesPreview) ".preview" else ""
val firebaseClientFile = file("firebase-client.json")
val firebaseConfig = if (firebaseClientFile.exists()) JsonSlurper().parse(firebaseClientFile) as Map<*, *> else null
val firebaseProject = firebaseConfig?.get("project_info") as? Map<*, *>
val firebaseClients = firebaseConfig?.get("client") as? List<*>
val firebaseClient = firebaseClients?.filterIsInstance<Map<*, *>>()?.firstOrNull { client ->
    val info = client["client_info"] as? Map<*, *>
    val android = info?.get("android_client_info") as? Map<*, *>
    android?.get("package_name") == hermesApplicationId
}
val firebaseInfo = firebaseClient?.get("client_info") as? Map<*, *>
val firebaseApiKey = (firebaseClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>
val firebaseReady = firebaseInfo != null && firebaseProject != null && firebaseApiKey != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing (A7.7). Resolved from, in order:
 *  1. environment: HERMES_KEYSTORE_PATH / HERMES_KEYSTORE_PASSWORD / HERMES_KEY_ALIAS / HERMES_KEY_PASSWORD
 *  2. keystore.properties at the repo root (gitignored) with storeFile/storePassword/keyAlias/keyPassword
 * If neither is present the release build falls back to the debug key and prints a warning, so
 * local `assembleRelease` keeps working for sideload testing. CI sets the env vars from secrets.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStorePath = signing("HERMES_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signing("HERMES_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signing("HERMES_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signing("HERMES_KEY_PASSWORD", "keyPassword")
val hasReleaseKey = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null } &&
    file(releaseStorePath!!).let { if (it.isAbsolute) it.exists() else rootProject.file(releaseStorePath).exists() }

android {
    namespace = "app.hermes.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = hermesApplicationId
        manifestPlaceholders["applicationLabel"] = if (hermesPreview) "@string/app_name_preview" else "@string/app_name"
        minSdk = 31
        // Optional device build: avoid shipping other CPUs over wireless ADB. Universal by default.
        providers.gradleProperty("hermesDeviceAbi").orNull?.let { abi ->
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
            ndk.abiFilters.add(abi)
        }
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = (System.getenv("HERMES_VERSION_CODE")?.toIntOrNull()) ?: 1
        buildConfigField("boolean", "FCM_CONFIGURED", firebaseReady.toString())
        if (firebaseReady) {
            resValue("string", "google_app_id", firebaseInfo!!["mobilesdk_app_id"].toString())
            resValue("string", "google_api_key", firebaseApiKey!!["current_key"].toString())
            resValue("string", "gcm_defaultSenderId", firebaseProject!!["project_number"].toString())
            resValue("string", "project_id", firebaseProject["project_id"].toString())
        }
        versionName = System.getenv("HERMES_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
        // Operator recognition/conversation gate is still pending. Flip only after phase-one device acceptance.
        buildConfigField("boolean", "HOST_SPEECH_ENABLED", "false")
        buildConfigField("boolean", "HOST_SPEECH_STREAMING_ENABLED", "false")
        // Provider setup declined; transport tests do not enable live calls.
        buildConfigField("boolean", "NATIVE_REALTIME_ENABLED", "false")
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val path = releaseStorePath!!
                storeFile = file(path).let { if (it.isAbsolute) it else rootProject.file(path) }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("hermes-companion: no release keystore (HERMES_KEYSTORE_* or keystore.properties); release APK is debug-signed.")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation(project(":core-voice"))
    implementation(project(":core-model"))
    implementation(project(":core-design"))
    implementation(project(":domain"))
    implementation(project(":data-local"))
    implementation(project(":data-remote"))
    implementation(project(":feature-connect"))
    implementation(project(":feature-threads"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-profiles"))
    implementation(project(":feature-gateway"))
    implementation(project(":feature-device"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // Compose render tests live in testDebug, like the feature render suites.
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
