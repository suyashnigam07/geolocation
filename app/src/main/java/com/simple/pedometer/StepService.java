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
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Foreground service that listens to the step counter sensor all the time and
 * shows today's steps in a permanent notification.
 */
public class StepService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "steps";
    private static final int NOTIFICATION_ID = 1;

    private SensorManager sensorManager;
    private StepStore store;
    private boolean listening;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, StepService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new StepStore(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Step counter", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(store.getToday());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!listening) {
            Sensor stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (stepCounter == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);

            // The step detector gives each step with its time, for active
            // minutes and hourly totals. The wake-up version keeps working while
            // the screen is off; events are batched for up to a minute to save battery.
            Sensor detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true);
            if (detector == null) {
                detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            }
            if (detector != null) {
                sensorManager.registerListener(this, detector,
                        SensorManager.SENSOR_DELAY_NORMAL, 60_000_000);
            }
            listening = true;
        }
        MoveReminder.schedule(this);
        // Ask Android to restart the service if it ever gets killed.
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            // Event time is in the elapsed-realtime clock; convert to wall-clock time.
            long ageMillis = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000;
            store.onStepDetected(System.currentTimeMillis() - ageMillis);
            return;
        }
        int today = store.onCounterReading((long) event.values[0]);
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(today));
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
                .setContentText("Pedometer is counting your steps")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        listening = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
