package com.simple.pedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-to-one video call with a friend, using WebRTC. Firestore carries the
 * call setup (offer, answer and network routes); the video and sound go
 * directly between the two phones. Google's public STUN server helps the
 * phones find each other; there is no relay server, so a few networks that
 * block direct connections can't be used.
 *
 * Started with {@link #EXTRA_FRIEND_UID} to call a friend, or with
 * {@link #EXTRA_CALL_ID} to answer an incoming call.
 */
public class CallActivity extends Activity {

    static final String EXTRA_FRIEND_UID = "friendUid";
    static final String EXTRA_FRIEND_NAME = "friendName";
    static final String EXTRA_CALL_ID = "callId";

    private static final int REQUEST_PERMISSIONS = 5;
    private static final long RING_TIMEOUT_MILLIS = 45_000;
    private static final String STUN_SERVER = "stun:stun.l.google.com:19302";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String myUid;
    private boolean incoming;
    private DocumentReference callRef;
    private ListenerRegistration callListener;
    private ListenerRegistration candidateListener;
    private DocumentSnapshot latestCall;

    private TextView nameText;
    private TextView statusText;
    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;
    private View controls;
    private View incomingButtons;
    private ImageButton micButton;
    private ImageButton cameraButton;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideo;
    private AudioTrack localAudio;

    private boolean mediaStarted;
    private boolean answered;
    private boolean connected;
    private boolean finished;
    private boolean remoteDescriptionSet;
    private boolean callDocReady;
    private boolean micOn = true;
    private boolean cameraOn = true;
    private boolean frontCamera = true;
    private long connectedAt;
    private final List<IceCandidate> pendingRemote = new ArrayList<>();
    private final List<IceCandidate> pendingLocal = new ArrayList<>();

    private Ringtone ringtone;
    private AudioManager audioManager;
    private int previousAudioMode;

    private final Runnable ringTimeout = () -> {
        if (!connected && !finished) {
            setStatus(incoming ? "declined" : "missed");
            endCall(incoming ? null : "No answer");
        }
    };

    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            long s = (System.currentTimeMillis() - connectedAt) / 1000;
            statusText.setText(String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        if (Friends.user() == null) {
            finish();
            return;
        }
        myUid = Friends.user().getUid();

        nameText = findViewById(R.id.call_name);
        statusText = findViewById(R.id.call_status);
        localView = findViewById(R.id.local_video);
        remoteView = findViewById(R.id.remote_video);
        controls = findViewById(R.id.call_controls);
        incomingButtons = findViewById(R.id.call_incoming);
        micButton = findViewById(R.id.call_mic);
        cameraButton = findViewById(R.id.call_camera);
        micButton.setOnClickListener(v -> toggleMic());
        cameraButton.setOnClickListener(v -> toggleCamera());
        findViewById(R.id.call_switch).setOnClickListener(v -> switchCamera());
        findViewById(R.id.call_hangup).setOnClickListener(v -> hangUp());
        findViewById(R.id.call_decline).setOnClickListener(v -> hangUp());
        findViewById(R.id.call_accept).setOnClickListener(v -> accept());

        nameText.setText(getIntent().getStringExtra(EXTRA_FRIEND_NAME));
        String callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        incoming = callId != null;
        if (incoming) {
            callRef = db.collection("calls").document(callId);
            statusText.setText("Incoming video call");
            incomingButtons.setVisibility(View.VISIBLE);
            startRinging();
            listenToCall();
            handler.postDelayed(ringTimeout, RING_TIMEOUT_MILLIS);
        } else {
            statusText.setText("Starting…");
            controls.setVisibility(View.VISIBLE);
            if (hasPermissions()) {
                startOutgoingCall();
            } else {
                requestPermissions();
            }
        }
    }

    // ------------------------------------------------------------ permissions

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!hasPermissions()) {
            setStatus(incoming ? "declined" : "ended");
            endCall("Camera and microphone permission are needed for video calls.");
        } else if (incoming) {
            accept();
        } else {
            startOutgoingCall();
        }
    }

    // --------------------------------------------------------------- calling

    private void startOutgoingCall() {
        startMedia();
        createPeer();
        statusText.setText("Calling…");
        callRef = db.collection("calls").document();
        peer.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                peer.setLocalDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        runOnUiThread(() -> publishOffer(offer));
                    }
                }, offer);
            }
        }, new MediaConstraints());
    }

    private void publishOffer(SessionDescription offer) {
        Map<String, Object> call = new HashMap<>();
        call.put("caller", myUid);
        call.put("callee", getIntent().getStringExtra(EXTRA_FRIEND_UID));
        call.put("callerName", Friends.myName(this));
        call.put("status", "ringing");
        call.put("offer", sdpToMap(offer));
        call.put("created", FieldValue.serverTimestamp());
        callRef.set(call).addOnSuccessListener(this, v -> {
            callDocReady = true;
            flushLocalCandidates();
            listenToCall();
            listenToCandidates();
            statusText.setText("Ringing…");
            handler.postDelayed(ringTimeout, RING_TIMEOUT_MILLIS);
        }).addOnFailureListener(this, e ->
                endCall("Couldn't start the call. You can only call friends who added you back."));
    }

    /** Answers an incoming call. */
    private void accept() {
        if (answered || finished) return;
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (latestCall == null || latestCall.get("offer") == null) {
            statusText.setText("Connecting…");
            handler.postDelayed(this::accept, 500);
            return;
        }
        answered = true;
        stopRinging();
        handler.removeCallbacks(ringTimeout);
        incomingButtons.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        statusText.setText("Connecting…");
        startMedia();
        createPeer();
        listenToCandidates();

        SessionDescription offer = mapToSdp(latestCall.get("offer"), SessionDescription.Type.OFFER);
        peer.setRemoteDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                runOnUiThread(() -> {
                    remoteDescriptionSet = true;
                    flushRemoteCandidates();
                    peer.createAnswer(new SdpAdapter() {
                        @Override
                        public void onCreateSuccess(SessionDescription answer) {
                            peer.setLocalDescription(new SdpAdapter() {
                                @Override
                                public void onSetSuccess() {
                                    runOnUiThread(() -> publishAnswer(answer));
                                }
                            }, answer);
                        }
                    }, new MediaConstraints());
                });
            }
        }, offer);
    }

    private void publishAnswer(SessionDescription answer) {
        callRef.update("answer", sdpToMap(answer), "status", "accepted")
                .addOnSuccessListener(this, v -> {
                    callDocReady = true;
                    flushLocalCandidates();
                })
                .addOnFailureListener(this, e -> endCall("Couldn't answer the call."));
    }

    private void listenToCall() {
        callListener = callRef.addSnapshotListener(this, (snap, e) -> {
            if (snap == null || !snap.exists()) return;
            latestCall = snap;
            String status = snap.getString("status");
            if (!incoming && !remoteDescriptionSet && snap.get("answer") != null) {
                remoteDescriptionSet = true;
                statusText.setText("Connecting…");
                handler.removeCallbacks(ringTimeout);
                peer.setRemoteDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        runOnUiThread(CallActivity.this::flushRemoteCandidates);
                    }
                }, mapToSdp(snap.get("answer"), SessionDescription.Type.ANSWER));
            }
            if ("declined".equals(status)) {
                endCall(incoming ? null : nameText.getText() + " is busy");
            } else if ("ended".equals(status) || "missed".equals(status)) {
                endCall(connected ? "Call ended" : (incoming ? "Missed call" : null));
            }
        });
    }

    private void listenToCandidates() {
        candidateListener = callRef.collection("candidates").addSnapshotListener(this, (snap, e) -> {
            if (snap == null) return;
            for (DocumentChange change : snap.getDocumentChanges()) {
                if (change.getType() != DocumentChange.Type.ADDED) continue;
                DocumentSnapshot doc = change.getDocument();
                if (myUid.equals(doc.getString("from"))) continue;
                Long index = doc.getLong("sdpMLineIndex");
                IceCandidate candidate = new IceCandidate(doc.getString("sdpMid"),
                        index == null ? 0 : index.intValue(), doc.getString("candidate"));
                if (remoteDescriptionSet && peer != null) {
                    peer.addIceCandidate(candidate);
                } else {
                    pendingRemote.add(candidate);
                }
            }
        });
    }

    private void flushRemoteCandidates() {
        for (IceCandidate c : pendingRemote) peer.addIceCandidate(c);
        pendingRemote.clear();
    }

    private void flushLocalCandidates() {
        for (IceCandidate c : pendingLocal) publishCandidate(c);
        pendingLocal.clear();
    }

    private void publishCandidate(IceCandidate c) {
        Map<String, Object> data = new HashMap<>();
        data.put("from", myUid);
        data.put("candidate", c.sdp);
        data.put("sdpMid", c.sdpMid == null ? "" : c.sdpMid);
        data.put("sdpMLineIndex", c.sdpMLineIndex);
        callRef.collection("candidates").add(data);
    }

    private void onConnected() {
        if (connected) return;
        connected = true;
        handler.removeCallbacks(ringTimeout);
        connectedAt = System.currentTimeMillis();
        handler.post(timer);
    }

    // ----------------------------------------------------------------- media

    private void startMedia() {
        if (mediaStarted) return;
        mediaStarted = true;
        egl = EglBase.create();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext()).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

        localView.init(egl.getEglBaseContext(), null);
        localView.setZOrderMediaOverlay(true);
        localView.setMirror(true);
        localView.setVisibility(View.VISIBLE);
        remoteView.init(egl.getEglBaseContext(), null);

        Camera2Enumerator cameras = new Camera2Enumerator(this);
        String cameraName = null;
        for (String name : cameras.getDeviceNames()) {
            if (cameras.isFrontFacing(name)) cameraName = name;
        }
        if (cameraName == null && cameras.getDeviceNames().length > 0) {
            cameraName = cameras.getDeviceNames()[0];
            frontCamera = false;
            localView.setMirror(false);
        }
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudio = factory.createAudioTrack("audio", audioSource);
        if (cameraName != null) {
            capturer = cameras.createCapturer(cameraName, null);
            textureHelper = SurfaceTextureHelper.create("CameraThread", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            capturer.initialize(textureHelper, this, videoSource.getCapturerObserver());
            capturer.startCapture(640, 480, 30);
            localVideo = factory.createVideoTrack("video", videoSource);
            localVideo.addSink(localView);
        }
        startCallAudio();
    }

    private void createPeer() {
        List<PeerConnection.IceServer> servers = Collections.singletonList(
                PeerConnection.IceServer.builder(STUN_SERVER).createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peer = factory.createPeerConnection(config, new PeerObserver());
        List<String> streams = Collections.singletonList("call");
        peer.addTrack(localAudio, streams);
        if (localVideo != null) peer.addTrack(localVideo, streams);
    }

    private void toggleMic() {
        if (localAudio == null) return;
        micOn = !micOn;
        localAudio.setEnabled(micOn);
        micButton.setBackgroundResource(micOn ? R.drawable.bg_call_button : R.drawable.bg_call_button_off);
        micButton.setColorFilter(micOn ? 0xFFFFFFFF : 0xFF15191E);
    }

    private void toggleCamera() {
        if (localVideo == null) return;
        cameraOn = !cameraOn;
        localVideo.setEnabled(cameraOn);
        localView.setVisibility(cameraOn ? View.VISIBLE : View.INVISIBLE);
        cameraButton.setBackgroundResource(cameraOn ? R.drawable.bg_call_button : R.drawable.bg_call_button_off);
        cameraButton.setColorFilter(cameraOn ? 0xFFFFFFFF : 0xFF15191E);
    }

    private void switchCamera() {
        if (capturer == null) return;
        capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFront) {
                runOnUiThread(() -> {
                    frontCamera = isFront;
                    localView.setMirror(isFront);
                });
            }

            @Override
            public void onCameraSwitchError(String error) {
            }
        });
    }

    /** Routes call audio to the loudspeaker, like most video call apps. */
    @SuppressWarnings("deprecation")
    private void startCallAudio() {
        audioManager = getSystemService(AudioManager.class);
        previousAudioMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audioManager.setCommunicationDevice(device);
                    break;
                }
            }
        } else {
            audioManager.setSpeakerphoneOn(true);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopCallAudio() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            audioManager.setSpeakerphoneOn(false);
        }
        audioManager.setMode(previousAudioMode);
    }

    private void startRinging() {
        ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        if (ringtone != null) ringtone.play();
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 800}, 0));
        }
    }

    private void stopRinging() {
        if (ringtone != null) ringtone.stop();
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator != null) vibrator.cancel();
    }

    // ---------------------------------------------------------------- ending

    private void hangUp() {
        if (incoming && !answered) {
            setStatus("declined");
        } else {
            setStatus("ended");
        }
        endCall(null);
    }

    private void setStatus(String status) {
        if (callRef != null && (callDocReady || incoming)) callRef.update("status", status);
    }

    private void endCall(String message) {
        if (finished) return;
        finished = true;
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onBackPressed() {
        hangUp();
    }

    @Override
    protected void onDestroy() {
        if (!finished) setStatus(incoming && !answered ? "declined" : "ended");
        finished = true;
        handler.removeCallbacksAndMessages(null);
        stopRinging();
        if (callListener != null) callListener.remove();
        if (candidateListener != null) candidateListener.remove();
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            capturer.dispose();
        }
        if (peer != null) peer.dispose();
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (textureHelper != null) textureHelper.dispose();
        if (mediaStarted) {
            localView.release();
            remoteView.release();
        }
        if (factory != null) factory.dispose();
        if (egl != null) egl.release();
        stopCallAudio();
        super.onDestroy();
    }

    // --------------------------------------------------------------- helpers

    private static Map<String, Object> sdpToMap(SessionDescription sdp) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", sdp.type.canonicalForm());
        map.put("sdp", sdp.description);
        return map;
    }

    private static SessionDescription mapToSdp(Object value, SessionDescription.Type type) {
        Map<?, ?> map = (Map<?, ?>) value;
        return new SessionDescription(type, String.valueOf(map.get("sdp")));
    }

    /** Starts a call to a friend. */
    static void call(Context context, String friendUid, String friendName) {
        context.startActivity(new Intent(context, CallActivity.class)
                .putExtra(EXTRA_FRIEND_UID, friendUid)
                .putExtra(EXTRA_FRIEND_NAME, friendName));
    }

    /** Shows the ringing screen for an incoming call. */
    static void ring(Context context, String callId, String callerName) {
        context.startActivity(new Intent(context, CallActivity.class)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_FRIEND_NAME, callerName));
    }

    /** SdpObserver with empty defaults. */
    private static class SdpAdapter implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
        }

        @Override
        public void onSetFailure(String error) {
        }
    }

    /** Receives WebRTC events (on WebRTC's own thread) and hands them to the main thread. */
    private class PeerObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(IceCandidate candidate) {
            runOnUiThread(() -> {
                if (callDocReady) {
                    publishCandidate(candidate);
                } else {
                    pendingLocal.add(candidate);
                }
            });
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            if (track instanceof VideoTrack) {
                runOnUiThread(() -> ((VideoTrack) track).addSink(remoteView));
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            runOnUiThread(() -> {
                if (finished) return;
                switch (state) {
                    case CONNECTED:
                        onConnected();
                        break;
                    case DISCONNECTED:
                        statusText.setText("Reconnecting…");
                        break;
                    case FAILED:
                        setStatus("ended");
                        endCall("Couldn't connect. One of the networks may block direct video calls.");
                        break;
                    default:
                        break;
                }
            });
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(MediaStream stream) {
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
        }

        @Override
        public void onDataChannel(DataChannel channel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }
    }
}
