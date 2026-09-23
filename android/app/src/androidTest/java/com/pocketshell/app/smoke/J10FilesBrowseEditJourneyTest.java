package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged Android file browser/editor journey against a real Docker SFTP host. */
@RunWith(AndroidJUnit4.class)
public final class J10FilesBrowseEditJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void browseEditConflictAndTransferFilesWithinTheConfiguredRoot() throws Exception {
        assertTrue("file workspace journey uses the packaged API 35+ Android shell", Build.VERSION.SDK_INT >= 35);
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        String fixtureRoot = arguments.getString("fileFixtureRoot");
        assertTrue("the Docker SSH port is required", port != null && port.matches("[0-9]{1,5}"));
        assertTrue("the test-only SSH key is required", encodedKey != null && !encodedKey.isEmpty());
        assertTrue("the host must seed a run-scoped remote folder", fixtureRoot != null
                && fixtureRoot.matches("/home/testuser/\\.ps2858-files-[A-Za-z0-9_-]+"));
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String remoteHome = "/home/testuser";
        File artifacts = new File(targetContext().getFilesDir(), "js2858-files/" + fixtureRoot.substring(fixtureRoot.lastIndexOf('/') + 1));
        assertTrue("run-scoped screenshot directory must be new", artifacts.mkdirs());

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing','live'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        awaitJsTrue("!!document.querySelector('[data-testid=open-files]')");

        String fileButtonGeometry = evalString("(() => {const node=document.querySelector('[data-testid=open-files]');"
                + "const box=node.getBoundingClientRect();return JSON.stringify({width:box.width,height:box.height});})()");
        JSONObject fileButtonBounds = new JSONObject(fileButtonGeometry);
        assertTrue("Files must remain an Android touch target at least 48 CSS pixels high: " + fileButtonBounds,
                fileButtonBounds.getDouble("height") >= 48.0);
        click("[data-testid=open-files]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'files' && !!document.querySelector('[data-testid=file-list]')");
        assertTouchTargetsMeetPhoneMinimum();
        captureScreenshot(artifacts, "files-home.png");

        setValue("[data-testid=file-path]", "/etc/passwd");
        click(".files-goto button[type=submit]");
        awaitJsTrue("document.querySelector('[data-testid=file-error]')?.textContent.includes('outside the configured file workspace root')");

