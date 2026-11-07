# Compliance Kit — disclosure, consent, and store forms

What to tell your users, what to tell the stores, and the wording for
both. This is the homework that kept BLE-relay SDKs alive in the stores
while location-harvesting SDKs got their hosts banned. Have your counsel
read it once; it's short.

## 1. User disclosure (required)

Google Play's User Data policy requires prominent disclosure and runtime
consent before an SDK collects location in the background. Apple's
Guideline 5.1.2 requires you to disclose third-party sharing and get
explicit permission. Both are satisfied by one screen shown before you
call `start()`.

Drop-in disclosure copy (edit the app name, keep the substance):

> **YourApp earns from idle radio time.**
>
> With your permission, YourApp relays nearby Bluetooth beacon packets
> from sensor networks (shipment tags, asset trackers) using the
> Bluetooth and location access you've already granted. Packets are
> encrypted by their owners; neither we nor our partner Encrypted
> Energy can read them. Your location is attached to each relayed
> packet so the network knows where coverage happened. It's never sold
> and never used for ads.
>
> This runs in the background, uses little battery, and you can turn it
> off any time in Settings.
>
> [Turn on and support YourApp]   [No thanks]

Rules:

- Show it before the first `start()`, not buried in a policy link.
- "No thanks" must work and must persist. Gate `start()` on the stored
  answer.
- Put a toggle in your settings screen that calls `stop()`.
- Add one line to your privacy policy naming Encrypted Energy, what's
  sent (beacon packets, location stamps, gateway heartbeats), and
  linking to https://encryptedenergy.com/legal.

## 2. Google Play Data safety form

Declare in the Data safety section (these answers assume the SDK is the
only thing in your app doing this; merge with your existing answers):

- **Location → Precise location**: Collected: YES. Shared: YES (with
  Encrypted Energy, which forwards to the upstream beacon network).
  Processed ephemerally: NO. Required or optional: OPTIONAL (users can
  decline the disclosure screen). Purposes: App functionality.
- **No advertising purposes anywhere.** The location is never used for
  ads or marketing, so don't tick those boxes.
- **Device or other IDs**: the SDK sends its own per-install gateway ID
  (not your user's identity, not the advertising ID). If you already
  declare Device IDs, nothing changes; if not, declare Device or other
  IDs: Collected, Shared, App functionality.
- **Data deletion**: disabling the toggle stops collection; gateway
  records can be disabled on request via contact@encryptedenergy.com.

Background location declaration: your app must already justify
`ACCESS_BACKGROUND_LOCATION` with a user-facing feature of your own.
The SDK must never be the reason for the permission; it only rides a
grant your features earned. If you don't hold background location, the
SDK still works in the foreground sessions you do have.

Manifest note: `BLUETOOTH_SCAN` must NOT assert
`android:usesPermissionFlags="neverForLocation"`, because relayed
packets carry location stamps and the declaration must match.

## 3. Apple App Privacy label (for the iOS SDK)

- **Location → Precise Location**: Collected. Used for App
  Functionality. Linked to the user: NO, if you provision gateway
  tokens without tying them to user identity (recommended); YES if your
  backend links gateway IDs to user accounts.
- **Identifiers**: the gateway ID is a per-install identifier. Declare
  under Identifiers → Device ID, App Functionality, same linkage answer
  as above.
- **Tracking**: NO. Nothing is used for cross-app advertising or shared
  with data brokers, so App Tracking Transparency is not triggered.

## 4. What you can say with a straight face

- The packet payload is encrypted by the device owner's key; the host
  app, the SDK, and Encrypted Energy cannot read it.
- Location stamps are used to pay bounties and build the coverage map.
  Encrypted Energy does not sell location data and does not share it
  for advertising. See https://encryptedenergy.com/legal.
- The SDK never prompts for permissions, never starts its own
  service, and a remote kill switch can pause every install without an
  app update.

If a store reviewer asks what the SDK does, the honest one-liner:
"It relays encrypted Bluetooth beacon packets from logistics sensor
networks, with user consent, and the app developer is paid for the
coverage."
