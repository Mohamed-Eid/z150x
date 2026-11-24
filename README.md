# Z150X

**Wireless Android Auto on the Zontes Z150X dash.** Google Maps, Waze, music, calls and the
Assistant on the scooter's 5" screen, over Wi‑Fi, from an Android phone. No root, no PC, no account.

Made for Z150X riders in Egypt 🇪🇬: Arabic (Egyptian) and English, switchable in the app.

## Status

- Android Auto runs on a real Z150X dash (first test, Nov 2026). The dash draws its own top and
  bottom bars over the picture; the dash safe area (Settings) keeps Android Auto's buttons clear of
  them and is being tuned on the bike.
- Every screen tested on the Android emulator in Arabic and English.
- Still to confirm on the bike: the dash's model ID and screen size, whether ▲ / ▼ reach the
  phone, the full-screen map layout.

## What the app does

- **Connect** — projects Android Auto to the dash (the round ring on Home). It can also connect by
  itself when the bike's Wi‑Fi / Bluetooth appears.
- **Controls** — the dash is not a touch screen, so Android Auto runs in focus mode. Steer it with
  handlebar buttons (if they reach the phone over Bluetooth), a Bluetooth helmet remote, the
  on-screen pad in the app, or "Hey Google".
- **Screen fit** — adjust the picture to the Z150X panel.
- **Find bike** — parking spot (optionally with a photo) and walking directions.
- **Service** — maintenance reminders (oil, filters, belt…), fuel log in EGP.
- **Rides** — automatic ride log while Android Auto is on the dash.
- **Dash clock sync**, speed-dependent volume, keyless (PKE) battery read-out (read only).

Left out because a Z150X rider in Egypt doesn't need them: the app's own map /
GPX navigation, mirror mode, trip videos, trophies / year in review, Spain-only fuel prices, traffic
lights, live location sharing, passenger mode, the Wear OS app, telemetry, self-update and
donations, crash detection and its SMS alert, Moto Morini / QJ and CFMoto unlock transports,
14 European languages.

Privacy: no account, no ads, no analytics; see [PRIVACY.md](PRIVACY.md).

## Install

1. Download the APK:
   - `Z150X-<version>-arm64.apk` — almost every phone.
   - `Z150X-<version>-armv7.apk` — only old 32-bit phones.
2. Allow installing from the app you opened it with (Files, WhatsApp…).
3. Install **Android Auto** from Google Play if the phone doesn't have it.
4. Open Z150X and follow the 4 steps:
   1. Language — Arabic / English.
   2. Permissions — nearby devices, location, camera, notifications.
   3. **Android Auto developer switch** (one time): Android Auto settings → tap **Version** 10
      times → ⋮ → **Developer settings** → turn on **Unknown sources**, then ⋮ →
      **Start head unit server**.
   4. Scan the dash: turn the key, **hold ▲** until the pairing QR shows, scan it.

**After every phone restart or Android Auto update**, open Android Auto → ⋮ →
**Start head unit server** again. Android Auto doesn't let other apps start it; Home shows a
banner when it's needed.

## Build

