package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures the pulse with the back camera and flash (photoplethysmography):
 * with a fingertip over the lens, each heartbeat pushes blood into the finger
 * and makes the picture very slightly darker. We record the brightness for a
 * few seconds and count the dips.
 */
public class PulseActivity extends Activity {

    private static final int REQUEST_CAMERA = 2;
    private static final double SETTLE_SECONDS = 2;
    private static final double MEASURE_SECONDS = 20;

    private enum State { WAITING, MEASURING, DONE, FAILED }

    private HeartView heart;
    private TextView bpmText;
    private TextView message;
    private PulseWaveView wave;
    private Button button;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private volatile boolean cameraWanted;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;

    private volatile State state = State.WAITING;
    // Only touched on the camera thread.
    private long startNanos;
    private final List<Double> times = new ArrayList<>();
    private final List<Double> values = new ArrayList<>();
    private double lastBeatTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pulse);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        heart = findViewById(R.id.heart);
        bpmText = findViewById(R.id.bpm);
        message = findViewById(R.id.message);
        wave = findViewById(R.id.wave);
        button = findViewById(R.id.button);
        cameraThread = new HandlerThread("pulse-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        button.setOnClickListener(v -> {
            if (state == State.FAILED) {
                restart();
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            restart();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cameraThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message.setText("Camera permission is needed to measure your pulse.");
            button.setText("Close");
        }
        // If granted, onResume runs again and starts the camera.
    }

    private void restart() {
        stopCamera();
        state = State.WAITING;
        times.clear();
        values.clear();
        lastBeatTime = 0;
        heart.setProgress(0);
        wave.clear();
        bpmText.setText("--");
        button.setText("Cancel");
        message.setText("Cover the back camera and flash completely with your fingertip.");
        startCamera();
    }

    // ---------------------------------------------------------------- camera

    private void startCamera() {
        CameraManager manager = getSystemService(CameraManager.class);
        try {
            String cameraId = null;
            CameraCharacteristics chars = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                        && Boolean.TRUE.equals(flash)) {
                    cameraId = id;
                    chars = c;
                    break;
                }
            }
            if (cameraId == null) {
                fail("This phone has no back camera with a flash, so the pulse can't be measured.");
                return;
            }

            Size size = smallestSize(chars);
            Range<Integer> fps = bestFpsRange(chars);

            cameraWanted = true;
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) onFrame(image);
                } catch (IllegalStateException e) {
                    // Reader was closed while a frame was arriving; nothing to do.
                }
            }, cameraHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    if (!cameraWanted) {
                        // The screen was closed while the camera was opening.
                        device.close();
                        return;
                    }
                    camera = device;
                    startSession(fps);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    camera = null;
                    fail("Couldn't open the camera. Close other camera apps and try again.");
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            fail("Couldn't open the camera. Close other camera apps and try again.");
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession(Range<Integer> fps) {
        try {
            camera.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (camera == null) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                CaptureRequest.Builder request =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(imageReader.getSurface());
                                request.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                                if (fps != null) {
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                                }
                                s.setRepeatingRequest(request.build(), null, cameraHandler);
                            } catch (CameraAccessException | IllegalStateException e) {
                                fail("Couldn't start the camera. Please try again.");
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Couldn't start the camera. Please try again.");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            fail("Couldn't start the camera. Please try again.");
        }
    }

    private void stopCamera() {
        cameraWanted = false;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private static Size smallestSize(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() < 160) continue;
            if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best != null ? best : new Size(640, 480);
    }

    /** Prefers a steady 30 fps so the heartbeat is sampled evenly. */
    private static Range<Integer> bestFpsRange(CameraCharacteristics chars) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() != 30) continue;
            if (best == null || r.getLower() > best.getLower()) best = r;
        }
        return best;
    }

    // ------------------------------------------------------- signal handling

    /** Runs on the camera thread for every frame. */
    private void onFrame(Image image) {
        if (state == State.DONE || state == State.FAILED) return;

        Image.Plane[] planes = image.getPlanes();
        double brightness = average(planes[0], image.getWidth(), image.getHeight());
        double red = average(planes[2], image.getWidth() / 2, image.getHeight() / 2);
        double seconds = image.getTimestamp() / 1e9;

        // A fingertip lit by the flash makes the whole picture bright red.
        boolean fingerOnLens = red > 138 && brightness > 30;
        if (!fingerOnLens) {
            if (state == State.MEASURING) {
                state = State.WAITING;
                times.clear();
                values.clear();
                runOnUiThread(() -> {
                    heart.setProgress(0);
                    bpmText.setText("--");
                    message.setText("Finger moved. Cover the camera and flash completely.");
                });
            }
            return;
        }

        if (state == State.WAITING) {
            state = State.MEASURING;
            startNanos = image.getTimestamp();
            lastBeatTime = 0;
            runOnUiThread(() -> {
                wave.clear();
                message.setText("Measuring… keep your finger still.");
            });
        }

        double elapsed = (image.getTimestamp() - startNanos) / 1e9;
        float progress = (float) (elapsed / (SETTLE_SECONDS + MEASURE_SECONDS));
        // Blood makes the image darker, so flip the sign: beats become peaks.
        float sample = (float) -brightness;
        if (elapsed < SETTLE_SECONDS) {
            runOnUiThread(() -> heart.setProgress(progress));
            return;
        }

        times.add(seconds);
        values.add(-brightness);
        Result result = analyse(times, values);
        boolean newBeat = result.lastPeak > lastBeatTime;
        if (newBeat) lastBeatTime = result.lastPeak;

        if (elapsed >= SETTLE_SECONDS + MEASURE_SECONDS) {
            if (result.bpm > 0) {
                state = State.DONE;
                new PulseStore(this).save(result.bpm);
                runOnUiThread(() -> {
                    heart.setProgress(1);
                    bpmText.setText(String.valueOf(result.bpm));
                    message.setText("Done! You can remove your finger.");
                    button.setText("Done");
                    stopCamera();
                });
            } else {
                fail("Couldn't get a steady reading. Rest your finger lightly, keep still and try again.");
            }
            return;
        }

        runOnUiThread(() -> {
            heart.setProgress(progress);
            wave.add(sample);
            if (result.bpm > 0) bpmText.setText(String.valueOf(result.bpm));
            if (newBeat) heart.beat();
        });
    }

    private void fail(String text) {
        state = State.FAILED;
        runOnUiThread(() -> {
            heart.setProgress(0);
            bpmText.setText("--");
            message.setText(text);
            button.setText("Try again");
            stopCamera();
        });
    }

    /** Average value of the centre of a camera plane, sampling every 4th pixel. */
    private static double average(Image.Plane plane, int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        long sum = 0;
        int n = 0;
        for (int y = height / 4; y < height * 3 / 4; y += 4) {
            for (int x = width / 4; x < width * 3 / 4; x += 4) {
                int index = y * rowStride + x * pixelStride;
                if (index < buffer.limit()) {
                    sum += buffer.get(index) & 0xFF;
                    n++;
                }
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    static class Result {
        int bpm = -1;
        double lastPeak;
    }

    /**
     * Finds heartbeats in the brightness signal: removes slow drift, smooths
     * it, picks local peaks at least 0.3 s apart and turns the median time
     * between peaks into beats per minute.
     */
    static Result analyse(List<Double> t, List<Double> v) {
        Result result = new Result();
        int n = t.size();
        if (n < 30) return result;
        double fps = (n - 1) / (t.get(n - 1) - t.get(0));
        if (fps <= 0) return result;

        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v.get(i);

        int trend = Math.max(3, (int) Math.round(fps * 1.0) / 2);
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

        int half = Math.max(2, (int) Math.round(fps * 0.25));
        List<Double> peaks = new ArrayList<>();
        for (int i = half; i < n - half; i++) {
            if (smooth[i] <= 0) continue;
            boolean isPeak = true;
            for (int j = i - half; j <= i + half && isPeak; j++) {
                if (smooth[j] > smooth[i]) isPeak = false;
            }
            if (!isPeak) continue;
            double time = t.get(i);
            if (!peaks.isEmpty() && time - peaks.get(peaks.size() - 1) < 0.3) continue;
            peaks.add(time);
        }
        if (!peaks.isEmpty()) result.lastPeak = peaks.get(peaks.size() - 1);

        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < peaks.size(); i++) {
            double d = peaks.get(i) - peaks.get(i - 1);
            if (d >= 0.3 && d <= 1.5) intervals.add(d);
        }
        if (intervals.size() < 4) return result;
        Collections.sort(intervals);
        double median = intervals.get(intervals.size() / 2);
        int bpm = (int) Math.round(60 / median);
        if (bpm >= 40 && bpm <= 200) result.bpm = bpm;
        return result;
    }
}
