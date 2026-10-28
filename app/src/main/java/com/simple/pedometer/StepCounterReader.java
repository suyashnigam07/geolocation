package com.simple.pedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Reads the hardware step counter once and saves the new steps. The sensor
 * reports its current total as soon as it is switched on, so this only keeps
 * it on for a moment.
 */
final class StepCounterReader {

    private static final long TIMEOUT_MILLIS = 5000;

    private StepCounterReader() {
    }

    /** Reads the counter, then calls {@code done} on the main thread (also on timeout). */
    static void readOnce(Context context, Runnable done) {
        SensorManager sm = context.getSystemService(SensorManager.class);
        Sensor counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Handler handler = new Handler(Looper.getMainLooper());
        if (counter == null) {
            handler.post(done);
            return;
        }
        StepStore store = new StepStore(context);
        boolean[] finished = {false};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (finished[0]) return;
                store.onCounterReading((long) event.values[0]);
                finish(sm, this, handler, finished, done);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL, handler);
        handler.postDelayed(() -> finish(sm, listener, handler, finished, done), TIMEOUT_MILLIS);
    }

    private static void finish(SensorManager sm, SensorEventListener listener, Handler handler,
                               boolean[] finished, Runnable done) {
        if (finished[0]) return;
        finished[0] = true;
        sm.unregisterListener(listener);
        handler.removeCallbacksAndMessages(null);
        done.run();
    }
}
