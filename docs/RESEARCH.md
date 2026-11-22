# LiteRT-LM research

Verified on 2026-11-22 against Google's Android guide, the published AAR, and the upstream `v0.19.1` source tag.

## Dependency and platform

| Item | Verified result |
| --- | --- |
| Android runtime | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` from Google Maven. Google Maven lists `0.19.1` as the latest release. The Android guide shows the same coordinates with a dynamic version. |
| JVM runtime | `com.google.ai.edge.litertlm:litertlm-jvm:0.19.1` is the companion desktop artifact. The Android SDK uses the Android AAR. |
| Minimum Android version | API 24 (Android 7.0), from `uses-sdk` in the published Android AAR. A device still needs a supported ABI and enough memory for the selected model. |
| Android ABIs | The published AAR has JNI binaries for `arm64-v8a` and `x86_64`. ABI packaging alone establishes neither device nor accelerator compatibility. |
| Model file format | LiteRT-LM's Kotlin Android guide documents `.litertlm` bundles. The proposed v0.1 importer accepts this format. Legacy MediaPipe `.task`, standalone `.tflite`, GGUF, and Safetensors are outside that importer contract. |
| Compile SDK | Android SDK Platform 37.2 is the highest final platform in the Android SDK repository metadata as checked. Android Developers lists AGP 9.4.0 as the minimum for this platform. The scaffold pins AGP 9.5.2 and Gradle 9.9.2. |

[Official Android guide](https://developers.google.com/edge/litert-lm/android) · [Google Maven release metadata](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml) · [Published 0.19.1 AAR](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.19.1/litertlm-android-0.19.1.aar) · [Upstream release](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.19.1) · [Android SDK repository metadata](https://dl.google.com/android/repository/repository2-3.xml) · [Android API level and AGP compatibility](https://developer.android.com/studio/releases#api-level-support) · [AGP 9.5 compatibility](https://developer.android.com/build/releases/agp-9-5-0-release-notes)

## Android inference backends

| Backend | Kotlin entry point | Requirements |
| --- | --- | --- |
| CPU | `Backend.CPU(threadCount = ...)` | The upstream default. Requires a CPU-compatible model, supported ABI, and adequate RAM. |
| GPU | `Backend.GPU()` | Requires a compatible model and driver. Google documents optional `uses-native-library` entries for `libvndksupport.so` and `libOpenCL.so` in the app manifest. Initialization can still fail on a device. |
| NPU | `Backend.NPU(nativeLibraryDir = ...)` | Requires a compatible model, device, and accelerator libraries. For libraries bundled with an Android app, use `context.applicationInfo.nativeLibraryDir`. The published AAR contains JNI binaries, so NPU deployment must be verified separately. |

The tagged source also exposes a separate Google Tensor backend. The proposal keeps v0.1 selection to CPU, GPU, NPU, and the SDK's automatic policy. [Tagged Kotlin guide](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/docs/api/kotlin/getting_started.md) · [Tagged backend configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt) · [Google's NPU guide](https://ai.google.dev/edge/litert/next/litert_lm_npu)

## API findings

- Create an `Engine` from a local model path. Initialize it on a worker dispatcher, then create a `Conversation` with a system instruction, history, and sampling configuration. Close the conversation before its engine. [Engine source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt)
- The tag exposes `SamplerConfig(topK, topP, temperature, seed)`, `EngineConfig.maxNumTokens` for combined context, and `maxOutputToken` on conversation configuration and individual sends. It has no Kotlin arbitrary stop-string setting. [Configuration source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
- The Android artifact and Kotlin libraries it depends on carry Kotlin 2.4 metadata. The build pins Kotlin Gradle Plugin 2.4.21 on the Android convention plugins' classpath so the compiler can read that metadata. [Published dependency manifest](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.19.1/litertlm-android-0.19.1.pom) · [AGP guidance for upgrading KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version)
- The callback API reports text chunks and provides terminal success/error callbacks. The upstream Flow's cancellation block is empty. The wrapper needs to call `cancelProcess()` explicitly and coordinate native cleanup. The upstream source notes that cancellation does not roll back the conversation automatically. [Conversation source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt)
- `getTokenCount()` reports KV-cache tokens, including prefill and decode. Per-decode benchmark counts and rates use an experimental benchmark setting. A callback chunk can span more than one token. [Conversation source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt), [benchmark source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Benchmark.kt)
- Manual tool calling is available through `automaticToolCalling = false`, structured tool calls in `Message`, and tool-role responses. `ModelInfo.Llm.supportsFunctionCalling()` exposes model-declared capability. Device tests are needed before claiming that a catalog entry works reliably with tools. [Message source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Message.kt), [tool source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/Tool.kt), [model introspection source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/ModelInfo.kt)

Runtime verification starts with a model-backed instrumented test. Model catalog entries require publisher provenance, licensing and gating checks, a pinned file revision, published size, and a verified SHA-256.
