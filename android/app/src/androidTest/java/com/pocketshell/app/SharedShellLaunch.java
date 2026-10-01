package com.pocketshell.app;

import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;

/**
 * Launch intent for the shared PocketShell app (#2936). The pre-#2936 phone
 * screens stay the default launch until the shared app reaches parity
 * (#2941) and the maintainer signs it off; journeys of the shared app opt in
 * here, and MainActivity chooses the shell before its WebView loads.
 */
public final class SharedShellLaunch {
    private SharedShellLaunch() {}

    public static Intent intent() {
        return new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHELL, MainActivity.SHELL_SHARED);
    }
}
