package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import androidx.core.content.FileProvider;

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
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private File downloadedFixture;
    private File uploadFixture;
    private String screenshotRunId;

    @Before
    public void launchPackagedShell() {
        Intents.init();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
        Intents.release();
        if (downloadedFixture != null) downloadedFixture.delete();
        if (uploadFixture != null) uploadFixture.delete();
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
        String requestedScreenshotRunId = arguments.getString("screenshotRunId");
        assertTrue("the host must provide a safe screenshot collection folder", requestedScreenshotRunId != null
                && requestedScreenshotRunId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}"));
        screenshotRunId = requestedScreenshotRunId;
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String remoteHome = "/home/testuser";
        File artifacts = new File(targetContext().getFilesDir(), "js2858-files/" + screenshotRunId);
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
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'files'"
                + " && !!document.querySelector('[data-testid=file-list]')"
                + " && document.querySelector('[data-testid=file-loading]') === null"
                + " && document.querySelectorAll('.files-row').length > 0");

        setValue("[data-testid=file-path]", "/etc/passwd");
        click(".files-goto button[type=submit]");
        awaitJsTrue("document.querySelector('[data-testid=file-error]')?.textContent.includes('outside the configured file workspace root') === true");

        String fixtureName = fixtureRoot.substring(fixtureRoot.lastIndexOf('/') + 1);
        click("[data-file-name='" + fixtureName + "'] .files-row-open");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'editable.txt')");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'binary.bin')");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileType === 'symlink')");
        assertTouchTargetsMeetPhoneMinimum();
        captureScreenshot(artifacts, "files-home.png");

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

        click("[data-file-name='save-success.txt'] .files-row-open");
        awaitJsTrue("document.querySelector('[data-testid=file-editor]')?.value === 'before-save\\n'");
        setValue("[data-testid=file-editor]", "saved-through-ui\n");
        click("[data-testid=file-save]");
        awaitJsTrue("document.querySelector('[data-testid=file-status]')?.textContent.includes('Saved') === true");
        captureScreenshot(artifacts, "files-saved.png");

        click("[data-file-name='link.txt'] .files-row-open");
        awaitJsTrue("document.querySelector('[data-testid=file-open-error]')?.textContent.includes('Symbolic links cannot be opened safely') === true");
        click("[data-file-name='large.bin'] .files-row-open");
        awaitJsTrue("!!document.querySelector('[data-testid=file-too-large]')");
        assertEquals("opening the next file clears the previous symlink error", "true",
                evalRaw("document.querySelector('[data-testid=file-error]') === null"));
        String largeMessage = evalString("document.querySelector('[data-testid=file-too-large]')?.innerText ?? ''");
        assertTrue("oversized files must explain the bridge limit and state that bytes were not read", largeMessage.contains("512.0 KB") && largeMessage.contains("No file contents were read"));

        click("[data-file-name='binary.bin'] .files-row-open");
        awaitJsTrue("!!document.querySelector('[data-testid=file-binary-viewer]')");

        File downloadedFile = new File(targetContext().getCacheDir(), "js2858-download-result.bin");
        downloadedFixture = downloadedFile;
        assertTrue("download result fixture must be new", !downloadedFile.exists());
        Uri downloadUri = FileProvider.getUriForFile(
                targetContext(), targetContext().getPackageName() + ".fileprovider", createFile(downloadedFile, new byte[0]));
        // The chooser result is a real packaged content URI, so the same native
        // SAF writer and ContentResolver path used by Android document providers is exercised.
        Intent createResult = new Intent().setData(downloadUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, createResult));
        click("[data-testid=file-download]");
        awaitJsTrue("document.querySelector('[data-testid=file-status]')?.textContent.includes('Saved') === true");
        byte[] expectedBinary = new byte[] {0, (byte) 0xff, 'B', 'I', 'N', 'A', 'R', 'Y'};
        assertTrue("the SAF content URI must receive the exact remote binary bytes",
                Arrays.equals(expectedBinary, readAllBytes(downloadedFile)));
        captureScreenshot(artifacts, "files-downloaded.png");

        File uploadSource = new File(targetContext().getCacheDir(), "uploaded.bin");
        uploadFixture = uploadSource;
        byte[] uploadBytes = new byte[] {'u', 'p', 'l', 'o', 'a', 'd', 0, 'f', 'r', 'o', 'm', ' ', 'U', 'I', (byte) 0xff};
        Uri uploadUri = FileProvider.getUriForFile(
                targetContext(), targetContext().getPackageName() + ".fileprovider", createFile(uploadSource, uploadBytes));
        // The picker result points at a packaged content URI rather than a
        // fabricated Web File or a synthetic input change event.
        Intent openResult = new Intent().setData(uploadUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, openResult));
        click("[data-testid=file-upload]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === 'uploaded.bin')");
        awaitJsTrue("document.querySelector('[data-testid=file-status]')?.textContent.includes('Uploaded uploaded.bin') === true");
        captureScreenshot(artifacts, "files-uploaded.png");
        System.out.println("J10_FILES_EVIDENCE root=" + fixtureRoot + " listCount="
                + evalString("document.querySelectorAll('[data-file-name]').length")
                + " downloadedBytes=" + expectedBinary.length + " uploadedBytes=" + uploadBytes.length
                + " screenshots=" + artifacts.getAbsolutePath());
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

    private File createFile(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private byte[] readAllBytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private void assertTouchTargetsMeetPhoneMinimum() throws Exception {
        String result = evalString("(() => {const selectors=['.files-icon-button','.files-primary-button','.files-secondary-button',"
                + "'.files-row-open','.files-row-download','.files-crumb'];const bad=[];"
                + "for(const selector of selectors){for(const node of document.querySelectorAll(selector)){"
                + "const style=getComputedStyle(node);const rect=node.getBoundingClientRect();"
                + "if(style.display==='none'||style.visibility==='hidden'||rect.width===0||rect.height===0)continue;"
                + "if(rect.width<48||rect.height<48)bad.push({selector,width:rect.width,height:rect.height});}}"
                + "return JSON.stringify({bad});})()");
        JSONObject report = new JSONObject(result);
        assertTrue("visible file controls must retain 48 dp touch targets: " + report, report.getJSONArray("bad").length() == 0);
    }

    private void captureScreenshot(File directory, String name) throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=file-loading]') === null"
                + " && !Array.from(document.querySelectorAll('button')).some(button =>"
                + " ['Saving…','Uploading…'].includes(button.textContent.trim()))");
        evalString("(() => {window.__ps2858ScreenshotFrameReady=false;"
                + "requestAnimationFrame(() => requestAnimationFrame(() => {window.__ps2858ScreenshotFrameReady=true;}));"
                + "return 'waiting';})()");
        awaitJsTrue("window.__ps2858ScreenshotFrameReady === true", 5_000);
        CountDownLatch rendered = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            if (webView == null) throw new AssertionError("packaged Capacitor activity has no WebView");
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    rendered.countDown();
                }
            });
        });
        assertTrue("the WebView must render the requested file state before its screenshot is captured",
                rendered.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Thread.sleep(200);

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

        ContentValues media = new ContentValues();
        media.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        media.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        media.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/PocketShell/J10/" + screenshotRunId);
        media.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri sharedScreenshot = targetContext().getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, media);
        assertTrue("the packaged journey must export screenshots outside its uninstallable app sandbox", sharedScreenshot != null);
        OutputStream screenshotOutput = targetContext().getContentResolver().openOutputStream(sharedScreenshot);
        assertTrue("the screenshot export URI must be writable", screenshotOutput != null);
        try (OutputStream output = screenshotOutput) {
            output.write(bytes);
        }
        media.clear();
        media.put(MediaStore.Images.Media.IS_PENDING, 0);
        assertTrue("the packaged screenshot must become visible to the host artifact collector",
                targetContext().getContentResolver().update(sharedScreenshot, media, null, null) == 1);
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
