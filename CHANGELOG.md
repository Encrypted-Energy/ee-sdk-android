# Changelog

## 0.2.0: 2026-11-07

Guest-courtesy and resilience release; frozen public API unchanged.

- RadioGate: the scanner parks below 20% battery on battery power
  (resumes at 25% or on charger) and after 5 minutes without a fresh
  fix: packets it can't stamp are wasted radio. Location keeps
  running while parked as the recovery signal; heartbeats report
  `scan_mode: "paused"`.
- Upload 429 cooldown: a rate-limited server backs the uploader off
  60 s, doubling to 15 min, reset on the first accepted batch.
- EidThrottle bounded at 4,096 entries (access-ordered LRU): a
  rotating-identifier flood can no longer grow the map without limit.
- New additive heartbeat field `host_app` (the host's package name)
  for per-app fleet grouping.
- 173 → 191 JVM tests.

## 0.1.1: 2026-11-07

- Relicensed from GPL-3.0 (dual) to Apache-2.0; COMMERCIAL-LICENSE.md
  removed. docs/COMPLIANCE.md added (disclosure copy, Play data-safety
  answers). Docs-only: no source changes vs 0.1.0.

## 0.1.0: 2026-11-07

First release, extracted from the shipped EE Gateway app for Android:
frozen public API (`EEGateway` / `EEConfig` / `EEStatus` / `EEState` /
`EEListener`), full scan-stamp-queue-upload pipeline with the
published ingest contract, token provisioning with
verify-before-persist, permission dormancy gate, remote kill switch,
adaptive scan policy, 3-minute heartbeats. 173-test JVM harness.
