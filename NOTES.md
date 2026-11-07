# Build notes

Decisions and caveats, chunk by chunk, for John and future chunks.

## Arc review (post-C7, 10/25): findings and fixes

Full C1–C7 pass against the plan's contracts (never-throws, never-prompts,
no-FGS, zero deps, frozen surface, battery/ANR honesty), threading, wire
semantics vs ee-web source, and resource hygiene. Five findings fixed:

1. **Harness masked test-compile failures** (inherited from runtests.sh):
   `kotlinc … | grep -v warning || true` swallowed non-zero exits, and
   JUnitCore with an empty class list exits 0 — a broken test source set
   could print "OK (0 tests)" and pass. Fixed: no pipe (-nowarn already
   covers warnings), plus a hard failure when zero test classes are
   discovered. Verified both directions: an injected broken test now exits 1;
   clean tree back to 173 green.
2. **Facade guards were silent.** "Never throws" is the contract, but a
   swallowed Throwable a partner can't see in logcat is undiagnosable-by-
   design. Every facade catch now logs `Log.w("EEGateway", …)`.
3. **Engine ctor did synchronous disk I/O** (queue restore) on the caller's
   thread — which is the host's main thread on a typical configure()+start().
   An ANR seed and a StrictMode red flag in every partner debug build, i.e.
   exactly the first-impression failure §6.3 warns about. Restore now runs on
   a one-shot daemon thread; persistence arms after restore completes, and
   packets heard in the gap persist on the next queue change (≤10 s). The
   same pattern in gateway-android's GatewayEngine is the app's business —
   the SDK holds a higher bar. PacketQueue.onChange became @Volatile for the
   cross-thread arm (documented delta from verbatim).
4. **Provisioning executor idled a thread until stop()** after its one-shot
   task finished. Now self-shuts-down when the task completes.
5. **Sample bricked on "approximate location"**: coarse-only grant satisfies
   the API 34 FGS location-type prerequisite, but the sample refused to start
   the service with a misleading message. Now fine-OR-coarse starts the FGS;
   scanning stays dormant (SDK in AWAITING_PERMISSIONS) until FINE +
   BLUETOOTH_SCAN arrive — the honest dormancy demo.

Reviewed and ACCEPTED as-is (documented where they live):

- ScanPolicy mode read from the heartbeat thread is unsynchronized — benign
  telemetry-only race, byte-identical to the shipped app (comment in Engine).
- Config-change engine rebuild resets process-lifetime counters and has a
  theoretical queue-persist race — rare path, noted in the C5 section.
- Token rotation to a rejected token while the old one is revoked leaves the
  phone dark until the next start — C6 section.
- Listener state flips ride the ≤1 s coalescing tick; host-initiated
  transitions (start/stop) notify immediately.
- DeviceRollup fully @Synchronized, Counters snapshot/restore atomic,
  BatchSender 413-split order, Backoff overflow clamp: all re-verified sound.

## C7: sample host app (minimal, per John 10/25)

New `:sample` module — a one-screen test harness, deliberately NOT a
product: paste a token (from the C8 org-API curl), Start/Stop, live status
via EEListener. 173 tests unchanged; the harness grew a stage.

- **Zero dependencies beyond `project(":sdk")`** — no androidx, no Compose,
  no resources (programmatic views, `android.R.drawable` notification icon).
  That's on purpose twice over: the module doubles as proof the SDK imposes
  no transitive baggage, and it keeps the whole app inside the stub-compile
  set. Plain `android.app.Activity`, `Notification.Builder` (API 26+ form).
- **New harness stage 1b:** the sample compiles as a SEPARATE kotlinc module
  against the SDK's class output — `internal` is invisible across that
  boundary, so this pass fails if the frozen public surface alone can't
  support a real integration. It passes: EEGateway/EEConfig/EEStatus/
  EEListener were enough, no surface additions needed.
- **The reference patterns the C10 guide will quote:**
  - Manifest: BLUETOOTH_SCAN *without* neverForLocation (honest location
    declaration — store review depends on it), FINE+COARSE location, legacy
    BT grants maxSdkVersion=30, FGS + FOREGROUND_SERVICE_LOCATION +
    _CONNECTED_DEVICE, POST_NOTIFICATIONS, `foregroundServiceType=
    "location|connectedDevice"` on the service.
  - GatewayService: configure() in onStartCommand (NOT only the Activity —
    a START_STICKY restart after process death reaches the service with no
    UI having run; configure is idempotent so the double call is free),
    startForeground with the runtime-qualified type subset (location always
    — the Activity gates service start on the fine-location grant, API 34
    throws otherwise; connectedDevice OR'd in only once BLUETOOTH_SCAN is
    granted, 34 enforces that too), EEGateway.stop() in onDestroy.
  - MainActivity: the host-owns-permissions flow. Fine location denied →
    no FGS (API 34 constraint), message shown. Scan denied but location
    granted → service starts and the SDK demos AWAITING_PERMISSIONS → grants
    later → RUNNING by itself.
