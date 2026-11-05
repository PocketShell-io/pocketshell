package com.pocketshell.app;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Lets the user bypass a phone's unavailable Google credential provider deliberately. */
final class GoogleSignInMethodDialog implements SelectedGoogleSignIn.Selector {
    private final Supplier<Activity> activity;

    GoogleSignInMethodDialog(Supplier<Activity> activity) {
        this.activity = activity;
    }

    @Override public SelectedGoogleSignIn.Method choose() throws SyncAuthException {
        Activity host = activity.get();
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            throw new SyncAuthException(SyncAuthException.FAILED,
                    "Google sign-in needs PocketShell in the foreground.");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<SelectedGoogleSignIn.Method> choice =
                new AtomicReference<>(SelectedGoogleSignIn.Method.CANCEL);
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        host.runOnUiThread(() -> {
            if (host.isFinishing() || host.isDestroyed()) {
                done.countDown();
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(host)
                    .setTitle("Sign in with Google")
                    .setMessage("Choose how to sign in. If selecting an account on this phone returns you without signing in, use your browser.")
                    .setPositiveButton("Use browser", (ignored, which) ->
                            choice.set(SelectedGoogleSignIn.Method.BROWSER))
                    .setNeutralButton("Account on this phone", (ignored, which) ->
                            choice.set(SelectedGoogleSignIn.Method.DEVICE))
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnDismissListener(ignored -> done.countDown());
            shown.set(dialog);
            dialog.show();
        });
        try {
            if (!done.await(5, TimeUnit.MINUTES)) {
                host.runOnUiThread(() -> { if (shown.get() != null) shown.get().dismiss(); });
                throw new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was cancelled.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            host.runOnUiThread(() -> { if (shown.get() != null) shown.get().dismiss(); });
            throw new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was interrupted.");
        }
        return choice.get();
    }
}
