# PocketInferenceKit API proposal

This v0.1 API proposal awaits maintainer approval.

## Modules and coordinates

| Gradle project | Proposed Maven artifact under `io.github.Avinash-006` | Responsibility |
| --- | --- | --- |
| `:locallm-core` | `pocketinferencekit-core` | Engine, sessions, streaming, tool use, typed errors |
| `:locallm-models` | `pocketinferencekit-models` | Catalog, downloads, imports, cache, model resolution for core |
| `:locallm-compose` | `pocketinferencekit-compose` | Compose chat state and download progress |
| `:sample` | | Material 3 app |

Proposed Kotlin packages use `io.github.avinash006.pocketinferencekit` with `.core`, `.models`, and `.compose` suffixes. Core depends on models. Compose depends on core and models. The SDK targets Android API 24 and newer on ABI-compatible devices.

## Load and chat

```kotlin
PocketInferenceKit.load(context, model).use { engine ->
    engine.newChat(systemPrompt = "You are helpful.").use { chat ->
        chat.sendText("Hello").collect { print(it) }
    }
}
```

```kotlin
object PocketInferenceKit {
    suspend fun load(
        context: Context,
        model: ModelSpec,
        config: EngineConfig = EngineConfig(),
    ): LocalLMEngine
}

interface LocalLMEngine : AutoCloseable {
    val activeBackend: Backend
    fun newChat(
        systemPrompt: String? = null,
        generationConfig: GenerationConfig = GenerationConfig(),
        history: List<ChatMessage> = emptyList(),
        historyTrimmer: HistoryTrimmer = HistoryTrimmer.DropOldest,
        tools: ToolRegistry = ToolRegistry.Empty,
    ): ChatSession
    override fun close()
    suspend fun awaitClosed()
}

interface ChatSession : AutoCloseable {
    fun send(prompt: String): Flow<GenerationEvent>
    fun sendText(prompt: String): Flow<String>
    suspend fun sendAndAwait(prompt: String): String
    override fun close()
    suspend fun awaitClosed()
}
```

- `EngineConfig` carries `preferredBackend`, `maxTokens`, `contextLength`, `warmup`, and `unloadOnLowMemory`. Defaults are Auto, the model's limits, warmup off, and automatic unload off. `maxTokens` is an upper bound and `contextLength` requests the active context within that bound. If both are supplied, validate `contextLength <= maxTokens`. Pass the effective limit to LiteRT-LM. Warmup uses a disposable conversation off the main thread.
- Auto tries GPU, then CPU. Explicit GPU falls back to CPU. Explicit NPU tries NPU, GPU, then CPU. Only retry recoverable backend initialization failures. Log the attempted backend and reason without recording paths, prompts, or inference output. CPU selection makes a CPU-only attempt. An optional NPU library directory defaults to the app's native library directory.
- `load` uses application context, resolves and verifies the model, then initializes off the caller's main thread. `newChat` creates only Kotlin state. Native conversation allocation starts when a send is collected.
- `send` is a cold flow. Each collection starts one turn. One active turn per session is allowed. Overlapping collections get a typed `ConcurrentGeneration` error. The engine serializes native turns across sessions. Callers can use separate sessions from different coroutines.
- `GenerationEvent` is sealed. `Token(text)` carries an incremental text chunk, `ToolCall` carries validated tool input, `Finished(stats)` ends a successful turn, and `Error(exception)` ends a failed turn. `sendText` and `sendAndAwait` throw typed errors. Coroutine cancellation propagates to the cancelling caller.
- Closing is idempotent, stops accepting work immediately, and schedules native cancellation and teardown on the worker dispatcher. `awaitClosed()` waits for cleanup and propagates teardown errors. Active native calls finish unwinding before their handles are freed. Collector cancellation explicitly cancels native inference and discards the incomplete turn. Committed turns survive cancellation through transcript replay into a fresh conversation when needed. Tests will verify teardown ordering and callback handling.
- History consists of immutable user and assistant messages. Retain the system prompt and latest user request, dropping the oldest complete turns under the configured history budget. Allow callers to replace the trimming policy. The default uses a bounded turn history. Native overflow still produces a typed error if the system prompt or newest turn exceeds the runtime budget.
- `GenerationConfig` provides optional temperature, top-K, top-P, max new tokens, and seed, with runtime/model defaults where unspecified. A per-turn output budget is shared across tool rounds when the backend exposes decode counts. Stop sequences are matched across chunk boundaries, withheld from the stream, and trigger native cancellation. Any hidden suffix is excluded from committed history.
- `GenerationStats` carries nullable `timeToFirstTokenMillis: Long?`, `tokensPerSecond: Double?`, and `totalTokens: Int?`. First-token timing measures time to the first visible text chunk using a monotonic clock. Token totals count generated tokens across the turn. Expose counts and rates only when verified against runtime measurements. The test adapter exercises both available and unavailable stats. UI formatters show an unavailable value explicitly.
- `LocalLMException` is sealed with `ModelNotFound`, `DownloadFailed`, `ChecksumMismatch`, `InsufficientStorage`, `UnsupportedDevice`, `BackendInitFailed`, `OutOfMemory`, `GenerationFailed`, `ToolExecutionFailed`, `Cancelled`, and `ConcurrentGeneration`. Include causes and recovery context where available. Internal adapters and dispatchers are injectable for tests.

