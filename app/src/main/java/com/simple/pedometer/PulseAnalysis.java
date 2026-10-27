package com.simple.pedometer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the fingertip brightness signal from the camera into heartbeats, and
 * for the rhythm check into heart rate variability and a regular/irregular
 * rhythm estimate.
 */
final class PulseAnalysis {

    /** Beats whose shape matches the average beat less than this are treated as noise. */
    private static final double MIN_BEAT_SIMILARITY = 0.8;
    /** At least this share of beats must look clean for a rhythm result. */
    private static final double MIN_GOOD_BEATS = 0.7;
    /** Beat-to-beat variation (RMSSD / mean interval) above this is called irregular. */
    static final double IRREGULAR_THRESHOLD = 0.12;
    static final int MIN_INTERVALS = 20;

    private PulseAnalysis() {
    }

    /** Detected beats in a recording. */
    static final class Beats {
        double fps;
        /** Detrended, smoothed signal (one value per camera frame). */
        double[] smooth;
        final List<Integer> peakIndex = new ArrayList<>();
        /** Precise beat times in seconds (interpolated between frames). */
        final List<Double> peakTime = new ArrayList<>();
    }

    /**
     * Removes slow drift, smooths the signal and picks local peaks at least
     * 0.3 s apart. Returns null if there is too little data.
     */
    static Beats detect(List<Double> t, List<Double> v) {
        int n = t.size();
        if (n < 30) return null;
        double fps = (n - 1) / (t.get(n - 1) - t.get(0));
        if (fps <= 0) return null;

        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v.get(i);
        int trend = Math.max(3, (int) Math.round(fps) / 2);
        double[] detrended = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - trend), b = Math.min(n, i + trend + 1);
            detrended[i] = v.get(i) - (prefix[b] - prefix[a]) / (b - a);
        }
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 2), b = Math.min(n - 1, i + 2);
            double s = 0;
            for (int j = a; j <= b; j++) s += detrended[j];
            smooth[i] = s / (b - a + 1);
        }

        Beats beats = new Beats();
        beats.fps = fps;
        beats.smooth = smooth;
        int half = Math.max(2, (int) Math.round(fps * 0.25));
        for (int i = half; i < n - half; i++) {
            if (smooth[i] <= 0) continue;
            boolean isPeak = true;
            for (int j = i - half; j <= i + half && isPeak; j++) {
                if (smooth[j] > smooth[i]) isPeak = false;
            }
            if (!isPeak) continue;
            // Refine the peak time with a parabola through the neighbouring frames.
            double y0 = smooth[i - 1], y1 = smooth[i], y2 = smooth[i + 1];
            double denom = y0 - 2 * y1 + y2;
            double offset = denom == 0 ? 0 : Math.max(-0.5, Math.min(0.5, 0.5 * (y0 - y2) / denom));
            double time = t.get(i) + offset * (t.get(i + 1) - t.get(i - 1)) / 2;
            int last = beats.peakTime.size() - 1;
            if (last >= 0 && time - beats.peakTime.get(last) < 0.3) continue;
            beats.peakIndex.add(i);
            beats.peakTime.add(time);
        }
        return beats;
    }

    /** Beats per minute from the median beat interval, or -1 if unclear. */
    static int bpm(Beats beats) {
        if (beats == null) return -1;
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < beats.peakTime.size(); i++) {
            double d = beats.peakTime.get(i) - beats.peakTime.get(i - 1);
            if (d >= 0.3 && d <= 1.5) intervals.add(d);
        }
        if (intervals.size() < 4) return -1;
        Collections.sort(intervals);
        int bpm = (int) Math.round(60 / intervals.get(intervals.size() / 2));
        return bpm >= 40 && bpm <= 200 ? bpm : -1;
    }

    /** Result of a rhythm check. */
    static final class Rhythm {
        static final int REGULAR = 0;
        static final int IRREGULAR = 1;
        static final int UNCLEAR = 2;

        int status = UNCLEAR;
        int bpm = -1;
        /** Heart rate variability: root mean square of successive differences, in ms. */
        int rmssdMs = -1;
        int beatsAnalysed;
        /** Beat-to-beat intervals in ms (between consecutive clean beats). */
        final List<Integer> intervalsMs = new ArrayList<>();
        /** Times (s) of the clean beats. */
        final List<Double> goodBeatTimes = new ArrayList<>();
    }

    /**
     * Checks every beat's shape against the average beat to drop noisy ones,
     * then measures how much the time between clean beats varies.
     */
    static Rhythm rhythm(Beats beats) {
        Rhythm r = new Rhythm();
        if (beats == null) return r;
        int w = Math.max(3, (int) Math.round(beats.fps * 0.3));
        int n = beats.smooth.length;

        List<double[]> shapes = new ArrayList<>();
        List<Integer> shapeBeat = new ArrayList<>();
        for (int b = 0; b < beats.peakIndex.size(); b++) {
            int c = beats.peakIndex.get(b);
            if (c - w < 0 || c + w >= n) continue;
            double[] shape = new double[2 * w + 1];
            System.arraycopy(beats.smooth, c - w, shape, 0, shape.length);
            shapes.add(shape);
            shapeBeat.add(b);
        }
        if (shapes.size() < MIN_INTERVALS) return r;

        double[] template = new double[2 * w + 1];
        for (double[] s : shapes) {
            for (int i = 0; i < template.length; i++) template[i] += s[i] / shapes.size();
        }
        boolean[] good = new boolean[beats.peakIndex.size()];
        int goodCount = 0;
        for (int k = 0; k < shapes.size(); k++) {
            if (correlation(shapes.get(k), template) >= MIN_BEAT_SIMILARITY) {
                good[shapeBeat.get(k)] = true;
                goodCount++;
            }
        }
        r.beatsAnalysed = goodCount;
        if (goodCount < MIN_GOOD_BEATS * shapes.size()) return r;

        for (int b = 0; b < good.length; b++) {
            if (good[b]) r.goodBeatTimes.add(beats.peakTime.get(b));
        }
        List<Double> intervals = new ArrayList<>();
        for (int b = 1; b < good.length; b++) {
            if (!good[b] || !good[b - 1]) continue;
            double d = beats.peakTime.get(b) - beats.peakTime.get(b - 1);
            if (d < 0.3 || d > 1.5) continue;
            intervals.add(d);
            r.intervalsMs.add((int) Math.round(d * 1000));
        }
        if (intervals.size() < MIN_INTERVALS) return r;

        double mean = 0;
        for (double d : intervals) mean += d;
        mean /= intervals.size();
        double sumSq = 0;
        for (int i = 1; i < intervals.size(); i++) {
            double diff = intervals.get(i) - intervals.get(i - 1);
            sumSq += diff * diff;
        }
        double rmssd = Math.sqrt(sumSq / (intervals.size() - 1));

        r.bpm = (int) Math.round(60 / mean);
        r.rmssdMs = (int) Math.round(rmssd * 1000);
        r.status = rmssd / mean > IRREGULAR_THRESHOLD ? Rhythm.IRREGULAR : Rhythm.REGULAR;
        return r;
    }

    private static double correlation(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length;
        mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            num += (a[i] - ma) * (b[i] - mb);
            da += (a[i] - ma) * (a[i] - ma);
            db += (b[i] - mb) * (b[i] - mb);
        }
        return da == 0 || db == 0 ? 0 : num / Math.sqrt(da * db);
    }
}
