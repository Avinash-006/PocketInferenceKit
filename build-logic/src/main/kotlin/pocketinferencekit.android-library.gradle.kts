plugins {
    id("com.android.library")
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk {
        version = release(versions.findVersion("android-compile").get().requiredVersion.toInt()) {
            minorApiLevel = versions.findVersion("android-compile-minor").get().requiredVersion.toInt()
        }
    }
    defaultConfig {
        minSdk = versions.findVersion("android-min").get().requiredVersion.toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
