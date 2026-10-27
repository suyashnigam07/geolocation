package com.simple.pedometer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One recorded walk: when, how long, how far, steps and the GPS route. */
public class Walk {

    public long startMillis;
    public long durationMillis;
    public double distanceMeters;
    public int steps;
    /** Route points as {latitude, longitude}. */
    public List<double[]> route = new ArrayList<>();

    JSONObject toJson() throws JSONException {
        JSONArray points = new JSONArray();
        for (double[] p : route) {
            points.put(Math.round(p[0] * 1e6) / 1e6);
            points.put(Math.round(p[1] * 1e6) / 1e6);
        }
        return new JSONObject()
                .put("start", startMillis)
                .put("duration", durationMillis)
                .put("distance", distanceMeters)
                .put("steps", steps)
                .put("route", points);
    }

    static Walk fromJson(JSONObject o) throws JSONException {
        Walk w = new Walk();
        w.startMillis = o.getLong("start");
        w.durationMillis = o.getLong("duration");
        w.distanceMeters = o.getDouble("distance");
        w.steps = o.getInt("steps");
        JSONArray points = o.getJSONArray("route");
        for (int i = 0; i + 1 < points.length(); i += 2) {
            w.route.add(new double[]{points.getDouble(i), points.getDouble(i + 1)});
        }
        return w;
    }

    // ------------------------------------------------------------- formatting

    static String distance(double meters) {
        return meters < 1000
                ? String.format(Locale.getDefault(), "%d m", Math.round(meters))
                : String.format(Locale.getDefault(), "%.2f km", meters / 1000);
    }

    static String duration(long millis) {
        long s = millis / 1000;
        return s >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    /** Minutes per kilometre, e.g. "11:30 /km". */
    static String pace(long millis, double meters) {
        if (meters < 50) return "--";
        long secPerKm = Math.round(millis / 1000.0 / (meters / 1000));
        return String.format(Locale.getDefault(), "%d:%02d /km", secPerKm / 60, secPerKm % 60);
    }
}
