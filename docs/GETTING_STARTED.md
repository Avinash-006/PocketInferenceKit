# Getting started

Use an Android device with API 24 or later and an `arm64-v8a` or `x86_64` runtime. The SDK requires Google Maven for LiteRT-LM. Use Kotlin 2.4 or later to compile code against the library. Inference runs locally after the model file is installed.

If AGP's built-in Kotlin compiler is older, pin a compatible compiler by adding the Kotlin Gradle plugin to the project's root buildscript classpath. The SDK uses `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21`. [Android Gradle plugin guidance](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version)

## Install from the checkout

Clone the repository and build with JDK 21, Android SDK Platform 37.2, and Build Tools 36.0.0.

```sh
./gradlew build
./gradlew publishAllPublicationsToVerificationRepository -PreleaseVersion=0.1.0
```

This publishes the Android libraries to `build/verification-repository` under the SDK checkout. Add that Maven directory, Google Maven, and Maven Central to the consuming Android project's settings.

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/absolute/path/to/PocketInferenceKit/build/verification-repository") }
        google()
        mavenCentral()
    }
}
```

Add the core artifact to the app module.

```kotlin
dependencies {
    implementation("io.github.Avinash-006:pocketinferencekit-core:0.1.0")
}
```

Run the publishing command from the v0.1.0 tag to reproduce that release. The core dependency includes the model catalog. Add `pocketinferencekit-compose` for the Compose helpers.

## Stream a response

Call this from a coroutine while the app is visible. The first load can download several hundred megabytes. The model file persists across launches.

```kotlin
import io.github.avinash006.pocketinferencekit.core.PocketInferenceKit
import io.github.avinash006.pocketinferencekit.models.ModelCatalog

PocketInferenceKit.load(context, ModelCatalog.Qwen3_0_6B).use { engine ->
    engine.newChat(systemPrompt = "You are helpful.").use { chat ->
        chat.sendText("Hello").collect { print(it) }
    }
}
```

Retain an engine across multiple chats. Keep one active collector per chat. Cancel its collection to stop inference. Call `close()` on owned handles when their lifecycle ends, followed by `awaitClosed()` when another operation must wait for native teardown.

For programmatic errors and timing, collect `chat.send(prompt)` and inspect the sealed `GenerationEvent` values. Model files can be imported and removed with `ModelCache`. [Models and downloads](MODELS.md)
