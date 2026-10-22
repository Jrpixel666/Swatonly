plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The manjaro-sway release this launcher is built from (see scripts/fetch-manjaro-sway.sh).
val manjaroSwayTag: String = providers.gradleProperty("manjaroSwayTag").getOrElse("202610180719")

android {
    namespace = "org.swatonly.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.swatonly.launcher"
        minSdk = 26
        // Kept at 28 like Termux: proot executes guest binaries that live in the
        // app's data directory, which newer target levels forbid (W^X).
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0-$manjaroSwayTag"
        buildConfigField("String", "MANJARO_SWAY_TAG", "\"$manjaroSwayTag\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("swatonly") {
            storeFile = file("swatonly.jks")
            storePassword = "swatonly"
            keyAlias = "swatonly"
            keyPassword = "swatonly"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("swatonly")
        }
        debug {
            signingConfig = signingConfigs.getByName("swatonly")
        }
    }

    buildFeatures { buildConfig = true }

    packaging {
        // proot and its loader are shipped as "libraries" so Android extracts
        // them to the (executable) native library directory.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.tukaani:xz:1.10")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
}
