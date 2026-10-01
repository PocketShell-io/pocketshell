package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import androidx.core.content.FileProvider;

import org.hamcrest.Matchers;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged Android file browser/editor journey against a real Docker SFTP host. */
@RunWith(AndroidJUnit4.class)
public final class J10FilesBrowseEditJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;
    /**
     * The platform's system document picker. AOSP images ship it as com.android.documentsui;
     * Google APIs images (the hosted CI emulator) ship the same DocumentsUI as
     * com.google.android.documentsui. Nothing else is accepted as the chooser.
     */
    private static final List<String> SYSTEM_DOCUMENTS_UI_PACKAGES =
            Arrays.asList("com.android.documentsui", "com.google.android.documentsui");
    private ActivityScenario<MainActivity> scenario;
    private File downloadedFixture;
    private File uploadFixture;
    private String screenshotRunId;
    private Uri documentsUiSourceUri;
    private String documentsUiPackage;

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
        if (documentsUiSourceUri != null) targetContext().getContentResolver().delete(documentsUiSourceUri, null, null);
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
        // A cold first launch keeps Connect disabled while the installed-data migration check
        // runs (and Vue re-renders `disabled` a tick after the input events), so wait for the
        // real enabled state instead of clicking into a disabled button.
        awaitJsTrue("(() => {const node=document.querySelector('[data-testid=ssh-connect]');"
                + "return !!node && !node.disabled;})()");
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
        assertFilesIsOnlyVisibleScreen();

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

        documentsUiPackage = resolveSystemDocumentsUiPackage();
        System.out.println("J10_DOCUMENTSUI_PACKAGE resolved=" + documentsUiPackage);
        String documentsUiUploadName = "documentsui-upload-" + screenshotRunId + ".bin";
        byte[] documentsUiUploadBytes = new byte[] {
                'D', 'O', 'C', 'S', 0, 'F', 'R', 'O', 'M', ' ', 'D', 'O', 'C', 'S', (byte) 0xff
        };
        documentsUiSourceUri = createDownloadsDocument(documentsUiUploadName, documentsUiUploadBytes);
        int openDocumentCountBefore = intentCountForAction(Intent.ACTION_OPEN_DOCUMENT);
        click("[data-testid=file-upload]");
        String openChooserPackage = awaitDocumentsUiForeground();
        assertEquals("the real ACTION_OPEN_DOCUMENT chooser must be the installed system DocumentsUI",
                documentsUiPackage, openChooserPackage);
        captureForegroundScreenshot(artifacts, "documentsui-open-picker.png");
        browseDocumentsUiDownloads();
        awaitDocumentsUiText(documentsUiUploadName);
        selectDocumentsUiOpenFile(documentsUiUploadName, artifacts);
        awaitPocketShellForeground();
        awaitJsTrue("Array.from(document.querySelectorAll('[data-file-name]')).some(node => node.dataset.fileName === "
                + JSONObject.quote(documentsUiUploadName) + ")");
        awaitJsTrue("document.querySelector('[data-testid=file-status]')?.textContent.includes('Uploaded ' + "
                + JSONObject.quote(documentsUiUploadName) + ") === true");
        assertEquals("selecting a real Downloads document must dispatch one ACTION_OPEN_DOCUMENT",
                openDocumentCountBefore + 1, intentCountForAction(Intent.ACTION_OPEN_DOCUMENT));
        captureScreenshot(artifacts, "documentsui-uploaded.png");
        System.out.println("J10_DOCUMENTSUI_OPEN chooser=" + openChooserPackage
                + " action=ACTION_OPEN_DOCUMENT selected=" + documentsUiUploadName
                + " returned=true uploadedBytes=" + documentsUiUploadBytes.length);

        String documentsUiDownloadName = "documentsui-download-" + screenshotRunId + ".bin";
        click("[data-file-name='" + documentsUiDownloadName + "'] .files-row-open");
        awaitJsTrue("document.querySelector('[data-testid=file-open-name]')?.textContent.trim() === "
                + JSONObject.quote(documentsUiDownloadName));
        awaitFileDownloadReady(documentsUiDownloadName);
        int createDocumentCountBefore = intentCountForAction(Intent.ACTION_CREATE_DOCUMENT);
        click("[data-testid=file-download]");
        String createChooserPackage = awaitDocumentsUiForeground();
        assertEquals("the real ACTION_CREATE_DOCUMENT chooser must be the installed system DocumentsUI",
                documentsUiPackage, createChooserPackage);
        assertTrue("the save chooser must show the Downloads destination: " + documentUiSnapshot(),
                documentUiHasText("Downloads"));
        captureForegroundScreenshot(artifacts, "documentsui-create-picker.png");
        clickDocumentsUiText("SAVE");
        awaitPocketShellForeground();
        awaitFileDownloadSaved(documentsUiDownloadName);
        assertEquals("saving a real destination must dispatch one ACTION_CREATE_DOCUMENT",
                createDocumentCountBefore + 1, intentCountForAction(Intent.ACTION_CREATE_DOCUMENT));
        captureScreenshot(artifacts, "documentsui-downloaded.png");
        System.out.println("J10_DOCUMENTSUI_CREATE chooser=" + createChooserPackage
                + " action=ACTION_CREATE_DOCUMENT selected=Downloads/" + documentsUiDownloadName
                + " returned=true savedStatus=true expectedBytes=7");

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
        awaitFileDownloadReady();

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
        awaitFileDownloadSaved(downloadedFile.getName());
        Intents.intended(Matchers.allOf(
                IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT),
                IntentMatchers.hasExtra(Intent.EXTRA_TITLE, "binary.bin")));
        int binaryCreateDocumentCount = 0;
        Intent dispatchedCreateDocument = null;
        for (Intent intent : Intents.getIntents()) {
            if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())
                    && "binary.bin".equals(intent.getStringExtra(Intent.EXTRA_TITLE))) {
                binaryCreateDocumentCount++;
                dispatchedCreateDocument = intent;
            }
        }
        assertEquals("downloading binary.bin must dispatch exactly one ACTION_CREATE_DOCUMENT", 1, binaryCreateDocumentCount);
        assertTrue("the ACTION_CREATE_DOCUMENT request must name binary.bin: " + dispatchedCreateDocument,
                dispatchedCreateDocument != null
                        && "binary.bin".equals(dispatchedCreateDocument.getStringExtra(Intent.EXTRA_TITLE)));
        byte[] expectedBinary = new byte[] {0, (byte) 0xff, 'B', 'I', 'N', 'A', 'R', 'Y'};
        byte[] actualBinary = readAllBytes(downloadedFile);
        assertTrue("the completed SAF write must contain the exact remote bytes; expected="
                        + Arrays.toString(expectedBinary) + " actual=" + Arrays.toString(actualBinary),
                Arrays.equals(expectedBinary, actualBinary));
        System.out.println("J10_FILES_DOWNLOAD_EVIDENCE action=ACTION_CREATE_DOCUMENT title=binary.bin"
                + " result=" + evalString("document.querySelector('[data-testid=file-status]')?.textContent.trim() ?? ''")
                + " bytes=" + actualBinary.length + " exact=true");
        File downloadedScreenshot = new File(artifacts, "files-downloaded.png");
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
        click("[data-testid=file-close]");
        awaitJsTrue("document.querySelector('[data-testid=file-viewer]') === null");
        evalString("(() => {const list=document.querySelector('[data-testid=file-list]');"
                + "const row=Array.from(document.querySelectorAll('[data-file-name]')).find(node=>node.dataset.fileName==='uploaded.bin');"
                + "if(!list||!row)throw new Error('uploaded row is missing before screenshot');"
                + "row.scrollIntoView({block:'center',inline:'nearest'});return 'scrolled';})()");
        assertUploadedResultIsVisible();
        captureScreenshot(artifacts, "files-uploaded.png");
        File uploadedScreenshot = new File(artifacts, "files-uploaded.png");
        assertTrue("the post-upload screenshot must show a visibly different UI state from the download screenshot",
                !Arrays.equals(readAllBytes(downloadedScreenshot), readAllBytes(uploadedScreenshot)));
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

    private Uri createDownloadsDocument(String name, byte[] bytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = targetContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertTrue("the Android DocumentsUI source fixture must be created in Downloads", uri != null);
        OutputStream output = targetContext().getContentResolver().openOutputStream(uri);
        assertTrue("the Android DocumentsUI source fixture must be writable", output != null);
        try (OutputStream stream = output) {
            stream.write(bytes);
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        assertEquals("the DocumentsUI source fixture must be visible to the picker", 1,
                targetContext().getContentResolver().update(uri, values, null, null));
        return uri;
    }

    private int intentCountForAction(String action) {
        int count = 0;
        for (Intent intent : Intents.getIntents()) {
            if (action.equals(intent.getAction())) count++;
        }
        return count;
    }

    /**
     * Resolve which system DocumentsUI package this image ships, verified through the shell's
     * unrestricted package list (no package-visibility filtering) to be a system package.
     */
    private String resolveSystemDocumentsUiPackage() throws Exception {
        String systemPackages = shell("pm list packages -s");
        String found = null;
        for (String candidate : SYSTEM_DOCUMENTS_UI_PACKAGES) {
            if (systemPackages.contains("package:" + candidate + "\n")
                    || systemPackages.endsWith("package:" + candidate)) {
                if (found != null) {
                    throw new AssertionError("both " + found + " and " + candidate
                            + " are installed system DocumentsUI packages; the chooser is ambiguous");
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new AssertionError("no system DocumentsUI package " + SYSTEM_DOCUMENTS_UI_PACKAGES
                    + " is installed on this image");
        }
        return found;
    }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        }
    }

    private String awaitDocumentsUiForeground() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<no active window>";
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                last = root.getPackageName() + "\n" + documentUiSnapshot(root);
                String packageName = String.valueOf(root.getPackageName());
                if (documentsUiPackage.equals(packageName)) return packageName;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Android DocumentsUI did not become the foreground chooser: " + last);
    }

    private void awaitPocketShellForeground() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String targetPackage = targetContext().getPackageName();
        String last = "<no active window>";
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                last = String.valueOf(root.getPackageName());
                if (targetPackage.equals(last)) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the selected DocumentsUI result did not return to the packaged app "
                + targetPackage + "; active window=" + last + "\n" + documentUiSnapshot());
    }

    private void selectDocumentsUiOpenFile(String name, File artifacts) throws Exception {
        longClickDocumentsUiText(name);
        awaitDocumentsUiText("Select");
        captureForegroundScreenshot(artifacts, "documentsui-open-selected.png");
        clickDocumentsUiText("Select");
    }

    private void longClickDocumentsUiText(String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        String last = "<no active window>";
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                last = documentUiSnapshot(root);
                if (documentsUiPackage.equals(String.valueOf(root.getPackageName()))
                        && longClickDocumentsUiNode(root, text)) {
                    Thread.sleep(300);
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("could not long-press '" + text + "' in Android DocumentsUI: " + last);
    }

    private boolean longClickDocumentsUiNode(AccessibilityNodeInfo root, String text) {
        AccessibilityNodeInfo match = findDocumentsUiNode(root, text);
        AccessibilityNodeInfo candidate = match;
        while (candidate != null) {
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
            candidate = candidate.getParent();
        }
        return false;
    }

    private AccessibilityNodeInfo findDocumentsUiNode(AccessibilityNodeInfo node, String text) {
        CharSequence label = node.getText();
        CharSequence description = node.getContentDescription();
        if ((label != null && label.toString().contains(text))
                || (description != null && description.toString().contains(text))) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                AccessibilityNodeInfo match = findDocumentsUiNode(child, text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void browseDocumentsUiDownloads() throws Exception {
        if (!clickAnyDocumentsUiText("Browse", "Show roots", "Show navigation drawer", "Open navigation drawer")) {
            throw new AssertionError("DocumentsUI did not expose its Browse locations: " + documentUiSnapshot());
        }
        awaitDocumentsUiText("Downloads");
        clickDocumentsUiText("Downloads");
    }

    private void awaitDocumentsUiText(String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<no active window>";
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                last = documentUiSnapshot(root);
                if (documentsUiPackage.equals(String.valueOf(root.getPackageName()))
                        && documentUiHasText(root, text)) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("DocumentsUI did not show '" + text + "': " + last);
    }

    private void clickDocumentsUiText(String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        String last = "<no active window>";
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                last = documentUiSnapshot(root);
                if (documentsUiPackage.equals(String.valueOf(root.getPackageName()))
                        && clickDocumentsUiNode(root, text)) {
                    Thread.sleep(300);
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("could not tap '" + text + "' in Android DocumentsUI: " + last);
    }

    private boolean clickAnyDocumentsUiText(String... labels) throws Exception {
        for (String label : labels) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null && documentsUiPackage.equals(String.valueOf(root.getPackageName()))
                    && clickDocumentsUiNode(root, label)) {
                Thread.sleep(300);
                return true;
            }
        }
        return false;
    }

    private boolean documentUiHasText(String text) {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        return root != null && documentUiHasText(root, text);
    }

    private boolean documentUiHasText(AccessibilityNodeInfo node, String text) {
        CharSequence label = node.getText();
        CharSequence description = node.getContentDescription();
        if ((label != null && label.toString().contains(text))
                || (description != null && description.toString().contains(text))) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null && documentUiHasText(child, text)) return true;
        }
        return false;
    }

    private boolean clickDocumentsUiNode(AccessibilityNodeInfo root, String text) {
        CharSequence label = root.getText();
        CharSequence description = root.getContentDescription();
        if ((label != null && label.toString().contains(text))
                || (description != null && description.toString().contains(text))) {
            AccessibilityNodeInfo clickable = root;
            while (clickable != null) {
                if (clickable.isClickable() && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                clickable = clickable.getParent();
            }
            if (root.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int index = 0; index < root.getChildCount(); index++) {
            AccessibilityNodeInfo child = root.getChild(index);
            if (child != null && clickDocumentsUiNode(child, text)) return true;
        }
        return false;
    }

    private String documentUiSnapshot() {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        return root == null ? "<no active window>" : documentUiSnapshot(root);
    }

    private String documentUiSnapshot(AccessibilityNodeInfo root) {
        StringBuilder output = new StringBuilder();
        appendDocumentsUiNode(root, output, 0);
        return output.toString();
    }

    private void appendDocumentsUiNode(AccessibilityNodeInfo node, StringBuilder output, int depth) {
        if (depth > 12) return;
        for (int index = 0; index < depth; index++) output.append("  ");
        output.append(node.getClassName());
        if (node.getText() != null) output.append(" text=").append(node.getText());
        if (node.getContentDescription() != null) output.append(" desc=").append(node.getContentDescription());
        if (node.getViewIdResourceName() != null) output.append(" id=").append(node.getViewIdResourceName());
        if (node.isClickable()) output.append(" clickable");
        output.append('\n');
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) appendDocumentsUiNode(child, output, depth + 1);
        }
    }

    private File createFile(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private byte[] readAllBytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return readAllBytes(input);
        }
    }

    private byte[] readAllBytes(java.io.InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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

    private void assertFilesIsOnlyVisibleScreen() throws Exception {
        String geometry = evalString("(() => {const home=document.querySelector('.home-screen');"
                + "const files=document.querySelector('[data-testid=files-screen]');"
                + "const rect=files?.getBoundingClientRect();"
                + "const visibleScreens=Array.from(document.querySelectorAll('main.screen-content')).filter(node=>{"
                + "const style=getComputedStyle(node);const box=node.getBoundingClientRect();"
                + "return style.display!=='none'&&style.visibility!=='hidden'&&box.width>0&&box.height>0;});"
                + "return JSON.stringify({route:document.querySelector('.app-shell')?.dataset.route,"
                + "homeHidden:!!home&&getComputedStyle(home).display==='none'&&home.getAttribute('aria-hidden')==='true'&&home.hasAttribute('inert'),"
                + "visibleScreens:visibleScreens.map(node=>node.dataset.testid),filesVisible:!!files&&!!rect&&rect.width>0&&rect.height>0,"
                + "filesInsideViewport:!!rect&&rect.top>=0&&rect.left>=0&&rect.bottom<=window.innerHeight+1&&rect.right<=window.innerWidth+1});})()");
        JSONObject state = new JSONObject(geometry);
        assertEquals("opening Files must change the active app route", "files", state.getString("route"));
        assertTrue("the retained Sessions surface must be hidden and inert on the Files route: " + state,
                state.getBoolean("homeHidden"));
        assertEquals("Files must be the only visible app screen: " + state,
                "files-screen", state.getJSONArray("visibleScreens").getString(0));
        assertEquals("no other app screen may remain visible behind Files: " + state,
                1, state.getJSONArray("visibleScreens").length());
        assertTrue("the Files screen must fit the visible app viewport: " + state, state.getBoolean("filesInsideViewport"));
    }

    private void assertUploadedResultIsVisible() throws Exception {
        String geometry = evalString("(() => {const browser=document.querySelector('.files-browser');"
                + "const list=document.querySelector('[data-testid=file-list]');"
                + "const row=Array.from(document.querySelectorAll('[data-file-name]')).find(node=>node.dataset.fileName==='uploaded.bin');"
                + "const status=document.querySelector('[data-testid=file-status]');"
                + "const browserRect=browser?.getBoundingClientRect();const listRect=list?.getBoundingClientRect();"
                + "const rowRect=row?.getBoundingClientRect();const statusRect=status?.getBoundingClientRect();"
                + "return JSON.stringify({rowText:row?.innerText??'',statusText:status?.textContent?.trim()??'',"
                + "viewerClosed:document.querySelector('[data-testid=file-viewer]')===null,"
                + "rowVisible:!!rowRect&&!!listRect&&rowRect.width>0&&rowRect.height>0&&rowRect.top>=listRect.top&&rowRect.bottom<=listRect.bottom,"
                + "statusVisible:!!statusRect&&!!browserRect&&statusRect.width>0&&statusRect.height>0&&statusRect.top>=browserRect.top&&statusRect.bottom<=browserRect.bottom});})()");
        JSONObject state = new JSONObject(geometry);
        assertTrue("the uploaded row must be visible within the file list for its screenshot: " + state,
                state.getBoolean("rowVisible"));
        assertTrue("the upload status must be visible in the browser for its screenshot: " + state,
                state.getBoolean("statusVisible"));
        assertTrue("the uploaded screenshot must show the listing rather than a stale file viewer: " + state,
                state.getBoolean("viewerClosed"));
        assertTrue("the visible listing row must name uploaded.bin: " + state, state.getString("rowText").contains("uploaded.bin"));
        assertTrue("the visible status must identify the successful upload: " + state,
                state.getString("statusText").contains("Uploaded uploaded.bin"));
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
        writeScreenshotArtifactAndPublish(directory, name, bytes);
    }

    private void captureForegroundScreenshot(File directory, String name) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertTrue("Android system chooser screenshot must be available: " + name, screenshot != null);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue("Android system chooser screenshot must encode as PNG: " + name,
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        screenshot.recycle();
        writeScreenshotArtifactAndPublish(directory, name, encoded.toByteArray());
    }

    private void writeScreenshotArtifactAndPublish(File directory, String name, byte[] bytes) throws Exception {
        assertTrue("Android screenshot must contain a rendered screen: " + name, bytes != null && bytes.length > 1024);
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
        // evaluateJavascript reports a thrown script as null, so return the reason as a value.
        String outcome = evalString("(() => {try {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "if('disabled' in node && node.disabled)throw new Error('disabled ' + " + JSONObject.quote(selector)
                + " + '; loading=' + (document.querySelector('[data-testid=file-loading]')?.textContent.trim() ?? 'none')"
                + " + '; status=' + (document.querySelector('[data-testid=file-status]')?.textContent.trim() ?? 'none')"
                + " + '; error=' + (document.querySelector('[data-testid=file-error]')?.textContent.trim() ?? 'none'));"
                + "node.click();return 'clicked';} catch (error) {return 'click failed: ' + error.message;}})()");
        assertEquals("packaged file UI click on " + selector, "clicked", outcome);
    }

    private void awaitFileDownloadReady() throws Exception {
        awaitFileDownloadReady("binary.bin");
    }

    private void awaitFileDownloadReady(String name) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject state = readFileDownloadUiState();
        while (SystemClock.uptimeMillis() < deadline) {
            if (name.equals(state.optString("openedName"))
                    && state.optBoolean("downloadButtonEnabled")
                    && state.isNull("loading")) {
                return;
            }
            Thread.sleep(80);
            state = readFileDownloadUiState();
        }
        throw new AssertionError("binary.bin must finish loading and enable Download before the tap; UI=" + state);
    }

    private void awaitFileDownloadSaved(String destinationName) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject state = readFileDownloadUiState();
        while (SystemClock.uptimeMillis() < deadline) {
            String status = state.optString("status");
            if (status.startsWith("Saved ") && status.endsWith(" to " + destinationName + ".")
                    && state.isNull("loading")) {
                return;
            }
            String error = state.optString("error");
            if (!error.isEmpty()) {
                throw new AssertionError("binary.bin download failed before SAF completion; UI=" + state);
            }
            Thread.sleep(80);
            state = readFileDownloadUiState();
        }
        throw new AssertionError("binary.bin download did not report a completed SAF save; UI=" + state);
    }

    private JSONObject readFileDownloadUiState() throws Exception {
        String state = evalString("(() => {const button=document.querySelector('[data-testid=file-download]');"
                + "const loading=document.querySelector('[data-testid=file-loading]');"
                + "const status=document.querySelector('[data-testid=file-status]');"
                + "const error=document.querySelector('[data-testid=file-error]');"
                + "const opened=document.querySelector('[data-testid=file-open-name]');"
                + "const statusText=status?.textContent.trim() ?? '';"
                + "return JSON.stringify({openedName:opened?.textContent.trim() ?? '',"
                + "downloadButtonExists:!!button,downloadButtonEnabled:!!button&&!button.disabled,"
                + "loading:loading?.textContent.trim() ?? null,status:statusText,error:error?.textContent.trim() ?? ''});})()");
        return new JSONObject(state);
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
