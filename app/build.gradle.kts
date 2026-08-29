import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fleet-dashboard heartbeat endpoint/secret: kept out of source control in
// local.properties (already gitignored, same file used for sdk.dir), with
// safe empty defaults so a build without them still compiles — the
// heartbeat sender simply no-ops when unconfigured (see HeartbeatSender.kt).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(key: String): String =
    (localProperties.getProperty(key) ?: System.getenv(key) ?: "")

android {
    namespace = "com.bs.sunmibridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bs.sunmibridge"
        minSdk = 24        // Android 7.0 — covers the SUNMI V2 (Android 7.1)
        targetSdk = 28     // modest target: avoids newer runtime-permission prompts
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "HEARTBEAT_URL", "\"${localOrEnv("HEARTBEAT_URL")}\"")
        buildConfigField("String", "HEARTBEAT_SECRET", "\"${localOrEnv("HEARTBEAT_SECRET")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing key: generated once (keystore/release.keystore, gitignored)
    // and must stay the SAME key for every future release — Android refuses to
    // install an update whose signature doesn't match what's already on the
    // device, so losing/rotating this key means every deployed unit needs a
    // manual uninstall+reinstall. Back this file up somewhere durable.
    // Falls back to the auto-generated debug key when unconfigured, so
    // assembleDebug keeps working without any of this set up.
    val hasReleaseSigning = localOrEnv("RELEASE_STORE_FILE").isNotBlank()
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(localOrEnv("RELEASE_STORE_FILE"))
                storePassword = localOrEnv("RELEASE_STORE_PASSWORD")
                keyAlias = localOrEnv("RELEASE_KEY_ALIAS")
                keyPassword = localOrEnv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk 28 is intentional (see defaultConfig comment) — this app
        // is sideloaded onto a private SUNMI fleet, never distributed via
        // Play Store, so Play's target-SDK policy check doesn't apply here.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // SUNMI built-in printer SDK (published on mavenCentral).
    // Provides InnerPrinterManager / SunmiPrinterService, which wraps the
    // woyou.aidlservice.jiuiv5 AIDL interface correctly — so we never
    // hand-write AIDL and never hit the transaction-id mismatch trap.
    implementation("com.sunmi:printerlibrary:1.0.23")
}
