# Models

The catalog uses pinned LiteRT Community conversions of Qwen models. Hugging Face's model metadata identifies each repository as ungated and gives Apache-2.0 as the license. The file sizes and SHA-256 digests in `ModelCatalog` come from each revision's Git LFS metadata. PocketInferenceKit downloads from the publisher and verifies both values before installation.

| Catalog entry | File bytes | License | Publisher |
| --- | ---: | --- | --- |
| `Qwen3_0_6B` | 343,490,560 | Apache-2.0 | [Qwen3 0.6B INT4](https://huggingface.co/litert-community/Qwen3-0.6B/tree/8d42ab5f1db14cad987fac57aeb17d070f9e180d) |
| `Qwen3_5_0_8B` | 486,539,264 | Apache-2.0 | [Qwen3.5 0.8B mixed precision](https://huggingface.co/litert-community/Qwen3.5-0.8B/tree/7c656dc9e84b135df6b702e54fbe14a05c67abb8) |
| `Qwen2_5_1_5B` | 1,597,922,856 | Apache-2.0 | [Qwen2.5 1.5B Instruct INT8](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/tree/60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b) |

The publishers do not specify validated Android RAM requirements for these files. Runtime memory also depends on context length, backend, and other apps on the device. Start with the smallest model and test on supported devices before shipping.

## Download, pause, and resume

`PocketInferenceKit.load(context, model)` schedules a missing catalog file and suspends until it is installed. For UI-controlled downloads, observe `DownloadManager.progress(model)` and call `start(model, wifiOnly = true)` if the user selects Wi-Fi only. Start and resume a large transfer while the app is visible so Android can allow foreground execution.

The manager schedules unique WorkManager work and retains partial bytes in app-private storage. Pause preserves a partial file and its server validator. Resume requests a byte range with that validator. If the server returns a complete response, the worker restarts at byte zero. Cancel stops the worker and discards its partial files. Applying Wi-Fi only binds each HTTP connection to a validated Wi-Fi network.

Android job quotas and connectivity can delay work. The persisted configuration and partial file let WorkManager continue after process restart. The foreground notification reports progress where the operating system permits it. [WorkManager long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

## Import and cache

Use `ModelCache(context).importModel(spec, uri)` for a file selected with Android's document picker, or call the overload that accepts a readable path. Set `url = null` for a local-only model and supply the expected size and SHA-256. For a gated model, acquire the file from the publisher under its terms before importing it. The SDK does not copy model weights into the repository or the app package.

The cache stores verified bundles under the app's no-backup directory. `list()` and `size()` exclude partial downloads. `delete(spec)` returns false while an engine is using the file. Close the engine and await cleanup before deleting it.
