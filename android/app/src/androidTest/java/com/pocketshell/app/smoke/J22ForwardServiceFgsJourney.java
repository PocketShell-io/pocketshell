package com.pocketshell.app.smoke;

import static org.junit.Assert.*;
import android.app.Notification;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.Socket;

/** Native FGS lifetime, background traffic, host effects, and the actual notification Stop all action. */
@RunWith(AndroidJUnit4.class)
public final class J22ForwardServiceFgsJourney extends ForwardServiceJourneySupport {
    @Test public void foregroundServiceKeepsTunnelInBackgroundAndNotificationStopsIt() throws Exception {
        connectAndRecord(); startServer("J22", true);
        // Outside the automatic range: only the real shared UI Add action can
        // create this tunnel. The native bridge must bind the chosen local port.
        click("button[title^='Port forwarding']");
        awaitJsTrue("!!document.querySelector('button.add-toggle')");
        click("button.add-toggle");
        awaitJsTrue("document.querySelector('section.add-form')?.offsetParent !== null");
        int chosenLocal;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) { chosenLocal = free.getLocalPort(); }
        setValue("section.add-form input[type=number]", String.valueOf(chosenLocal));
        setValue("section.add-form input.host", "127.0.0.1");
        setValue("section.add-form label:last-of-type input[type=number]", String.valueOf(remotePort));
        click("section.add-form button.add-btn");
        awaitJsTrue("window.__forwardOpens.some((f)=>f.remotePort===" + remotePort + ")", 20_000);
        localPort = Integer.parseInt(evalString("String(window.__forwardOpens.find((f)=>f.remotePort===" + remotePort + ").localPort)"));
        assertEquals("manual Add must bind the UI's chosen local port", chosenLocal, localPort);
        awaitJsTrue("[...document.querySelectorAll('.fwd-table tbody tr')].some((row)=>row.querySelector('.port-num')?.textContent.trim()==='" + remotePort + "' && row.querySelector('.origin')?.textContent==='manual')");
        assertHttp(); awaitService();
        Notification posted=notification().getNotification();
        assertTrue("forward notification must be ongoing", (posted.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue("notification body must identify the real forwarded local port",
            String.valueOf(posted.extras.getCharSequence(Notification.EXTRA_TEXT)).contains(String.valueOf(localPort)));
        screenshot("j22-forward-active");
        scenario.moveToState(Lifecycle.State.CREATED);
        long until=SystemClock.uptimeMillis()+10_000;
        int requests=1;
        while (SystemClock.uptimeMillis()<until) {
            assertTrue("FGS must stay foreground throughout background sojourn", serviceForeground());
            assertNotNull("notification must remain posted in background", notification());
            assertHttp(); requests++; Thread.sleep(500);
        }
        scenario.moveToState(Lifecycle.State.RESUMED);
        assertHostTraffic(requests);
        Notification.Action stop=null;
        for (Notification.Action action : notification().getNotification().actions)
            if (action.title.toString().equals("Stop all")) stop=action;
        assertNotNull("the foreground notification needs a working Stop all action", stop);
        stop.actionIntent.send();
        long deadline=SystemClock.uptimeMillis()+10_000;
        boolean reachable=true;
        while (SystemClock.uptimeMillis()<deadline) {
            try (Socket ignored=new Socket("127.0.0.1",localPort)) { reachable=true; }
            catch (Exception closed) { reachable=false; }
            if (!reachable && !serviceForeground() && notification()==null) break;
            Thread.sleep(100);
        }
        assertFalse("Stop all must close the real local listener",reachable);
        assertFalse("Stop all must stop the actual foreground service",serviceForeground());
        assertNull("Stop all must remove the notification",notification());
        // A subsequent foreground scan must not recreate a user-stopped tunnel.
        Thread.sleep(6000);
        try (Socket ignored=new Socket("127.0.0.1",localPort)) { fail("automatic scan reopened a stopped forward"); }
        catch (java.io.IOException closed) { /* required */ }
        screenshot("j22-stopped");
    }
}
