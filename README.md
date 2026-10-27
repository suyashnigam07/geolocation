# Simple Pedometer (Android)

A tiny Android app that counts your daily steps using the phone's built-in
step counter sensor. It runs in the background on its own and shows today's
steps in a notification.

## Features

- Today's step count, in big numbers
- Progress ring towards a 10,000 step daily goal
- Bar chart of the last 7 days with total, average and best day
- Light and dark mode
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
- `BootReceiver` – restarts the service after a reboot or app update.
- `MainActivity` – asks for permissions and shows the counts.
- `StepRingView` / `BarChartView` – small custom views for the ring and the weekly chart.

Counting starts when you first open the app; steps from before that are not included.
