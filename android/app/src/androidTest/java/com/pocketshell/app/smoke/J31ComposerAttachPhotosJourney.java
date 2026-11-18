package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3191: phone photos attached through the composer's attach button.
 *
 * The maintainer picked two Pixel camera JPEGs (~630-670 KB, and one of
 * 1.67 MB) with the system file picker and both failed with a truncated
 * "This transfer supports files up to 5…" diagnostic: the picker reused the
 * text editor's 512 KiB read limit as its upload cap. These journeys run the
 * real path in the shared shell: the app dispatches ACTION_OPEN_DOCUMENT, the
 * result carries content:// URIs, the native document plugin streams them,
 * and the native SFTP client writes them to the Docker host. The bytes the
 * host holds are compared by sha256 (here over the app's own exec, and again
 * by the runner over its own SSH connection).
 *
 * The photos are generated (SyntheticPixelJpeg): never personal images.
 */
public final class J31ComposerAttachPhotosJourney extends SharedAppDockerJourneyTest {
    private static final String EVIDENCE = "ps3191-attachments.json";
    private final List<File> localFiles = new ArrayList<>();

    @Before
    public void recordIntents() {
        Intents.init();
    }

    @After
    public void releaseIntents() {
        Intents.release();
        for (File file : localFiles) file.delete();
    }

    @Test
    public void pickedPhonePhotosLandByteExactOnTheHost() throws Exception {
        String run = sessionRun() + "-p3191";
        connectFixtureAndAttach(run);
        JSONObject transport = latestLiveJournalEntry();
        String token = "ps3191p" + Math.abs(new Random().nextInt(1_000_000));
        // The maintainer's two failing camera photos and the 1.67 MB third pick.
        int[] sizes = {627_672, 667_110, 1_700_000};
        int[] heights = {1275, 1231, 2040};
        List<Uri> uris = new ArrayList<>();
        JSONArray expected = new JSONArray();
        for (int index = 0; index < sizes.length; index++) {
            String name = "PXL_" + token + "_" + (index + 1) + ".jpg";
            byte[] jpeg = SyntheticPixelJpeg.create(sizes[index], heights[index] * 3 / 4, heights[index], index + 3191L);
            File file = writeLocal(name, jpeg);
            assertFixtureIsAPixelLikePhoto(file, jpeg);
            uris.add(contentUri(file));
            expected.put(new JSONObject().put("name", name).put("size", jpeg.length).put("sha256", sha256(jpeg)));
        }
        assertTrue("the fixtures reproduce the report: every photo is over the editor's 512 KiB limit",
                sizes[0] > 512 * 1024 && sizes[1] > 512 * 1024);

        installDiagWatch();
        pickThroughOpenDocument(uris);
        awaitJsTrue("document.querySelectorAll('.tiles .tile').length === 3", 90_000);
        awaitJsTrue("!document.querySelector('[data-testid=android-upload-progress]')");

        JSONArray tiles = new JSONArray(evalString("JSON.stringify(Array.from(document.querySelectorAll('.tiles .tile')).map(node=>node.title))"));
        assertEquals("every picked photo is staged as a tile", 3, tiles.length());
        for (int index = 0; index < expected.length(); index++) {
            JSONObject file = expected.getJSONObject(index);
            String remote = tileFor(tiles, file.getString("name"));
            assertTrue("attachments use the real remote staging directory: " + remote, remote.contains("/.pocketshell/attachments/"));
            file.put("remotePath", remote);
            JSONObject hashed = awaitNativeExec("ps3191-sha-" + index + "-" + token,
                    transport.getString("transportId"), transport.getString("generationId"),
                    "stat -c %s -- " + shellQuote(remote) + " && sha256sum -- " + shellQuote(remote));
            assertEquals("the host must read the uploaded photo: " + hashed, 0, hashed.getInt("exitCode"));
            String[] lines = hashed.getString("stdout").trim().split("\n");
            assertEquals("the host holds every byte of " + file.getString("name"), file.getInt("size"), Integer.parseInt(lines[0].trim()));
            assertEquals("the host holds the exact bytes of " + file.getString("name"),
                    file.getString("sha256"), lines[1].trim().split("\\s+", 2)[0]);
        }
        JSONObject residue = awaitNativeExec("ps3191-part-" + token, transport.getString("transportId"),
                transport.getString("generationId"), "find \"$HOME/.pocketshell/attachments\" -name '*" + token + "*.part' | wc -l");
        assertEquals("no partial upload is left behind", "0", residue.getString("stdout").trim());
        assertNoDiagnostic();
        assertEquals("one real ACTION_OPEN_DOCUMENT request was dispatched", 1, openDocumentRequests());
        screenshot("j31-three-photos-staged");
        keepEvidence("photos", new JSONObject().put("token", token).put("files", expected));
    }

