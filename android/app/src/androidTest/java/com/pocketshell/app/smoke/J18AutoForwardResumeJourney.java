package com.pocketshell.app.smoke;

import org.junit.Test;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import com.pocketshell.app.SharedShellLaunch;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

/** Explicitly enabled forwarding starts on a cold launcher and resumes without visiting Ports. */
@RunWith(AndroidJUnit4.class)
public final class J18AutoForwardResumeJourney extends ForwardServiceJourneySupport {
    @Test public void automaticForwardStartsWithoutPortsAndSurvivesResume() throws Exception {
        connectAndRecord();
        startServer("J18");
        // Setup uses the shipping shared toggle, the user's explicit durable
        // host enablement. The measured cold launch below never opens Ports.
        click("button[title^='Port forwarding']");
        awaitJsTrue("!!document.querySelector('button.toggle')");
        awaitJsTrue("document.querySelector('button.toggle').textContent.includes('OFF')");
        click("button.toggle");
        awaitJsTrue("document.querySelector('button.toggle').textContent.includes('ON')");
        awaitNativeForwardEndpoint();
        assertHttp();
        awaitJsTrue("JSON.parse(localStorage.getItem('pocketshell.android.forward-resume.v1')??'[]').length===1");

        // A new activity creates a new WebView, platform and controller; no
        // old timer or live SSH socket can qualify the cold-start behavior.
        String oldTransport=transport.getString("transportId");
        scenario.close();
        scenario=ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        installRecoveryRecorder();
        awaitNativeForwardEndpoint();
        assertNotEquals("cold start must create a fresh native transport", oldTransport, transport.getString("transportId"));
        assertTrue("the cold launcher must stay out of Ports", "true".equals(evalString("document.querySelector('.fwd-table')===null")));
        assertHttp();
        awaitService();
        screenshot("j18-auto-without-ports");
        backgroundAndResume();
        assertHttp();
        assertHostTraffic(3);
        screenshot("j18-resumed");
    }
}
