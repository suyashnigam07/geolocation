package com.simple.pedometer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Hourly alarm. Every hour it reads the step counter (so daily totals stay
 * right without a background service), and between 10:00 and 21:00 it
 * vibrates with a "time to move" notification if fewer than
 * {@link #MIN_STEPS} steps were taken in the past hour.
 */
public class MoveReminder extends BroadcastReceiver {

    public static final int MIN_STEPS = 250;
    private static final int FIRST_HOUR = 10;
    private static final int LAST_HOUR = 21;
    private static final String CHANNEL_ID = "move_reminder";
    private static final int NOTIFICATION_ID = 2;

    /** Sets the alarm for the next full hour (replacing any earlier one). */
    public static void schedule(Context context) {
        long next = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        context.getSystemService(AlarmManager.class)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, MoveReminder.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        schedule(context);
        PendingResult pending = goAsync();
        StepCounterReader.readOnce(context, () -> {
            remindIfInactive(context);
            pending.finish();
        });
    }

    private static void remindIfInactive(Context context) {
        StepStore store = new StepStore(context);
        int steps = store.takeStepsSinceLastHourMark();
        // Inexact alarms can fire a few minutes late, so round to the nearest hour.
        int hour = LocalDateTime.now().plusMinutes(30).getHour();
        if (!store.remindersEnabled() || hour < FIRST_HOUR || hour > LAST_HOUR) return;
        if (steps < 0 || steps >= MIN_STEPS) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Move reminders", NotificationManager.IMPORTANCE_DEFAULT);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
        PendingIntent openApp = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle("Time to move")
                .setContentText("Only " + steps + " steps in the last hour. A short walk helps!")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }
}
