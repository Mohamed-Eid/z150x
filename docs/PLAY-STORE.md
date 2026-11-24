# Z150X on Google Play — checklist

Status on 24 November 2026: the code side is ready (the bundle builds, lint has no errors, the
permissions are down to what the app uses) and the source repository is public. What is left is the
release key (see below) and the Play Console work, by the account owner.

## Build the bundle

```bash
./gradlew :app:bundlePlayRelease -PappVersion=1.7 -PversionSuffix=
```

Output: `app/build/outputs/bundle/playRelease/app-play-release.aab`, signed with the Z150X key from
`keystore.properties`. `versionCode` comes from the version (1.7 → 10700); raise the version for
every upload.

**The key must be in place first.** `keystore.properties` and `keystore/z150x-release.jks` are
git-ignored, so a fresh clone has neither and the bundle comes out **unsigned**; Play refuses an
unsigned bundle. Copy both files into the checkout before building, and confirm with
`jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab`.

Checked on the 1.7 bundle:

| Play requirement | State |
|---|---|
| Target API level 35 or newer | targetSdk 36 |
| App bundle (AAB), not APK | yes |
| 64-bit code | arm64-v8a and x86_64 included |
| 16 KB memory page support (64-bit libraries) | all four 64-bit libraries aligned to 16 KB |
| Signed with a release key | Z150X key (it becomes the *upload key*) |
| No restricted permissions | no SMS, call log, background location, all-files, install-packages, accessibility or query-all-packages |

## Decide before the first upload (cannot be changed later)

**The application ID.** The `play` flavor is set to `com.z150x.app.play`; the sideloaded APK is
`com.z150x.app`. Google Play never lets an app change its ID.

- Keep `com.z150x.app.play`: the Play app installs *next to* the sideloaded one. Riders who have the
  APK move by hand (settings export / import in Advanced settings; rides are not exported).
- Use `com.z150x.app` for Play too: one app. A rider with the sideloaded APK can update from Play
  **only if** Play signs with the same key, which means choosing "use your own key" when enrolling
  in Play App Signing and uploading `keystore/z150x-release.jks`. Otherwise they must uninstall first.

To switch, delete the `applicationId = "com.z150x.app.play"` line in `app/build.gradle.kts`.

## Before submitting

1. **Source and privacy policy reachable — done.** The app's About screen links to
   `github.com/Mohamed-Eid/z150x` (licence and source, required by the AGPL) and to `PRIVACY.md` in
   it. The repository was made public on 24 Nov 2026 and both links open without signing in. Use
   `https://github.com/Mohamed-Eid/z150x/blob/main/PRIVACY.md` as the privacy policy URL in the
   store listing. If the repository ever goes private again, both links break.
2. **Play Console developer account** (25 USD, identity check). A personal account opened after
   November 2023 must run a **closed test with at least 12 testers for 14 days** before it can apply
   for production.
3. **Store listing**: texts below; icon 512×512; feature graphic 1024×500; at least 2 phone
   screenshots (4–8 is better) in Arabic and English. These images still have to be made.
4. **App content** forms: see the answers below.
5. **App access**: the reviewer cannot connect without a Zontes Z150X. Say so in "App access →
   instructions" and attach a short video of the app connecting on the bike, so the review does not
   stop at "the app does nothing".

## Risks to know about

- **How the app talks to Android Auto.** It acts as an Android Auto head unit using the widely
  shared head-unit key that this whole family of apps uses (`res/raw/aa_privkey`), and it needs
  Android Auto's developer option "Start head unit server". Other head-unit apps have been on Google
  Play for years, but Google has not approved this, and it can reject or later remove the app for it.
  Keep the sideloaded APK as a second channel.
- **The TLS check on that link.** Google Play blocks apps whose `X509TrustManager` accepts every
  certificate. The app's one (`aa/AaPeerTrustManager.kt`) now refuses a peer with no certificate or
  with the head unit's own, and lint's `TrustAllX509TrustManager` check passes. It still cannot
  validate Android Auto's chain (Google's Automotive Link root is not public), so identity rests on
  the key pinned on first use (`aa/AaPeerTrust.kt`). Whether Play's own scanner accepts this shows
  only in the pre-launch report of the first internal-test upload: read it before going further.
- **Names.** "Zontes" and "Android Auto" are other companies' trademarks. Do not put either in the
  app *title* or the icon. The app's own name, Z150X, is also the maker's model name: if the maker
  objects, the name and the store title have to change. In the description, describe compatibility ("for the Zontes Z150X dash",
  "works with Android Auto") and keep the "not affiliated" line.
- **The scooter drawing** in the icon and the splash is traced from a photo of the Z150X. It is your
  own drawing, but a trademark complaint from the maker is possible.

## Store listing

**Title (30):** `Z150X — Dash Link`

**Short description (80)**
- EN: `Wireless Android Auto on the Zontes Z150X dash. Arabic and English.`
- AR: `أندرويد أوتو لاسلكي على شاشة Zontes Z150X. عربي وإنجليزي.`

**Full description — EN**

