package com.simple.pedometer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Lists every sensor and the main hardware features this phone reports. */
public class SensorsActivity extends Activity {

    private static final String[][] FEATURES = {
            {"Camera", PackageManager.FEATURE_CAMERA_ANY},
            {"Camera flash", PackageManager.FEATURE_CAMERA_FLASH},
            {"GPS", PackageManager.FEATURE_LOCATION_GPS},
            {"Microphone", PackageManager.FEATURE_MICROPHONE},
            {"Bluetooth LE", PackageManager.FEATURE_BLUETOOTH_LE},
            {"Wi-Fi", PackageManager.FEATURE_WIFI},
            {"NFC", PackageManager.FEATURE_NFC},
            {"Fingerprint", PackageManager.FEATURE_FINGERPRINT},
            {"Infrared blaster", PackageManager.FEATURE_CONSUMER_IR},
            {"Vibration motor", null},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensors);

        StringBuilder report = new StringBuilder();
        report.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n\nHardware:\n");

        LinearLayout features = findViewById(R.id.features);
        PackageManager pm = getPackageManager();
        for (String[] f : FEATURES) {
            boolean has = f[1] == null
                    ? getSystemService(android.os.Vibrator.class).hasVibrator()
                    : pm.hasSystemFeature(f[1]);
            addRow(features, (has ? "✓  " : "✗  ") + f[0], null, has);
            report.append(has ? "  [yes] " : "  [no]  ").append(f[0]).append('\n');
        }

        SensorManager sm = getSystemService(SensorManager.class);
        List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
        LinearLayout list = findViewById(R.id.sensors);
        report.append("\nSensors (").append(sensors.size()).append("):\n");
        for (Sensor s : sensors) {
            String type = friendlyType(s);
            String details = s.getName() + " · " + s.getVendor();
            addRow(list, type, details, true);
            report.append("  - ").append(type).append(": ").append(details).append('\n');
        }

        ((TextView) findViewById(R.id.summary)).setText(String.format(Locale.getDefault(),
                "%s %s · %d sensors", Build.MANUFACTURER, Build.MODEL, sensors.size()));

        findViewById(R.id.share).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, report.toString());
            startActivity(Intent.createChooser(send, "Share sensor list"));
        });
    }

    /** "android.sensor.step_counter" -> "Step counter". */
    private static String friendlyType(Sensor sensor) {
        String type = sensor.getStringType();
        if (type == null || type.isEmpty()) return "Type " + sensor.getType();
        type = type.substring(type.lastIndexOf('.') + 1).replace('_', ' ');
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    private void addRow(LinearLayout parent, String title, String subtitle, boolean enabled) {
        float density = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(enabled ? R.color.text_primary : R.color.text_secondary));
        t.setPadding(0, (int) (10 * density), 0, 0);
        parent.addView(t);
        if (subtitle != null) {
            TextView d = new TextView(this);
            d.setText(subtitle);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            d.setTextColor(getColor(R.color.text_secondary));
            parent.addView(d);
        }
    }
}
