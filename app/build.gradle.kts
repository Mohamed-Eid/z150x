import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.z150x.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Slim is the default ship shape: one ABI + R8. Opt out with -PslimApk=false (fat debug/CI).
    // -Pabi=armeabi-v7a ships the 32-bit ARM APK for phones whose Android is still 32-bit.
    val slimApk = (project.findProperty("slimApk") as String?)?.equals("false", ignoreCase = true) != true
    val abiFilter = (project.findProperty("abi") as String?)?.trim().orEmpty()

    defaultConfig {
        applicationId = "com.z150x.app"
        minSdk = 29
        targetSdk = 36
        // Version: -PappVersion=1.7 (default "1") and -PversionSuffix= ("" for a release, "-dev"
        // otherwise). Shown as "v<versionName>".
        // versionCode is derived from the version, so two releases can never share one (1.4, 1.5 and
        // 1.6 all went out as 81): major*10000 + minor*100 + patch, e.g. 1.7 -> 10700, 1.7.2 -> 10702,
        // 2.0 -> 20000. Every Z150X release so far is below 100, so any of these installs over them.
        // -PversionCode=N overrides it (Google Play needs a higher number for every upload).
        val appVersion = (project.findProperty("appVersion") as String?)?.trim().orEmpty().ifEmpty { "1" }
        val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
        require(versionParts.size <= 3 && versionParts.all { it in 0..99 } && versionParts[0] >= 1) {
            "appVersion must look like 1, 1.7 or 1.7.2 (each part 0-99): '$appVersion'"
        }
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: (versionParts[0] * 10_000 + versionParts.getOrElse(1) { 0 } * 100 + versionParts.getOrElse(2) { 0 })
        versionName = appVersion + ((project.findProperty("versionSuffix") as String?) ?: "-dev")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"



        // Short git hash for Share Logs triage (configuration-cache safe).
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            workingDir(rootProject.projectDir)
            isIgnoreExitValue = true
        }.standardOutput.asText.map { text ->
            val t = text.trim()
            if (t.matches(Regex("[0-9a-f]{4,40}"))) t else "unknown"
        }.orElse("unknown")
        buildConfigField("String", "GIT_HASH", "\"${gitHash.get()}\"")

    }

    // Two distribution channels, same code and same permissions:
    //  github — sideloaded APK. One ABI per APK (size).
    //  play   — Google Play: all ABIs in one bundle (Play splits it per phone).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("String", "STORE", "\"github\"")
            if (slimApk || abiFilter.isNotEmpty()) {
                ndk {
                    abiFilters += listOf(abiFilter.ifEmpty { "arm64-v8a" })
                }
            }
        }
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"play\"")
        }
    }

    // Z150X release key: keystore.properties at the repo root (git-ignored, see keystore/).
    val releaseKey = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    signingConfigs {
        create("debugRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (!releaseKey.isEmpty) {
            create("z150xRelease") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = slimApk
            isShrinkResources = slimApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No Z150X key → unsigned APK (won't install) unless -PallowDebugSigning=true (CI test builds).
            val allowDebugSigning = (project.findProperty("allowDebugSigning") as String?) == "true"
            signingConfig = when {
                !releaseKey.isEmpty -> signingConfigs.getByName("z150xRelease")
                allowDebugSigning -> signingConfigs.getByName("debugRelease")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    // Wireless Android Auto needs the packaged aa_privkey (same as prior releases).
    lint {
        disable += "PackagedPrivateKey"
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mlkit.barcodescanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    // Trip route / heat map (TripMapActivity): OpenStreetMap raster tiles, no API key.
    implementation(libs.osmdroid)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (Android's is a stub)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}