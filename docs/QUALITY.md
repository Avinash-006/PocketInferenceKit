# Quality checks

```sh
./gradlew build lint testDebugUnitTest spotlessCheck detekt apiCheck
```

The Kotlin formatter is Spotless with ktlint. Detekt analyzes the primary Android sources. The ABI tasks run JetBrains' binary compatibility validator on each release AAR's compiled classes and compare them with the checked-in `api/*.api` files. After an approved API change, run `./gradlew apiDump` and review the diff.

## Unit coverage

```sh
./gradlew :locallm-core:createDebugUnitTestCoverageReport :locallm-models:createDebugUnitTestCoverageReport
```

The current JVM line coverage is 62.8% in core and 31.0% in models. The target is 80% for each module. JVM tests exercise generation, model transfer, validation, and lifecycle logic. Model-backed and Android-only paths need device tests.

## Optional native CPU smoke test

The test APK builds. Device execution is pending. On a connected Android API 24+ device with an `arm64-v8a` or `x86_64` runtime, run the gated test.

```sh
./gradlew :locallm-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.modelSmoke=true
```

With the flag, the test downloads the 48,696,498-byte upstream fixture, verifies its SHA-256 through the import API, runs one CPU turn, and waits for native cleanup. The fixture stays outside version control and release artifacts. [Pinned LiteRT-LM test fixture](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/runtime/testdata/test_lm.litertlm)
