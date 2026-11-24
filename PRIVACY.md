# Z150X — Privacy Policy

Last updated: 24 November 2026

Z150X shows Android Auto from your phone on the dash of a Zontes Z150X scooter. It is made by
Mohamed Eid. Questions about this policy: [WhatsApp +20 127 526 2482](https://wa.me/201275262482)
or an issue on [github.com/Mohamed-Eid/z150x](https://github.com/Mohamed-Eid/z150x).

**In short:** the app has no account, no ads, no analytics and no tracking. The developer runs no
server and receives nothing from the app. What the app records stays on your phone unless you share
it yourself. A few features ask a public map or weather service for data; they are listed below and
can be switched off.

## What stays on your phone

| Data | Why | Where |
|---|---|---|
| The dash pairing code (the bike's Wi‑Fi name and password) | To connect to the dash again without scanning | App storage. Never backed up |
| Your rides: GPS track, distance, time, speed | The Rides list, the ride map, the odometer estimate | App storage. Only recorded while "Log rides" is on |
| Maintenance log, fuel log, odometer | Service reminders | App storage |
| Parked-bike spot and, if you take one, a photo | "Find bike" | App storage |
| Saved places (home, work) | Shortcuts to start navigation | App storage |
| Button mapping, screen fit and other settings | So you set them once | App storage |
| Support log (connection steps, error messages) | So a problem can be diagnosed | App storage, last 5 sessions. Never backed up |

The support log hides Wi‑Fi passwords, shortens the dash's serial numbers and hides the middle of
Bluetooth and Wi‑Fi hardware addresses. It leaves the phone only when you tap **Share log** and
choose where to send it.

Uninstalling the app, or clearing its storage in Android settings, deletes all of it.

## Android backup

If Android backup is on in your phone's settings, Android saves the app's settings, rides,
maintenance log, saved places and parked-bike spot to **your own Google account**, encrypted with
your screen lock, so a new phone has them. The app excludes the bike's Wi‑Fi password, the keyless
(PKE) code, the support logs and the map cache from that backup. Backup is a feature of Android; the
developer has no access to it. Turn it off in Android settings → Google → Backup.

## What is sent over the internet

| Service | What is sent | When | How to stop it |
|---|---|---|---|
| OpenStreetMap Nominatim (`nominatim.openstreetmap.org`) | The area where a ride started and ended, rounded to about 100 m, and the app language | After a ride is saved, to give it a name such as "Maadi → Zamalek" | Settings → turn off "Name my rides from the map" |
| Open-Meteo (`api.open-meteo.com`) | Your position rounded to about 1 km | At the start of a ride, at most every 45 minutes, for the rain alert | Settings → turn off the rain alert |
| OpenStreetMap tile server (`tile.openstreetmap.org`) | Which map squares you are looking at | Only while a ride map is open | Don't open the ride map |

Like any internet request, these also reveal your IP address to that service. The requests carry the
app's name and version, nothing that identifies you. These services have their own privacy policies:
[OpenStreetMap Foundation](https://osmfoundation.org/wiki/Privacy_Policy),
[Open-Meteo](https://open-meteo.com/en/terms).

Scanning the dash's QR code uses Google's ML Kit barcode scanner, which runs on the phone. The
picture is not uploaded. ML Kit may send Google anonymous usage and performance figures; see
[Google's ML Kit terms](https://developers.google.com/ml-kit/terms).

Nothing else is sent. In particular: no rides, no logs, no contacts, no audio and no video go to the
developer or to anyone else.

## The link to the dash and to Android Auto

- **Android Auto** is Google's app. Z150X talks to it only inside the phone (a local connection that
  never leaves the device) and passes its picture to the dash. What Android Auto itself collects is
  covered by [Google's privacy policy](https://policies.google.com/privacy).
- **The dash** is reached over the bike's own Wi‑Fi network. The picture, the time and button presses
  travel on that link. Nothing on it goes to the internet.

## Permissions

| Permission | What it is used for |
|---|---|
| Nearby devices / Wi‑Fi | Finding and joining the dash's Wi‑Fi |
| Location | Recording your rides, the parked-bike spot, the rain alert, speed-dependent volume. On Android 12 and older it is also what Android requires to see Wi‑Fi networks. Used only while the app or its ride notification is running |
| Bluetooth | Noticing that the bike was switched on, handlebar buttons, the keyless-box battery level (read only) |
| Microphone | Only when Android Auto asks for it, so the Google Assistant can hear you ("Hey Google" or the voice button). The sound goes to Android Auto on the phone and nowhere else; the app does not record or store it |
| Camera | Scanning the dash's QR code and taking the parked-bike photo |
| Notifications | The "connected" notification with its Stop button, and reminders |
| Display over other apps (optional) | Lets the app open Android Auto by itself when the bike is switched on while the phone is in your pocket |
| Run in the foreground | Keeps the dash picture running with the screen off |

Every permission can be refused or taken back in Android settings; the app says which feature stops
working.

## Children

The app is for licensed riders. It is not directed at children and collects nothing about them.

## Changes

If this policy changes, the new version is published at this address with a new date.

---

## بالعربي — ملخص

- التطبيق مفيهوش حساب ولا إعلانات ولا تتبّع، والمطوّر مش بيستقبل أي بيانات منه.
- اللي التطبيق بيسجّله (كود الشاشة، الرحلات، الصيانة، مكان الركنة، الإعدادات، سجل الدعم) بيفضل على
  موبايلك، ومش بيطلع غير لما إنت تشاركه بنفسك.
- تلات خدمات عامة بيتبعت لها بيانات، وتقدر تقفلها من الإعدادات:
  - Nominatim (OpenStreetMap): منطقة بداية ونهاية الرحلة بالتقريب، عشان يسمّي الرحلة.
  - Open-Meteo: مكانك بالتقريب، عشان تنبيه المطر.
  - خرايط OpenStreetMap: وإنت فاتح خريطة رحلة بس.
- المايك بيتفتح بس لما Android Auto يطلبه عشان مساعد جوجل يسمعك، والصوت مش بيتسجّل ولا بيتبعت لحد.
- النسخ الاحتياطي بتاع أندرويد (لو مفعّله) بيحفظ الإعدادات والرحلات والصيانة في حساب جوجل بتاعك إنت،
  ومش بيتضمّن باسورد Wi‑Fi الموتوسيكل ولا كود الـPKE ولا سجل الدعم.
- مسح التطبيق أو مسح بياناته من إعدادات أندرويد بيمسح كل حاجة.
