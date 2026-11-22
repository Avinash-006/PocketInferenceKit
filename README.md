# PocketInferenceKit

PocketInferenceKit is an Android SDK in development for running large language models on-device with Kotlin and LiteRT-LM. The SDK is designed to provide streaming chat, verified model downloads, and Compose integration for apps that want to run inference locally.

The core module implements streaming chat, cancellation, and CPU, GPU, and NPU selection. The model catalog, downloader, Compose helpers, and sample are in development. See [the design](docs/DESIGN.md) and [upstream research](docs/RESEARCH.md) for the API and device requirements.

## Build the scaffold

Use JDK 21 with Android SDK Platform 37.2 and Build Tools 36.0.0.

```sh
./gradlew build
```

Licensed under [Apache 2.0](LICENSE).
