package com.pocketshell.app.smoke;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Test-only helper: key bytes stay in native app files and cross JS only as a content URI. */
final class SshKeyVaultTestSupport {
    private static final String DOCKER_KEY_FINGERPRINT = "SHA256:geJoGi64Up5pm2TGC6bdVNrvlIA1vuPIOtNKo2tLsuQ";
    static final String IMPORT_RESULT = "__ps2926ImportedKey";

    private SshKeyVaultTestSupport() {}

    /** Runner-staged raw fixture keys live only here, outside every app package. */
    private static final java.util.regex.Pattern STAGED_KEY_PATH =
        java.util.regex.Pattern.compile("/data/local/tmp/pocketshell-[A-Za-z0-9._-]+\\.pem");

    /**
     * Moves a runner-staged fixture key into a private cache document. The raw
     * copy sits in /data/local/tmp so it does not depend on which suffixed APK
     * is installed when the runner stages it; only the shell can read it, so the
     * bytes are read and then deleted through UiAutomation's shell.
     */
    static File copyDockerKeyDocument(Context context, String sourcePath, String suffix) throws Exception {
        if (sourcePath == null || !STAGED_KEY_PATH.matcher(sourcePath).matches()) {
            throw new IllegalArgumentException("the test runner must stage the Docker fixture key under /data/local/tmp/pocketshell-*.pem");
        }
        byte[] key = shell("cat " + sourcePath);
        shell("rm -f " + sourcePath);
        String text = new String(key, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.startsWith("-----BEGIN ") || !text.contains("PRIVATE KEY-----")) {
            throw new IllegalStateException("the runner-staged Docker fixture key is missing or unreadable");
        }
        File directory = new File(context.getCacheDir(), "ps2926-key-import");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("key test cache directory is unavailable");
        File file = new File(directory, "docker-key-" + safe(suffix) + ".pem");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(key);
            output.getFD().sync();
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        return file;
    }

    private static byte[] shell(String command) throws Exception {
        android.os.ParcelFileDescriptor output = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    static Uri asContentUri(Context context, File document) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", document);
        context.grantUriPermission(context.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return uri;
    }

    /** Idempotently imports the test key from a real content URI, returning only its public metadata. */
    static String beginImport(Uri uri, String label) {
        return "window." + IMPORT_RESULT + "={state:'pending'};"
            + "(async()=>{try{const vault=window.Capacitor.Plugins.SshKeyVault;"
            + "const listed=await vault.listKeys();"
            + "let key=listed.keys.find(row=>row.fingerprintSha256===" + JSONObject.quote(DOCKER_KEY_FINGERPRINT) + ");"
            + "if(!key) key=await vault.importDocumentUri({uri:" + JSONObject.quote(uri.toString())
            + ",label:" + JSONObject.quote(label) + "});"
            + "window." + IMPORT_RESULT + "={state:'ready',...key};"
            + "}catch(error){window." + IMPORT_RESULT + "={state:'failed',message:String(error?.message||error)};}})();'started'";
    }

    static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
