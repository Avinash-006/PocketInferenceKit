plugins {
    id("pocketinferencekit.android-library")
}

android {
    namespace = "io.github.avinash006.pocketinferencekit.core"
    packaging.jniLibs.keepDebugSymbols.add("**/liblitertlm_jni.so")
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildTypes.getByName("debug").enableUnitTestCoverage = true
    testCoverage.jacocoVersion = libs.versions.jacoco.get()
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":locallm-models"))
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.litert.lm)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
