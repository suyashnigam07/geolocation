package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Saves heart rhythm checks (newest first, up to {@link #MAX_RECORDS}). */
public class RhythmStore {

    private static final int MAX_RECORDS = 30;
    private static final String KEY = "records";

    private final SharedPreferences prefs;

    public RhythmStore(Context context) {
        prefs = context.getSharedPreferences("rhythm", Context.MODE_PRIVATE);
    }

    public List<RhythmRecord> getRecords() {
        List<RhythmRecord> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                records.add(RhythmRecord.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Corrupt data: start again with an empty list.
        }
        return records;
    }

    public void add(RhythmRecord record) {
        List<RhythmRecord> records = getRecords();
        records.add(0, record);
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < Math.min(records.size(), MAX_RECORDS); i++) {
                array.put(records.get(i).toJson());
            }
        } catch (JSONException e) {
            return;
        }
        // commit() so the result screen opened right after can read it.
        prefs.edit().putString(KEY, array.toString()).commit();
    }
}
