package com.simple.pedometer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A saved heart rhythm check: the result plus the pulse trace to look at later. */
public class RhythmRecord {

    /** The pulse trace is stored at this many samples per second. */
    static final int TRACE_HZ = 25;

    long timeMillis;
    int status;
    int bpm;
    int rmssdMs;
    int beats;
    /** Pulse trace, evenly sampled at {@link #TRACE_HZ}. */
    float[] trace = new float[0];
    /** Clean beats, in seconds from the start of the trace. */
    List<Double> beatSeconds = new ArrayList<>();
    List<Integer> intervalsMs = new ArrayList<>();

    static RhythmRecord from(List<Double> times, PulseAnalysis.Beats beats, PulseAnalysis.Rhythm rhythm) {
        RhythmRecord r = new RhythmRecord();
        r.timeMillis = System.currentTimeMillis();
        r.status = rhythm.status;
        r.bpm = rhythm.bpm;
        r.rmssdMs = rhythm.rmssdMs;
        r.beats = rhythm.beatsAnalysed;
        r.intervalsMs.addAll(rhythm.intervalsMs);

        // Resample the (unevenly timed) camera frames to an even rate.
        double start = times.get(0);
        double duration = times.get(times.size() - 1) - start;
        int count = (int) (duration * TRACE_HZ);
        r.trace = new float[count];
        int j = 0;
        for (int k = 0; k < count; k++) {
            double t = start + (double) k / TRACE_HZ;
            while (j < times.size() - 2 && times.get(j + 1) < t) j++;
            double t0 = times.get(j), t1 = times.get(j + 1);
            double f = t1 == t0 ? 0 : Math.max(0, Math.min(1, (t - t0) / (t1 - t0)));
            r.trace[k] = (float) (beats.smooth[j] + f * (beats.smooth[j + 1] - beats.smooth[j]));
        }
        for (double t : rhythm.goodBeatTimes) r.beatSeconds.add(t - start);
        return r;
    }

    String statusText() {
        switch (status) {
            case PulseAnalysis.Rhythm.REGULAR: return "Regular rhythm";
            case PulseAnalysis.Rhythm.IRREGULAR: return "Irregular rhythm";
            default: return "Unclear";
        }
    }

    JSONObject toJson() throws JSONException {
        JSONArray trace = new JSONArray();
        for (float v : this.trace) trace.put(Math.round(v * 100) / 100.0);
        JSONArray beats = new JSONArray();
        for (double b : beatSeconds) beats.put(Math.round(b * 1000) / 1000.0);
        JSONArray intervals = new JSONArray();
        for (int i : intervalsMs) intervals.put(i);
        return new JSONObject()
                .put("time", timeMillis)
                .put("status", status)
                .put("bpm", bpm)
                .put("rmssd", rmssdMs)
                .put("beats", this.beats)
                .put("trace", trace)
                .put("beatSeconds", beats)
                .put("intervals", intervals);
    }

    static RhythmRecord fromJson(JSONObject o) throws JSONException {
        RhythmRecord r = new RhythmRecord();
        r.timeMillis = o.getLong("time");
        r.status = o.getInt("status");
        r.bpm = o.getInt("bpm");
        r.rmssdMs = o.getInt("rmssd");
        r.beats = o.getInt("beats");
        JSONArray trace = o.getJSONArray("trace");
        r.trace = new float[trace.length()];
        for (int i = 0; i < trace.length(); i++) r.trace[i] = (float) trace.getDouble(i);
        JSONArray beats = o.getJSONArray("beatSeconds");
        for (int i = 0; i < beats.length(); i++) r.beatSeconds.add(beats.getDouble(i));
        JSONArray intervals = o.getJSONArray("intervals");
        for (int i = 0; i < intervals.length(); i++) r.intervalsMs.add(intervals.getInt(i));
        return r;
    }
}
