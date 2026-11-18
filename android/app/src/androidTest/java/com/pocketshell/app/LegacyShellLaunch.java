package com.pocketshell.app;

import android.content.ComponentName;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;

/**
 * Launch intent opting into the legacy phone screens. MainActivity chooses the
 * shell before its WebView loads.
 *
 * <p>A phone boots the SHARED shell (#3063); the legacy shell is reached only by
 * asking for it. #3158: a plain {@code ActivityScenario.launch(MainActivity.class)}
 * silently booted the legacy shell under instrumentation, so J23 kept proving a
 * fast-key dock users no longer had. Every journey therefore names its shell
 * ({@link SharedShellLaunch} or this) and scripts/check-js-first-android-journeys.py
 * rejects a plain launch. This is the plain launch's own intent (MAIN/LAUNCHER)
 * plus the explicit shell choice, nothing else.
 */
public final class LegacyShellLaunch {
    private LegacyShellLaunch() {}

    public static Intent intent() {
        return Intent.makeMainActivity(new ComponentName(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class))
                .putExtra(MainActivity.EXTRA_SHELL, MainActivity.SHELL_LEGACY);
    }
}
