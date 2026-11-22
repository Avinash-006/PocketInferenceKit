plugins {
    id("pocketinferencekit.android-library")
    id("pocketinferencekit.android-compose")
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.compose"
    buildFeatures.compose = true
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":locallm-core"))
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
}
