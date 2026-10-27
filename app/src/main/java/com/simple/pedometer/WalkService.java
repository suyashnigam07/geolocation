package com.simple.pedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service that records a walk with GPS, so recording continues
 * with the screen off or while another app is open.
 */
public class WalkService extends Service implements LocationListener {

    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String CHANNEL_ID = "walk";
    private static final int NOTIFICATION_ID = 3;
    /** Walks shorter than this are discarded (probably started by mistake). */
    private static final long MIN_DURATION_MILLIS = 60_000;

    private LocationManager locationManager;
    private StepStore stepStore;

    public static void start(Context context) {
        context.startForegroundService(
                new Intent(context, WalkService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, WalkService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = getSystemService(LocationManager.class);
        stepStore = new StepStore(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Walk recording", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            finishWalk();
            return START_NOT_STICKY;
        }
        if (WalkSession.isRecording()) return START_NOT_STICKY;

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this,
                    Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        WalkSession.begin(stepStore.getToday());
        return START_NOT_STICKY;
    }

    private void finishWalk() {
        locationManager.removeUpdates(this);
        Walk walk = WalkSession.end(stepStore.getToday());
        if (walk != null && walk.durationMillis >= MIN_DURATION_MILLIS) {
            new WalkStore(this).add(walk);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onLocationChanged(Location location) {
        WalkSession.onLocation(location);
        WalkSession.updateSteps(stepStore.getToday());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    // Required on Android 10 and older, where these methods have no default.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    private Notification buildNotification() {
        Walk walk = WalkSession.current;
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, WalkActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle("Recording walk")
                .setContentText(walk == null ? "Waiting for GPS…"
                        : Walk.distance(walk.distanceMeters) + " · " + walk.steps + " steps")
                .setUsesChronometer(true)
                .setWhen(walk == null ? System.currentTimeMillis() : walk.startMillis)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        locationManager.removeUpdates(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
