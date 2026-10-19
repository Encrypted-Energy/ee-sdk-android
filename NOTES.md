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
