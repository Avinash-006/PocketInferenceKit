# Releasing

GitHub Actions verifies semantic version tags and creates a GitHub Release with the matching changelog entry. The production publication path is Maven Central through the Sonatype Central Portal. Installing from the checkout uses the local Maven repository described in [Getting started](GETTING_STARTED.md).

## Maven Central

The maintainer needs a Central Portal account and control of the `io.github.Avinash-006` namespace. Add a portal token and an ASCII-armored signing key to the repository's GitHub Actions secrets.

| Secret | Value |
| --- | --- |
| `CENTRAL_USERNAME` | Central Portal token username |
| `CENTRAL_PASSWORD` | Central Portal token password |
| `CENTRAL_SIGNING_KEY` | ASCII-armored PGP private key used to sign publications |
| `CENTRAL_SIGNING_PASSWORD` | The signing key passphrase, if required |

Publish the corresponding PGP public key so Maven Central can validate the signatures. The workflow uploads the three signed AAR publications and selects automatic publication. Check the deployment status in the portal before advertising the Maven Central coordinates. [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/) · [Namespace verification](https://central.sonatype.org/register/namespace/) · [Signing requirements](https://central.sonatype.org/publish/requirements/gpg/)

GitHub Releases can proceed without the Central Portal secrets. The Maven step reports that it skipped publishing when credentials are missing. Run the release workflow again on the release tag after setup.

## API documentation

Run `./gradlew :dokkaGeneratePublicationHtml` to generate the combined documentation under `build/dokka/html`. The API docs workflow packages that output as an Actions artifact and deploys it when GitHub Pages is enabled with GitHub Actions as the source. [GitHub Pages workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
