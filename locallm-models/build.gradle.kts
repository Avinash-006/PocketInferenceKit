plugins {
    id("pocketinferencekit.android-library")
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.models"
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.coroutines.core)
    implementation(libs.workmanager)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
