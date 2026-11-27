# EE Gateway SDK for Android — Integration Guide

Turn the Android phones running your app into Encrypted Energy gateways.
Two lines from your existing service:

```kotlin
EEGateway.configure(context, EEConfig.GatewayToken(tokenFromYourBackend))
EEGateway.start()
```

No signup screens, no new permission prompts,
no EE credential in your binary. Your app keeps owning the user experience;
the SDK scans for sensor beacons, stamps them with a fresh GPS fix, and
forwards them. You get paid per unique device covered per day.

## Requirements

- minSdk 26, zero runtime dependencies (the AAR pulls in nothing but the
  Kotlin stdlib you already ship).
- Your app already holds the scan and location grants. The SDK never
  requests permissions and never starts a service of its own: your
  lifecycle drives it.
- A foreground service keeps scanning alive off-screen. If you already run
  one (tracker, fitness, automotive, and field-ops apps do), the 2 lines go
  there. If you don't, you add the reference service once:
  [Earning while the app is closed](#earning-while-the-app-is-closed)
  covers both paths.
- Manifest: the reference set lives in the sample app's manifest and is
  quoted here. BLUETOOTH_SCAN deliberately does NOT assert neverForLocation,
  because relayed packets carry location and the declaration must match:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- The host owns the foreground service; the SDK never starts one. -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<!-- API 33+: without it the FGS notification is hidden (service still runs). -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Your existing FGS should declare (or add) the `location|connectedDevice`
types; the sample's GatewayService shows the API-34-safe pattern.

## Install

The repo is public ([github.com/Encrypted-Energy/ee-sdk-android](https://github.com/Encrypted-Energy/ee-sdk-android)).
Two paths:

**Prebuilt AAR (works today, recommended)**: build once, drop it in.
The module has zero dependencies beyond the Kotlin stdlib, so it adds
nothing to your dependency tree.

```
git clone --branch 0.2.1 https://github.com/Encrypted-Energy/ee-sdk-android.git
cd ee-sdk-android && ./gradlew :sdk:assembleRelease
cp sdk/build/outputs/aar/sdk-release.aar YOUR_APP/app/libs/
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/sdk-release.aar"))
}
```

**Composite build (source dependency)**: clone the repo next to your
project (or as a submodule) and let Gradle substitute the coordinate.
The SDK builds with its own version catalog and AGP, so your project's
Gradle/AGP must be recent enough to load both (Gradle 8.9+ / AGP 8.6+
is safe):

```kotlin
// settings.gradle.kts
includeBuild("ee-sdk-android") {
    dependencySubstitution {
        substitute(module("com.encryptedenergy:gateway-sdk")).using(project(":sdk"))
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.encryptedenergy:gateway-sdk:0.2.1")
}
```

Do NOT `include(":sdk")` directly into your own settings: the module
resolves its plugins through this repo's version catalog, which your
build doesn't load.

**GitHub Packages (publishes with the first partner build)**: GitHub
requires a token with `read:packages` even for public packages:

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
implementation("com.encryptedenergy:gateway-sdk:0.2.1")
```

## Earning while the app is closed

Most coverage happens while the phone rides in a pocket: your app
backgrounded, screen off, or swiped away from recents. An integration
that only scans while your app is open leaves most of that on the table.

Sustained scanning needs your process alive off screen, and on modern
Android that means a foreground service. The SDK never starts one of its
own, so the service is yours.

### If you already run a foreground service

Put the 2 configure/start lines in it, declare the
`location|connectedDevice` service types on it, and confirm the 3
FOREGROUND_SERVICE* permissions quoted in [Requirements](#requirements)
are in your manifest. Done.

### If you don't

Copy the sample's
[`GatewayService`](../sample/src/main/java/com/encryptedenergy/sdk/sample/GatewayService.kt)
into your app and start it from your opt-in flow. It's about 80 lines:
a quiet persistent notification plus the API-34-safe `startForeground`
call, which declares `connectedDevice` only once `BLUETOOTH_SCAN` is
granted (API 34 throws if you declare a type you don't qualify for).

### Surviving swipe-away

2 habits inside the service keep scanning alive after the user swipes
your app from recents or Android reclaims the process:

- Return `START_STICKY` from `onStartCommand`, so Android relaunches the
  service after process death.
- Call `configure()` + `start()` in `onStartCommand`, not only in your
  Activity. The sticky relaunch reaches that line with no UI having run,
  and `configure()` is idempotent, so the double call on a normal launch
  costs nothing.

The sample's `GatewayService` shows both. The packet queue and the token
both survive process death (the queue is bounded on disk, the token sits
Keystore-encrypted), so a relaunched service resumes where the old one
stopped.

### You probably don't need ACCESS_BACKGROUND_LOCATION

A foreground service with the `location` type keeps GPS fixes flowing
under the normal while-in-use grant, even with your app backgrounded.
That covers the SDK completely.

`ACCESS_BACKGROUND_LOCATION` only matters if you collect location with
no foreground service running (geofencing callbacks, periodic jobs).
Requesting it adds real Play review weight: a declaration form and a
video of the user-facing feature that needs it. If the service above
covers you, skip the permission.

### What OEM battery savers do to your numbers

Stock Android honors a foreground service. Many OEM builds don't:
Xiaomi, Oppo, Vivo, Realme, and Transsion (Infinix, Tecno) ship battery
managers that kill foreground services of backgrounded apps unless the
user exempts the app, and some Samsung builds "put to sleep" apps unused
for a few days. These devices dominate South Asia, Southeast Asia,
Africa, and Latin America.

If your installs skew to those devices and your backgrounded yield looks
low, this is the usual cause. The fix is user-granted: prompt with
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or walk the user to
unrestricted battery for your app. [dontkillmyapp.com](https://dontkillmyapp.com)
documents the per-OEM steps. We flag it here so a quiet fleet reads as
what it is: killed services, and a per-device setting that revives them.

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

API-created gateways default to `"email_alerts": false`: fleet installs
go quiet every shift end, and nobody wants a thousand outage emails. Pass
`"email_alerts": true` on create to opt a specific gateway in.

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
- **Battery is a contract, not a hope.** Heartbeats every 3 minutes.
  Scanning adapts down when the phone sits still off-charger, parks below
  20% battery (resumes at 25% or on charger), and parks when the fix
  stream dies for 5 minutes (packets it can't stamp are wasted radio);
  heartbeats report `scan_mode` full/reduced/paused so your fleet view
  stays accurate. Packets queue locally (bounded, survives process death)
  and upload in batches on a 10 second cadence, oldest-first after an
  outage, and a rate-limited server backs the uploader off 60 s doubling
  to 15 min instead of being hammered.
- **There's a fleet kill switch.** One `gateways_paused` flag on your org
  (we flip it on request; self-serve toggle coming) parks every SDK phone
  within one heartbeat; heartbeats keep flowing so un-pausing takes effect
  just as fast. No app release needed.

## What you can read

`EEGateway.status()` / the listener give you: lifecycle state
(`UNCONFIGURED / READY / AWAITING_PERMISSIONS / RUNNING / PAUSED_REMOTE /
STOPPED`), pings heard, packets forwarded, local queue depth, the gateway's
public id, and the last successful heartbeat time. Fleet-side, every phone
reports as `worker_version: ee-sdk-android/<version>` so you and EE ops can
tell SDK traffic from first-party apps at a glance.

## Sample app

`sample/` in this repo is a one-screen host app: token paste, Start/Stop,
live status, and the reference FGS + permission flow (including the
dormancy demo when a grant is missing).

---

*iOS counterpart: `Encrypted-Energy/ee-sdk-ios` (first-device dogfood
passed 2026-11-07; the two SDKs share wire contracts and test fixtures).*
