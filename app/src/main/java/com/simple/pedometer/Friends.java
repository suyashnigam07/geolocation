package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sharing daily activity with up to {@link #MAX_FRIENDS} friends through
 * Firebase. Each person has a short friend code. You see a friend only after
 * you have both added each other's code: each person's data can only be read
 * by the people they added themselves (enforced by the Firestore rules).
 *
 * Shared: name, steps for the last 7 days, today's active minutes and calories.
 */
final class Friends {

    static final int MAX_FRIENDS = 3;
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String KEY_NAME = "name";
    private static final String KEY_CODE = "code";
    private static final String KEY_FRIENDS = "friends";

    private Friends() {
    }

    /** A friend as stored on this phone. */
    static final class Friend {
        String uid;
        String code;
    }

    /** What we know about a friend (or yourself) for the dashboard. */
    static final class Activity {
        String uid;
        String code;
        String name;
        boolean isMe;
        /** True until the friend has added you back. */
        boolean pending;
        int[] last7Days = new int[7];
        int stepsToday;
        int activeMinutes;
        int calories;
        long updatedMillis;
    }

    // ------------------------------------------------------------ Firebase

    static boolean isConfigured() {
        return !BuildConfig.FIREBASE_PROJECT_ID.isEmpty();
    }

    private static synchronized FirebaseApp app(Context context) {
        if (!FirebaseApp.getApps(context).isEmpty()) return FirebaseApp.getInstance();
        return FirebaseApp.initializeApp(context.getApplicationContext(), new FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build());
    }

    private static FirebaseFirestore db(Context context) {
        return FirebaseFirestore.getInstance(app(context));
    }

    /** Signs in anonymously (once per install) and returns this phone's user id. */
    private static Task<String> uid(Context context) {
        FirebaseAuth auth = FirebaseAuth.getInstance(app(context));
        if (auth.getCurrentUser() != null) return Tasks.forResult(auth.getCurrentUser().getUid());
        return auth.signInAnonymously().continueWith(t -> t.getResult().getUser().getUid());
    }

    // --------------------------------------------------------- local state

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("friends", Context.MODE_PRIVATE);
    }

    static String myName(Context context) {
        return prefs(context).getString(KEY_NAME, "");
    }

    static String myCode(Context context) {
        return prefs(context).getString(KEY_CODE, "");
    }

    static boolean isSetUp(Context context) {
        return isConfigured() && !myCode(context).isEmpty();
    }

    static List<Friend> friends(Context context) {
        List<Friend> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_FRIENDS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Friend f = new Friend();
                f.uid = array.getJSONObject(i).getString("uid");
                f.code = array.getJSONObject(i).getString("code");
                list.add(f);
            }
        } catch (JSONException e) {
            // Corrupt data: treat as no friends.
        }
        return list;
    }

    private static void saveFriends(Context context, List<Friend> friends) {
        JSONArray array = new JSONArray();
        try {
            for (Friend f : friends) array.put(new JSONObject().put("uid", f.uid).put("code", f.code));
        } catch (JSONException e) {
            return;
        }
        prefs(context).edit().putString(KEY_FRIENDS, array.toString()).apply();
    }

    // ------------------------------------------------------------- actions

    /** First-time setup: picks a free friend code for this phone and uploads. */
    static Task<Void> setUp(Context context, String name) {
        prefs(context).edit().putString(KEY_NAME, name).apply();
        return uid(context).continueWithTask(t -> claimCode(context, t.getResult(), 0))
                .continueWithTask(t -> {
                    prefs(context).edit().putString(KEY_CODE, t.getResult()).apply();
                    return upload(context);
                });
    }

    /** Tries random codes until one is free (creating an existing code is refused). */
    private static Task<String> claimCode(Context context, String uid, int attempt) {
        String code = randomCode();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        return db(context).collection("codes").document(code).set(data).continueWithTask(t -> {
            if (t.isSuccessful()) return Tasks.forResult(code);
            if (attempt >= 4) return Tasks.forException(t.getException());
            return claimCode(context, uid, attempt + 1);
        });
    }

    private static String randomCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        return sb.toString();
    }

    /** Uploads today's summary. Does nothing if friends aren't set up. */
    static Task<Void> upload(Context context) {
        if (!isSetUp(context)) return Tasks.forResult(null);
        StepStore store = new StepStore(context);
        LocalDate today = LocalDate.now();
        Map<String, Object> days = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.minusDays(i);
            days.put(day.toString(), store.getSteps(day));
        }
        List<String> readers = new ArrayList<>();
        for (Friend f : friends(context)) readers.add(f.uid);

        Map<String, Object> data = new HashMap<>();
        data.put("name", myName(context));
        data.put("code", myCode(context));
        data.put("readers", readers);
        data.put("days", days);
        data.put("date", today.toString());
        data.put("active", store.getActiveMinutes(today));
        data.put("calories", store.caloriesFor(store.getToday()));
        data.put("updated", FieldValue.serverTimestamp());
        return uid(context).continueWithTask(t ->
                db(context).collection("users").document(t.getResult()).set(data));
    }

    /** Adds a friend by their code; you'll see them once they add your code too. */
    static Task<Void> add(Context context, String rawCode) {
        String code = rawCode.trim().toUpperCase();
        if (code.equals(myCode(context))) {
            return Tasks.forException(new IllegalArgumentException("That's your own code."));
        }
        List<Friend> friends = friends(context);
        if (friends.size() >= MAX_FRIENDS) {
            return Tasks.forException(new IllegalArgumentException(
                    "You can follow up to " + MAX_FRIENDS + " friends."));
        }
        for (Friend f : friends) {
            if (f.code.equals(code)) {
                return Tasks.forException(new IllegalArgumentException("You already added " + code + "."));
            }
        }
        return db(context).collection("codes").document(code).get().continueWithTask(t -> {
            DocumentSnapshot doc = t.getResult();
            if (!doc.exists() || doc.getString("uid") == null) {
                throw new IllegalArgumentException("No one has the code " + code + ".");
            }
            Friend f = new Friend();
            f.uid = doc.getString("uid");
            f.code = code;
            friends.add(f);
            saveFriends(context, friends);
            return upload(context);
        });
    }

    /** Stops sharing with a friend and removes them from your dashboard. */
    static Task<Void> remove(Context context, String uid) {
        List<Friend> friends = friends(context);
        friends.removeIf(f -> f.uid.equals(uid));
        saveFriends(context, friends);
        return upload(context);
    }

    /** Fetches every friend's latest summary. Friends who haven't added you back are pending. */
    static Task<List<Activity>> fetch(Context context) {
        List<Friend> friends = friends(context);
        List<Task<DocumentSnapshot>> reads = new ArrayList<>();
        for (Friend f : friends) {
            DocumentReference ref = db(context).collection("users").document(f.uid);
            reads.add(ref.get());
        }
        return Tasks.whenAllComplete(reads).continueWith(t -> {
            List<Activity> result = new ArrayList<>();
            for (int i = 0; i < friends.size(); i++) {
                Task<DocumentSnapshot> read = reads.get(i);
                Activity a = read.isSuccessful() && read.getResult().exists()
                        ? fromDocument(read.getResult()) : new Activity();
                a.pending = !read.isSuccessful() || !read.getResult().exists();
                a.uid = friends.get(i).uid;
                a.code = friends.get(i).code;
                if (a.name == null) a.name = a.code;
                result.add(a);
            }
            return result;
        });
    }

    private static Activity fromDocument(DocumentSnapshot doc) {
        Activity a = new Activity();
        a.name = doc.getString("name");
        LocalDate today = LocalDate.now();
        Object days = doc.get("days");
        if (days instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) days;
            for (int i = 0; i < 7; i++) {
                Object v = map.get(today.minusDays(6 - i).toString());
                a.last7Days[i] = v instanceof Number ? ((Number) v).intValue() : 0;
            }
        }
        a.stepsToday = a.last7Days[6];
        // Active minutes and calories are for the day they were uploaded.
        if (today.toString().equals(doc.getString("date"))) {
            Long active = doc.getLong("active");
            Long calories = doc.getLong("calories");
            a.activeMinutes = active == null ? 0 : active.intValue();
            a.calories = calories == null ? 0 : calories.intValue();
        }
        Timestamp updated = doc.getTimestamp("updated");
        a.updatedMillis = updated == null ? 0 : updated.toDate().getTime();
        return a;
    }

    /** Your own numbers, for the dashboard. */
    static Activity me(Context context) {
        StepStore store = new StepStore(context);
        LocalDate today = LocalDate.now();
        Activity a = new Activity();
        a.isMe = true;
        a.name = myName(context);
        a.code = myCode(context);
        for (int i = 0; i < 7; i++) a.last7Days[i] = store.getSteps(today.minusDays(6 - i));
        a.stepsToday = a.last7Days[6];
        a.activeMinutes = store.getActiveMinutes(today);
        a.calories = store.caloriesFor(a.stepsToday);
        a.updatedMillis = System.currentTimeMillis();
        return a;
    }
}