- Sample token storage is plain SharedPreferences — it's the TEXT FIELD's
  persistence, not the token store; the SDK keeps its own verified copy in
  the Keystore-sealed TokenStore regardless.
- Gradle: `com.android.application` added to the version catalog;
  settings includes `:sample`. App targetSdk = 36 (the library module still
  deliberately sets none).

C8 runbook for John: org-API curl → one gateway token; Studio: run `:sample`
on the phone; paste token, Start, grant prompts; expect state RUNNING,
gateway id after the first heartbeat, forwarded counters climbing against
prod. Pass = heartbeats + accepted batches under your org, zero EE creds in
the APK (the token was pasted at runtime, never compiled in).

## C6: guardrails — the chunk that makes it an SDK

173 tests green. No public-surface change; api-freeze.md untouched (PAUSED_
REMOTE semantics were frozen in C1 and are now real).

**Token provisioning wired (C4's invariants go live).** Every engine start
runs a reconcile on a daemon thread: `GatewayToken` whose token differs from
the store → verify-before-persist; `SelfMint` with no stored token → mint
(deviceName ?: Build.MODEL). Transient outcomes retry through Backoff
(equal-jitter, 10 min cap — respects the 300/hr mint cap); a server REJECTION
PARKS until the next start(). Parking is deliberate: retrying can't change a
deliberate "no", and the partner's recovery path is configure(rotated token)
+ start, which re-runs the reconcile. New pure `ProvisionRunner` owns that
policy (6 tests). On success the provisioning thread fires one immediate
heartbeat so telemetry/`disable`/public id land seconds after the token, not
one 5-min cadence later. Until the server says yes, loops run against the
previous stored token or none — upload requeues, heartbeat goes Unauthorized,
both safe. Caveat on rotation: if the NEW token is rejected the OLD stored
token stays authoritative, including when the partner already revoked it —
the phone goes dark until the next start with a good token. Edge accepted;
the partner backend that rotated is the party that notices.

**disable → PAUSED_REMOTE.** Heartbeat 2xx with `"disable": true` (read on
the main looper with the rest of the lifecycle): scanner + GPS + upload loop
stop, permission poll parks, HEARTBEATS CONTINUE — they're the only way to
learn the flag cleared. Flag drops → upload + pipeline resume, grants
permitting. The flag survives stop()/start() in-process (server likely still
paused; the start-tick heartbeat re-learns either way) and resets on engine
rebuild. Facade precedence: PAUSED_REMOTE > RUNNING/AWAITING_PERMISSIONS;
start() while paused is a no-op (already started).

**gatewayPublicId.** Parsed from every heartbeat 2xx body (`gateway.
public_id` — verified against ee-web controller source; verify echoes it too
but heartbeat is the one refresh path we need). Malformed/absent → null:
identity is a nicety, never a failure. Flows to EEStatus.

**Crash-isolation sweep, boundary by boundary:**

- Framework → SDK: BleScanner.onScanResult/onScanFailed and LocationProvider
  .onLocationChanged now catch Throwable (BleScanner is no longer 2-line
  verbatim — the guards are deltas 3 and 4, documented here on purpose). An
  uncaught throw there unwinds into the host's process = the one incident
  that ends a partner deal (§6.3).
- Loop executors: UploadLoop/HeartbeatLoop wrap the callback invocation —
  an uncaught throw cancels scheduleWithFixedDelay PERMANENTLY, and the
  queue/delta bookkeeping is already committed first (2 tests pin it).
- Main-looper entries: every posted runnable in Engine rides a `guard {}`
  (swallow + `Log.w("EEGateway", …)` — the one android.util dependency,
  framework-only, still zero third-party deps). Facade's coalesced refresh
  post got its own guard; public methods keep their C5 catch-Throwable.

Still open for later chunks: C7 sample app is the next Neo chunk; C8 is
John's dogfood gate (Studio compile verifies EEGateway/Engine against real
android.jar — the stub compile only proves our usage is internally
consistent).

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

## C8/C10 disposition (11/7, John's call)

Android on-device dogfood SKIPPED: the iOS SDK passed first-device dogfood
against prod (gateway 408 — provisioning, heartbeats, forwarded batches),
the two SDKs share the wire contracts, fixtures, and server endpoints, and
the Android pipeline additionally has the stub-compile + 173-test harness
plus the shipped-app heritage. Accepted risk: the Android Darwin... i.e.
device-side integration (Studio build, FGS, real radio) is exercised by the
first real partner integration instead. C10 tagged 0.1.0; AAR publish to
GitHub Packages happens via RELEASING.md when a consumer exists.
