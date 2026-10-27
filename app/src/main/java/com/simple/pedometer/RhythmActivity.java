package com.simple.pedometer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Shows the result of a heart rhythm check and the list of past checks. */
public class RhythmActivity extends Activity {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault());

    private View resultSection;
    private TextView date;
    private TextView status;
    private TextView bpm;
    private TextView hrv;
    private TextView beats;
    private TextView explanation;
    private TraceView trace;
    private IntervalView intervals;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rhythm);
        resultSection = findViewById(R.id.result_section);
        date = findViewById(R.id.rhythm_date);
        status = findViewById(R.id.rhythm_status);
        bpm = findViewById(R.id.rhythm_bpm);
        hrv = findViewById(R.id.rhythm_hrv);
        beats = findViewById(R.id.rhythm_beats);
        explanation = findViewById(R.id.rhythm_explanation);
        trace = findViewById(R.id.rhythm_trace);
        intervals = findViewById(R.id.rhythm_intervals);
        list = findViewById(R.id.rhythm_list);
        findViewById(R.id.rhythm_start).setOnClickListener(v -> startActivity(
                new Intent(this, PulseActivity.class).putExtra(PulseActivity.EXTRA_RHYTHM, true)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<RhythmRecord> records = new RhythmStore(this).getRecords();
        show(records.isEmpty() ? null : records.get(0));

        list.removeAllViews();
        if (records.isEmpty()) {
            list.addView(row("No checks yet", "Tap the button above to do your first 60-second check.", null));
        }
        for (RhythmRecord r : records) {
            String detail = r.statusText() + "  ·  " + r.bpm + " BPM  ·  HRV " + r.rmssdMs + " ms";
            list.addView(row(format(r.timeMillis), detail, v -> {
                show(r);
                ((ScrollView) findViewById(R.id.rhythm_scroll)).smoothScrollTo(0, 0);
            }));
        }
    }

    private void show(RhythmRecord r) {
        resultSection.setVisibility(r == null ? View.GONE : View.VISIBLE);
        if (r == null) {
            date.setText("Heart rhythm");
            return;
        }
        date.setText(format(r.timeMillis));
        status.setText(r.statusText());
        boolean regular = r.status == PulseAnalysis.Rhythm.REGULAR;
        status.setTextColor(getColor(regular ? R.color.accent : R.color.warning));
        status.getBackground().mutate().setTint(getColor(regular ? R.color.accent_soft : R.color.warning_soft));
        bpm.setText(r.bpm + " BPM");
        hrv.setText(r.rmssdMs + " ms");
        beats.setText(String.valueOf(r.beats));
        explanation.setText(regular
                ? "The time between your heartbeats was steady, with the normal small variation from "
                        + "breathing. HRV is the beat-to-beat variation: higher usually means more relaxed "
                        + "and recovered; it is lower with stress, tiredness or illness and drops with age."
                : "The time between your heartbeats varied more than expected. This often happens "
                        + "from movement or a poor signal, and can also be caused by extra beats or an "
                        + "arrhythmia such as atrial fibrillation. Sit still and repeat the check. If it "
                        + "keeps showing irregular, especially with palpitations, dizziness or "
                        + "breathlessness, see a doctor for a proper ECG.");
        trace.setData(r.trace, r.beatSeconds);
        intervals.setIntervals(r.intervalsMs);
    }

    private static String format(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE);
    }

    private View row(String title, String detail, View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.text_primary));
        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setTextColor(getColor(R.color.text_secondary));
        row.addView(t);
        row.addView(d);
        if (onClick != null) {
            TypedValue ripple = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(onClick);
        }
        return row;
    }
}