    @Test
    public void overLimitFileShowsAReadableChipErrorWithoutADiagnostic() throws Exception {
        String run = sessionRun() + "-o3191";
        connectFixtureAndAttach(run);
        String token = "ps3191o" + Math.abs(new Random().nextInt(1_000_000));
        // A sparse 101 MiB file: its provider-reported size is over the limit; nothing is read.
        File huge = new File(context().getCacheDir(), "VID_" + token + ".jpg");
        try (RandomAccessFile file = new RandomAccessFile(huge, "rw")) {
            file.setLength(101L * 1024 * 1024);
        }
        localFiles.add(huge);
        byte[] small = SyntheticPixelJpeg.create(200_000, 600, 800, 77L);
        File smallFile = writeLocal("PXL_" + token + "_ok.jpg", small);
        List<Uri> uris = new ArrayList<>();
        uris.add(contentUri(huge));
        uris.add(contentUri(smallFile));

        installDiagWatch();
        pickThroughOpenDocument(uris);
        String chip = "[data-testid=android-upload-chip][data-state=rejected]";
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(chip) + ")", 30_000);
        assertEquals("the refusal names the size in plain words on the file's own chip",
                "This photo is 101.0 MB; the limit is 100.0 MB.",
                evalString("document.querySelector(" + JSONObject.quote(chip + " [data-testid=android-upload-error]") + ").textContent.trim()"));
        assertEquals(huge.getName(), evalString("document.querySelector(" + JSONObject.quote(chip) + ").dataset.name"));
        String page = evalString("document.body.innerText");
        assertFalse("no raw byte count reaches the screen: " + page, page.contains(String.valueOf(101L * 1024 * 1024)));
        assertFalse("the old editor-limit refusal is gone: " + page, page.contains("524288") || page.contains("supports files up to"));
        // The valid file of the same pick still uploads.
        awaitJsTrue("document.querySelectorAll('.tiles .tile').length === 1", 60_000);
        assertNoDiagnostic();
        assertComposerRowsDoNotOverlap("over-limit chip and a staged tile", 1);
        screenshot("j31-over-limit-chip");
        click("[data-testid=android-upload-dismiss]");
        awaitJsTrue("!document.querySelector(" + JSONObject.quote(chip) + ")");
        keepEvidence("overLimit", new JSONObject().put("token", token).put("name", huge.getName()));
    }

    @Test
    public void cancellingMidUploadLeavesNothingOnTheHost() throws Exception {
        String run = sessionRun() + "-c3191";
        connectFixtureAndAttach(run);
        JSONObject transport = latestLiveJournalEntry();
        String token = "ps3191c" + Math.abs(new Random().nextInt(1_000_000));
        byte[] video = new byte[48 * 1024 * 1024];
        new Random(3191).nextBytes(video);
        File file = writeLocal("VID_" + token + ".mp4", video);
        List<Uri> uris = new ArrayList<>();
        uris.add(contentUri(file));

        installDiagWatch();
        pickThroughOpenDocument(uris);
        String uploading = "[data-testid=android-upload-chip][data-state=uploading]";
        awaitJsTrue("Number(document.querySelector(" + JSONObject.quote(uploading) + ")?.dataset.written ?? 0) > 0", 60_000);
        JSONObject midFlight = awaitNativeExec("ps3191-mid-" + token, transport.getString("transportId"),
                transport.getString("generationId"), "find \"$HOME/.pocketshell/attachments\" -name '*" + token + "*'");
        String inFlight = midFlight.getString("stdout").trim();
        assertTrue("mid-upload the host holds only a clearly named .part file: " + inFlight,
                inFlight.endsWith(".part") && !inFlight.contains("\n"));
        assertTrue("the upload is still in flight when Cancel is pressed", Long.parseLong(evalString(
                "document.querySelector(" + JSONObject.quote(uploading) + ")?.dataset.written ?? '-1'")) < video.length);
        assertComposerRowsDoNotOverlap("an uploading chip", 0);
        screenshot("j31-upload-in-flight");
        click("[data-testid=android-upload-cancel]");
        awaitJsTrue("!document.querySelector('[data-testid=android-upload-chip]')", 60_000);
        assertEquals("a cancelled upload is not staged", "0", evalString("String(document.querySelectorAll('.tiles .tile').length)"));
        JSONObject after = awaitNativeExec("ps3191-after-" + token, transport.getString("transportId"),
                transport.getString("generationId"), "find \"$HOME/.pocketshell/attachments\" -name '*" + token + "*' | wc -l");
        assertEquals("cancelling leaves neither the file nor its .part on the host", "0", after.getString("stdout").trim());
        assertNoDiagnostic();
        screenshot("j31-upload-cancelled");
        keepEvidence("cancelled", new JSONObject().put("token", token).put("partSeenMidFlight", inFlight));
    }

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private File writeLocal(String name, byte[] bytes) throws Exception {
        File file = new File(context().getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        localFiles.add(file);
        return file;
    }

    private Uri contentUri(File file) {
        Context context = context();
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        assertEquals("a real content URI", "content", uri.getScheme());
        return uri;
    }

    private static void assertFixtureIsAPixelLikePhoto(File file, byte[] jpeg) throws Exception {
        assertTrue("EXIF, Motion Photo XMP and MPF markers", SyntheticPixelJpeg.hasCameraMarkers(jpeg));
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        assertEquals("the EXIF orientation tag", SyntheticPixelJpeg.ORIENTATION_ROTATE_90,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1));
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE));
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        assertTrue("the fixture decodes as an image", bounds.outWidth > 0 && bounds.outHeight > 0);
    }

    /** Open the composer, stub the system picker's answer, and tap the real attach button. */
    private void pickThroughOpenDocument(List<Uri> uris) throws Exception {
        Intent result = new Intent().setData(uris.get(0)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clips = ClipData.newUri(context().getContentResolver(), "picked photos", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clips.addItem(new ClipData.Item(uris.get(index)));
        result.setClipData(clips);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, result));
        if (!exists(".composer-root .composer")) click(".composer-root > .rail");
        String attach = ".composer button[aria-label='Attach to prompt']";
        awaitVisible(attach);
        click(attach);
    }

    /**
     * The reviewer's #3191 containment check: the upload chips, the staged
     * tiles, the uploading line and the draft field each get their own rows;
     * none paints over another (rect intersection, CSS px).
     */
    private void assertComposerRowsDoNotOverlap(String screen, int expectedTiles) throws Exception {
        JSONObject layout = new JSONObject(evalString("(() => {"
                + "const box=(el,kind)=>{const r=el.getBoundingClientRect();return {kind,top:r.top,bottom:r.bottom,left:r.left,right:r.right};};"
                + "const rows=[...[...document.querySelectorAll('.composer [data-testid=android-upload-chip]')].map(e=>box(e,'chip')),"
                + "...[...document.querySelectorAll('.composer .tiles .tile')].map(e=>box(e,'tile')),"
                + "...[...document.querySelectorAll('.composer .uploading')].map(e=>box(e,'uploading')),"
                + "...[...document.querySelectorAll('.composer textarea.draft')].map(e=>box(e,'draft'))];"
                + "const hits=[];for(let i=0;i<rows.length;i++)for(let j=i+1;j<rows.length;j++){const a=rows[i],b=rows[j];"
                + "if(Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)>0.5&&Math.min(a.right,b.right)-Math.max(a.left,b.left)>0.5)hits.push(a.kind+' x '+b.kind);}"
                + "return JSON.stringify({rows,hits});})()"));
        JSONArray rows = layout.getJSONArray("rows");
        int chips = 0, tiles = 0, drafts = 0;
        for (int index = 0; index < rows.length(); index++) {
            String kind = rows.getJSONObject(index).getString("kind");
            if (kind.equals("chip")) chips += 1;
            if (kind.equals("tile")) tiles += 1;
            if (kind.equals("draft")) drafts += 1;
        }
        assertEquals("the " + screen + " screen shows one chip: " + layout, 1, chips);
        assertEquals("the " + screen + " screen shows its tiles: " + layout, expectedTiles, tiles);
        assertEquals("the " + screen + " screen shows the draft field: " + layout, 1, drafts);
        assertEquals("on the " + screen + " screen no composer row overlaps another: " + layout,
                0, layout.getJSONArray("hits").length());
    }

    private int openDocumentRequests() {
        int count = 0;
        for (Intent intent : Intents.getIntents()) {
            if (Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) count += 1;
        }
        return count;
    }

    /** Latch any diagnostic strip that appears from here on. */
    private void installDiagWatch() throws Exception {
        assertEquals("the run starts with no diagnostic showing", "false", evalString("String(!!document.querySelector('.diag-strip'))"));
        evalString("window.__ps3191Diag=[];window.__ps3191DiagObserver?.disconnect();"
                + "window.__ps3191DiagObserver=new MutationObserver(()=>{const strip=document.querySelector('.diag-strip');"
                + "if(strip)window.__ps3191Diag.push(strip.innerText);});"
                + "window.__ps3191DiagObserver.observe(document.body,{subtree:true,childList:true,characterData:true}); 'watching'");
    }

    private void assertNoDiagnostic() throws Exception {
        assertEquals("no diagnostic toast was raised", "[]", evalString("JSON.stringify(window.__ps3191Diag)"));
    }

    private static String tileFor(JSONArray tiles, String name) throws Exception {
        String found = null;
        for (int index = 0; index < tiles.length(); index++) {
            String path = tiles.getString(index);
            if (path.endsWith("-" + name)) {
                assertTrue("one tile per photo", found == null);
                found = path;
            }
        }
        assertTrue("a tile for " + name + " in " + tiles, found != null);
        return found;
    }

    /** The runner re-checks these on the host over its own SSH connection. */
    private void keepEvidence(String key, JSONObject value) throws Exception {
        // External files: the shell user that copies it out can read them.
        File directory = context().getExternalFilesDir(null);
        File file = new File(directory, key + "-" + EVIDENCE);
        writeText(file, value.toString());
        keepForRunner(file, "ps3191-" + key + ".json");
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value & 255));
        return digest.toString();
    }
}
