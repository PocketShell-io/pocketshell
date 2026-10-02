package com.pocketshell.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Secure Android adapter for opaque SSH credential handles. */
@CapacitorPlugin(name = "SshKeyVault")
public final class SshKeyVaultPlugin extends Plugin {
    private static final long MAX_DOCUMENT_BYTES = CredentialHandleVault.MAX_KEY_BYTES;
    private final Map<String, PickedKeyDocument> pickedDocuments = new LinkedHashMap<>();

    @PluginMethod
    public void listKeys(PluginCall call) {
        try {
            JSArray keys = new JSArray();
            for (CredentialHandleVault.KeyMetadata key : vault().list()) keys.put(key.asJson());
            call.resolve(new JSObject().put("keys", keys));
        } catch (Exception error) {
            reject(call, "KEY_VAULT_READ_FAILED", "SSH keys could not be listed safely.");
        }
    }

    @PluginMethod
    public void pickKeyDocument(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "keyDocumentPicked");
    }

    @ActivityCallback
    private void keyDocumentPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.resolve(new JSObject().put("cancelled", true));
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            call.reject("The selected SSH key is not an Android document URI.", "KEY_DOCUMENT_INVALID_URI");
            return;
        }
        String documentId = UUID.randomUUID().toString();
        String name = documentName(uri);
        if (name == null || name.trim().isEmpty()) name = "Imported SSH key";
        synchronized (pickedDocuments) {
            pickedDocuments.put(documentId, new PickedKeyDocument(uri, name));
            while (pickedDocuments.size() > 8) pickedDocuments.remove(pickedDocuments.keySet().iterator().next());
        }
        call.resolve(new JSObject().put("cancelled", false).put("documentId", documentId).put("name", name));
    }

    @PluginMethod
    public void importPickedKey(PluginCall call) {
        JSObject request = call.getData();
        String label = request.getString("label", "Imported SSH key");
        String passphrase = request.getString("passphrase", "");
        request.remove("passphrase");
        String documentId = request.getString("documentId");
        PickedKeyDocument document;
        synchronized (pickedDocuments) {
            document = documentId == null ? null : pickedDocuments.get(documentId);
        }
        if (document == null) {
            call.reject("The selected SSH key document expired. Pick the file again.", "KEY_DOCUMENT_EXPIRED");
            return;
        }
        importUri(call, document.uri, request.getString("label", document.name), passphrase, documentId);
    }

    /** Content URI entrypoint also permits native test providers to exercise the real resolver boundary. */
    @PluginMethod
    public void importDocumentUri(PluginCall call) {
        JSObject request = call.getData();
        String label = request.getString("label", "Imported SSH key");
        String passphrase = request.getString("passphrase", "");
        request.remove("passphrase");
        String uriValue = request.getString("uri");
        Uri uri = uriValue == null ? null : Uri.parse(uriValue);
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            call.reject("The SSH key must be imported from an Android document URI.", "KEY_DOCUMENT_INVALID_URI");
            return;
        }
        importUri(call, uri, label, passphrase, null);
    }

    /**
     * Pasted private-key text (#3021). The text crosses the bridge once, is
     * validated and sealed here, and is never echoed back, logged, or stored
     * outside the encrypted vault. The WebView clears its field afterwards.
     */
    @PluginMethod
    public void importKeyText(PluginCall call) {
        JSObject request = call.getData();
        String text = request.getString("text");
        String label = request.getString("label", "Pasted SSH key");
        String rawPassphrase = request.getString("passphrase", "");
        request.remove("text");
        request.remove("passphrase");
        char[] passphrase = rawPassphrase == null ? new char[0] : rawPassphrase.toCharArray();
        byte[] bytes = null;
        try {
            bytes = CredentialHandleVault.normalizePastedKey(text);
            if (label == null || label.trim().isEmpty()) label = "Pasted SSH key";
            CredentialHandleVault.KeyMetadata key = vault().importBytes(bytes, label, passphrase, null, false);
            call.resolve(publicMetadata(key));
        } catch (CredentialHandleVault.DuplicateKeyException error) {
            reject(call, "KEY_DUPLICATE", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_IMPORT_FAILED", safeMessage(error));
        } finally {
            java.util.Arrays.fill(passphrase, '\0');
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /** The OpenSSH public line for a stored key; public data only. */
    @PluginMethod
    public void publicKey(PluginCall call) {
        try {
            String handleId = requiredString(call, "handleId");
            String publicKey = publicKeyLine(call);
            call.resolve(new JSObject().put("handleId", handleId).put("publicKey", publicKey));
        } catch (CredentialHandleVault.PassphraseRequiredException error) {
            reject(call, "KEY_PASSPHRASE_REQUIRED", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_PUBLIC_FAILED", safeMessage(error));
        }
    }

    /** Copies the public line to the Android clipboard natively. */
    @PluginMethod
    public void copyPublicKey(PluginCall call) {
        try {
            String publicKey = publicKeyLine(call);
            getActivity().runOnUiThread(() -> {
                try {
                    ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null) throw new IllegalStateException("clipboard unavailable");
                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey));
                    call.resolve(new JSObject().put("copied", true));
                } catch (Exception error) {
                    reject(call, "KEY_COPY_FAILED", "The public key could not be copied.");
                }
            });
        } catch (CredentialHandleVault.PassphraseRequiredException error) {
            reject(call, "KEY_PASSPHRASE_REQUIRED", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_COPY_FAILED", safeMessage(error));
        }
    }

    /** Opens the Android share sheet with the public line as plain text. */
    @PluginMethod
    public void sharePublicKey(PluginCall call) {
        try {
            String publicKey = publicKeyLine(call);
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, publicKey)
                .putExtra(Intent.EXTRA_SUBJECT, "SSH public key");
            Intent chooser = Intent.createChooser(send, "Share SSH public key");
            getActivity().runOnUiThread(() -> {
                try {
                    getActivity().startActivity(chooser);
                    call.resolve(new JSObject().put("shared", true));
                } catch (Exception error) {
                    reject(call, "KEY_SHARE_FAILED", "No app is available to share the public key.");
                }
            });
        } catch (CredentialHandleVault.PassphraseRequiredException error) {
            reject(call, "KEY_PASSPHRASE_REQUIRED", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_SHARE_FAILED", safeMessage(error));
        }
    }

    private String publicKeyLine(PluginCall call) throws Exception {
        JSObject request = call.getData();
        String handleId = requiredString(call, "handleId");
        String rawPassphrase = request.getString("passphrase", "");
        request.remove("passphrase");
        char[] passphrase = rawPassphrase == null ? new char[0] : rawPassphrase.toCharArray();
        try {
            return vault().publicKeyLine(handleId, passphrase);
        } finally {
            java.util.Arrays.fill(passphrase, '\0');
        }
    }

    @PluginMethod
    public void generateKey(PluginCall call) {
        try {
            String label = call.getString("label");
            String algorithm = call.getString("algorithm");
            CredentialHandleVault.KeyMetadata key = vault().generate(label, algorithm);
            call.resolve(publicMetadata(key));
        } catch (CredentialHandleVault.DuplicateKeyException error) {
            reject(call, "KEY_DUPLICATE", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_GENERATION_FAILED", safeMessage(error));
        }
    }

    @PluginMethod
    public void deleteKey(PluginCall call) {
        try {
            String handleId = requiredString(call, "handleId");
            String fingerprint = requiredString(call, "fingerprintSha256");
            boolean deleted = vault().delete(handleId, fingerprint);
            call.resolve(new JSObject().put("deleted", deleted));
        } catch (Exception error) {
            reject(call, "KEY_DELETE_FAILED", safeMessage(error));
        }
    }

    @PluginMethod
    public void importLegacyKeys(PluginCall call) {
        try {
            JSArray requested = call.getArray("keys");
            if (requested == null || requested.length() > 256) throw new IOException("The saved SSH key list is invalid.");
            JSArray imported = new JSArray();
            JSArray failures = new JSArray();
            CredentialHandleVault vault = vault();
            for (int index = 0; index < requested.length(); index++) {
                JSONObject row = requested.getJSONObject(index);
                long legacyKeyId = row.getLong("legacyKeyId");
                String sha256 = row.getString("sha256");
                String label = row.getString("label");
                boolean hasPassphrase = row.getBoolean("passphraseRequired");
                try {
                    CredentialHandleVault.KeyMetadata key = vault.importLegacyKey(
                        getContext(), legacyKeyId, sha256, label, hasPassphrase
                    );
                    imported.put(new JSObject()
                        .put("legacyKeyId", legacyKeyId)
                        .put("key", key.asJson()));
                } catch (Exception error) {
                    failures.put(new JSObject()
                        .put("legacyKeyId", legacyKeyId)
                        .put("message", safeMessage(error)));
                }
            }
            call.resolve(new JSObject().put("keys", imported).put("failures", failures));
        } catch (Exception error) {
            reject(call, "LEGACY_KEY_IMPORT_FAILED", "Saved SSH keys could not be imported. Original app data was left unchanged.");
        }
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (pickedDocuments) {
            pickedDocuments.clear();
        }
    }

    private void importUri(PluginCall call, Uri uri, String label, String rawPassphrase, String documentId) {
        char[] passphrase = rawPassphrase == null ? new char[0] : rawPassphrase.toCharArray();
        byte[] bytes = null;
        try {
            bytes = readDocument(uri);
            CredentialHandleVault.KeyMetadata key = vault().importBytes(bytes, label, passphrase, null, false);
            if (documentId != null) {
                synchronized (pickedDocuments) { pickedDocuments.remove(documentId); }
            }
            call.resolve(publicMetadata(key));
        } catch (CredentialHandleVault.DuplicateKeyException error) {
            reject(call, "KEY_DUPLICATE", error.getMessage());
        } catch (Exception error) {
            reject(call, "KEY_IMPORT_FAILED", safeMessage(error));
        } finally {
            java.util.Arrays.fill(passphrase, '\0');
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] readDocument(Uri uri) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) throw new IOException("The SSH key must come from an Android document URI.");
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("The selected SSH key document could not be opened.");
            WipingByteArrayOutputStream output = new WipingByteArrayOutputStream(CredentialHandleVault.MAX_KEY_BYTES);
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (output.size() + count > MAX_DOCUMENT_BYTES) throw new IOException("The selected SSH private key exceeds the 1 MiB limit.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArrayAndWipe();
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
                output.wipe();
            }
        } catch (SecurityException error) {
            throw new IOException("Android no longer grants access to the selected SSH key document.");
        }
    }

    private CredentialHandleVault vault() throws IOException {
        return CredentialHandleVault.forContext(getContext());
    }

    private static String requiredString(PluginCall call, String key) throws IOException {
        String value = call.getString(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("SSH key request is missing " + key + ".");
        return value;
    }

    private static JSObject publicMetadata(CredentialHandleVault.KeyMetadata key) {
        return new JSObject()
            .put("handleId", key.handleId)
            .put("label", key.label)
            .put("algorithm", key.algorithm)
            .put("fingerprintSha256", key.fingerprintSha256)
            .put("passphraseRequired", key.passphraseRequired)
            .put("createdAt", key.createdAt);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "The SSH key operation failed safely.";
        // Provider exception text can contain parser fragments. Keep only our
        // curated messages; callers never receive private-key input or URI data.
        if (message.contains("-----BEGIN") || message.contains("PRIVATE KEY-----") || message.contains("content://")
            || message.length() > 180) {
            return "SSH key operation failed. Check the selected key and try again.";
        }
        return message;
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0) {
                    String name = cursor.getString(nameColumn);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // A user-provided label is available on the next screen.
        }
        return "Imported SSH key";
    }

    private static void reject(PluginCall call, String code, String message) {
        call.reject(message == null ? "SSH key operation failed safely." : message, code);
    }

    private static final class PickedKeyDocument {
        final Uri uri;
        final String name;
        PickedKeyDocument(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        WipingByteArrayOutputStream(int capacity) { super(capacity); }
        byte[] toByteArrayAndWipe() {
            byte[] result = toByteArray();
            wipe();
            return result;
        }
        void wipe() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
