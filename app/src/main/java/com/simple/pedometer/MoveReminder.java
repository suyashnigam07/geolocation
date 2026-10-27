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
 * Hourly "time to move" reminder. An alarm fires at the top of every hour;
 * between 10:00 and 21:00 it checks the steps taken in the hour that just
 * ended and, if there were fewer than {@link #MIN_STEPS}, vibrates with a
 * notification.
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

        StepStore store = new StepStore(context);
        // Inexact alarms can fire a few minutes late, so round to the nearest hour.
        int hour = LocalDateTime.now().plusMinutes(30).getHour();
        if (!store.remindersEnabled() || hour < FIRST_HOUR || hour > LAST_HOUR) return;

        int steps = store.getHourSteps(hour - 1);
        if (steps >= MIN_STEPS) return;

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
