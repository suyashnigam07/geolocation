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
 * last booted and keeps counting even when the app isn't running. The app
 * reads it now and then (when opened, once an hour, and while walking) and
 * adds the difference since the previous reading to the day's total.
 */
public class StepStore {

    public static final String PREFS = "steps";
    private static final String KEY_LAST_COUNTER = "last_counter";
    private static final String KEY_LAST_DATE = "last_date";
    private static final String DAY_PREFIX = "day_";
    /** Steps added by hand (walks without the phone), kept apart from counted steps. */
    private static final String MANUAL_PREFIX = "manual_";
    public static final int MAX_MANUAL_STEPS = 100_000;

    /** A minute with at least this many steps counts as an active minute. */
    public static final int ACTIVE_MINUTE_STEPS = 60;
    private static final String ACTIVE_PREFIX = "active_";
    private static final String ACTIVE_STEPS_PREFIX = "active_steps_";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_MINUTE_STEPS = "minute_steps";
    private static final String KEY_HOUR_MARK = "hour_mark";
    private static final String KEY_HOUR_MARK_DATE = "hour_mark_date";
    /** Walking time is measured per block of this many steps. */
    public static final int BLOCK_STEPS = 1000;
    /** Gaps between steps longer than this are pauses and aren't counted as walking time. */
    private static final long MAX_STEP_GAP_MILLIS = 5000;
    private static final String KEY_LAST_STEP = "last_step_ms";
    private static final String KEY_BLOCK_STEPS = "block_steps";
    private static final String KEY_BLOCK_MILLIS = "block_ms";
    private static final String KEY_LAST_BLOCK_MILLIS = "last_block_ms";
    private static final String BLOCK_BEST_PREFIX = "block_best_";
    private static final String BLOCK_COUNT_PREFIX = "block_count_";
    private static final String BLOCK_TOTAL_PREFIX = "block_total_";
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

        // Readings are at most about an hour apart. If midnight passed since the
        // previous one, those steps were (almost always) taken before midnight,
        // so they belong to the previous reading's day.
        String today = LocalDate.now().toString();
        String dayOfSteps = prefs.getString(KEY_LAST_DATE, today);
        String key = DAY_PREFIX + dayOfSteps;
        prefs.edit()
                .putLong(KEY_LAST_COUNTER, counter)
                .putString(KEY_LAST_DATE, today)
                .putInt(key, (int) (prefs.getInt(key, 0) + delta))
                .apply();
        return getSteps(LocalDate.now());
    }

    /**
     * Records a single step from the step detector while walking. The detector
     * reports each step with the time it happened, so it is used for active
     * minutes (the counter above can arrive late in batches).
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

        // Time to walk 1,000 steps: add up the time between steps, leaving out pauses.
        long lastStep = prefs.getLong(KEY_LAST_STEP, 0);
        long gap = wallMillis - lastStep;
        long blockMillis = prefs.getLong(KEY_BLOCK_MILLIS, 0);
        if (lastStep > 0 && gap > 0 && gap <= MAX_STEP_GAP_MILLIS) blockMillis += gap;
        int blockSteps = prefs.getInt(KEY_BLOCK_STEPS, 0) + 1;
        if (blockSteps >= BLOCK_STEPS) {
            String best = BLOCK_BEST_PREFIX + date;
            long previousBest = prefs.getLong(best, 0);
            if (previousBest == 0 || blockMillis < previousBest) edit.putLong(best, blockMillis);
            edit.putInt(BLOCK_COUNT_PREFIX + date, prefs.getInt(BLOCK_COUNT_PREFIX + date, 0) + 1);
            edit.putLong(BLOCK_TOTAL_PREFIX + date, prefs.getLong(BLOCK_TOTAL_PREFIX + date, 0) + blockMillis);
            edit.putLong(KEY_LAST_BLOCK_MILLIS, blockMillis);
            blockSteps = 0;
            blockMillis = 0;
        }
        edit.putLong(KEY_LAST_STEP, wallMillis)
                .putInt(KEY_BLOCK_STEPS, blockSteps)
                .putLong(KEY_BLOCK_MILLIS, blockMillis);

        edit.apply();
    }

    /** Walking time of the most recent 1,000 steps, or 0 if none yet. */
    public long getLastBlockMillis() {
        return prefs.getLong(KEY_LAST_BLOCK_MILLIS, 0);
    }

    /** Fastest 1,000 steps on the given day, or 0. */
    public long getBestBlockMillis(LocalDate date) {
        return prefs.getLong(BLOCK_BEST_PREFIX + date, 0);
    }

    /** Average walking time per 1,000 steps on the given day, or 0. */
    public long getAverageBlockMillis(LocalDate date) {
        int count = prefs.getInt(BLOCK_COUNT_PREFIX + date, 0);
        return count == 0 ? 0 : prefs.getLong(BLOCK_TOTAL_PREFIX + date, 0) / count;
    }

    /** Steps counted so far towards the next 1,000. */
    public int getCurrentBlockSteps() {
        return prefs.getInt(KEY_BLOCK_STEPS, 0);
    }

    public int getActiveMinutes(LocalDate date) {
        return prefs.getInt(ACTIVE_PREFIX + date, 0);
    }

    /** Steps taken during today's active minutes, used for walking speed. */
    public int getActiveSteps(LocalDate date) {
        return prefs.getInt(ACTIVE_STEPS_PREFIX + date, 0);
    }

    /**
     * Steps taken since the previous call (used by the hourly reminder), or -1
     * on the first call of the day.
     */
    public int takeStepsSinceLastHourMark() {
        String today = LocalDate.now().toString();
        // Only steps the phone counted: adding steps by hand mustn't hide a reminder.
        int total = prefs.getInt(DAY_PREFIX + today, 0);
        boolean sameDay = today.equals(prefs.getString(KEY_HOUR_MARK_DATE, ""));
        int steps = sameDay ? total - prefs.getInt(KEY_HOUR_MARK, 0) : -1;
        prefs.edit().putInt(KEY_HOUR_MARK, total).putString(KEY_HOUR_MARK_DATE, today).apply();
        return steps;
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

    /** Walking burns roughly 0.5 kcal per kg of body weight per km. */
    public int caloriesFor(int steps) {
        return (int) Math.round(steps * getStrideMeters() / 1000.0 * getWeightKg() * 0.5);
    }

    public boolean remindersEnabled() {
        return prefs.getBoolean(KEY_REMINDERS, true);
    }

    public void setRemindersEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REMINDERS, enabled).apply();
    }

    /** All steps for a day: counted by the phone plus any added by hand. */
    public int getSteps(LocalDate date) {
        return prefs.getInt(DAY_PREFIX + date, 0) + getManualSteps(date);
    }

    public int getManualSteps(LocalDate date) {
        return prefs.getInt(MANUAL_PREFIX + date, 0);
    }

    /** Adds steps walked without the phone to a day (up to {@link #MAX_MANUAL_STEPS} in total). */
    public void addManualSteps(LocalDate date, int steps) {
        int total = Math.max(0, Math.min(MAX_MANUAL_STEPS, getManualSteps(date) + steps));
        prefs.edit().putInt(MANUAL_PREFIX + date, total).apply();
    }

    public void clearManualSteps(LocalDate date) {
        prefs.edit().remove(MANUAL_PREFIX + date).apply();
    }

    /** Kilometres walked on a day, from steps and stride length. */
    public double distanceKm(LocalDate date) {
        return getSteps(date) * getStrideMeters() / 1000.0;
    }

    public int getToday() {
        return getSteps(LocalDate.now());
    }
}
