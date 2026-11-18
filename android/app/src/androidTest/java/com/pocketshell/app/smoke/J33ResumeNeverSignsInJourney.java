package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.GoogleSyncEnvironment;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue #3189 P0 (maintainer, Pixel 7a): working in a terminal, the Google
 * "Signing you in" sheet popped over it every 1-2 s after returning from
 * another app. The driver was a pre-renewal on every resume through
 * Credential Manager, whose sheet itself pauses and resumes the activity.
 *
 * <p>Signed in with a token minutes from expiry (inside the old pre-renewal
 * window) and the sync service failing, the journey opens a real terminal on
 * the Docker host, leaves for another app and comes back, then stays on the
 * terminal for 60 s. Zero sign-in UI launches are allowed; the background sync
 * may retry only on its backoff.
 */
public class J33ResumeNeverSignsInJourney extends SharedAppDockerJourneyTest {
    private OfflineFirstSyncEdges.Google google;
    private OfflineFirstSyncEdges.Backend backend;

    @Before @Override public void launchSharedApp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteSharedPreferences("pocketshell-sync-auth");
        google = new OfflineFirstSyncEdges.Google();
        google.lifetimeSeconds = 300L;
        google.token = google.mint();
        backend = new OfflineFirstSyncEdges.Backend(OfflineFirstSyncEdges.Mode.FAILING);
        GoogleSyncEnvironment.installForTesting(activity -> {
            google.activity = activity;
            return google;
        }, backend);
        super.launchSharedApp();
    }

    @After @Override public void closeApp() {
        try {
            super.closeApp();
        } finally {
            if (backend != null) backend.release();
            GoogleSyncEnvironment.resetForTesting();
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteSharedPreferences("pocketshell-sync-auth");
        }
    }

    @Test public void resumeOnATerminalNeverOpensSignInWhileTheSilentPathFails() throws Exception {
        // The one sign-in UI this journey allows: the user's own tap.
        awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]:not([disabled])')");
        click("[data-testid=server-google-login]");
        // Signed in: the chip may already read "Not synced · retry" when the sync is failing.
        awaitJsTrue("!!document.querySelector('.picker .header-actions .account-action.signed-in')");
        Thread.sleep(3_000);
        int afterTap = google.launches.get();
        assertEquals("one sign-in, from the tap", 1, afterTap);
        // This device's own SSH host, kept signed in to Google.
        click("[data-testid=server-use-local]");
        awaitJsTrue("document.querySelector('.trusted-servers')?.textContent.includes('SSH without an account')");
        fixtureHostAdded = false;
        String run = sessionRun() + "-resume";
        openFixtureHost(run);
        awaitFolder(sessionRun() + "-a");
        openFolder(sessionRun() + "-a");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
        typeLine("echo PSRESUME$((6*7))_" + sessionRun());
        awaitTerminalLine("PSRESUME42_" + sessionRun());
        assertEquals("no sign-in UI while opening the terminal", afterTap, google.launches.get());

        // To another app and back.
        scenario.moveToState(Lifecycle.State.CREATED);
        Thread.sleep(2_000);
        int syncChecksBefore = backend.meRequests.get();
        scenario.moveToState(Lifecycle.State.RESUMED);
        long resumedAt = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - resumedAt < 60_000) {
            assertEquals("no sign-in UI after returning to the terminal ("
                    + (SystemClock.uptimeMillis() - resumedAt) + " ms)", afterTap, google.launches.get());
            Thread.sleep(500);
        }
        int syncChecks = backend.meRequests.get() - syncChecksBefore;
        // Backoff: 30 s, 1 min, ... plus at most one retry for the return itself.
        assertTrue("background sync retries only on its backoff: " + syncChecks, syncChecks <= 3);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('PSRESUME42_" + sessionRun() + "')");
        typeLine("echo PSRESUMEAFTER$((6*7))_" + sessionRun());
        awaitTerminalLine("PSRESUMEAFTER42_" + sessionRun());
        screenshot("j33-terminal-after-60s-no-sign-in");
        Log.i(TAG, "RESUME_SIGN_IN " + new JSONObject().put("signInLaunchesAfterResume", google.launches.get() - afterTap)
                .put("watchedMs", 60_000).put("syncChecksAfterResume", syncChecks));
    }
}
