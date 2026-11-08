# EE Gateway SDK for Android

Your app already holds Bluetooth and location permissions. This SDK
borrows the idle radio: it hears encrypted beacon packets from
logistics sensor networks, stamps where they were heard, and relays
them up. Every device your installs hear first in a day earns your
org a bounty, paid in Bitcoin over Lightning. No ads, no data sale,
and nothing changes for your users beyond one opt-in screen you
control.

```kotlin
// from the foreground service your app already runs
EEGateway.configure(context, EEConfig.GatewayToken(tokenFromYourBackend))
EEGateway.start()
```

That's the whole integration.

## Requirements

- minSdk 26. Zero runtime dependencies (Kotlin stdlib only: no
  androidx, no play-services).
- Your app already holds `BLUETOOTH_SCAN` and fine/coarse location
  grants, and runs a foreground service (tracker, fitness, automotive,
  and field-ops apps do). The SDK never requests permissions and never
  starts a service of its own: without the grants it parks in
  `AWAITING_PERMISSIONS` and starts itself the moment your permission
  flow completes.
- The reference manifest and foreground-service pattern ship in
  [`sample/`](sample/).

## Quickstart

1. **Create an org** at [encryptedenergy.com](https://encryptedenergy.com/sign-up)
   and mint an org API key (`ee_org_…`) under Settings → API Keys.
2. **Provision one gateway per install** from your backend: the org
   key never ships in your binary:

   ```
   POST https://encryptedenergy.com/api/v1/org/gateways
   Authorization: Bearer ee_org_...
   { "name": "install-8f3a" }
   → { "gateway": { ... }, "token": { "key": "ee_live_..." } }
   ```

3. **Install the SDK.** Build the AAR once and drop it in:

   ```
   git clone --branch 0.2.0 https://github.com/Encrypted-Energy/ee-sdk-android.git
   cd ee-sdk-android && ./gradlew :sdk:assembleRelease
   cp sdk/build/outputs/aar/sdk-release.aar YOUR_APP/app/libs/
   ```

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/sdk-release.aar"))
   }
   ```

   (Prefer a source dependency? [docs/INTEGRATION.md](docs/INTEGRATION.md)
   has the composite-build setup. The
   `com.encryptedenergy:gateway-sdk` coordinate publishes to GitHub
   Packages with the first partner build.)
4. **Add the two lines** above in your foreground service, handing
   each device its `ee_live_…` token over your own authenticated
   channel.
5. **Confirm it's alive.** Within 3 minutes your
   [dashboard](https://encryptedenergy.com/settings/org/gateways)
   shows the install's first heartbeat ("Seen seconds ago" plus a GPS
   status). That heartbeat means the integration works.

A word on what you'll see next: the gateway stays **"Pending first
packet"** until it hears a real beacon, and at a desk that can take a
while: beacon fleets cluster around warehouses, delivery routes, and
logistics corridors, not office parks. Heartbeats flowing = done;
packets follow wherever your users actually go. Take the sample for a
drive if you want to see one land.

Kicking the tires first? `EEConfig.SelfMint()` lets a device mint its
own identity with no backend work: demo mode, not the production
path.

## Try the sample

[`sample/`](sample/) is a one-screen host app: the permission flow,
both identity modes, Start/Stop, and live status. Open this repo in
Android Studio, run the `sample` configuration, and paste a token.

## The contract

These are promises, not just behavior (frozen in
[api-freeze.md](api-freeze.md): the public API only grows, it never
changes meaning):

- **It never throws.** Bad input, a dead network, a missing grant:
  all surface in status, never as an exception in your app.
- **It never prompts.** Your app owns the permission UX; the SDK only
  waits for it.
- **Your lifecycle drives it.** No services, no background modes of
  its own.
- **Battery is a contract.** Scanning adapts down when the phone sits
  still off-charger, parks below 20% battery and when the fix stream
  dies (packets it can't stamp are wasted radio), and resumes on its
  own. A rate-limited server backs the uploader off instead of being
  hammered.
- **There's a fleet kill switch.** One flag parks every install within
  one heartbeat, no app update needed; heartbeats keep flowing so
  un-pausing is just as fast.
- **Packets queue through process death** (bounded at 1,000) and
  upload oldest-first when the network returns.

## Watching it run

```kotlin
EEGateway.setListener { status -> log(status.state) }   // main thread, coalesced
val now = EEGateway.status()
```

Fleet-side, every install heartbeats as `ee-sdk-android/<version>`
with packet counters and a devices-seen rollup, so your dashboard and
EE ops see the same truth.

## Store compliance

Google Play requires prominent disclosure and consent before an SDK
collects location in the background.
[docs/COMPLIANCE.md](docs/COMPLIANCE.md) has the drop-in disclosure
copy, the data-safety form answers, and the rules that keep review
clean. Treat it as part of the integration, not paperwork.

## Docs

| Doc | What's in it |
|---|---|
| [docs/INTEGRATION.md](docs/INTEGRATION.md) | Full guide: manifest, identity modes, provisioning, the contract in detail |
| [docs/COMPLIANCE.md](docs/COMPLIANCE.md) | Disclosure copy + Play data-safety answers |
| [api-freeze.md](api-freeze.md) | The frozen public surface |
| [NOTES.md](NOTES.md) | Engineering decisions and platform deltas |
| [RELEASING.md](RELEASING.md) | Maintainer release flow |

## Economics

1 sat per unique device heard first per UTC day, pooled across your
org, settled daily over Lightning once the pool clears 1,000 sats.
Founding-partner terms are a conversation:
[encryptedenergy.com/sdk](https://encryptedenergy.com/sdk) or
contact@encryptedenergy.com.

## License

Apache-2.0 (see [LICENSE](LICENSE)). Use it in proprietary apps, no
strings attached.

iOS counterpart: [`Encrypted-Energy/ee-sdk-ios`](https://github.com/Encrypted-Energy/ee-sdk-ios):
same wire contracts, same test fixtures.
