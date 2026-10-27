package com.simple.pedometer;

import android.location.Location;

/**
 * The walk currently being recorded. Shared between {@link WalkService}, which
 * fills it from GPS, and the screens that display it. Only used on the main thread.
 */
final class WalkSession {

    /** GPS fixes less accurate than this (in metres) are ignored. */
    private static final float MAX_ACCURACY = 25f;
    /** Ignore movements smaller than this, which are usually GPS jitter. */
    private static final float MIN_MOVE = 5f;

    static Walk current;
    private static Location last;
    private static int startSteps;

    private WalkSession() {
    }

    static boolean isRecording() {
        return current != null;
    }

    static void begin(int stepsSoFarToday) {
        current = new Walk();
        current.startMillis = System.currentTimeMillis();
        startSteps = stepsSoFarToday;
        last = null;
    }

    static void onLocation(Location location) {
        if (current == null || !location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY) {
            return;
        }
        if (last != null) {
            float moved = last.distanceTo(location);
            if (moved < MIN_MOVE) return;
            current.distanceMeters += moved;
        }
        last = location;
        current.route.add(new double[]{location.getLatitude(), location.getLongitude()});
    }

    static void updateSteps(int stepsToday) {
        if (current != null) current.steps = Math.max(0, stepsToday - startSteps);
    }

    /** Ends the walk and returns it with its final duration. */
    static Walk end(int stepsToday) {
        Walk walk = current;
        if (walk != null) {
            updateSteps(stepsToday);
            walk.durationMillis = System.currentTimeMillis() - walk.startMillis;
        }
        current = null;
        last = null;
        return walk;
    }

    static long elapsedMillis() {
        return current == null ? 0 : System.currentTimeMillis() - current.startMillis;
    }
}
