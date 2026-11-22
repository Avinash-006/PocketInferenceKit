plugins {
    id("pocketinferencekit.android-library")
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.core"
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":locallm-models"))
    api(libs.coroutines.core)
    implementation(libs.litert.lm)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}
