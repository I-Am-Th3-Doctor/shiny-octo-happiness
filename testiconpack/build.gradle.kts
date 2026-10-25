// A small icon pack in the standard (ADW) format, used only by the emulator tests: it themes a
// few apps in each of the ways a pack can, and styles the rest with back / mask / upon / scale.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.iamth3doctor.searchlight.testiconpack"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.iamth3doctor.searchlight.testiconpack"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
}
