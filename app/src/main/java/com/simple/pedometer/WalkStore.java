package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Saves recorded walks (newest first, up to {@link #MAX_WALKS}). */
public class WalkStore {

    private static final int MAX_WALKS = 50;
    /** Routes are thinned to at most this many points to keep storage small. */
    private static final int MAX_POINTS = 400;
    private static final String KEY_WALKS = "walks";

    private final SharedPreferences prefs;

    public WalkStore(Context context) {
        prefs = context.getSharedPreferences("walks", Context.MODE_PRIVATE);
    }

    public List<Walk> getWalks() {
        List<Walk> walks = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_WALKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                walks.add(Walk.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Corrupt data: start again with an empty list.
        }
        return walks;
    }

    public void add(Walk walk) {
        if (walk.route.size() > MAX_POINTS) {
            List<double[]> thinned = new ArrayList<>();
            double step = (walk.route.size() - 1) / (double) (MAX_POINTS - 1);
            for (int i = 0; i < MAX_POINTS; i++) {
                thinned.add(walk.route.get((int) Math.round(i * step)));
            }
            walk.route = thinned;
        }
        List<Walk> walks = getWalks();
        walks.add(0, walk);
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < Math.min(walks.size(), MAX_WALKS); i++) {
                array.put(walks.get(i).toJson());
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY_WALKS, array.toString()).apply();
    }
}
