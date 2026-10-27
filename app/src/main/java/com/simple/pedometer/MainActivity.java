package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
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
import android.view.View;
import android.widget.TextView;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int DAILY_GOAL = 10000;
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

        PowerManager pm = getSystemService(PowerManager.class);
        batteryCard.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
    }

    private void askToIgnoreBatteryOptimizations() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
}
