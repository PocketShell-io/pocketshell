package com.pocketshell.app.smoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.GoogleSyncSession;
import com.pocketshell.app.SyncAuthException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Issue #3189: the two fake edges for the offline-first and sign-in journeys,
 * in the non-happy states the maintainer hit on the phone. Everything between
 * them (the GoogleSync plugin, encrypted token store, the shared picker, the
 * vault adapter) is production code.
 *
 * <ul>
 *   <li>{@link Mode#STALLED}: every sync request hangs until the test ends —
 *       a sync service that never answers.</li>
 *   <li>{@link Mode#FAILING}: every request fails on the transport with
 *       {@link SocketTimeoutException}, exactly what the phone reported.</li>
 *   <li>{@link Mode#REVOKED}: the authorizer refuses the token signed in
 *       before the revocation (401) — the account was signed out, revoked or
 *       deleted server-side. A token from a later sign-in is accepted (and
 *       then stalls, so nothing past the sign-in is needed).</li>
 * </ul>
 *
 * <p>Google's side counts every Credential Manager launch: each one is
 * sign-in UI on a real phone (even the "silent" auto-select option shows a
 * "Signing you in" sheet). Like the real sheet, each launch pauses and
 * resumes the activity — the feedback that looped the sheet over the
 * maintainer's terminal — and hands back the account's current token.
 */
final class OfflineFirstSyncEdges {
    static final String EMAIL = "offline-first@example.com";
    static final String SUBJECT = "journey-sub-3189";
    static final String ACCOUNT_ID = "google:" + SUBJECT;
    /** Unique marker in every fake ID token's signature; must never leave native code. */
    static final String TOKEN_SIGNATURE = "FAKEIDTOKEN3189SIGNATUREdoNotLeak";

    enum Mode { STALLED, FAILING, REVOKED }

    private OfflineFirstSyncEdges() {}

    /** Google's account chooser / Credential Manager. */
    static final class Google implements GoogleSyncSession.SignInProvider {
        /** Every Credential Manager call: each is sign-in UI on a phone. */
        final AtomicInteger launches = new AtomicInteger();
        private final AtomicInteger issued = new AtomicInteger();
        volatile Supplier<Activity> activity;
        /** Seconds a newly issued token lives; a short life puts it inside a pre-renewal window. */
        volatile long lifetimeSeconds = 3600L;
        /** The account's current token: what Credential Manager hands back, cached or fresh. */
        volatile String token;
        /** After a revocation, the next launch issues a new token the backend accepts. */
        volatile boolean issueOnNextLaunch;

        Google() {
            token = mint();
        }

        @Override
        public String obtainIdToken() throws SyncAuthException {
            return launch();
        }

        String launch() {
            launches.incrementAndGet();
            showSheet();
            if (issueOnNextLaunch) {
                issueOnNextLaunch = false;
                token = mint();
            }
            return token;
        }

        String mint() {
            long exp = System.currentTimeMillis() / 1000L + lifetimeSeconds;
            String payload = b64url("{\"sub\":\"" + SUBJECT + "\",\"email\":\"" + EMAIL + "\",\"exp\":" + exp
                    + ",\"n\":" + issued.incrementAndGet() + "}");
            return b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + payload + "." + TOKEN_SIGNATURE;
        }

        /** The sheet covers the activity: pause, a moment on screen, resume. */
        private void showSheet() {
            Supplier<Activity> host = activity;
            Activity shown = host == null ? null : host.get();
            if (shown == null || shown.isFinishing()) return;
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            instrumentation.runOnMainSync(() -> instrumentation.callActivityOnPause(shown));
            SystemClock.sleep(300);
            instrumentation.runOnMainSync(() -> {
                if (!shown.isFinishing() && !shown.isDestroyed()) instrumentation.callActivityOnResume(shown);
            });
        }

        @Override
        public void clearCredentialState() {
            // Nothing to forget: the scripted chooser holds no state.
        }

        private static String b64url(String json) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The sync API in one of the {@link Mode}s; counts requests per path. */
    static final class Backend implements GoogleSyncSession.Transport {
        volatile Mode mode;
        /** In {@link Mode#REVOKED}: the bearer the server no longer accepts. */
        volatile String revokedToken;
        private final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger meRequests = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        /** Requests currently held open by {@link Mode#STALLED}. */
        final AtomicInteger stalledNow = new AtomicInteger();

        Backend(Mode mode) {
            this.mode = mode;
        }

        @Override
        public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body)
                throws IOException {
            String path = url.replaceFirst("^https://[^/]+", "");
            requests.incrementAndGet();
            if ("/me".equals(path)) meRequests.incrementAndGet();
            Mode current = mode;
            if (current == Mode.REVOKED) {
                String bearer = headers.get("Authorization");
                if (revokedToken == null || ("Bearer " + revokedToken).equals(bearer)) {
                    refused.incrementAndGet();
                    return new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}");
                }
                current = Mode.STALLED;
            }
            if (current == Mode.FAILING) throw new SocketTimeoutException("Read timed out");
            stalledNow.incrementAndGet();
            try {
                // Held until the test ends; then the request fails like a dropped socket.
                released.await(10, TimeUnit.MINUTES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                stalledNow.decrementAndGet();
            }
            throw new SocketTimeoutException("stalled sync endpoint released");
        }

        /** Let every held request finish (as a failure) so no worker thread outlives the test. */
        void release() {
            released.countDown();
        }
    }
}
