package com.simple.pedometer;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
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
 * Firebase (Google Sign-In + Firestore). Each person has a short friend code. You see a friend only after
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

    private static FirebaseFirestore db() {
        return FirebaseFirestore.getInstance();
    }

    static FirebaseUser user() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    static boolean isSignedIn() {
        return user() != null;
    }

    private static Task<String> uid() {
        FirebaseUser user = user();
        return user != null ? Tasks.forResult(user.getUid())
                : Tasks.forException(new IllegalStateException("Not signed in"));
    }

    /** Signs in to Firebase with the ID token from "Sign in with Google", then restores any earlier setup. */
    static Task<Void> signInWithGoogle(Context context, String idToken) {
        return FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .continueWithTask(t -> {
                    t.getResult();
                    return restore(context);
                });
    }

    /**
     * After signing in (e.g. on a new phone or after reinstalling), brings
     * back your name, friend code and friends from your Firestore document.
     */
    private static Task<Void> restore(Context context) {
        return uid().continueWithTask(t -> db().collection("users").document(t.getResult()).get())
                .continueWith(t -> {
                    DocumentSnapshot doc = t.getResult();
                    if (!doc.exists()) return null;
                    prefs(context).edit()
                            .putString(KEY_NAME, doc.getString("name"))
                            .putString(KEY_CODE, doc.getString("code"))
                            .apply();
                    List<Friend> friends = new ArrayList<>();
                    Object readers = doc.get("readers");
                    if (readers instanceof List) {
                        for (Object uid : (List<?>) readers) {
                            Friend f = new Friend();
                            f.uid = String.valueOf(uid);
                            f.code = "";
                            friends.add(f);
                        }
                    }
                    saveFriends(context, friends);
                    return null;
                });
    }

    /** Signs out and forgets the friends setup on this phone (it stays saved in your account). */
    static void signOut(Context context) {
        FirebaseAuth.getInstance().signOut();
        prefs(context).edit().clear().apply();
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
        return isSignedIn() && !myCode(context).isEmpty();
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
        return uid().continueWithTask(t -> claimCode(t.getResult(), 0))
                .continueWithTask(t -> {
                    prefs(context).edit().putString(KEY_CODE, t.getResult()).apply();
                    return upload(context);
                });
    }

    /** Tries random codes until one is free (creating an existing code is refused). */
    private static Task<String> claimCode(String uid, int attempt) {
        String code = randomCode();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        return db().collection("codes").document(code).set(data).continueWithTask(t -> {
            if (t.isSuccessful()) return Tasks.forResult(code);
            if (attempt >= 4) return Tasks.forException(t.getException());
            return claimCode(uid, attempt + 1);
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
        return uid().continueWithTask(t ->
                db().collection("users").document(t.getResult()).set(data));
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
        return db().collection("codes").document(code).get().continueWithTask(t -> {
            DocumentSnapshot doc = t.getResult();
            if (!doc.exists() || doc.getString("uid") == null) {
                throw new IllegalArgumentException("No one has the code " + code + ".");
            }
            for (Friend existing : friends) {
                if (existing.uid.equals(doc.getString("uid"))) {
                    throw new IllegalArgumentException("You already added " + code + ".");
                }
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
            DocumentReference ref = db().collection("users").document(f.uid);
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
                if (a.code == null) a.code = friends.get(i).code;
                if (a.name == null) a.name = a.code.isEmpty() ? "Friend" : a.code;
                result.add(a);
            }
            return result;
        });
    }

    private static Activity fromDocument(DocumentSnapshot doc) {
        Activity a = new Activity();
        a.name = doc.getString("name");
        a.code = doc.getString("code");
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
