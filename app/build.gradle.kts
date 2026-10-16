import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// ---- Release signing ----
// The key never lives in the repository. Locally, keystore.properties in the
// project root (git-ignored) points at the .jks kept outside it. On GitHub
// Actions, .github/workflows/release.yml decodes the key from repository
// secrets and passes it in through the OMNIVPN_* environment variables.
// Android only installs an update signed with the same key as the installed
// app, so this one key signs every build that reaches a phone.
val signingProps = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? = signingValue("storeFile", "OMNIVPN_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

// ---- Version ----
// versionCode = number of commits up to HEAD: it grows with every commit, so a
// newer build always installs over an older one (Android refuses downgrades),
// whether it came from GitHub Releases or over USB. versionName comes from the
// release tag in CI (OMNIVPN_VERSION_NAME); local builds are "dev.<code>".
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.betalgezia.omnivpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.betalgezia.omnivpn"
        minSdk = 24
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = System.getenv("OMNIVPN_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "dev.$gitCommitCount"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "OMNIVPN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "OMNIVPN_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "OMNIVPN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key as release when it's available, so a build installed
            // over USB updates the app from GitHub Releases in place (and the
            // other way round) instead of failing on a signature mismatch.
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libbox.so (the sing-box core) is ~75 MB per ABI. Stored uncompressed
        // (the default for minSdk 23+) it made the APK 316 MB; compressed it
        // is ~26 MB and Android unpacks it once at install.
        jniLibs.useLegacyPackaging = true
    }

    // One APK per phone architecture instead of all four in one: arm64-v8a for
    // practically every phone since ~2017, armeabi-v7a for old 32-bit ones.
    // x86/x86_64 are emulator-only and are left out. All split APKs share one
    // versionCode and key, so either installs over the other.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

val libboxAar = file("libs/libbox.aar")
if (!libboxAar.exists()) {
    throw GradleException(
        "Missing app/libs/libbox.aar. Run scripts/fetch-libbox.sh (Linux/macOS) " +
            "or scripts/fetch-libbox.ps1 (Windows) before building."
    )
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    // Subscription auto-update (see SubscriptionAutoUpdateWorker) - a plain
    // CoroutineWorker reached over a Hilt EntryPoint, so no hilt-work needed.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-compiler:2.57.1")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20260814")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.yaml:snakeyaml:2.7")
    implementation(files(libboxAar))
}
