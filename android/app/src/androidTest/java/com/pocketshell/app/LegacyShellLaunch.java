package com.pocketshell.app;

import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;

/**
 * Launch intent for the pre-#2936 phone screens. The shared app is the
 * default shell; journeys that still use the legacy screens as their oracle
 * opt in here until each screen's shared replacement passes them (#2941).
 */
public final class LegacyShellLaunch {
    private LegacyShellLaunch() {}

    public static Intent intent() {
        return withLegacyShell(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class));
    }

    public static Intent withLegacyShell(Intent intent) {
        return intent.putExtra(MainActivity.EXTRA_SHELL, "legacy");
    }
}
