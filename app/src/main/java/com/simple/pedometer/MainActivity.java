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
import android.widget.Button;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE dd MMM", Locale.getDefault());

    private StepStore store;
    private TextView stepsToday;
    private TextView status;
    private TextView history;
    private Button batteryButton;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new StepStore(this);
        stepsToday = findViewById(R.id.steps_today);
        status = findViewById(R.id.status);
        history = findViewById(R.id.history);
        batteryButton = findViewById(R.id.battery_button);
        batteryButton.setOnClickListener(v -> askToIgnoreBatteryOptimizations());

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
        status.setText("Counting in the background. Keep walking!");
    }

    private void refresh() {
        stepsToday.setText(String.valueOf(store.getToday()));

        StringBuilder sb = new StringBuilder();
        LocalDate today = LocalDate.now();
        for (int i = 1; i <= 7; i++) {
            LocalDate day = today.minusDays(i);
            sb.append(String.format(Locale.getDefault(), "%-12s %8d%n",
                    day.format(DAY_FORMAT), store.getSteps(day)));
        }
        history.setText(sb.toString());

        PowerManager pm = getSystemService(PowerManager.class);
        batteryButton.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
    }

    private void askToIgnoreBatteryOptimizations() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
}
