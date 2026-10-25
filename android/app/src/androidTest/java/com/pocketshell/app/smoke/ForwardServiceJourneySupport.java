package com.pocketshell.app.smoke;

import static org.junit.Assert.*;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import com.pocketshell.app.ForwardService;

/** Shared setup and independent traffic oracles, never a substitute for journey tests. */
public abstract class ForwardServiceJourneySupport extends SharedAppDockerJourneyTest {
    protected int remotePort;
    protected int localPort;
    protected String token;
    protected String stem;
    protected JSONObject transport;

    protected void connectAndRecord() throws Exception {
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        installRecoveryRecorder();
        evalString("(() => {window.__forwardOpens=[];window.__forwardCalls=[];const invoke=window.Capacitor.nativePromise.bind(window.Capacitor);"
            + "window.Capacitor.nativePromise=(plugin,method,options)=>{const answer=invoke(plugin,method,options);"
            + "if(plugin!=='SshCapability'||!['exec','openPortForward','closePortForward'].includes(method)) return answer;"
            + "const call={method,remotePort:options?.remotePort,requestId:options?.requestId,connectionId:options?.connectionId,generationId:options?.generationId,scan:method==='exec'&&String(options?.command).includes('PS_SS_TLN')};"
            + "window.__forwardCalls.push(call);return answer.then((result)=>{call.ok=true;call.result=method==='exec'?(call.scan?{exitCode:result.exitCode,stdout:result.stdout,stderr:result.stderr}:null):result;"
            + "if(method==='openPortForward')window.__forwardOpens.push({remotePort:options.remotePort,...result});return result;},(error)=>{call.ok=false;call.error=String(error?.message??error);throw error;});}; return 'recording';})()");
        openFixtureHost(sessionRun());
        awaitJsTrue("(window.__pocketshellConnectionJournal??[]).some((e)=>e.transportId&&e.generationId)");
        transport = new JSONObject(evalString("JSON.stringify([...(window.__pocketshellConnectionJournal??[])].reverse().find((e)=>e.transportId&&e.generationId))"));
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
    }
    protected void startServer(String label) throws Exception { startServer(label, false); }
    protected void startServer(String label, boolean manualOnly) throws Exception {
        remotePort = (manualOnly ? 22000 : 4100) + (int)(SystemClock.uptimeMillis() % 400);
        token = "PS_" + label + "_" + sessionRun();
        stem = "/tmp/" + token;
        String command = "set -eu; printf '%s' '" + remotePort + "' > " + stem + ".port; printf '%s' '" + token + "' > " + stem + ".txt; "
            + "nohup python3 -u -m http.server " + remotePort + " --bind 127.0.0.1 --directory /tmp > " + stem
            + ".log 2>&1 < /dev/null & echo $! > " + stem + ".pid; sleep 1; kill -0 $(cat " + stem + ".pid)";
        JSONObject result = awaitNativeExec("seed-" + token, transport.getString("transportId"), transport.getString("generationId"), command);
        assertEquals("fresh host HTTP fixture must start: " + result, 0, result.getInt("exitCode"));
    }
    protected void awaitAutomaticForward() throws Exception {
        awaitJsTrue("window.__forwardOpens.some((f)=>f.remotePort===" + remotePort + ")", 30_000);
        localPort = Integer.parseInt(evalString("String(window.__forwardOpens.find((f)=>f.remotePort===" + remotePort + ").localPort)"));
        assertFalse("automatic forwarding must not require opening Ports", evalString("document.body.innerText").contains("Port forwarding"));
        assertHttp();
    }
    protected void awaitNativeForwardEndpoint() throws Exception {
        long deadline=SystemClock.uptimeMillis()+30_000;
        while (SystemClock.uptimeMillis()<deadline) {
            evalString("(() => {window.__forwardResource=null;window.Capacitor.Plugins.SshCapability.resourceSnapshot({requestId:'forward-evidence-'+Date.now()}).then((r)=>window.__forwardResource=r);return 'reading';})()");
            awaitJsTrue("window.__forwardResource!==null", 5000);
            JSONObject snapshot=new JSONObject(evalString("JSON.stringify(window.__forwardResource)"));
            org.json.JSONArray ports=snapshot.getJSONArray("forwardPorts");
            for (int i=0;i<ports.length();i++) {
                JSONObject endpoint=ports.getJSONObject(i);
                if (endpoint.getInt("remotePort")!=remotePort) continue;
                assertEquals("cold-start forwarding must have one shared SSH owner", 1, snapshot.getInt("connections"));
                localPort=endpoint.getInt("localPort");
                transport=new JSONObject().put("transportId",endpoint.getString("connectionId"))
                    .put("generationId",endpoint.getString("generationId"));
                return;
            }
            Thread.sleep(150);
        }
        fail("native SSH did not open the remembered remote port "+remotePort+"; resources="+evalString("JSON.stringify(window.__forwardResource)"));
    }
    protected void assertHttp() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", localPort)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /" + token + ".txt HTTP/1.0\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); String line;
            while ((line=reader.readLine()) != null) result.append(line).append('\n');
            assertTrue("real SSH tunnel must return this run's body: " + result, result.toString().contains(token));
        }
    }
    protected void assertHostTraffic(int minimum) throws Exception {
        JSONObject result = awaitNativeExec("proof-" + SystemClock.uptimeMillis(), transport.getString("transportId"),
            transport.getString("generationId"), "grep -c 'GET /" + token + ".txt' " + stem + ".log");
        assertEquals("independent Docker HTTP access log must exist", 0, result.getInt("exitCode"));
        assertTrue("host must receive each checked request", Integer.parseInt(result.getString("stdout").trim()) >= minimum);
    }
    protected boolean serviceForeground() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (info.service.getClassName().equals(ForwardService.class.getName()) && info.foreground) return true;
        return false;
    }
    protected android.service.notification.StatusBarNotification notification() {
        for (android.service.notification.StatusBarNotification n : InstrumentationRegistry.getInstrumentation().getTargetContext()
            .getSystemService(NotificationManager.class).getActiveNotifications())
            if (n.getId() == ForwardService.NOTIFICATION_ID) return n;
        return null;
    }
    protected void awaitService() throws Exception {
        long deadline=SystemClock.uptimeMillis()+10_000;
        while (SystemClock.uptimeMillis()<deadline && (!serviceForeground() || notification()==null)) Thread.sleep(100);
        assertTrue("active SSH forwards must own a real Android foreground service", serviceForeground());
        assertNotNull("foreground service must post its notification", notification());
    }
    @Override protected void screenshot(String name) {
        try {
            String token=Long.toString(SystemClock.uptimeMillis());
            evalString("(() => {window.__forwardPainted=null;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__forwardPainted="+JSONObject.quote(token)+"));return 'scheduled';})()");
            awaitJsTrue("window.__forwardPainted==="+JSONObject.quote(token), 5000);
            java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);
            scenario.onActivity(activity -> activity.getBridge().getWebView().postVisualStateCallback(
                SystemClock.uptimeMillis(), new android.webkit.WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) { committed.countDown(); }
                }));
            assertTrue("WebView must commit the asserted screen before capture", committed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(250);
            super.screenshot(name);
        } catch (Exception failure) { throw new AssertionError("Unable to capture the committed journey screen",failure); }
    }
    protected void backgroundAndResume() throws Exception {
        scenario.moveToState(Lifecycle.State.CREATED);
        Thread.sleep(1200);
        scenario.moveToState(Lifecycle.State.RESUMED);
    }
    @After public void stopOurServer() throws Exception {
        if (stem == null || transport == null) return;
        scenario.moveToState(Lifecycle.State.RESUMED);
        String diagnostics=evalString("JSON.stringify({calls:window.__forwardCalls,journal:window.__pocketshellConnectionJournal,body:document.body.innerText,storage:Object.fromEntries(Object.entries(localStorage).filter(([k])=>k.startsWith('pocketshell.android.forwards.')))})");
        java.io.File diagnosticFile=new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), token+".json");
        writeText(diagnosticFile, diagnostics);
        keepForRunner(diagnosticFile, token+".json");
        android.util.Log.i("ForwardServiceJourney", token + " DIAGNOSTICS " + diagnostics);
        screenshot(token);
        JSONObject result = awaitNativeExec("cleanup-" + SystemClock.uptimeMillis(), transport.getString("transportId"),
            transport.getString("generationId"), "pid=$(cat " + stem + ".pid); args=$(tr '\\000' ' ' < /proc/$pid/cmdline); "
                + "case \"$args\" in *'python3 -u -m http.server " + remotePort + " --bind 127.0.0.1'*) kill $pid;; *) exit 1;; esac");
        assertEquals("cleanup must stop this test's HTTP server", 0, result.getInt("exitCode"));
    }
}
