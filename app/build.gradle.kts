import java.util.Properties

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
        applicationId = "app.hermes.companion"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.getenv("HERMES_VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("HERMES_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
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
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
