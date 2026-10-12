package com.pocketshell.app;

import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;

/**
 * Launch intent opting into the legacy phone screens. MainActivity chooses the
 * shell before its WebView loads.
 */
public final class LegacyShellLaunch {
    private LegacyShellLaunch() {}

    public static Intent intent() {
        return new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHELL, MainActivity.SHELL_LEGACY);
    }
}
