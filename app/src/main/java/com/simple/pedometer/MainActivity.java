package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    /** Walking burns roughly 0.5 kcal per kg of body weight per km. */
    private static final double KCAL_PER_KG_KM = 0.5;
    private static final DateTimeFormatter HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault());
    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE", Locale.getDefault());

    private StepStore store;
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance();
    private TextView date;
    private TextView stepsToday;
    private TextView goal;
    private TextView status;
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
    private TextView walkSummary;
    private TextView rhythmSummary;
    private PulseStore pulseStore;
    private HeartView pulseHeart;
    private TextView pulseValue;
    private TextView pulseTime;

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
        weekTotal = findViewById(R.id.week_total);
        weekAverage = findViewById(R.id.week_average);
        weekBest = findViewById(R.id.week_best);
        ring = findViewById(R.id.ring);
        chart = findViewById(R.id.chart);
        batteryCard = findViewById(R.id.battery_card);
        findViewById(R.id.battery_button).setOnClickListener(v -> askToIgnoreBatteryOptimizations());
        rhythmSummary = findViewById(R.id.rhythm_summary);
        findViewById(R.id.rhythm_card).setOnClickListener(
                v -> startActivity(new Intent(this, RhythmActivity.class)));
        walkSummary = findViewById(R.id.walk_summary);
        findViewById(R.id.walk_card).setOnClickListener(
                v -> startActivity(new Intent(this, WalkActivity.class)));
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

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
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
        refresh();
    }

    @Override
    protected void onPause() {
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsListener);
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
        StepService.start(this);
        status.setText("\u25CF  Counting in the background");
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
        calories.setText(String.format(Locale.getDefault(), "%d kcal",
                Math.round(km * store.getWeightKg() * KCAL_PER_KG_KM)));
        speed.setText(active > 0
                ? String.format(Locale.getDefault(), "%.1f km/h",
                        store.getActiveSteps(today) * stride / 1000.0 / (active / 60.0))
                : "--");
        profile.setText(String.format(Locale.getDefault(),
                "Based on %d cm, %d kg \u00B7 Edit", store.getHeightCm(), store.getWeightKg()));

        // Last 7 days, oldest first, ending with today.
        int[] values = new int[7];
        String[] labels = new String[7];
        int total = 0;
        int best = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.minusDays(6 - i);
            values[i] = store.getSteps(day);
            labels[i] = i == 6 ? "Today" : day.format(DAY_FORMAT);
            total += values[i];
            best = Math.max(best, values[i]);
        }
        chart.setData(values, labels, DAILY_GOAL);
        weekTotal.setText(numberFormat.format(total));
        weekAverage.setText(numberFormat.format(total / 7));
        weekBest.setText(numberFormat.format(best));

        List<RhythmRecord> checks = new RhythmStore(this).getRecords();
        rhythmSummary.setText(checks.isEmpty()
                ? "60-second camera check of rhythm and HRV"
                : "Last: " + checks.get(0).statusText() + " \u00B7 HRV " + checks.get(0).rmssdMs + " ms \u00B7 "
                        + DateUtils.getRelativeTimeSpanString(checks.get(0).timeMillis));

        if (WalkSession.isRecording()) {
            walkSummary.setText("\u25CF  Walk in progress \u00B7 "
                    + Walk.distance(WalkSession.current.distanceMeters));
        } else {
            List<Walk> walks = new WalkStore(this).getWalks();
            walkSummary.setText(walks.isEmpty()
                    ? "Track distance, time and route with GPS"
                    : "Last: " + Walk.distance(walks.get(0).distanceMeters) + " \u00B7 "
                            + Walk.duration(walks.get(0).durationMillis) + " \u00B7 "
                            + DateUtils.getRelativeTimeSpanString(walks.get(0).startMillis));
        }

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

        PowerManager pm = getSystemService(PowerManager.class);
        batteryCard.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
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
