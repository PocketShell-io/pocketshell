package com.pocketshell.app.smoke;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Test-only helper: key bytes stay in native app files and cross JS only as a content URI. */
final class SshKeyVaultTestSupport {
    private static final String DOCKER_KEY_FINGERPRINT = "SHA256:geJoGi64Up5pm2TGC6bdVNrvlIA1vuPIOtNKo2tLsuQ";
    static final String IMPORT_RESULT = "__ps2926ImportedKey";

    private SshKeyVaultTestSupport() {}

    /** Copies a runner-staged fixture from this app's external files into a private cache document. */
    static File copyDockerKeyDocument(Context context, String sourcePath, String suffix) throws Exception {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("the test runner must stage the Docker fixture key");
        }
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) throw new IllegalStateException("app external files directory is unavailable");
        File source = new File(sourcePath);
        String allowedRoot = externalFiles.getCanonicalPath() + File.separator;
        if (!source.getCanonicalPath().startsWith(allowedRoot) || !source.isFile()) {
            throw new IllegalArgumentException("the staged fixture must be inside the target app external files directory");
        }
        File directory = new File(context.getCacheDir(), "ps2926-key-import");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("key test cache directory is unavailable");
        File file = new File(directory, "docker-key-" + safe(suffix) + ".pem");
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (!source.delete()) throw new IllegalStateException("could not remove the runner-staged raw fixture copy");
        return file;
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
