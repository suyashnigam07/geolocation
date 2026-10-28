# Simple Pedometer (Android)

A tiny Android app that counts your daily steps using the phone's built-in
step counter sensor. It is battery friendly: the phone's step chip counts on
its own, the app reads it when opened and once an hour, and only runs (with a
notification) while you are walking.

## Features

- Today's step count, in big numbers
- Progress ring towards a 10,000 step daily goal
- Bar chart of the last 7 days with total, average and best day
- Calories burned next to the step ring
- Distance and walking speed (based on your height and weight)
- Active minutes (minutes with 60+ steps) towards a 30 minute daily goal
- Hourly "time to move" reminder (10:00-21:00, under 250 steps in the past hour)
- Heart rhythm check (60 s, camera): heart rate, HRV, regular/irregular estimate,
  pulse trace and beat-to-beat chart. Not an ECG and not a diagnosis.
- Friends: share steps with up to 3 friends and see everyone ranked on the main screen
  (mutual friend codes; pulse and heart data are never shared)
- 1-to-1 video calls with friends (WebRTC; rings only while the app is open)
- Light and dark mode
- Pulse rate: tap the heart, cover the back camera and flash with your fingertip
  for ~20 seconds (an estimate, not a medical device)
- Runs only while you walk (Google activity recognition starts and stops it)
- Starts again by itself after the phone restarts
- Very low battery use: the step counting is done by the phone's hardware sensor
- No internet, no account, no ads. Data stays on the phone.

## Install on your phone

1. On your phone, open this repository on GitHub and go to **Releases → latest**
   (or **Actions → Build APK → latest run → Pedometer-apk**).
2. Download **Pedometer.apk** and open it.
3. If Android asks, allow "Install unknown apps" for your browser / file manager.
4. Open **Pedometer** and tap **Allow** for:
   - **Physical activity** (needed to read the step sensor)
   - **Notifications** (shows today's steps)
5. Tap **Allow running in background** and choose **Allow**, so Android
   doesn't stop the app to save battery.

That's it. You never need to open the app again; it keeps counting. Open it any
time to see today's steps and the last 7 days.

> Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) have extra battery savers.
> If counting stops, set the app's battery setting to **Unrestricted** / enable
> **Autostart** in the phone's Settings → Apps → Pedometer.

## Friends sharing (Firebase)

Friends sharing uses the Firebase project **freindspedometer**: Google Sign-In
(Firebase Authentication) and Cloud Firestore (Standard edition, asia-south1).

- `app/google-services.json` – config of the registered Android app
  `com.simple.pedometer` (not a secret; the security rules protect the data).
- `firestore.rules` – who can read/write what. Deploy changes with
  `npx -y firebase-tools@latest deploy --only firestore`.
- `firebase.json` – Firestore and Google Sign-In settings
  (`npx -y firebase-tools@latest deploy --only auth`).

Video calls use WebRTC directly between the phones, with Firestore (`calls`
collection) carrying the call setup and Google's public STUN server. There is no
relay (TURN) server yet, so a few mobile networks can't connect.

Google Sign-In on Android only works for APKs signed with a key whose SHA-1 is
registered on the Firebase Android app (the committed `app/debug.keystore` is).
Signing in with the same Google account on a new phone brings back your friend
code and friends.

## Build it yourself

Requires the Android SDK (or Android Studio).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push to GitHub also builds the APK automatically (see
`.github/workflows/build-apk.yml`).

## How it works

- `WalkDetector` – Google activity recognition; starts `StepService` when you start
  walking and stops it when you stop.
- `StepService` – runs only during walks: records active minutes from the step
  detector and shows today's steps in a notification.
- `MoveReminder` – hourly alarm that reads the step counter and reminds you to move.
- `StepStore` – saves steps per day. The sensor gives "steps since boot", so the
  app adds the difference between readings to today's total (handles reboots).
- `PulseAnalysis` / `RhythmActivity` – finds beats in the camera pulse signal, drops
  noisy ones by comparing each beat's shape, and measures beat-to-beat variation.
- `BootReceiver` – re-enables walk detection and the hourly reading after a reboot or update.
- `MainActivity` – asks for permissions and shows the counts.
- `PulseActivity` – measures the pulse from the camera: with the flash on, each
  heartbeat slightly darkens the fingertip; the app counts those dips.
- `StepRingView` / `BarChartView` / `HeartView` – small custom views for the ring and the weekly chart.

Counting starts when you first open the app; steps from before that are not included.