## Model acquisition

- `ModelSpec(id, displayName, url, sizeBytes, sha256, format, minRamMb, license)` describes a pinned file. The nullable URL permits a verified local-only model entry. Only enable automatic downloads for verified, ungated distribution URLs. Treat the RAM field as a recommendation with a documented basis. Import gated models from a user-supplied Android `Uri` or file path after the user obtains the model under its publisher's terms.
- `DownloadManager` exposes a per-model state flow and start, pause, resume, and cancel operations. WorkManager unique work and a persistent partial file carry progress across process restarts. A pause preserves the partial file. A cancel discards it. Resume validates the saved entity identity and checks `Content-Range`. A server that ignores the range triggers a clean restart.
- Use a storage precheck and report storage failures during the transfer. Stream a SHA-256 check, verify exact length, and atomically install a validated file in an app-private, backup-excluded directory. Coordinate cache deletion with active downloads and engines. Expose cache list, size, and delete.
- WorkManager is proposed for persistent scheduling and network constraints, using foreground execution for large downloads. On Android 16 and later, long-running workers are subject to job quota. Keep partial progress if execution is interrupted. Wi-Fi-only mode checks the actual network transport in addition to the scheduler constraint. [Android long-running worker documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

## Tools

A registry DSL accepts a name, description, JSON Schema for parameters, and a suspend Kotlin lambda. The SDK registers the schema with LiteRT-LM, disables native automatic execution, validates structured arguments, emits a tool event, invokes the registered handler, returns a tool-role response to the same conversation, and continues with a configurable round limit. v0.1 proposes support for a documented set of JSON Schema features for object parameters. Unknown tools, invalid input, incomplete structured output, and handler failures are handled without executing an invalid request. Models without verified structured tool support can chat with tools inactive. Ordinary assistant text is never executed as a tool request.

## Compose and sample

`ChatState` holds messages, send/stop status, errors, and generation stats. A download progress composable renders the observable model state. The sample moves from model selection to chat, then demonstrates a clock and arithmetic tools. Persist selected model and chat transcript locally for restoration. Engine handles belong to a lifecycle owner in the current process. Restore downloads from WorkManager state and restore chat by replaying committed messages.

## Dependencies proposed for approval

| Dependency | Proposed use |
| --- | --- |
| LiteRT-LM Android AAR 0.19.1 | Required native runtime, with the exact package and upstream constraints in [RESEARCH.md](RESEARCH.md). |
| Kotlin coroutines | Main-safe work, cancellable streams, and observable state. |
| AndroidX WorkManager | Persistent downloads. Use Android URL connections for the production HTTP transport. |
| Kotlin serialization JSON | Structured tool arguments and small persistent records. |
| Jetpack Compose and Material 3 | Compose helpers and the sample, with the Material 3 UI dependency scoped to the sample where possible. |
| MockWebServer and coroutine test tools | Test-only dependencies. |

Quality tooling is build-only. Maintain explicit API mode, KDoc, Android lint and Kotlin static checks, native consumer keep rules, a committed binary API dump, and an 80% unit coverage target for core and models. Gate the model-backed instrumented test so hosted CI can build and run unit tests without model weights.
