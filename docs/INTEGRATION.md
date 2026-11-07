# EE Gateway SDK for Android — Integration Guide (draft)

Turn your fleet's Android phones into Encrypted Energy gateways. Two lines
from your existing service:

```kotlin
EEGateway.configure(context, EEConfig.GatewayToken(tokenFromYourBackend))
EEGateway.start()
```

That's the whole integration. No signup screens, no new permission prompts,
no EE credential in your binary. Your app keeps owning the user experience;
the SDK scans for sensor beacons, stamps them with a fresh GPS fix, and
forwards them. You get paid per unique device covered per day.

## Requirements

- minSdk 26, zero runtime dependencies (the AAR pulls in nothing but the
  Kotlin stdlib you already ship).
- Your app already holds the scan and location grants and runs a foreground
  service (fleet apps do). The SDK never requests permissions and never
  starts a service of its own: your lifecycle drives it.
- Manifest: the reference set lives in the sample app's manifest and is
  quoted here. BLUETOOTH_SCAN deliberately does NOT assert neverForLocation,
  because relayed packets carry location and we declare that honestly:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
```

Your existing FGS should declare (or add) the `location|connectedDevice`
types; the sample's GatewayService shows the API-34-safe pattern.

## Install (private phase: GitHub Packages)

```kotlin
// settings.gradle.kts
maven {
    url = uri("https://maven.pkg.github.com/encrypted-energy/ee-sdk-android")
    credentials {
        username = providers.gradleProperty("gpr.user").get()
        password = providers.gradleProperty("gpr.token").get()  // read:packages PAT
    }
}

// app/build.gradle.kts
implementation("com.encryptedenergy:gateway-sdk:0.1.0")
```

## Identity: two modes

**GatewayToken (production).** Your backend provisions one gateway per
device through the EE Org API and hands each device its `ee_live_` token
over your own authenticated channel:

```
POST /api/v1/org/gateways
Authorization: Bearer <your org API key>
{ "name": "van-042" }
→ { "gateway": { ... }, "token": { "key": "ee_live_..." } }
```

The org key stays on your servers. The device token is proven against the
server before it's ever persisted (Keystore-encrypted at rest), so a
revoked or mistyped token can't poison a phone. Rotation: hand the device a
new token, `configure` + `start` again.

**SelfMint (demo).** `EEConfig.SelfMint()` and the device creates its own
identity on first run. Great for kicking the tires; not the production path.

## The contract

- **The SDK never throws and never prompts.** `start()` without the grants
  parks in `AWAITING_PERMISSIONS` and advances to `RUNNING` by itself the
  moment your permission flow completes; a mid-run revocation returns it to
  the same dormant state.
- **Status is observable, not polled.** `EEGateway.setListener { status ->
  ... }` delivers on the main thread, crash-isolated, coalesced to about
  once per second no matter how dense the beacon traffic gets.
- **Battery is a contract, not a hope.** Heartbeats every 5 minutes.
  Scanning adapts down when the phone sits still off-charger. Packets queue
  locally (bounded, survives process death) and upload in batches on a
  10 second cadence, oldest-first after an outage.
- **The fleet kill switch is yours.** Flip `gateways_paused` on your org
  and every SDK phone parks within one heartbeat; heartbeats keep flowing
  so un-pausing takes effect just as fast. No app release needed.

## What you can read

`EEGateway.status()` / the listener give you: lifecycle state
(`UNCONFIGURED / READY / AWAITING_PERMISSIONS / RUNNING / PAUSED_REMOTE /
STOPPED`), pings heard, packets forwarded, local queue depth, the gateway's
public id, and the last successful heartbeat time. Fleet-side, every phone
reports as `worker_version: ee-sdk-android/<version>` so you and EE ops can
tell SDK traffic from first-party apps at a glance.

## Sample app

`sample/` in this repo is a one-screen host app: token paste, Start/Stop,
live status, and the reference FGS + permission flow (including the honest
dormancy demo when a grant is missing).

---

*Draft status: ships as C10 after C8 dogfood. iOS counterpart:
`Encrypted-Energy/ee-sdk-ios`.*
