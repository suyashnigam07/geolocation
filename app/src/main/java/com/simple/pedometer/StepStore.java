package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Keeps the daily step totals in SharedPreferences.
 *
 * The hardware step counter reports the total number of steps since the phone
 * last booted. We remember the last value we saw and add the difference to
 * today's total, so steps are never lost even if the app was killed for a while
 * (the sensor keeps counting in hardware).
 */
public class StepStore {

    public static final String PREFS = "steps";
    private static final String KEY_LAST_COUNTER = "last_counter";
    private static final String DAY_PREFIX = "day_";

    /** A minute with at least this many steps counts as an active minute. */
    public static final int ACTIVE_MINUTE_STEPS = 60;
    private static final String ACTIVE_PREFIX = "active_";
    private static final String ACTIVE_STEPS_PREFIX = "active_steps_";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_MINUTE_STEPS = "minute_steps";
    private static final String KEY_HOURS_DATE = "hours_date";
    private static final String HOUR_PREFIX = "hour_";
    private static final String KEY_HEIGHT = "height_cm";
    private static final String KEY_WEIGHT = "weight_kg";
    public static final String KEY_REMINDERS = "reminders";

    private final SharedPreferences prefs;

    public StepStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    /** Records a new reading from the step counter sensor and returns today's total. */
    public synchronized int onCounterReading(long counter) {
        long last = prefs.getLong(KEY_LAST_COUNTER, -1);
        long delta;
        if (last < 0) {
            delta = 0;          // First reading ever: start counting from now.
        } else if (counter < last) {
            delta = counter;    // Phone rebooted, sensor restarted from zero.
        } else {
            delta = counter - last;
        }

        String todayKey = DAY_PREFIX + LocalDate.now();
        int today = (int) (prefs.getInt(todayKey, 0) + delta);
        prefs.edit()
                .putLong(KEY_LAST_COUNTER, counter)
                .putInt(todayKey, today)
                .apply();
        return today;
    }

    /**
     * Records a single step from the step detector. The detector reports each
     * step with the time it happened, so it is used for per-minute and
     * per-hour figures (the counter above can arrive late in batches).
     */
    public synchronized void onStepDetected(long wallMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(wallMillis), ZoneId.systemDefault());
        LocalDate date = time.toLocalDate();
        long minute = wallMillis / 60_000;
        SharedPreferences.Editor edit = prefs.edit();

        // Active minutes.
        int minuteSteps = prefs.getLong(KEY_MINUTE, -1) == minute
                ? prefs.getInt(KEY_MINUTE_STEPS, 0) + 1 : 1;
        edit.putLong(KEY_MINUTE, minute).putInt(KEY_MINUTE_STEPS, minuteSteps);
        String activeKey = ACTIVE_PREFIX + date;
        String activeStepsKey = ACTIVE_STEPS_PREFIX + date;
        if (minuteSteps == ACTIVE_MINUTE_STEPS) {
            edit.putInt(activeKey, prefs.getInt(activeKey, 0) + 1);
            edit.putInt(activeStepsKey, prefs.getInt(activeStepsKey, 0) + minuteSteps);
        } else if (minuteSteps > ACTIVE_MINUTE_STEPS) {
            edit.putInt(activeStepsKey, prefs.getInt(activeStepsKey, 0) + 1);
        }

        // Steps per hour of today, used by the move reminder.
        boolean newDay = !date.toString().equals(prefs.getString(KEY_HOURS_DATE, ""));
        if (newDay) {
            for (int h = 0; h < 24; h++) edit.remove(HOUR_PREFIX + h);
            edit.putString(KEY_HOURS_DATE, date.toString());
        }
        String hourKey = HOUR_PREFIX + time.getHour();
        edit.putInt(hourKey, (newDay ? 0 : prefs.getInt(hourKey, 0)) + 1);
        edit.apply();
    }

    public int getActiveMinutes(LocalDate date) {
        return prefs.getInt(ACTIVE_PREFIX + date, 0);
    }

    /** Steps taken during today's active minutes, used for walking speed. */
    public int getActiveSteps(LocalDate date) {
        return prefs.getInt(ACTIVE_STEPS_PREFIX + date, 0);
    }

    /** Steps detected in the given hour (0-23) of today. */
    public int getHourSteps(int hour) {
        if (!LocalDate.now().toString().equals(prefs.getString(KEY_HOURS_DATE, ""))) return 0;
        return prefs.getInt(HOUR_PREFIX + hour, 0);
    }

    public int getHeightCm() {
        return prefs.getInt(KEY_HEIGHT, 170);
    }

    public int getWeightKg() {
        return prefs.getInt(KEY_WEIGHT, 70);
    }

    public void setProfile(int heightCm, int weightKg) {
        prefs.edit().putInt(KEY_HEIGHT, heightCm).putInt(KEY_WEIGHT, weightKg).apply();
    }

    /** Typical walking stride is about 41.5% of body height. */
    public double getStrideMeters() {
        return getHeightCm() * 0.415 / 100.0;
    }

    public boolean remindersEnabled() {
        return prefs.getBoolean(KEY_REMINDERS, true);
    }

    public void setRemindersEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REMINDERS, enabled).apply();
    }

    public int getSteps(LocalDate date) {
        return prefs.getInt(DAY_PREFIX + date, 0);
    }

    public int getToday() {
        return getSteps(LocalDate.now());
    }
}
