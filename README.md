# EE Gateway SDK for Android

Your app already holds Bluetooth and location permissions. This SDK
borrows the idle radio: it hears encrypted beacon packets from
logistics sensor networks, stamps where they were heard, and relays
them up. Every device your installs hear first in a day earns your
org a bounty, paid in Bitcoin over Lightning. No ads, no data sale.

```kotlin
// from the foreground service your app already runs
EEGateway.configure(context, EEConfig.GatewayToken(tokenFromYourBackend))
EEGateway.start()
```

That's the whole integration.

## What actually changes

For your users:

- **No new permission prompts.** The SDK uses the grants your app
  already holds and never asks for anything itself.
- **One opt-in screen, shown by your app, before the first scan.**
  Your users granted Bluetooth and location for your features.
  Relaying beacon data to a third party is a new use of those grants,
  and Google Play requires prominent disclosure and consent before an
  app does that. The opt-in screen is that consent, and it's what
  keeps your app on the right side of store review. We ship the exact
  copy in [docs/COMPLIANCE.md](docs/COMPLIANCE.md). Users who decline
  keep using your app exactly as before; you never call `start()` for
  them.
- **A small, managed battery cost.** Scanning adapts down when the
  phone sits still, parks below 20% battery or when GPS has no fix,
  and resumes on its own.
- **Nothing readable leaves the phone.** Packet payloads are encrypted
  by the sensor owner's key; neither you nor we can read them. The
  location stamps price bounties and draw the coverage map, and
  they're never sold.

For you:

1. Drop in the AAR and add the two lines above.
2. Show the disclosure screen (our copy, restyled however you like)
   and call `start()` only for users who said yes.
3. Copy the data-safety answers from
   [docs/COMPLIANCE.md](docs/COMPLIANCE.md) into your Play listing.
4. Provision one token per install from your backend
   ([Quickstart](#quickstart) below).
5. Get paid: 1 sat per unique device heard first per UTC day, pooled
   across your org, settled daily over Lightning.

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
4. **Add the two lines** above in your foreground service, behind
   your opt-in screen, handing each device its `ee_live_…` token over
   your own authenticated channel.
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

## What this SDK will never do to your app

You're letting our code live inside the app you built. These six
promises are frozen in [api-freeze.md](api-freeze.md): the public API
only grows, it never changes meaning.

- **It can't crash your app.** Bad input, a dead network, a missing
  grant: all of it surfaces in `status()`, never as an exception.
- **Your users never see a prompt from us.** You own the permission
  UX; the SDK waits behind it.
- **It runs only where your app already runs.** No services, no
  background modes of its own; your foreground service drives it and
  it stops when your app stops.
- **It protects the battery before it earns.** Scanning adapts down
  when the phone sits still off-charger, parks below 20% battery or
  when GPS has no fix (packets it can't stamp are wasted radio), then
  resumes on its own. A rate-limited server backs the uploader off
  instead of being hammered.
- **You can pause every install with one switch.** One flag on your
  org parks the whole fleet within a heartbeat, no app update needed.
  Flip it back and they resume just as fast.
- **It doesn't lose packets when Android kills the process.** The
  queue (bounded at 1,000) persists and uploads oldest-first when the
  network returns.

## Watching it run

```kotlin
EEGateway.setListener { status -> log(status.state) }   // main thread, coalesced
val now = EEGateway.status()
```

Fleet-side, every install heartbeats as `ee-sdk-android/<version>`
with packet counters and a devices-seen rollup, so your dashboard and
EE ops see the same truth.

## Store compliance

Google Play requires prominent disclosure and consent before granted
permissions are used for a new purpose, and relaying beacon data is a
new purpose. [docs/COMPLIANCE.md](docs/COMPLIANCE.md) has the drop-in
disclosure copy, the data-safety form answers, and the rules that keep
review clean. Treat it as part of the integration, not paperwork.

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
