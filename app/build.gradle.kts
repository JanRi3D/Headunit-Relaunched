plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.ri3d.headunit.relaunched"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.ri3d.headunit.relaunched"
        // API 16 is the whole point of this project.
        minSdk = 16
        // Deliberately old: at targetSdk 29+ the platform starts enforcing
        // scoped storage, background-start limits and foreground service types,
        // none of which a head unit wants.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Conscrypt is the only native code here. Old head units are
            // armeabi-v7a; arm64 covers newer ones, x86 covers the emulator.
            // Dropping x86_64 alone saves ~1.5 MB.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        // NewApi stays an ERROR on purpose -- it is what actually keeps this
        // codebase runnable on API 16. Everything below is a deliberate choice:
        disable += setOf(
            // old target/APIs are the point; AudioTrack(int, ...) and
            // MediaCodec.getInputBuffers() are the only spellings API 16 has
            "OldTargetApi", "ExpiredTargetSdkVersion", "UnusedAttribute",
            "DiscouragedApi", "GradleDependency",
            // mic and Bluetooth calls are wrapped in try/catch and degrade to
            // "feature unavailable" rather than crashing
            "MissingPermission", "UnspecifiedRegisterReceiverFlag",
            // AA's own certificate cannot chain to a public CA, and the
            // protocol does not ask us to verify the phone
            "TrustAllX509TrustManager", "CustomX509TrustManager", "TrulyRandom",
            // res/raw/privkey is Google's published head-unit reference key --
            // the identity the protocol requires, not a secret of ours
            "PackagedPrivateKey",
            // the wakelock is released in onDestroy and when the session ends
            "WakelockTimeout",
            // a SurfaceView is not a button, and the overlay is drawn over
            // video on purpose
            "ClickableViewAccessibility", "Overdraw", "ButtonStyle",
            // paddingStart/marginStart are API 17, so the layouts use Left/Right
            // and the manifest never opts into RTL mirroring
            "RtlHardcoded",
            // an IP address is not a thing to autofill, and autofill is API 26
            "Autofill",
            // commit() on purpose: a head unit gets its power cut, not shut down,
            // and apply() can lose the write that just happened
            "ApplySharedPref"
        )
    }

    testOptions {
        // Logger calls android.util.Log, which is a stub in unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 puts kotlin-stdlib on the app's classpath by default. There is not a
// line of Kotlin here, and it was costing ~1000 classes of dex that a 1GB unit
// has to load and verify on every cold start. Scoped to the app's own variant
// classpaths -- lint itself is written in Kotlin and needs the stdlib.
configurations.matching {
    it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath")
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    // The one runtime dependency, and it is not optional on old hardware.
    // API 16's built-in JSSE has TLS 1.2 but none of the ECDHE/GCM cipher
    // suites current Android Auto insists on, so the handshake dies and the
    // phone shows "communication error 7 - security problem with the car
    // display". Conscrypt is BoringSSL packaged as a JSSE provider and gives
    // API 16 the modern suites. 2.5.3 is the last release supporting minSdk 16
    // (2.6.x needs a higher floor) -- do not bump it without testing on 4.x.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Nothing else ships: no AndroidX, no support library, no protobuf runtime,
    // no kotlin-stdlib. JUnit is test-only.
    testImplementation(libs.junit)
}