Requirements: JDK 21, Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleGithubDebug -PslimApk=false        # debug, all ABIs
./gradlew :app:testGithubDebugUnitTest                    # unit tests
./gradlew :app:lintGithubDebug                            # lint (must pass: CI runs it)
./gradlew :app:assembleGithubRelease -PappVersion=1.7 -PversionSuffix=            # release APK, arm64
./gradlew :app:assembleGithubRelease -PappVersion=1.7 -PversionSuffix= -Pabi=armeabi-v7a
./gradlew :app:bundlePlayRelease -PappVersion=1.7 -PversionSuffix=                # Google Play bundle
```

The APK lands in `app/build/outputs/apk/github/release/`, the Play bundle in
`app/build/outputs/bundle/playRelease/`.

`versionCode` comes from the version: `major*10000 + minor*100 + patch` (1.7 → 10700,
1.7.2 → 10702). Raise the version for every release; Google Play refuses a bundle whose
`versionCode` it has already seen (`-PversionCode=N` overrides it). See
[docs/PLAY-STORE.md](docs/PLAY-STORE.md) for the Play checklist.

### Release signing

Release builds are signed with the Z150X key from `keystore.properties` at the repo root:

```properties
storeFile=keystore/z150x-release.jks
storePassword=…
keyAlias=z150x
keyPassword=…
```

`keystore/`, `keystore.properties`, `*.jks` and `dist/` are git-ignored. **Back up the keystore and
its password**: Android only installs an update signed with the same key.

Without `keystore.properties` a release build is **unsigned** (it won't install);
`-PallowDebugSigning=true` signs it with the local debug key for a test. CI only compiles, tests and
lints: releases are built on the machine that holds the key.

## Security

Review done on 2026-11-01. Fixed:

| Issue | Risk | Fix |
|---|---|---|
| The Android Auto receiver (port 5288) listened on every network interface, announced itself over mDNS (`_aawireless._tcp`) and accepted any address | High — someone on the same Wi‑Fi could take the session, push a picture to the dash and open the phone's microphone through it | Listens on `127.0.0.1` only, no mDNS, non-local connections refused (`aa/AaReceiver.kt`) |
| Home (`MainActivity`) was exported | High — any app could open it with the reconnect flag and stop Android Auto (and navigation) mid-ride | `android:exported="false"`; the app reaches it through its own splash, widgets and notifications |
| The keyless (PKE) code was included in Android backups | Medium — it's the password to the scooter's keyless box over Bluetooth | `pke_box.xml` excluded from cloud backup and device transfer |
| The exported Bluetooth trigger acted on any "device connected" broadcast | Low — a fake broadcast could start a connection | Only devices paired with the phone count |
| Release builds silently fell back to the debug key | Low | Unsigned unless `-PallowDebugSigning=true` |
| File sharing covered the whole private `files/` folder | Low | Only the folders the app shares on purpose |

Second review, 2026-11-24. Fixed:

| Issue | Risk | Fix |
|---|---|---|
| Any app on the phone could play Android Auto on the loopback link and ask for the microphone | High — the rider's voice through this app's microphone permission | The certificate Android Auto presents is pinned on first use; a different one (without an Android Auto update) gets no microphone until the rider allows it (`aa/AaPeerTrust.kt`) |
| The dash link's three ports accepted any device on the dash's Wi‑Fi | Medium — it could pull the Android Auto picture or send touches | Only the address the app invited with its probe may connect back (`EasyConnProber.kt`) |
| A frame header from the dash link could ask for a 2 GB buffer | Medium — one packet crashed the app | Frames are limited to 1 MB (`PxcFrame.kt`) |
| After the bike's Wi‑Fi dropped, the probe could go out over mobile data or the home Wi‑Fi | Low — the pairing probe sent to an unrelated network | No probing unless the socket is on the bike's network |
| A leftover file held another person's CFMoto token and AES key | Low | Deleted (`BleSecrets.kt`) |
| Support logs named every paired Bluetooth device and showed full hardware addresses | Low — privacy | Names of other devices are not logged; addresses are masked (`DC:0D:30:••:••:66`) |
| Cloud backup included support logs and the map tile cache | Low — privacy, and the cache could push the backup over Android's 25 MB limit | Both excluded |
| `KILL_BACKGROUND_PROCESSES` and the CFMoto app query | Low — permissions the app does not need | Removed |

Already in place: the bike's Wi‑Fi password and hotspot password are excluded from backups; the settings export leaves out passwords; logs mask passwords; no WebView, no shell
commands, no plain-HTTP internet traffic; all PendingIntents are immutable; Android Auto broadcasts
are addressed to Android Auto only; release builds are shrunk with R8.

Known and accepted:
- The Android Auto link ships a head-unit key shared by every copy of the app, and it cannot
  verify Android Auto's certificate against Google's root (the app does not have it) — true of every
  head-unit app of this kind. The receiver is on loopback only, so nothing outside the phone can
  reach it, and the certificate is pinned on first use for the microphone.
- The dash link (Carbit / EasyConnect) has no authentication of its own; it runs only on the
  bike's Wi‑Fi.

## Roadmap / to improve

1. **Real-bike test** — Z150X dash model ID and panel size (add it to `KnownDashes` in
   `BikeProfile.kt`), whether ▲ / ▼ reach the phone, connection paths touched by the cleanup.
2. **Service plan** — the intervals come from the Zontes 125X manual; check them against the
   Z150X service booklet.
3. **Google Play** — see [docs/PLAY-STORE.md](docs/PLAY-STORE.md): the app ID to publish under,
   the store listing, the permission declarations and the closed test.
4. **Code structure** — `MainActivity` is ~2,000 lines; move the connection logic into its own
   class.
5. **Design** — handlebar-button test screen and ride summary screen (designed, not built), light
   "sun mode", a simpler Settings with an Advanced section, a scooter outline that matches the
   real Z150X.
6. **Head unit server** — optional accessibility helper that starts it from Connect (GitHub APK
   only; Google Play doesn't allow it), or a reminder notification after a phone restart.
