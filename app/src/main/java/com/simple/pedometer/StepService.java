package com.simple.pedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Runs only while you are walking (started and stopped by {@link WalkDetector}).
 * It records active minutes from the step detector and shows today's steps in
 * a notification. It stops itself after a few minutes without steps.
 */
public class StepService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "steps";
    private static final int NOTIFICATION_ID = 1;
    /** Stop when no step has been detected for this long. */
    private static final long IDLE_STOP_MILLIS = 3 * 60_000;
    /** Update the notification at most this often, to save battery. */
    private static final long NOTIFICATION_INTERVAL_MILLIS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private StepStore store;
    private boolean listening;
    private long lastStepMillis;
    private long lastNotificationMillis;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.elapsedRealtime() - lastStepMillis >= IDLE_STOP_MILLIS) {
                stopSelf();
            } else {
                handler.postDelayed(this, 60_000);
            }
        }
    };

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, StepService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, StepService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new StepStore(this);
        sensorManager = getSystemService(SensorManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Walking", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(store.getToday());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        lastStepMillis = SystemClock.elapsedRealtime();

        if (!listening) {
            Sensor counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (counter == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL);
            // The step detector gives each step with its time, for active minutes.
            // The wake-up version keeps working with the screen off; events are
            // batched for up to a minute to save battery.
            Sensor detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true);
            if (detector == null) detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            if (detector != null) {
                sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000);
            }
            listening = true;
            handler.postDelayed(idleCheck, 60_000);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            lastStepMillis = SystemClock.elapsedRealtime();
            // Event time is in the elapsed-realtime clock; convert to wall-clock time.
            long ageMillis = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000;
            store.onStepDetected(System.currentTimeMillis() - ageMillis);
            return;
        }
        int today = store.onCounterReading((long) event.values[0]);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationMillis >= NOTIFICATION_INTERVAL_MILLIS) {
            lastNotificationMillis = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(today));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private Notification buildNotification(int steps) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle(steps + " steps today")
                .setContentText("Walking – counting active minutes")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        // A walk just ended: share the new total with friends.
        Friends.upload(this);
        handler.removeCallbacks(idleCheck);
        sensorManager.unregisterListener(this);
        listening = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
