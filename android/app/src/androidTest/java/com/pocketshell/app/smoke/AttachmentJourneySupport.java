package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;

/** Android share delivery into core's real composer and native SFTP effects. */
public abstract class AttachmentJourneySupport extends SharedAppDockerJourneyTest {
    protected void shareAndVerifyHostBytes(int count, boolean requireProgress) throws Exception {
        String run = sessionRun() + (requireProgress ? "-progress" : "-share" + count);
        connectFixtureAndAttach(run);
        JSONObject transport = latestLiveJournalEntry();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> expectedHashes = new ArrayList<>();
        ArrayList<File> localFiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] bytes = new byte[requireProgress ? 384 * 1024 : 1024 + index];
            for (int offset = 0; offset < bytes.length; offset++) bytes[offset] = (byte) ((offset * 31 + index * 7) % 251);
            File file = new File(context.getCacheDir(), "attachment-" + run + "-" + index + ".bin");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            localFiles.add(file);
            StringBuilder digest = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value & 255));
            expectedHashes.add(digest.toString());
            uris.add(FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file));
        }
        evalString("window.__attachmentProgress=[]; window.__attachmentObserver?.disconnect();"
                + "window.__attachmentObserver=new MutationObserver(()=>{const bar=document.querySelector('[data-testid=android-upload-progress]');"
                + "if(bar)window.__attachmentProgress.push({written:Number(bar.dataset.written),total:Number(bar.dataset.total),count:Number(bar.dataset.fileCount)});});"
                + "window.__attachmentObserver.observe(document.body,{subtree:true,childList:true,attributes:true}); 'observing'");
        Intent share = new Intent(count == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                .setClass(context, MainActivity.class).setType("application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (count == 1) share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clips = ClipData.newUri(context.getContentResolver(), "journey attachments", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clips.addItem(new ClipData.Item(uris.get(index)));
        share.setClipData(clips);
        context.startActivity(share);
        awaitJsTrue("!!document.querySelector('[data-testid=android-share-target]')");
        assertTrue("share chooser must expose its real host selection", Boolean.parseBoolean(evalString(
                "String(!!document.querySelector('[data-testid=android-share-host]'))")));
        String selector = "[data-testid=android-share-session][data-session-name='shell-" + run + "']";
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(selector) + ")");
        click(selector);
        awaitJsTrue("document.querySelectorAll('.tiles .tile').length === " + count, 60_000);
        awaitJsTrue("!document.querySelector('[data-testid=android-upload-progress]')");
        JSONArray paths = new JSONArray(evalString("JSON.stringify(Array.from(document.querySelectorAll('.tiles .tile')).map(node=>node.title))"));
        assertEquals("every selected share file is staged", count, paths.length());
        for (int index = 0; index < paths.length(); index++) {
            String path = paths.getString(index);
            assertTrue("attachments must use the real remote staging directory", path.contains("/.pocketshell/attachments/"));
            JSONObject actual = awaitNativeExec("attachment-sha-" + index + "-" + run,
                    transport.getString("transportId"), transport.getString("generationId"),
                    "sha256sum -- " + shellQuote(path));
            assertEquals("independent host read must succeed: " + actual, 0, actual.getInt("exitCode"));
            String hash = actual.getString("stdout").trim().split("\\s+", 2)[0];
            assertEquals("native SFTP delivered the exact original shared file bytes", expectedHashes.get(index), hash);
        }
        if (requireProgress) {
            assertEquals("an acknowledged byte bar must render before the three-file batch completes", "true", evalString(
                    "String(window.__attachmentProgress.some(event=>event.count===3 && event.written>0 && event.written<event.total))"));
            assertEquals("finished uploads leave no progress or uploading indicator", "0", evalString(
                    "String(document.querySelectorAll('[data-testid=android-upload-progress], .uploading').length)"));
        }
        screenshot(requireProgress ? "j20-three-file-upload-finished" : "j11-shared-file-staged");
        evalString("window.__attachmentObserver.disconnect(); 'stopped'");
        for (File file : localFiles) assertTrue("local share fixture is removed", file.delete());
    }
}
