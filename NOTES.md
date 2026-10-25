# Build notes

Decisions and caveats, chunk by chunk, for John and future chunks.

## C5: pipeline behind the facade

162 tests green, and a NEW harness stage: every main source — android-bound
files included — now full-compiles against hand-written android stubs
(`/home/clawuser/toolchain/android-stubs/`, built this chunk). Typos and
signature drift in EEGateway/Engine/BleScanner/LocationProvider no longer
wait for the C8 Studio build to surface; Studio remains the authority on
real framework behavior.

Verbatim moves from gateway-android @ cf0dde2 (package + `internal` only):
`PacketQueue` (WITHOUT UploadPacket, per the C2 note), `QueueStore`,
`EidThrottle`, `BleScanner` (diff-verified: two-line delta), plus
PacketQueueTest/QueueStoreTest/EidThrottleTest carried unchanged.

Adapted:

- `ScanPermissions` — `Context.checkSelfPermission` replaces androidx
  ContextCompat (same framework call on minSdk 26+); keeps the AAR at zero
  runtime deps. Still check-only: the SDK never requests.
- `LocationProvider` — fixed-location override NOT ported, deliberately: it's
  a first-party operator feature ("this gateway is HERE"), and on a fleet SDK
  a partner-settable static location is a coverage-spoofing lever. 25 s
  freshness gate, null-island rejection, seed-from-cache all intact.
- `UploadLoop` — now android-free (no Handler/Looper; callbacks fire on the
  loop executor, the engine marshals). The tick drains ONE server batch and
  delegates the contract to C3's BatchSender; requeue order + forwarded
  counters are the loop's job. Harness-tested via tick() with an injected
  wire function (8 tests).
- `HeartbeatLoop` — android-free, same shape. **Cadence is 5 min, not the
  app's 60 s** — the architecture diagram pins it and partner battery budget
  is a contract (§6 risk 3); a test pins INTERVAL_SECONDS=300. Snapshot/
  restore-on-transient semantics carried; a throwing wire call maps to
  Transient so scheduleWithFixedDelay can never die (8 tests).

New: `Engine` — GatewayEngine minus the entire product surface (no Compose
state, notifications, claim lifecycle, DiagLog, fixed location), plus the
permission-dormancy gate the app never needed:

- start() always runs heartbeat/upload loops (ops can SEE a dormant fleet
  phone: gps_status `dongle_missing`, status `backgrounded`); radio+GPS only
  start once grants exist. Missing grants → 5 s checkSelfPermission poll;
  grant appears → pipeline starts itself (AWAITING_PERMISSIONS → RUNNING, no
  host call). Mid-run revocation (scanner UNAUTHORIZED) tears the pipeline
  down and returns to the poll — honest in both directions. The synchronous
  revocation race (grant pulled between check and radio call) is covered:
  `scanning` flips true BEFORE scanner.start() so the teardown handler acts,
  and startPipeline aborts if it fired.
- Queue file is SDK-namespaced (`com.encryptedenergy.sdk.packet-queue.eeq`)
  per the C4 rule. Loop threads are daemons — the SDK must never block a
  host process exit.
- Heartbeat `disable` is PARSED and stored (`remoteDisable`) but not yet
  acted on — C6 maps it to PAUSED_REMOTE.

EEGateway now drives the real engine; frozen surface untouched. Listener
callbacks are COALESCED (≤ ~1/s): a beacon-dense street fires onHeard per
advertisement, and posting each to the host's main thread is exactly the ANR
risk §6.3 warns about. State flips ride the same tick (never dropped —
transition compares snapshots). A changed config applies on the next start()
from a non-running state (engine rebuild; disk queue survives, process-local
counters reset); it never regresses a running gateway. Public methods got
interim catch-Throwable guards to honor "never throws" — C6 does the
systematic isolation sweep.

Still deferred: token provisioning orchestration at the facade (C4's
TokenProvision wired to start(): verify-before-persist for GatewayToken,
mint-on-first-start for SelfMint) — C6. Until then a phone with no stored
token heartbeats Unauthorized and requeues packets, which is safe. Also C6:
`disable`/PAUSED_REMOTE, gatewayPublicId in EEStatus.

## C4: TokenStore + verify-before-persist

128 tests green. Three files:

- `TokenStore` (android-bound, outside the harness — Studio/C8 verifies the
  compile, on-device dogfood verifies Keystore behavior): ported from
  gateway-android 0.6.0 with the claim-state block removed and every name
  SDK-namespaced — prefs file `com.encryptedenergy.sdk.store`, Keystore alias
  `com.encryptedenergy.sdk.token_key`. The app's generic names
  (`ee_gateway` / `ee_token_key`) are left for the host: the Keystore alias
  namespace is app-wide, so an SDK squatting generic names could collide with
  the host's own keys (or with the first-party app's, if the two codebases
  ever share a process). Also stores the self-mint gateway name (plain text —
  display label, not a secret) so it resets together with the token in
  `clear()`.
- `TokenCrypto` (pure, harness-tested): the AES-GCM seal/open split out of
  TokenStore so the crypto rules are JVM-pinned — fresh IV per seal, and
  wrong-key / tampered-ciphertext / corrupt-prefs ALL read as null ("no token
  stored" = the app's signed-out semantics). Uses java.util.Base64, not
  android.util (API 26+, our minSdk floor), which is what keeps the file in
  the harness compile set.
- `TokenProvision` (pure, harness-tested): the persistence invariant —
  NOTHING persists until the server said yes. `injectToken` runs
  verify-before-persist (verify sees the INJECTED token via
  `verify(tokenOverride)`, never the stored one); Terminal 4xx on verify maps
  to Rejected, not Retry — never persist on a guess. `selfMint` persists
  key+name only on Minted; the mint response needs no verify round-trip (the
  server that just created the key is the server that vouched for it).
  Transient outcomes persist nothing and the caller retries with Backoff —
  the previously stored token stays authoritative. C6 wires these to the real
  EEClient/TokenStore and maps outcomes onto EEState.

Version-string caveat for C10: `sdk/build.gradle.kts` hardcodes `0.1.0` in
two places (module version + publication) that Gradle can't read from
`SdkVersion.kt`. Release checklist must bump all three together — worth a
one-line grep check in the C10 publish steps.

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
