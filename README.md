# EE Gateway SDK (Android)

Turn your fleet's phones into silent Encrypted Energy gateways. Two lines:

```kotlin
EEGateway.configure(context, EEConfig.GatewayToken(token))  // token from your backend
EEGateway.start()                                           // from your existing service lifecycle
```

Next app update, every phone in your fleet scans for EE beacons and forwards
them in the background. No signup, no UI, no new permission prompts, and no EE
credential in your binary.

## Install

Private GitHub Packages. Add the coordinate:

```kotlin
dependencies {
    implementation("com.encryptedenergy:gateway-sdk:0.1.0")
}
```

The SDK pulls in zero runtime dependencies. It's the Kotlin stdlib your app
already ships and nothing else: no androidx, no Compose, no play-services.

## What it does

You provision one gateway per device from your own backend through the EE Org
API, then hand each phone its token over your own authenticated channel. The
SDK scans for BLE beacons, gates on a fresh location fix, queues packets, and
uploads them to EE. Coverage and earnings accrue to your org, and settlement
goes to your Lightning address. How you reward drivers is your business.

## The contracts

These are promises, not just behavior. They hold for good:

- It never throws. Every public call is crash-isolated, so bad input, a dead
  network, or a missing grant shows up in the status, never as an exception in
  your app.
- It never prompts for permissions. The SDK stays dormant until your app holds
  the scan and location grants. `start()` with grants missing lands in
  `AWAITING_PERMISSIONS`. It does not ask. Your app owns that UX.
- It runs no foreground service of its own. You drive the lifecycle from your
  service. The reference foreground service ships in the sample app.
- `configure()` is idempotent and holds only your application context, never an
  Activity, so it can't leak a screen.
- `start()` and `stop()` are idempotent and safe to call on the main thread.
- Listener callbacks arrive on the main thread, and a throwing listener can
  never break the SDK.
- There's a server-side kill switch. If EE disables your fleet, scanning and
  upload stop while heartbeats keep going, so you come back the instant it
  clears (`PAUSED_REMOTE`).
- The public API is frozen. It only grows, it never changes meaning, because we
  can't force your app to update. See [api-freeze.md](api-freeze.md).

## Status and callbacks

```kotlin
EEGateway.setListener { status -> log(status.state) }
val now = EEGateway.status()
```

## License

The SDK is dual-licensed: GPL-3.0 (see [LICENSE](LICENSE)), or a commercial
license for proprietary apps (see [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)).

## Integration guide

Foreground service patterns, the permission flow, iOS parity, and the battery
spec live in the integration guide. See the integration guide (coming soon).
