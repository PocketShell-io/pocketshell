package com.pocketshell.app;

import android.app.Activity;
import android.os.CancellationSignal;
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
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Google sign-in through Android Credential Manager ("Sign in with Google").
 * The interactive path shows Google's account chooser; the silent path renews
 * a token for an already-authorized account without UI. Scopes are Google's
 * fixed openid/email/profile set — the same identity-only scopes the desktop
 * client requests.
 *
 * <p>Called off the main thread; it blocks until Credential Manager answers.
 */
final class CredentialManagerSignIn implements GoogleSyncSession.SignInProvider {
    private static final long INTERACTIVE_TIMEOUT_SECONDS = 300;
    private static final long SILENT_TIMEOUT_SECONDS = 30;

    private final Supplier<Activity> activity;
    private final String serverClientId;
    private final Executor callbacks = Executors.newSingleThreadExecutor();

    CredentialManagerSignIn(Supplier<Activity> activity, String serverClientId) {
        this.activity = activity;
        this.serverClientId = serverClientId;
    }

    @Override
    public String obtainIdToken(boolean interactive) throws SyncAuthException {
        Activity host = activity.get();
        if (host == null || host.isFinishing()) {
            throw new SyncAuthException(SyncAuthException.FAILED, "Google sign-in needs PocketShell in the foreground.");
        }
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(interactive
                        ? new GetSignInWithGoogleOption.Builder(serverClientId).build()
                        : new GetGoogleIdOption.Builder()
                                .setServerClientId(serverClientId)
                                .setFilterByAuthorizedAccounts(true)
                                .setAutoSelectEnabled(true)
                                .build())
                .build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<GetCredentialResponse> result = new AtomicReference<>();
        AtomicReference<GetCredentialException> failure = new AtomicReference<>();
        CancellationSignal cancel = new CancellationSignal();
        CredentialManager.create(host).getCredentialAsync(host, request, cancel, callbacks,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        result.set(response);
                        done.countDown();
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        failure.set(error);
                        done.countDown();
                    }
                });
        try {
            if (!done.await(interactive ? INTERACTIVE_TIMEOUT_SECONDS : SILENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancel.cancel();
                throw new SyncAuthException(SyncAuthException.FAILED, "Google sign-in did not answer in time.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel.cancel();
            throw new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was interrupted.");
        }
        if (failure.get() != null) throw describe(failure.get(), host.getPackageName());
        Credential credential = result.get().getCredential();
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            return GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
        }
        throw new SyncAuthException(SyncAuthException.FAILED, "Google returned an unexpected credential type.");
    }

    @Override
    public void clearCredentialState() {
        Activity host = activity.get();
        if (host == null) return;
        CredentialManager.create(host).clearCredentialStateAsync(new ClearCredentialStateRequest(), null, callbacks,
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void ignored) {
                    }

                    @Override
                    public void onError(ClearCredentialException ignored) {
                        // Local sign-out already holds; this only stops auto-select.
                    }
                });
    }

    /** User-facing words for a Credential Manager failure; never the raw provider message. */
    static SyncAuthException describe(GetCredentialException error, String packageName) {
        if (error instanceof GetCredentialCancellationException) {
            return new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was cancelled.");
        }
        String detail = String.valueOf(error.getMessage());
        if (error instanceof NoCredentialException || detail.contains("28444") || detail.contains("not set up")) {
            return new SyncAuthException(SyncAuthException.UNAVAILABLE,
                    "Google sign-in is not available for " + packageName
                            + ". Add a Google account to this phone, or register this app's Android OAuth client"
                            + " (see docs/settings-sync.md).");
        }
        return new SyncAuthException(SyncAuthException.FAILED, "Google sign-in failed (" + error.getType() + ").");
    }
}
