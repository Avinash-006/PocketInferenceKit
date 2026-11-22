plugins {
    id("pocketinferencekit.android-library")
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.models"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    buildTypes.getByName("debug").enableUnitTestCoverage = true
    testCoverage.jacocoVersion = libs.versions.jacoco.get()
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
