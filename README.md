# Simple Pedometer (Android)

A tiny Android app that counts your daily steps using the phone's built-in
step counter sensor. It runs in the background on its own and shows today's
steps in a notification.

## Features

- Today's step count, in big numbers
- Progress ring towards a 10,000 step daily goal
- Bar chart of the last 7 days with total, average and best day
- Distance, calories and walking speed (based on your height and weight)
- Active minutes (minutes with 60+ steps) towards a 30 minute daily goal
- Hourly "time to move" reminder (10:00-21:00, under 250 steps in the past hour)
- Heart rhythm check (60 s, camera): heart rate, HRV, regular/irregular estimate,
  pulse trace and beat-to-beat chart. Not an ECG and not a diagnosis.
- Walk recording with GPS: distance, duration, pace, steps and route outline, with history
- Light and dark mode
- Pulse rate: tap the heart, cover the back camera and flash with your fingertip
  for ~20 seconds (an estimate, not a medical device)
- Runs in the background all the time (foreground service with a small notification)
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

## Street map for walks (Google Maps API key)

Without a key, walks are drawn as a route outline. To show them on a Google street map:

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project
   and link a billing account (the Maps SDK for Android itself is free to use).
2. Enable **Maps SDK for Android** (APIs & Services → Library).
3. Create an API key (APIs & Services → Credentials → Create credentials → API key) and restrict it:
   - Application restrictions → **Android apps** → add package `com.simple.pedometer`
     with SHA-1 `5E:B3:79:BD:12:0F:0B:59:83:BD:34:F3:17:0B:41:68:9F:07:F0:CC`
   - API restrictions → **Maps SDK for Android** only
4. In this GitHub repository: Settings → Secrets and variables → Actions →
   **New repository secret**, name `MAPS_API_KEY`, value = your key.
5. Rebuild: Actions → Build APK → **Run workflow** (or push any change), then install the new APK.

For a local build, put `MAPS_API_KEY=your-key` in `~/.gradle/gradle.properties`.

## Build it yourself

Requires the Android SDK (or Android Studio).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push to GitHub also builds the APK automatically (see
`.github/workflows/build-apk.yml`).

## How it works

- `StepService` – foreground service that listens to `Sensor.TYPE_STEP_COUNTER`
  and updates the notification.
- `StepStore` – saves steps per day. The sensor gives "steps since boot", so the
  app adds the difference between readings to today's total (handles reboots).
- `PulseAnalysis` / `RhythmActivity` – finds beats in the camera pulse signal, drops
  noisy ones by comparing each beat's shape, and measures beat-to-beat variation.
- `WalkService` / `WalkActivity` – records a walk with GPS in a foreground service
  (keeps going with the screen off) and lists past walks.
- `MoveReminder` – hourly alarm that vibrates if you've been inactive.
- `BootReceiver` – restarts the service after a reboot or app update.
- `MainActivity` – asks for permissions and shows the counts.
- `PulseActivity` – measures the pulse from the camera: with the flash on, each
  heartbeat slightly darkens the fingertip; the app counts those dips.
- `StepRingView` / `BarChartView` / `HeartView` – small custom views for the ring and the weekly chart.

Counting starts when you first open the app; steps from before that are not included.
