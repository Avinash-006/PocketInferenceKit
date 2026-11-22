# Troubleshooting

| Symptom | What to check |
| --- | --- |
| Work is waiting at 0% | Check the network and Wi-Fi-only setting. Start the download while the app is visible so Android can run the foreground worker. |
| A large download stopped after the app closed | WorkManager can run again when the device meets its constraints and Android grants job quota. Reopen the picker and check the transfer state. |
| Verification failed | Remove the invalid installed file if present, then retry. For imports, verify the source file's exact size and SHA-256. |
| GPU initialization failed | Check the device driver and merged manifest. The SDK tries CPU for recoverable initialization errors. Inspect `activeBackend` for the selected backend. |
| NPU initialization failed | Confirm that the model matches the device and the vendor NPU libraries are installed. Set `npuLibraryDirectory` when they live outside the app's default native library directory. |
| The app runs out of memory | Close unused engines and reduce the model or context size. Check the runtime and RAM characteristics on the actual device. |
| The chip shows no tokens/sec | Turn timing is measured when the first visible chunk arrives. Exact token statistics depend on LiteRT-LM benchmark data. |
| A tool did not run | The model must declare structured tool support and return a registered call with schema-valid arguments. Inspect the `ToolCall` events. |
| The app provides its own WorkManager configuration | Initialize WorkManager before constructing a download manager or loading a model that still needs a download. |

A missing Android device prevents validation of GPU and NPU compatibility, recording the sample, and running the optional Android smoke test. Device-specific results belong in a reproducible benchmark or smoke-test report.
