# Build notes

Decisions and caveats, chunk by chunk, for John and future chunks.

## C3: headless client

The wire layer: `EEClient` (verify/heartbeat/ingest/self-mint), `Counters`,
`HeartbeatPayload`, `DeviceRollup`, plus two new pure components, `Backoff`
and `BatchSender`. 114 tests green (59 carried/new on top of C2's 55). C3 is
spec-driven, NOT verbatim — gateway-android's loops never implemented the
published contract's 413-split/422-reasons/backoff, so those are new code
against ee-web's actual controller + /docs page (verified in source 10/24).

Verbatim moves (package + `internal` only): `Counters` + test, `DeviceRollup`
+ test.

Adapted from gateway-android @ cf0dde2:

- `EEClient` — the app's claim flow (`claim()`, `ClaimResult`, `mapClaim`) is
  REMOVED: a fleet SDK has no end user to attach; the partner org already
  owns every gateway. If a future product wants claims-from-SDK, port it back
  from gateway-android, including the 401-body split (invalid_credentials vs
  invalid_token). Other deltas: User-Agent prefix `ee-sdk-android`;
  `Result.Ok` gains `disable` (heartbeat C9 kill switch — parsed here,
  ACTED ON in C6) and `Result.Terminal` keeps the response body so
  `BatchSender` can read 422 `invalid_reasons`. New pure mappers
  `parseDisable` / `parseInvalidReasons` follow the parseBountyEarned
  pattern: malformed input is the safe default (false / empty) — a parse
  failure must never pause a fleet or crash an upload pass.
- `HeartbeatPayload` — `worker_version` is now `ee-sdk-android/<version>`
  via `SdkVersion.STRING` (new file), the single version source that also
  feeds `EEGateway.VERSION`. NOTE: `EEGateway.kt` now initializes its frozen
  `VERSION` const from `SdkVersion.STRING` — valid Kotlin (const-from-const)
  but outside the harness's compile set; John's Studio build is the check.
  Fleet telemetry showing a distinct worker string is deliberate: ops can
  tell SDK traffic from the first-party app, and the value doesn't lie about
  which codebase produced it.

New, spec-driven (tests pin every branch):

- `BatchSender` — the published ingest contract: pre-chunks to 500, 413
  halves-and-resends (size-1 413 drops: unsplittable), 422 drops the chunk
  and surfaces `invalid_reasons`, other non-401 4xx drops, transient/401
  stops the pass and returns everything undecided for requeue in original
  order. Pure: the wire call is injected. C5's upload loop wires it to
  `EEClient.ingest` + `PacketQueue`; pacing/requeue stays the loop's job.
- `Backoff` — exponential, equal-jitter (delay uniform in [cap/2, cap]),
  base 2 s, ceiling 10 min, seedable `java.util.Random` so tests are
  deterministic. For the self-mint 503/429 path (300/hr global cap) and
  dead-network verify retries. Computes delays only — the caller owns the
  clock and the sleep, which is why it unit-tests without time.

Still deferred: verify-before-persist ORCHESTRATION (needs TokenStore — C4;
the client-side hook, `verify(tokenOverride)`, is ready), heartbeat/upload
loops + threading (C5), `disable` behavior + crash isolation at the facade
(C6).

## C2: pure core moved from gateway-android 0.6.0

Moved verbatim from `gateway-android` @ cf0dde2 (the shipped 1.3/0.6.0 HEAD),
tests carried unchanged: `HubblePacketParser` (+`ParsedPacket`), `ScanPolicy`,
`LocationFix`, `Json`, `PacketJson`, and their five test classes
(parity-oracle fixtures included). Exactly two deltas vs. the originals,
verified by diff:

1. Package rename to `com.encryptedenergy.sdk`.
2. `internal` on every moved top-level type. api-freeze.md says anything
   outside the frozen surface is internal and may change; the AAR has to
   enforce that, or a partner compiles against `HubblePacketParser` and the
   freeze doctrine silently extends to the whole core.

Zero logic drift otherwise — comments, constants, and fixture expectations are
byte-identical. Harness: 55 tests green (11 C1 + 44 carried).

`UploadPacket` got its own file: in gateway-android it sits at the top of
`PacketQueue.kt`, but the queue itself is C5 scope. The data class + its kdoc
moved verbatim; when C5 brings `PacketQueue` over, it comes WITHOUT the data
class — do not re-copy it.

Deliberately left behind for later chunks: `HeartbeatPayload` (hardcodes
`WORKER_VERSION = "ee-gateway-android/0.6.0"` — the SDK needs its own worker
string, which is a C3 decision, not a verbatim move) and `Counters` /
`EidThrottle` (C3/C5 scope).

# C1 build notes

Decisions and caveats from the C1 scaffold, for John and future chunks.

## Dependencies: zero at runtime, one test-only

Runtime/`api`/`implementation` dependency count is **zero**, as required. The
AAR pulls in nothing but the Kotlin stdlib the host app already ships.

The one dependency declared anywhere in the module is `testImplementation(libs.junit)`
(JUnit 4.13.2). Reasoning, since the brief asked for any dep to be justified in
writing rather than added silently:

- It lives only in the `test` source set. It is not `api` or `implementation`,
  so it is never transitive to a consumer and never lands in the AAR. The
  zero-runtime-dependency selling point is intact.
- The unit tests are written against JUnit 4, matching gateway-android exactly
  (same framework, same jars the JVM harness uses). Without it declared,
  `./gradlew :sdk:test` in John's Studio could not compile the test source set,
  which would break "builds without prompts."
- The alternative (no test dep, tests only runnable through the out-of-tree
  kotlinc harness) would leave the Gradle project with uncompilable test
  sources, which is worse than one standard, non-shipping test dependency.

If a future chunk needs a true runtime dependency, STOP and justify it here
first; the zero-runtime-dep promise is a contract with partners.

## EEGateway.kt is not compiled by the JVM harness

`EEGateway.kt` imports `android.content.Context`, `android.os.Handler`, and
`android.os.Looper`. There is no `android.jar` on this build box, so it cannot
compile here, and the kotlinc harness (`runtests-sdk.sh`) deliberately excludes
any main file with an `android`/`androidx` import (same rule as gateway-android's
`runtests.sh`). It is verified two ways instead:

1. Careful review of the source. It uses only stdlib Kotlin plus the three
   android imports above, a single `Any()` lock, `synchronized`, and a
   main-looper `Handler`. No unresolved symbols, no non-android deps.
2. John's Studio build (C8 dogfood gate) compiles it against the real SDK.

The android-free types (`EEConfig`, `EEStatus`, `EEState`, `EEListener`) carry
no android imports by design, so they compile and unit-test on the pure JVM,
and they are what the harness exercises (11 tests, all green).

## State machine in this scaffold

`start()`/`stop()` flip to `RUNNING`/`STOPPED`. `AWAITING_PERMISSIONS` and
`PAUSED_REMOTE` exist in the frozen enum and are fully documented, but are only
reached once the permission-dormancy gate (C5) and the heartbeat `disable`
handling (C6) land. That is the intended C1 stub scope per the brief.
