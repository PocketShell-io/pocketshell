package com.pocketshell.app.smoke;

import android.os.SystemClock;
import android.util.Log;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.List;

/**
 * Issue #2946 round 2: per-test defense for Android-injected input.
 *
 * <p>The lane runners disable the HOME launcher and dismiss system error
 * dialogs once per lane, but hosted run 36792962871 attempt 3 showed a Pixel
 * Launcher ANR dialog can appear mid-lane. Each test that injects input calls
 * {@link #beforeFirstInjectedInput} right before its first injected key or
 * tap. That call, once per test method:
 * <ol>
 *   <li>fails with {@code POCKETSHELL_ERROR_DIALOG} if PocketShell itself owns
 *       an ANR/crash dialog (a product failure, never dismissed);</li>
 *   <li>force-stops the owner of any other live system error dialog, logs
 *       {@code DISMISSED_SYSTEM_ERROR_DIALOG}, and fails if the dialog does
 *       not go away;</li>
 *   <li>runs {@link AndroidInputDeliveryProbe} once, which fails with
 *       {@code ANDROID_INPUT_INJECTION_NOT_DELIVERED} if the injected probe
 *       key does not reach the page.</li>
 * </ol>
 * The dismissal happens before the probe; a failed probe is never retried.
 */
final class AndroidInputGuardRule implements TestRule {
    static final String OWN_DIALOG_SIGNATURE = "POCKETSHELL_ERROR_DIALOG";
    private static final long DIALOG_GONE_TIMEOUT_MILLIS = 10_000;

    private String testName = "<outside a test>";
    private boolean verifiedThisTest;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                testName = description.getMethodName();
                verifiedThisTest = false;
                base.evaluate();
            }
        };
    }

    void beforeFirstInjectedInput(String context, AndroidInputDeliveryProbe.PageEvaluator page,
                                  AndroidInputDeliveryProbe.ActivityRunner activity) throws Exception {
        if (verifiedThisTest) return;
        String where = testName + ": " + context;
        dismissForeignSystemErrorDialogs(where);
        AndroidInputDeliveryProbe.assertInjectedKeyReachesPage(where, page, activity);
        verifiedThisTest = true;
    }

    static void dismissForeignSystemErrorDialogs(String where) throws InterruptedException {
        List<String> dialogs = liveErrorDialogs();
        if (dialogs.isEmpty()) return;
        for (String title : dialogs) {
            String owner = dialogOwner(title);
            if (owner == null) {
                throw new AssertionError(AndroidInputDeliveryProbe.SIGNATURE
                        + ": a system error dialog with no owning package owns the screen at " + where + ": " + title);
            }
            if (isPocketShell(owner)) {
                throw new AssertionError(OWN_DIALOG_SIGNATURE + ": PocketShell itself owns a system error dialog at "
                        + where + ": " + title + ". Refusing to dismiss it; investigate the app ANR/crash.");
            }
        }
        for (String title : dialogs) {
            String owner = dialogOwner(title);
            Log.w(AndroidInputDeliveryProbe.TAG, "DISMISSED_SYSTEM_ERROR_DIALOG at " + where + ": " + title
                    + " (am force-stop " + owner + ")");
            AndroidInputDeliveryProbe.runShell("am force-stop " + owner);
        }
        long deadline = SystemClock.uptimeMillis() + DIALOG_GONE_TIMEOUT_MILLIS;
        List<String> remaining = liveErrorDialogs();
        while (!remaining.isEmpty() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(200);
            remaining = liveErrorDialogs();
        }
        if (!remaining.isEmpty()) {
            throw new AssertionError(AndroidInputDeliveryProbe.SIGNATURE
                    + ": system error dialog survived dismissal at " + where + ": " + remaining);
        }
    }

    private static List<String> liveErrorDialogs() {
        return AndroidInputDeliveryProbe.SystemInputState.systemErrorWindows(
                AndroidInputDeliveryProbe.runShell("dumpsys window windows"));
    }

    static String dialogOwner(String title) {
        int separator = title.indexOf(": ");
        if (separator < 0) return null;
        String owner = title.substring(separator + 2).trim();
        return owner.matches("[A-Za-z0-9._]+") ? owner : null;
    }

    static boolean isPocketShell(String owner) {
        return owner.equals("com.pocketshell") || owner.startsWith("com.pocketshell.");
    }
}
