package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int DAILY_GOAL = 10000;
    private static final int ACTIVE_GOAL_MINUTES = 30;

    // Measures the 7-day chart can show.
    private static final int METRIC_STEPS = 0;
    private static final int METRIC_KM = 1;
    private static final int METRIC_KCAL = 2;
    private static final int METRIC_ACTIVE = 3;
    private static final int[] METRIC_TABS = {R.id.metric_steps, R.id.metric_km, R.id.metric_kcal, R.id.metric_active};
    private static final String[] METRIC_TOTAL_LABELS = {"Total steps", "Total km", "Total kcal", "Total active min"};
    private static final DateTimeFormatter HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault());
    private static final DateTimeFormatter TABLE_DAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault());
    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE", Locale.getDefault());

    private StepStore store;
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance();
    private TextView date;
    private TextView stepsToday;
    private TextView goal;
    private TextView status;
    private TextView blockLast;
    private TextView blockBest;
    private TextView blockAverage;
    private TextView blockProgress;
    private int metric = METRIC_STEPS;
    private TextView weekTotalLabel;
    private LinearLayout weekTable;
    private TextView weekTotal;
    private TextView weekAverage;
    private TextView weekBest;
    private StepRingView ring;
    private BarChartView chart;
    private View batteryCard;
    private StepRingView activeRing;
    private TextView activeMinutes;
    private TextView activeGoal;
    private TextView distance;
    private TextView calories;
    private TextView speed;
    private TextView profile;
    private Switch reminderSwitch;
    private TextView rhythmSummary;
    private PulseStore pulseStore;
    private HeartView pulseHeart;
    private TextView pulseValue;
    private TextView pulseTime;

    private FriendsSection friendsSection;
    private SensorManager sensorManager;
    private boolean canCount;

    /** While the app is open, the step counter is read live. */
    private final SensorEventListener liveCounter = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            store.onCounterReading((long) event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new StepStore(this);
        date = findViewById(R.id.date);
        stepsToday = findViewById(R.id.steps_today);
        goal = findViewById(R.id.goal);
        status = findViewById(R.id.status);
        blockLast = findViewById(R.id.block_last);
        blockBest = findViewById(R.id.block_best);
        blockAverage = findViewById(R.id.block_average);
        blockProgress = findViewById(R.id.block_progress);
        weekTotal = findViewById(R.id.week_total);
        weekAverage = findViewById(R.id.week_average);
        weekBest = findViewById(R.id.week_best);
        weekTotalLabel = findViewById(R.id.week_total_label);
        weekTable = findViewById(R.id.week_table);
        for (int i = 0; i < METRIC_TABS.length; i++) {
            int chosen = i;
            findViewById(METRIC_TABS[i]).setOnClickListener(v -> {
                metric = chosen;
                refresh();
            });
        }
        findViewById(R.id.add_steps).setOnClickListener(v -> addSteps());
        ring = findViewById(R.id.ring);
        chart = findViewById(R.id.chart);
        batteryCard = findViewById(R.id.battery_card);
        findViewById(R.id.battery_button).setOnClickListener(v -> askToIgnoreBatteryOptimizations());
        rhythmSummary = findViewById(R.id.rhythm_summary);
        findViewById(R.id.rhythm_card).setOnClickListener(
                v -> startActivity(new Intent(this, RhythmActivity.class)));
        pulseStore = new PulseStore(this);
        pulseHeart = findViewById(R.id.pulse_heart);
        pulseValue = findViewById(R.id.pulse_value);
        pulseTime = findViewById(R.id.pulse_time);
        findViewById(R.id.pulse_widget).setOnClickListener(
                v -> startActivity(new Intent(this, PulseActivity.class)));
        activeRing = findViewById(R.id.active_ring);
        activeRing.setColor(getColor(R.color.active));
        activeMinutes = findViewById(R.id.active_minutes);
        activeGoal = findViewById(R.id.active_goal);
        distance = findViewById(R.id.distance);
        calories = findViewById(R.id.calories);
        speed = findViewById(R.id.speed);
        profile = findViewById(R.id.profile);
        profile.setOnClickListener(v -> editProfile());
        reminderSwitch = findViewById(R.id.reminder_switch);
        reminderSwitch.setChecked(store.remindersEnabled());
        reminderSwitch.setOnCheckedChangeListener((b, checked) -> store.setRemindersEnabled(checked));

        friendsSection = new FriendsSection(this);
        sensorManager = getSystemService(SensorManager.class);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) {
            status.setText("Sorry, this phone has no step counter sensor.");
            return;
        }
        requestPermissionsAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        if (canCount) {
            sensorManager.registerListener(liveCounter,
                    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER),
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
        refresh();
        friendsSection.refresh();
    }

    @Override
    protected void onPause() {
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsListener);
        sensorManager.unregisterListener(liveCounter);
        super.onPause();
    }

    private void requestPermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            startCounting();
        } else {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            status.setText("Please allow \"Physical activity\" permission in Settings "
                    + "so the app can count your steps.");
            return;
        }
        startCounting();
    }

    private void startCounting() {
        // No background service: the phone's step chip counts on its own, the
        // app reads it hourly, and walk detection starts the service only
        // while you walk.
        WalkDetector.register(this);
        MoveReminder.schedule(this);
        if (!canCount) {
            canCount = true;
            sensorManager.registerListener(liveCounter,
                    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER),
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
        status.setText("\u25CF  Steps count automatically \u00B7 the app only runs while you walk");
    }

    private void refresh() {
        LocalDate today = LocalDate.now();
        int steps = store.getToday();
        date.setText(today.format(HEADER_FORMAT));
        stepsToday.setText(numberFormat.format(steps));
        ring.setProgress((float) steps / DAILY_GOAL);
        goal.setText(steps >= DAILY_GOAL
                ? "Goal reached!"
                : numberFormat.format(DAILY_GOAL - steps) + " to " + numberFormat.format(DAILY_GOAL));

        blockLast.setText(minutesSeconds(store.getLastBlockMillis()));
        blockBest.setText(minutesSeconds(store.getBestBlockMillis(today)));
        blockAverage.setText(minutesSeconds(store.getAverageBlockMillis(today)));
        int inBlock = store.getCurrentBlockSteps();
        blockProgress.setText(store.getLastBlockMillis() == 0 && inBlock == 0
                ? "Measured while you walk; pauses don't count."
                : "Next 1,000: " + numberFormat.format(inBlock) + " / 1,000 steps \u00B7 pauses don't count");

        int active = store.getActiveMinutes(today);
        activeMinutes.setText(String.valueOf(active));
        activeRing.setProgress((float) active / ACTIVE_GOAL_MINUTES);
        activeGoal.setText(active >= ACTIVE_GOAL_MINUTES
                ? "Goal of " + ACTIVE_GOAL_MINUTES + " min reached!"
                : "Goal " + ACTIVE_GOAL_MINUTES + " min \u00B7 minutes with " + StepStore.ACTIVE_MINUTE_STEPS + "+ steps");

        double stride = store.getStrideMeters();
        double km = steps * stride / 1000.0;
        distance.setText(km < 1
                ? String.format(Locale.getDefault(), "%d m", Math.round(km * 1000))
                : String.format(Locale.getDefault(), "%.2f km", km));
        calories.setText(numberFormat.format(store.caloriesFor(steps)));
        speed.setText(active > 0
                ? String.format(Locale.getDefault(), "%.1f km/h",
                        store.getActiveSteps(today) * stride / 1000.0 / (active / 60.0))
                : "--");
        profile.setText(String.format(Locale.getDefault(),
                "Based on %d cm, %d kg \u00B7 Edit", store.getHeightCm(), store.getWeightKg()));

        refreshWeek(today);

        List<RhythmRecord> checks = new RhythmStore(this).getRecords();
        rhythmSummary.setText(checks.isEmpty()
                ? "60-second camera check of rhythm and HRV"
                : "Last: " + checks.get(0).statusText() + " \u00B7 HRV " + checks.get(0).rmssdMs + " ms \u00B7 "
                        + DateUtils.getRelativeTimeSpanString(checks.get(0).timeMillis));

        int bpm = pulseStore.getBpm();
        if (bpm > 0) {
            pulseValue.setText(String.valueOf(bpm));
            pulseHeart.setProgress(1);
            long ago = System.currentTimeMillis() - pulseStore.getTime();
            pulseTime.setText(ago < DateUtils.MINUTE_IN_MILLIS ? "Just now"
                    : DateUtils.getRelativeTimeSpanString(pulseStore.getTime(),
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            pulseValue.setText("--");
            pulseHeart.setProgress(0);
            pulseTime.setText("Tap to measure");
        }

        friendsSection.updateMe();

        PowerManager pm = getSystemService(PowerManager.class);
        batteryCard.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
    }

    /** The 7-day chart for the chosen measure, its totals, and the day-by-day table. */
    private void refreshWeek(LocalDate today) {
        for (int i = 0; i < METRIC_TABS.length; i++) {
            TextView tab = findViewById(METRIC_TABS[i]);
            boolean selected = i == metric;
            tab.setBackgroundResource(selected ? R.drawable.bg_chip : 0);
            tab.setTextColor(getColor(selected ? R.color.accent : R.color.text_secondary));
        }

        // Oldest first, ending with today.
        double[] values = new double[7];
        String[] labels = new String[7];
        double total = 0;
        double best = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.minusDays(6 - i);
            values[i] = metricValue(day, metric);
            labels[i] = i == 6 ? "Today" : day.format(DAY_FORMAT);
            total += values[i];
            best = Math.max(best, values[i]);
        }
        double goal;
        java.util.function.DoubleFunction<String> barLabel;
        switch (metric) {
            case METRIC_KM:
                goal = DAILY_GOAL * store.getStrideMeters() / 1000.0;
                barLabel = v -> String.format(Locale.getDefault(), "%.1f", v);
                break;
            case METRIC_KCAL:
                goal = store.caloriesFor(DAILY_GOAL);
                barLabel = v -> String.valueOf(Math.round(v));
                break;
            case METRIC_ACTIVE:
                goal = ACTIVE_GOAL_MINUTES;
                barLabel = v -> String.valueOf(Math.round(v));
                break;
            default:
                goal = DAILY_GOAL;
                barLabel = null;
        }
        if (barLabel == null) {
            int[] steps = new int[7];
            for (int i = 0; i < 7; i++) steps[i] = (int) values[i];
            chart.setData(steps, labels, DAILY_GOAL);
        } else {
            chart.setData(values, labels, goal, barLabel);
        }
        weekTotalLabel.setText(METRIC_TOTAL_LABELS[metric]);
        weekTotal.setText(formatMetric(total, metric));
        weekAverage.setText(formatMetric(total / 7, metric));
        weekBest.setText(formatMetric(best, metric));

        // Day-by-day table, newest first.
        weekTable.removeAllViews();
        weekTable.addView(tableRow(new String[]{"Day", "Steps", "Km", "kcal", "Active"}, true, false));
        boolean anyManual = false;
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.minusDays(i);
            boolean manual = store.getManualSteps(day) > 0;
            anyManual |= manual;
            weekTable.addView(tableRow(new String[]{
                    i == 0 ? "Today" : i == 1 ? "Yesterday" : day.format(TABLE_DAY_FORMAT),
                    numberFormat.format(store.getSteps(day)) + (manual ? " \u270E" : ""),
                    String.format(Locale.getDefault(), "%.2f", store.distanceKm(day)),
                    numberFormat.format(store.caloriesFor(store.getSteps(day))),
                    store.getActiveMinutes(day) + " min"}, false, i == 0));
        }
        if (anyManual) {
            TextView note = new TextView(this);
            note.setText("\u270E includes steps you added by hand");
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            note.setTextColor(getColor(R.color.text_secondary));
            note.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
            weekTable.addView(note);
        }
    }

    private double metricValue(LocalDate day, int metric) {
        switch (metric) {
            case METRIC_KM: return store.distanceKm(day);
            case METRIC_KCAL: return store.caloriesFor(store.getSteps(day));
            case METRIC_ACTIVE: return store.getActiveMinutes(day);
            default: return store.getSteps(day);
        }
    }

    private String formatMetric(double value, int metric) {
        if (metric == METRIC_KM) return String.format(Locale.getDefault(), "%.1f km", value);
        String n = numberFormat.format(Math.round(value));
        return metric == METRIC_KCAL ? n + " kcal" : metric == METRIC_ACTIVE ? n + " min" : n;
    }

    private View tableRow(String[] cells, boolean header, boolean highlight) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
        float[] weights = {1.3f, 1.1f, 0.9f, 0.8f, 0.9f};
        for (int i = 0; i < cells.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(cells[i]);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, header ? 12 : 14);
            cell.setTextColor(getColor(header ? R.color.text_secondary : R.color.text_primary));
            if (header || highlight) cell.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            cell.setGravity(i == 0 ? Gravity.START : Gravity.END);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]));
        }
        if (!header) {
            View line = new View(this);
            line.setBackgroundColor(getColor(R.color.track));
            LinearLayout wrapper = new LinearLayout(this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            wrapper.addView(row);
            return wrapper;
        }
        return row;
    }

    /** Lets you add steps you walked without your phone, to today or yesterday. */
    private void addSteps() {
        float density = getResources().getDisplayMetrics().density;
        LocalDate today = LocalDate.now();
        RadioGroup days = new RadioGroup(this);
        days.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton todayButton = new RadioButton(this);
        todayButton.setText("Today");
        todayButton.setId(View.generateViewId());
        RadioButton yesterdayButton = new RadioButton(this);
        yesterdayButton.setText("Yesterday");
        yesterdayButton.setId(View.generateViewId());
        days.addView(todayButton);
        days.addView(yesterdayButton);
        days.check(todayButton.getId());

        EditText amount = new EditText(this);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        amount.setHint("Steps, e.g. 2500");

        TextView already = new TextView(this);
        already.setTextColor(getColor(R.color.text_secondary));
        Runnable showAdded = () -> {
            LocalDate day = days.getCheckedRadioButtonId() == todayButton.getId() ? today : today.minusDays(1);
            int added = store.getManualSteps(day);
            already.setText(added == 0 ? "" : "Already added by hand: " + numberFormat.format(added) + " steps");
        };
        days.setOnCheckedChangeListener((group, id) -> showAdded.run());
        showAdded.run();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * density);
        form.setPadding(pad, (int) (8 * density), pad, 0);
        form.addView(days);
        form.addView(amount);
        form.addView(already);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add steps")
                .setMessage("For walks without your phone. Added steps count towards your total, "
                        + "distance, calories and friends.")
                .setView(form)
                .setPositiveButton("Add", null)
                .setNeutralButton("Remove added", null)
                .setNegativeButton("Cancel", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            LocalDate day = days.getCheckedRadioButtonId() == todayButton.getId() ? today : today.minusDays(1);
            int steps = parse(amount, 0);
            if (steps <= 0 || steps > StepStore.MAX_MANUAL_STEPS) {
                amount.setError("Enter 1 to " + numberFormat.format(StepStore.MAX_MANUAL_STEPS));
                return;
            }
            store.addManualSteps(day, steps);
            Friends.upload(this);
            Toast.makeText(this, "Added " + numberFormat.format(steps) + " steps", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            LocalDate day = days.getCheckedRadioButtonId() == todayButton.getId() ? today : today.minusDays(1);
            if (store.getManualSteps(day) == 0) {
                Toast.makeText(this, "No steps were added by hand for that day", Toast.LENGTH_SHORT).show();
                return;
            }
            store.clearManualSteps(day);
            Friends.upload(this);
            Toast.makeText(this, "Removed the steps added by hand", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void editProfile() {
        float density = getResources().getDisplayMetrics().density;
        EditText height = new EditText(this);
        height.setInputType(InputType.TYPE_CLASS_NUMBER);
        height.setHint("Height (cm)");
        height.setText(String.valueOf(store.getHeightCm()));
        EditText weight = new EditText(this);
        weight.setInputType(InputType.TYPE_CLASS_NUMBER);
        weight.setHint("Weight (kg)");
        weight.setText(String.valueOf(store.getWeightKg()));

        TextView heightLabel = new TextView(this);
        heightLabel.setText("Height (cm)");
        TextView weightLabel = new TextView(this);
        weightLabel.setText("Weight (kg)");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * density);
        form.setPadding(pad, (int) (8 * density), pad, 0);
        form.addView(heightLabel);
        form.addView(height);
        form.addView(weightLabel);
        form.addView(weight);

        new AlertDialog.Builder(this)
                .setTitle("Your height and weight")
                .setMessage("Used to estimate distance and calories.")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    int h = parse(height, store.getHeightCm());
                    int kg = parse(weight, store.getWeightKg());
                    if (h >= 100 && h <= 230 && kg >= 30 && kg <= 250) {
                        store.setProfile(h, kg);
                    } else {
                        Toast.makeText(this, "Please enter a height of 100-230 cm and weight of 30-250 kg",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "9:40" (minutes:seconds), or "--" when there's nothing measured yet. */
    private static String minutesSeconds(long millis) {
        if (millis <= 0) return "--";
        long s = Math.round(millis / 1000.0);
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    private static int parse(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void askToIgnoreBatteryOptimizations() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
}
