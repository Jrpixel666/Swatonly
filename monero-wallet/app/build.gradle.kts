plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI secrets), never from the repository.
val keystorePath: String? = System.getenv("XMRVAULT_KEYSTORE")

android {
    namespace = "org.swatonly.xmrvault"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.swatonly.xmrvault"
        // Android 10+: StrongBox/Keystore features, scoped storage, TLS 1.3.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("XMRVAULT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XMRVAULT_KEY_ALIAS")
                keyPassword = System.getenv("XMRVAULT_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("XMRVAULT_KEYSTORE not set: release APK is signed with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    dependenciesInfo {
        // No signing-block blob of dependency metadata (it is encrypted to Google's key).
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true; disable += listOf("SetTextI18n", "HardcodedText") }
}

// Build the Rust core for every ABI with cargo-ndk.
val cargoBuild by tasks.registering(Exec::class) {
    val rustDir = rootProject.file("rust")
    val outDir = layout.buildDirectory.dir("rustJniLibs").get().asFile
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.dir(rustDir.resolve("vendor"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    outputs.dir(outDir)
    doFirst { environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath) }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-P", "29",
        "-o", outDir.absolutePath,
        "build", "--release", "--locked",
    )
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    // Intentionally empty: no third-party JVM code runs next to the keys.
}
