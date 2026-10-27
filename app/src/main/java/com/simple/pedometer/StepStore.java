package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

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

    public int getSteps(LocalDate date) {
        return prefs.getInt(DAY_PREFIX + date, 0);
    }

    public int getToday() {
        return getSteps(LocalDate.now());
    }
}
