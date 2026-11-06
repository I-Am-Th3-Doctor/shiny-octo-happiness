plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// CI passes the run number so every build installs as an upgrade over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.iamth3doctor.searchlight"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.iamth3doctor.searchlight"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        testInstrumentationRunner = "io.github.iamth3doctor.searchlight.SearchlightTestRunner"
    }

    signingConfigs {
        // A fixed, committed debug key: every CI build has the same signature, so a new APK
        // installs over the old one and keeps favorites + the default-launcher choice.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            // Duplicate metadata from the Anthropic SDK's dependencies (Jackson, OkHttp, Kotlin).
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/versions/9/module-info.class",
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    // The Calendar widget's AI scheduling: Claude via the official Anthropic SDK (only used
    // with an API key set in Settings > AI).
    implementation(libs.anthropic.java)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real one for the weather parser tests.
    testImplementation(libs.org.json)
    // Screenshot capture on an emulator in CI (see .github/workflows/screenshots.yml).
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
