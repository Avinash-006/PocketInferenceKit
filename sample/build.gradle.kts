plugins {
    id("pocketinferencekit.android-application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.sample"
    defaultConfig {
        applicationId = "io.github.avinash006.pocketinferencekit.sample"
        versionCode = 1
        versionName = "0.1.0"
    }
}

android {
    buildFeatures.compose = true
}

dependencies {
    implementation(project(":locallm-core"))
    implementation(libs.activity.compose)
    implementation(libs.litert.lm)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
