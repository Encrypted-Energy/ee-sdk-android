# Engineering notes: ee-sdk-android

Design decisions that explain the code. The chronological build history
lives in EE's internal archives, not in this repo.

## Provenance

The pipeline core (parser, counters, rollup, location gate, batch
semantics) is extracted from the shipped EE Gateway app for Android,
with the product surface removed and SDK-only machinery added: the
permission-dormancy gate (the app ASKS for grants, the SDK only WAITS
for them), token provisioning with verify-before-persist, the remote
kill switch, and RadioGate (0.2.0: low-battery and no-fix parking).
ScanPolicy (charging/moving = full rate, stationary on battery =
reduced) carries over from the app intact. The iOS counterpart
(`Encrypted-Energy/ee-sdk-ios`) shares wire contracts and test
fixtures; where it differs, its NOTES.md documents why.

## Dependencies

Zero at runtime: the AAR ships against the Kotlin stdlib the host
already has: no androidx, no coroutines, no play-services. JUnit 4 is
the only test-time dependency.

## Test harness

- The JVM suite compiles the android-free subset of main sources plus
  every test, and runs them with plain JUnit: no device, no emulator,
  no gradle needed for the inner loop (`gradlew testDebugUnitTest`
  remains the authoritative full compile).
- Android-bound files (Engine, BleScanner, TokenStore, EEGateway
  facade) are compiled against a minimal hand-written stub jar of
  exactly the android surface the SDK touches. That catches signature
  drift; real framework behavior is proven by Studio builds and
  on-device runs.
- The sample app compiles as a SEPARATE module against the SDK classes,
  the way a partner compiles against the AAR: if the frozen public
  surface alone can't support a real integration, that stage fails.
- Never pipe the harness through `tail` inside a `&&` chain: it masks
  the exit code. This bit us once; the scripts guard against it now.

## Device verification status

The iOS SDK passed a first-device dogfood against production
(provisioning, heartbeats, packet forwarding, kill switch
harness-proven). The Android SDK shares the wire contracts and fixtures
but has not yet run on a physical device; the first integration build
should treat the Studio compile and a brief on-device sanity pass as
part of bring-up.

## Hazards for contributors

- **Engine's default self-mint path targets production.** Tests
  construct against injected transports/stubs; a test that forgets
  will hit the real API.
- The public API is frozen additive-only (see api-freeze.md) because a
  shipped SDK can't force its hosts to update. Grep the public surface
  before and after any refactor.
