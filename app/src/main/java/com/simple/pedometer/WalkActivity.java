package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.JointType;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Start/stop a GPS walk, see it live, and browse past walks. */
public class WalkActivity extends Activity {

    private static final int REQUEST_LOCATION = 3;
    private static final DateTimeFormatter WALK_DATE =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault());

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WalkStore store;
    private RouteView route;
    private TextView caption;
    private TextView time;
    private TextView distance;
    private TextView pace;
    private TextView steps;
    private TextView message;
    private Button button;
    private LinearLayout list;
    private Walk selected;

    // Street map (only when a Google Maps API key was built in).
    private final boolean useMap = !BuildConfig.MAPS_API_KEY.isEmpty();
    private MapView mapView;
    private GoogleMap map;
    private Walk drawnWalk;
    private int drawnPoints = -1;
    private boolean wasRecording;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_walk);
        store = new WalkStore(this);
        route = findViewById(R.id.route);
        caption = findViewById(R.id.walk_caption);
        time = findViewById(R.id.walk_time);
        distance = findViewById(R.id.walk_distance);
        pace = findViewById(R.id.walk_pace);
        steps = findViewById(R.id.walk_steps);
        message = findViewById(R.id.walk_message);
        button = findViewById(R.id.walk_button);
        list = findViewById(R.id.walk_list);
        mapView = findViewById(R.id.map);
        if (useMap) {
            mapView.setVisibility(View.VISIBLE);
            route.setVisibility(View.GONE);
            mapView.onCreate(savedInstanceState == null ? null : savedInstanceState.getBundle("map"));
            mapView.getMapAsync(googleMap -> {
                map = googleMap;
                map.getUiSettings().setMapToolbarEnabled(false);
                enableMyLocation();
                drawnPoints = -1;
                update();
            });
        }
        button.setOnClickListener(v -> {
            if (WalkSession.isRecording()) {
                WalkService.stop(this);
            } else {
                startWalk();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (useMap) mapView.onStart();
    }

    @Override
    protected void onStop() {
        if (useMap) mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (useMap) mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (useMap) mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (useMap) {
            Bundle mapState = new Bundle();
            mapView.onSaveInstanceState(mapState);
            outState.putBundle("map", mapState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (useMap) mapView.onResume();
        showHistory();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        if (useMap) mapView.onPause();
        super.onPause();
    }

    private void startWalk() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please turn on Location", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        selected = null;
        WalkService.start(this);
        handler.postDelayed(this::update, 300);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
            startWalk();
        } else {
            Toast.makeText(this, "Location permission is needed to record walks.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Refreshes the top card with the live walk, or the selected past walk. */
    private void update() {
        boolean recording = WalkSession.isRecording();
        if (wasRecording && !recording) {
            // A walk just finished and was saved: list it and show it.
            wasRecording = false;
            selected = null;
            showHistory();
            return;
        }
        wasRecording = recording;

        Walk walk;
        long elapsed;
        if (recording) {
            walk = WalkSession.current;
            elapsed = WalkSession.elapsedMillis();
            caption.setText("●  Recording");
            button.setText("Stop walk");
            message.setText(walk.route.isEmpty()
                    ? "Waiting for GPS signal… stay outdoors with a clear view of the sky."
                    : "Recording continues with the screen off.");
        } else {
            walk = selected;
            elapsed = walk == null ? 0 : walk.durationMillis;
            caption.setText(walk == null ? "GPS walk"
                    : Instant.ofEpochMilli(walk.startMillis).atZone(ZoneId.systemDefault()).format(WALK_DATE));
            button.setText("Start walk");
            message.setText("GPS works best outdoors. Recording continues with the screen off.");
        }

        time.setText(Walk.duration(elapsed));
        distance.setText(walk == null ? "0 m" : Walk.distance(walk.distanceMeters));
        pace.setText(walk == null ? "--" : Walk.pace(elapsed, walk.distanceMeters));
        steps.setText(walk == null ? "0" : String.valueOf(walk.steps));
        if (useMap) {
            drawOnMap(walk, recording);
        } else {
            route.setRoute(walk == null ? null : walk.route,
                    recording ? "Waiting for GPS…" : "Tap Start walk to record a route");
        }
    }

    /** Shows the blue "you are here" dot when location permission is granted. */
    private void enableMyLocation() {
        if (map == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            map.setMyLocationEnabled(true);
        } catch (SecurityException e) {
            // Permission was revoked meanwhile.
        }
    }

    /** Draws the walk's route on the street map, redrawing only when it changed. */
    private void drawOnMap(Walk walk, boolean recording) {
        if (map == null) return;
        int points = walk == null ? 0 : walk.route.size();
        if (walk == drawnWalk && points == drawnPoints) return;
        drawnWalk = walk;
        drawnPoints = points;

        map.clear();
        if (points == 0) return;

        List<LatLng> path = new ArrayList<>();
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (double[] p : walk.route) {
            LatLng point = new LatLng(p[0], p[1]);
            path.add(point);
            bounds.include(point);
        }
        float density = getResources().getDisplayMetrics().density;
        map.addPolyline(new PolylineOptions()
                .addAll(path)
                .width(6 * density)
                .color(getColor(R.color.accent))
                .jointType(JointType.ROUND)
                .startCap(new RoundCap())
                .endCap(new RoundCap()));
        LatLng start = path.get(0);
        LatLng end = path.get(path.size() - 1);
        map.addMarker(new MarkerOptions().position(start).title("Start")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));

        if (recording) {
            // Follow the walker.
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(end, 17));
            return;
        }
        if (points > 1) {
            map.addMarker(new MarkerOptions().position(end).title("Finish")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        }
        int width = mapView.getWidth();
        int height = mapView.getHeight();
        if (points > 1 && width > 0 && height > 0) {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), width, height, (int) (40 * density)));
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 16));
        }
    }

    private void showHistory() {
        List<Walk> walks = store.getWalks();
        if (selected == null && !walks.isEmpty()) selected = walks.get(0);
        list.removeAllViews();
        if (walks.isEmpty()) {
            list.addView(row("No walks yet", "Walks longer than a minute are saved here.", null));
        }
        for (Walk w : walks) {
            String title = Instant.ofEpochMilli(w.startMillis).atZone(ZoneId.systemDefault()).format(WALK_DATE);
            String detail = Walk.distance(w.distanceMeters) + "  ·  " + Walk.duration(w.durationMillis)
                    + "  ·  " + Walk.pace(w.durationMillis, w.distanceMeters) + "  ·  " + w.steps + " steps";
            list.addView(row(title, detail, v -> {
                if (WalkSession.isRecording()) return;
                selected = w;
                update();
                ((ScrollView) findViewById(R.id.walk_scroll)).smoothScrollTo(0, 0);
            }));
        }
        update();
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