        String fixtureName = fixtureRoot.substring(fixtureRoot.lastIndexOf('/') + 1);
        click("[data-file-name='" + fixtureName + "'] .files-row-open");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'editable.txt')");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'binary.bin')");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileType === 'symlink')");

        click("[data-file-name='editable.txt'] .files-row-open");
        awaitJsTrue("document.querySelector('[data-testid=file-editor]')?.value === 'before-edit\\n'");
        setValue("[data-testid=file-editor]", "draft-that-must-survive-conflict\n");
        awaitJsTrue("document.querySelector('[data-testid=file-save]')?.disabled === false");
        mutateRemoteFile(remoteHome + "/" + fixtureRoot.substring(remoteHome.length() + 1) + "/editable.txt");
        click("[data-testid=file-save]");
        awaitJsTrue("!!document.querySelector('[data-testid=file-conflict]')");
        assertEquals("the conflict must preserve the local edit buffer", "draft-that-must-survive-conflict\n",
                evalString("document.querySelector('[data-testid=file-editor]')?.value ?? ''"));
        captureScreenshot(artifacts, "files-edit-conflict.png");
        click("[data-testid=file-discard]");

        click("[data-file-name='link.txt'] .files-row-open");
        awaitJsTrue("document.querySelector('[data-testid=file-open-error]')?.textContent.includes('Symbolic links cannot be opened safely')");
        click("[data-file-name='large.bin'] .files-row-open");
        awaitJsTrue("!!document.querySelector('[data-testid=file-too-large]')");
        assertEquals("opening the next file clears the previous symlink error", "true",
                evalRaw("document.querySelector('[data-testid=file-error]') === null"));
        String largeMessage = evalString("document.querySelector('[data-testid=file-too-large]')?.innerText ?? ''");
        assertTrue("oversized files must explain the bridge limit and state that bytes were not read", largeMessage.contains("512 KiB") && largeMessage.contains("No file contents were read"));

        click("[data-file-name='binary.bin'] .files-row-open");
        awaitJsTrue("!!document.querySelector('[data-testid=file-binary-viewer]')");
        armDownloadProbe();
        click("[data-testid=file-download]");
        awaitJsTrue("window.__ps2858FileDownload?.done === true");
        JSONObject download = new JSONObject(evalString("JSON.stringify(window.__ps2858FileDownload)"));
        assertEquals("binary download must use the remote basename", "binary.bin", download.getString("name"));
        assertEquals("download bytes must remain binary and byte-exact", "AP9CSU5BUlk=", download.getString("base64"));

        dispatchUpload("upload-from-packaged-ui");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'uploaded.txt')");
        awaitJsTrue("document.querySelector('[data-testid=file-status]')?.textContent.includes('Uploaded uploaded.txt')");
        captureScreenshot(artifacts, "files-uploaded.png");
        System.out.println("J10_FILES_EVIDENCE root=" + fixtureRoot + " listCount="
                + evalString("document.querySelectorAll('[data-file-name]').length")
                + " download=" + download + " screenshots=" + artifacts.getAbsolutePath());
    }

    private void awaitTrustOrConnected() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                + " || ['connected','listing','live'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalRaw("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            click("[data-testid=trust-host-key]");
        }
    }

    private void mutateRemoteFile(String path) throws Exception {
        String expression = "(() => {const plugin=window.Capacitor?.Plugins?.SshCapability;"
                + "if(!plugin)return JSON.stringify({error:'SshCapability plugin proxy is unavailable'});"
                + "const shell=document.querySelector('.app-shell');window.__ps2858Mutation='pending';"
                + "plugin.sftpWrite({requestId:'j10-remote-mutation',connectionId:shell.dataset.sshConnectionId,"
                + "generationId:shell.dataset.sshGenerationId,rootPath:'/home/testuser',path:" + JSONObject.quote(path)
                + ",createOnly:false,dataBase64:btoa('changed-remotely\\n')})"
                + ".then(result=>window.__ps2858Mutation=JSON.stringify(result))"
                + ".catch(error=>window.__ps2858Mutation=JSON.stringify({error:String(error)}));return 'started';})()";
        assertEquals("the test must start a second real SFTP write", "started", evalString(expression));
        awaitJsTrue("window.__ps2858Mutation !== 'pending'");
        String outcome = evalString("window.__ps2858Mutation");
        assertTrue("remote mutation must be accepted by the native SFTP bridge: " + outcome,
                outcome.contains("bytesWritten"));
    }

    private void armDownloadProbe() throws Exception {
        String expression = "(() => {window.__ps2858FileDownload=null;"
                + "document.addEventListener('click',event=>{const node=event.target;"
                + "const anchor=node instanceof HTMLAnchorElement?node:node?.closest?.('a[download]');"
                + "if(!anchor||!anchor.download)return;const name=anchor.download;const href=anchor.href;"
                + "window.__ps2858FileDownload={name,done:false};"
                + "fetch(href).then(response=>response.arrayBuffer()).then(buffer=>{"
                + "const bytes=new Uint8Array(buffer);let binary='';for(const value of bytes)binary+=String.fromCharCode(value);"
                + "window.__ps2858FileDownload={name,base64:btoa(binary),done:true};"
                + "}).catch(error=>window.__ps2858FileDownload={name,error:String(error),done:true});},true);return 'armed';})()";
        assertEquals("download observation must be armed", "armed", evalString(expression));
    }

    private void dispatchUpload(String content) throws Exception {
        String expression = "(() => {const input=document.querySelector('[data-testid=file-upload-input]');"
                + "if(!input)throw new Error('missing file upload input');"
                + "const file=new File([" + JSONObject.quote(content) + "],'uploaded.txt',{type:'text/plain'});"
                + "Object.defineProperty(input,'files',{configurable:true,value:[file]});"
                + "input.dispatchEvent(new Event('change',{bubbles:true}));return 'sent';})()";
        assertEquals("the packaged browser upload input must accept a selected document", "sent", evalString(expression));
    }

    private void assertTouchTargetsMeetPhoneMinimum() throws Exception {
        String result = evalString("(() => {const selectors=['.files-icon-button','.files-primary-button','.files-secondary-button',"
                + "'.files-row-open','.files-row-download','.files-crumb'];const bad=[];"
                + "for(const selector of selectors){for(const node of document.querySelectorAll(selector)){"
                + "if(getComputedStyle(node).display==='none')continue;const rect=node.getBoundingClientRect();"
                + "if(rect.width<48||rect.height<48)bad.push({selector,width:rect.width,height:rect.height});}}"
                + "return JSON.stringify({bad});})()");
        JSONObject report = new JSONObject(result);
        assertTrue("visible file controls must retain 48 dp touch targets: " + report, report.getJSONArray("bad").length() == 0);
    }

    private void captureScreenshot(File directory, String name) throws Exception {
        AtomicReference<byte[]> png = new AtomicReference<>();
        scenario.onActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) return;
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded)) png.set(encoded.toByteArray());
            screenshot.recycle();
        });
        byte[] bytes = png.get();
        assertTrue("packaged Android screenshot must contain rendered file UI: " + name, bytes != null && bytes.length > 1024);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            output.write(bytes);
        }
    }

    private android.content.Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value=" + JSONObject.quote(value) + ";node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");node.click();return 'clicked';})()");
    }

    private void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, WAIT_TIMEOUT_MILLIS);
    }

    private void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            Thread.sleep(80);
        }
        throw new AssertionError("packaged file journey timed out: " + expression + " (last=" + last
                + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            if (webView == null) throw new AssertionError("packaged Capacitor activity has no WebView");
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating packaged file UI JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (result.get() == null || "null".equals(result.get())) throw new JSONException("JavaScript returned null: " + expression);
        return result.get();
    }

    private static WebView findWebView(android.view.View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
}
