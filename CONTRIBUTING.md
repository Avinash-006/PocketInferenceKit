# Contributing

Use JDK 21, Android SDK Platform 37.2, and Build Tools 36.0.0. Clone the repository and run the checks from its root.

```sh
./gradlew build lint testDebugUnitTest spotlessCheck detekt apiCheck
```

Keep commits small and use [Conventional Commits](https://www.conventionalcommits.org/). Describe externally visible behavior in the pull request and add tests for regressions. Changes to public types need a design review and an intentional update to the checked-in API dump.

For a model catalog change, link to the publisher revision and license. Include the gating status, exact file size, and SHA-256. Model weights and private credentials stay out of commits. Identify the device, model revision, and selected backend for any performance claim.

The optional Android smoke test requires a compatible device and downloads a pinned upstream fixture only when enabled. [Troubleshooting](docs/TROUBLESHOOTING.md)
