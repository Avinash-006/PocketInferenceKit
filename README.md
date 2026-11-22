# PocketInferenceKit

PocketInferenceKit is an Android SDK in development for running large language models on-device with Kotlin and LiteRT-LM. The SDK is designed to provide streaming chat, verified model downloads, and Compose integration for apps that want to run inference locally.

The SDK implements streaming chat, cancellation, CPU, GPU, and NPU selection, and verified model downloads with a persistent cache. See [the design](docs/DESIGN.md), [supported model files](docs/MODELS.md), and [upstream research](docs/RESEARCH.md) for the API and device requirements.

```kotlin
PocketInferenceKit.load(context, ModelCatalog.Qwen3_0_6B).use { engine ->
    engine.newChat(systemPrompt = "You are helpful.").use { chat ->
        chat.sendText("Hello").collect { print(it) }
    }
}
```

## Build the scaffold

Use JDK 21 with Android SDK Platform 37.2 and Build Tools 36.0.0.

```sh
./gradlew build
```

Licensed under [Apache 2.0](LICENSE).
