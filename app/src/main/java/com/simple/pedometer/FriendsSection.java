package com.simple.pedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** The "Friends today" card on the main screen: you and up to 3 friends, ranked by steps. */
class FriendsSection {

    private static final String APK_LINK =
            "https://github.com/suyashnigam07/geolocation/releases/download/latest/Pedometer.apk";
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};
    private static final int DAILY_GOAL = 10000;

    private final Activity activity;
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance();
    private final TextView code;
    private final View signIn;
    private final View setup;
    private final TextView signOut;
    private final EditText name;
    private final LinearLayout list;
    private final TextView message;
    private final Button add;
    private List<Friends.Activity> lastFriends = new ArrayList<>();

    FriendsSection(Activity activity) {
        this.activity = activity;
        code = activity.findViewById(R.id.friends_code);
        signIn = activity.findViewById(R.id.friends_signin);
        setup = activity.findViewById(R.id.friends_setup);
        signOut = activity.findViewById(R.id.friends_signout);
        name = activity.findViewById(R.id.friends_name);
        list = activity.findViewById(R.id.friends_list);
        message = activity.findViewById(R.id.friends_message);
        add = activity.findViewById(R.id.friends_add);
        activity.findViewById(R.id.friends_google).setOnClickListener(v -> signInWithGoogle());
        activity.findViewById(R.id.friends_start).setOnClickListener(v -> startSharing());
        signOut.setOnClickListener(v -> confirmSignOut());
        add.setOnClickListener(v -> askForCode());
        code.setOnClickListener(v -> shareCode());
    }

    /** Uploads your numbers and fetches your friends'. Call when the screen opens. */
    void refresh() {
        boolean signedIn = Friends.isSignedIn();
        boolean setUp = Friends.isSetUp(activity);
        signIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        setup.setVisibility(signedIn && !setUp ? View.VISIBLE : View.GONE);
        signOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            String email = Friends.user().getEmail();
            signOut.setText("Signed in" + (email == null ? "" : " as " + email) + " \u00B7 Sign out");
        }
        if (!setUp) {
            add.setVisibility(View.GONE);
            code.setText("");
            list.removeAllViews();
            if (signedIn && name.getText().length() == 0 && Friends.user().getDisplayName() != null) {
                // Suggest the first name from the Google account.
                name.setText(Friends.user().getDisplayName().split(" ")[0]);
            }
            showMessage(null);
            return;
        }
        code.setText("Your code: " + Friends.myCode(activity) + "  \u2197");
        render(lastFriends);
        Friends.upload(activity);
        Friends.fetch(activity).addOnCompleteListener(activity, t -> {
            if (t.isSuccessful()) {
                lastFriends = t.getResult();
                render(lastFriends);
            } else {
                showMessage("Couldn't reach the internet to update friends.");
            }
        });
    }

    /** Shows Google's account picker, then signs in to Firebase with the chosen account. */
    private void signInWithGoogle() {
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(
                activity.getString(R.string.default_web_client_id)).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        showMessage("Signing in…");
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(),
                mainThread(), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (!(credential instanceof CustomCredential) || !GoogleIdTokenCredential
                                .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            showMessage("Sign-in failed: unexpected account type.");
                            return;
                        }
                        String idToken;
                        try {
                            idToken = GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
                        } catch (Exception e) {
                            showMessage("Sign-in failed. Please try again.");
                            return;
                        }
                        Friends.signInWithGoogle(activity, idToken).addOnCompleteListener(activity, t -> {
                            if (t.isSuccessful()) {
                                refresh();
                            } else {
                                showMessage("Sign-in failed. Check your internet connection and try again.");
                            }
                        });
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            showMessage(null);
                        } else if (e instanceof NoCredentialException) {
                            showMessage("No Google account found. Add one in your phone's Settings → Accounts.");
                        } else {
                            showMessage("Sign-in failed: " + e.getMessage());
                        }
                    }
                });
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(activity)
                .setTitle("Sign out?")
                .setMessage("Friends stop updating on this phone until you sign in again. "
                        + "Your code and friends stay saved in your account.")
                .setPositiveButton("Sign out", (d, w) -> {
                    Friends.signOut(activity);
                    lastFriends = new ArrayList<>();
                    // Also forget the chosen account so the account picker shows next time.
                    CredentialManager.create(activity).clearCredentialStateAsync(
                            new ClearCredentialStateRequest(), new CancellationSignal(), mainThread(),
                            new CredentialManagerCallback<Void, ClearCredentialException>() {
                                @Override
                                public void onResult(Void result) {
                                }

                                @Override
                                public void onError(ClearCredentialException e) {
                                }
                            });
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static Executor mainThread() {
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    /** Called when your own steps change, without going online. */
    void updateMe() {
        if (Friends.isSetUp(activity)) render(lastFriends);
    }

    private void render(List<Friends.Activity> friends) {
        List<Friends.Activity> ranked = new ArrayList<>();
        List<Friends.Activity> pending = new ArrayList<>();
        ranked.add(Friends.me(activity));
        for (Friends.Activity f : friends) (f.pending ? pending : ranked).add(f);
        ranked.sort((a, b) -> Integer.compare(b.stepsToday, a.stepsToday));

        list.removeAllViews();
        for (int i = 0; i < ranked.size(); i++) list.addView(row(ranked.get(i), i));
        for (Friends.Activity f : pending) list.addView(row(f, -1));

        int count = Friends.friends(activity).size();
        add.setVisibility(count < Friends.MAX_FRIENDS ? View.VISIBLE : View.GONE);
        showMessage(count == 0
                ? "Tap \"Your code\" to send it to a friend, then add their code here."
                : null);
    }

    private View row(Friends.Activity a, int rank) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_friend, list, false);
        TextView rankText = row.findViewById(R.id.friend_rank);
        StepRingView ring = row.findViewById(R.id.friend_ring);
        TextView initial = row.findViewById(R.id.friend_initial);
        TextView nameText = row.findViewById(R.id.friend_name);
        TextView steps = row.findViewById(R.id.friend_steps);
        TextView detail = row.findViewById(R.id.friend_detail);
        MiniBarsView bars = row.findViewById(R.id.friend_bars);

        String displayName = a.name == null || a.name.isEmpty() ? a.code : a.name;
        initial.setText(displayName.substring(0, 1).toUpperCase());
        nameText.setText(a.isMe ? displayName + " (you)" : displayName);

        if (a.pending) {
            rankText.setText("⏳");
            ring.setProgress(0);
            steps.setText("Waiting for them to add you back");
            detail.setText("Ask " + (a.code.isEmpty() ? "your friend" : a.code)
                    + " to add your code " + Friends.myCode(activity));
            bars.setVisibility(View.INVISIBLE);
        } else {
            rankText.setText(rank < MEDALS.length ? MEDALS[rank] : String.valueOf(rank + 1));
            ring.setProgress((float) a.stepsToday / DAILY_GOAL);
            steps.setText(numberFormat.format(a.stepsToday) + " steps");
            String info = a.activeMinutes + " active min · " + numberFormat.format(a.calories) + " kcal";
            if (!a.isMe && a.updatedMillis > 0) {
                info += " · " + DateUtils.getRelativeTimeSpanString(a.updatedMillis,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            }
            detail.setText(info);
            bars.setValues(a.last7Days);
        }
        if (!a.isMe) row.setOnLongClickListener(v -> {
            confirmRemove(a, displayName);
            return true;
        });
        return row;
    }

    private void startSharing() {
        String chosen = name.getText().toString().trim();
        if (chosen.isEmpty()) {
            name.setError("Please enter your name");
            return;
        }
        showMessage("Setting up…");
        Friends.setUp(activity, chosen).addOnCompleteListener(activity, t -> {
            if (t.isSuccessful()) {
                refresh();
            } else {
                showMessage("Couldn't set up sharing. Check your internet connection and try again.");
            }
        });
    }

    private void askForCode() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        input.setHint("e.g. K7P2QX");
        LinearLayout box = new LinearLayout(activity);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("Add a friend")
                .setMessage("Enter your friend's 6-letter code. They need to add your code ("
                        + Friends.myCode(activity) + ") too.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    showMessage("Adding…");
                    Friends.add(activity, input.getText().toString()).addOnCompleteListener(activity, t -> {
                        if (t.isSuccessful()) {
                            refresh();
                        } else {
                            Exception e = t.getException();
                            showMessage(e instanceof IllegalArgumentException ? e.getMessage()
                                    : "Couldn't add the friend. Check the code and your internet connection.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(Friends.Activity friend, String displayName) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove " + displayName + "?")
                .setMessage("They will no longer see your steps, and you won't see theirs.")
                .setPositiveButton("Remove", (d, w) -> Friends.remove(activity, friend.uid)
                        .addOnCompleteListener(activity, t -> {
                            lastFriends.removeIf(f -> f.uid.equals(friend.uid));
                            refresh();
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareCode() {
        String text = "Let's track our steps together! Install the Pedometer app: " + APK_LINK
                + "\nThen open \"Friends today\", tap Add friend and enter my code: " + Friends.myCode(activity);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, "Share your friend code"));
    }

    private void showMessage(String text) {
        message.setVisibility(text == null ? View.GONE : View.VISIBLE);
        message.setText(text);
    }
}
