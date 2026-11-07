# Releasing (AAR via GitHub Packages)

## Checklist

1. `toolchain/runtests-sdk.sh` green (173+), plus a Studio
   `:sdk:assembleRelease` + `:sample` device run for any pipeline change.
2. Public surface unchanged, or additively extended with `api-freeze.md`
   updated in the same commit.
3. Bump the version in BOTH places it lives: `SdkVersion.STRING` and
   `sdk/build.gradle.kts` (group/artifact `com.encryptedenergy:gateway-sdk`).
   Update CHANGELOG/NOTES.
4. Publish from the repo root (PAT needs write:packages on the org):

   ```
   export GITHUB_ACTOR=<github user>
   export GITHUB_TOKEN=<PAT with write:packages>
   ./gradlew :sdk:publishReleasePublicationToGitHubPackagesRepository
   ```

5. Tag: `git tag -a 0.1.0 -m "0.1.0" && git push origin main --tags`.
6. Verify fleet telemetry: a dogfood device on the new AAR heartbeats
   `worker_version: ee-sdk-android/<new>` within 5 minutes.

Consumption snippet for partners: docs/INTEGRATION.md "Install".