```
Z150X puts Android Auto from your phone on the dash of the Zontes Z150X scooter: Google Maps, Waze,
music and calls on the scooter's screen, over Wi‑Fi, with no cable.

• One-tap Connect, and automatic connect when you switch the bike on
• Works with the phone in your pocket and the screen off
• Control Android Auto from a Bluetooth handlebar remote or the on-screen pad (the dash is not a
  touch screen)
• Screen fit and safe area for the Z150X panel
• Ride log with a map, maintenance reminders, fuel log in EGP
• Find your parked bike, with an optional photo
• Ride duas (أدعية الركوب)
• Arabic (Egyptian) and English
• No account, no ads, no tracking

You need: a Zontes Z150X with the factory dash, Android 10 or newer, and the Android Auto app. Setup
takes a few minutes and is explained step by step in the app; it includes switching on a developer
option inside Android Auto.

Z150X is an independent project. It is not affiliated with, endorsed by or sponsored by Zontes or
Google. Android Auto is a trademark of Google LLC. Set your route before you ride.

Free and open source (AGPL-3.0): github.com/Mohamed-Eid/z150x
```

**Full description — AR**

```
Z150X بيعرض أندرويد أوتو من موبايلك على شاشة سكوتر Zontes Z150X: خرايط جوجل وWaze والأغاني والمكالمات
على شاشة الموتوسيكل، عن طريق الـWi‑Fi ومن غير سلك.

• توصيل بضغطة واحدة، وتوصيل تلقائي لما تشغّل الموتوسيكل
• بيشتغل والموبايل في جيبك والشاشة مقفولة
• اتحكم في أندرويد أوتو بريموت بلوتوث على المقود أو من لوحة التحكم في التطبيق (الشاشة مش تاتش)
• ضبط الصورة على مقاس شاشة الـZ150X
• سجل الرحلات بالخريطة، تنبيهات الصيانة، وسجل البنزين بالجنيه
• اعرف ركنت فين، ومعاها صورة لو حبيت
• أدعية الركوب
• عربي (مصري) وإنجليزي
• من غير حساب، من غير إعلانات، من غير تتبّع

محتاج: Zontes Z150X بشاشته الأصلية، أندرويد 10 أو أحدث، وتطبيق Android Auto. الإعداد بياخد دقايق
ومشروح خطوة بخطوة جوه التطبيق، ومن ضمنه تفعيل خيار للمطورين جوه Android Auto.

Z150X مشروع مستقل، مالوش علاقة بـZontes ولا Google ومش معتمد منهم. Android Auto علامة تجارية لـGoogle
LLC. حدد طريقك قبل ما تتحرك.

مجاني ومفتوح المصدر (AGPL-3.0): github.com/Mohamed-Eid/z150x
```

**Category:** Auto & Vehicles · **Tags:** navigation, motorcycle · **Contains ads:** no ·
**In-app purchases:** no

## App content answers

**Privacy policy:** the public URL of `PRIVACY.md`.

**Ads:** no. **Target audience:** 18 and over. **News app / COVID / government / financial:** no.

**Content rating questionnaire:** utility app, no user-generated content, no sharing of location
with other users, no violence, no purchases → the lowest rating.

**Data safety** (what leaves the phone; see `PRIVACY.md`):

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes (location only) |
| Is data encrypted in transit? | Yes (HTTPS) |
| Can users ask for data to be deleted? | Nothing is kept by the developer; uninstalling removes everything |
| Location → Precise location | Collected: yes. Shared: yes (OpenStreetMap Nominatim gets a ride's start and end rounded to about 100 m, for ride names; Open-Meteo gets the position rounded to about 1 km, for the rain alert). Processed ephemerally: yes. Required: no (both can be switched off). Purpose: app functionality |
| Location → Approximate location | Not collected separately (nothing coarser than the two requests above is sent) |
| Audio (microphone) | Not collected (passed to Android Auto on the phone; never stored or sent by the app) |
| Photos | Not collected (the parked-bike photo stays on the phone) |
| App activity, diagnostics, device IDs, contacts, messages, files, financial, health | Not collected |

Why "precise": Google counts a position as approximate only when it is no finer than an area of
3 km². Both requests are finer than that (about 100 m, and a square of about 1 km), so they go under
Precise location even though they are rounded. The full GPS track of a ride never leaves the phone.

The support log leaves the phone only when the rider shares it by hand, so it is not "collected".

**Foreground service types** (Play asks for a description and a short video of each):

| Type | What to write |
|---|---|
| `connectedDevice` | "Keeps the wireless link to the scooter's dashboard (Wi‑Fi and Bluetooth) while the rider is riding with the phone in a pocket and the screen off." |
| `location` | "Records the ride the rider started (distance, route) and adjusts media volume with speed, while projecting to the dashboard. Starts only after the rider connects; shows a notification with a Stop button." |
| `microphone` | "Passes the rider's voice to Android Auto's Google Assistant when the rider starts a voice command, so a destination can be set hands-free. Audio is not recorded or stored." |

**Sensitive permissions**

| Permission | Declaration |
|---|---|
| Location (foreground only; no background location permission) | Core feature: ride log and parked-bike spot. The onboarding permissions step says what it is used for and what leaves the phone, before the system dialog |
| `SYSTEM_ALERT_WINDOW` (display over other apps) | Optional. Lets the app open Android Auto when the scooter is switched on while the phone is locked in a pocket. Asked for with an explanation of what it is for; the app works without it |
| `RECORD_AUDIO` | See the microphone row above |
| `CAMERA` | Scanning the dashboard's pairing QR code and the parked-bike photo |

## After the first upload

- Enrol in **Play App Signing** (see the application-ID decision above for which key).
- Internal test first (instant), then the closed test, then production.
- Every later upload needs a higher version (`-PappVersion=`).
