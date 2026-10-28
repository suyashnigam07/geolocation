package com.simple.pedometer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses Google's low-power activity recognition to find out when you start and
 * stop walking (or running), and starts {@link StepService} only for that time.
 */
public class WalkDetector extends BroadcastReceiver {

    private static final int[] ACTIVITIES = {DetectedActivity.WALKING, DetectedActivity.RUNNING};

    /** Asks Google Play services to tell us about walking. Safe to call repeatedly. */
    static void register(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        List<ActivityTransition> transitions = new ArrayList<>();
        for (int activity : ACTIVITIES) {
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build());
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build());
        }
        try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
                    new ActivityTransitionRequest(transitions), pendingIntent(context));
        } catch (SecurityException e) {
            // Permission was revoked; we'll try again next time the app opens.
        }
    }

    private static android.app.PendingIntent pendingIntent(Context context) {
        // Must be mutable: Play services adds the walking events to it.
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? android.app.PendingIntent.FLAG_MUTABLE : 0);
        return android.app.PendingIntent.getBroadcast(context, 1,
                new Intent(context, WalkDetector.class), flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return;
        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null || result.getTransitionEvents().isEmpty()) return;

        // The latest event tells us whether you're walking now.
        List<ActivityTransitionEvent> events = result.getTransitionEvents();
        ActivityTransitionEvent latest = events.get(events.size() - 1);
        if (latest.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            try {
                StepService.start(context);
            } catch (IllegalStateException e) {
                // Android refused to start the service in the background
                // (ForegroundServiceStartNotAllowedException); the hourly
                // reading still keeps the step total right.
            }
        } else {
            StepService.stop(context);
        }
    }
}
