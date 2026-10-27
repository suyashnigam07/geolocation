package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the most recent pulse measurement. */
public class PulseStore {

    private static final String KEY_BPM = "bpm";
    private static final String KEY_TIME = "time";

    private final SharedPreferences prefs;

    public PulseStore(Context context) {
        prefs = context.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    public void save(int bpm) {
        prefs.edit()
                .putInt(KEY_BPM, bpm)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply();
    }

    /** @return the last measured beats per minute, or -1 if never measured. */
    public int getBpm() {
        return prefs.getInt(KEY_BPM, -1);
    }

    public long getTime() {
        return prefs.getLong(KEY_TIME, 0);
    }
}
