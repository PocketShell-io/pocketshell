package com.pocketshell.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Android document-picker and inbound-share I/O. File policy stays in TypeScript. */
@CapacitorPlugin(name = "DocumentContent")
public final class DocumentContentPlugin extends Plugin {
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final long MAX_CREATED_DOCUMENT_BYTES = 512L * 1024L;
    private static final int MAX_OPEN_DOCUMENTS = 64;
    private final Map<String, PickedContent> pickedContent = new LinkedHashMap<>();
    private final Map<String, CreatedContent> createdContent = new LinkedHashMap<>();

    @Override
    public void load() {
        super.load();
        handleShareIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        handleShareIntent(intent);
    }

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String mimeType = call.getString("mimeType", "*/*");
        if (mimeType == null || mimeType.trim().isEmpty() || mimeType.length() > 127) {
            mimeType = "*/*";
        }
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, Boolean.TRUE.equals(call.getBoolean("multiple", true)));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, intent, "documentsPicked");
    }

    @PluginMethod
    public void createDocument(PluginCall call) {
        String name = call.getString("name");
        if (name == null || name.trim().isEmpty() || name.length() > 255) {
            call.reject("The download filename is invalid.", "DOCUMENT_CREATE_INVALID_NAME");
            return;
        }
        String mimeType = call.getString("mimeType", "application/octet-stream");
        if (mimeType == null || mimeType.trim().isEmpty() || mimeType.length() > 127 || !mimeType.contains("/")) {
            mimeType = "application/octet-stream";
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(call, intent, "documentCreated");
    }

    @ActivityCallback
    private void documentCreated(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.resolve(new JSObject().put("cancelled", true));
            return;
        }
        Uri uri = result.getData().getData();
        try {
            if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new IOException("The selected save destination is not a writable content URI.");
            }
            String name = displayName(uri);
            String mimeType = null;
            try {
                mimeType = getContext().getContentResolver().getType(uri);
            } catch (RuntimeException ignored) {
                // Some providers do not report a MIME type for a new document.
            }
            String fileId = UUID.randomUUID().toString();
            synchronized (createdContent) {
                createdContent.put(fileId, new CreatedContent(fileId, uri, name));
                while (createdContent.size() > MAX_OPEN_DOCUMENTS) {
                    CreatedContent oldest = createdContent.remove(createdContent.keySet().iterator().next());
                    closeQuietly(oldest.output);
                }
            }
            call.resolve(new JSObject()
                    .put("cancelled", false)
                    .put("fileId", fileId)
                    .put("name", name)
                    .put("mimeType", mimeType));
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "The save destination could not be opened." : error.getMessage(),
                    "DOCUMENT_CREATE_FAILED", error);
        }
    }

    @ActivityCallback
    private void documentsPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.resolve(new JSObject().put("cancelled", true).put("files", new JSArray()));
            return;
        }
        try {
            ArrayList<Uri> uris = extractUris(result.getData());
            JSArray files = describeUris(uris);
            call.resolve(new JSObject().put("cancelled", false).put("files", files));
        } catch (Exception error) {
            call.reject("The selected document could not be opened.", "DOCUMENT_PICK_FAILED", error);
        }
    }

    @PluginMethod
    public void readPickedFileChunk(PluginCall call) {
        try {
            JSObject request = call.getData();
            String fileId = request.getString("fileId");
            long offset = request.getLong("offset");
            int requestedBytes = request.getInt("maxBytes");
            if (fileId == null || fileId.isEmpty()) throw new IOException("Picked document reference is missing.");
            if (offset < 0 || requestedBytes < 1 || requestedBytes > MAX_CHUNK_BYTES) {
                throw new IOException("Document chunk request is outside the supported range.");
            }

            PickedContent content;
            synchronized (pickedContent) {
                content = pickedContent.get(fileId);
            }
            if (content == null) throw new IOException("Picked document reference expired. Pick the file again.");
            if (content.sizeBytes >= 0 && offset > content.sizeBytes) {
                throw new IOException("Document chunk offset is outside the source file.");
            }

            byte[] bytes = readAtOffset(content.uri, offset, requestedBytes);
            boolean eof = bytes.length < requestedBytes
                    || (content.sizeBytes >= 0 && offset + bytes.length >= content.sizeBytes);
            JSObject response = new JSObject()
                    .put("fileId", fileId)
                    .put("offset", offset)
                    .put("bytesRead", bytes.length)
                    .put("eof", eof)
                    .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            call.resolve(response);
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "Picked document could not be read." : error.getMessage(),
                    "DOCUMENT_READ_FAILED", error);
        }
    }

    @PluginMethod
    public void releasePickedFile(PluginCall call) {
        String fileId = call.getString("fileId");
        if (fileId != null) {
            synchronized (pickedContent) {
                pickedContent.remove(fileId);
            }
        }
        call.resolve(new JSObject().put("released", fileId != null));
    }

    @PluginMethod
    public void writeCreatedDocumentChunk(PluginCall call) {
        try {
            String fileId = call.getString("fileId");
            long offset = requiredNonNegativeLong(call, "offset");
            String encoded = call.getString("base64");
            if (fileId == null || fileId.trim().isEmpty() || encoded == null) {
                throw new IOException("The selected save destination reference is missing.");
            }
            if (offset < 0) throw new IOException("The document write offset is invalid.");
            byte[] bytes;
            try {
                bytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException error) {
                throw new IOException("Document chunk is not valid base64.", error);
            }
            if (bytes.length < 1 || bytes.length > MAX_CHUNK_BYTES) {
                throw new IOException("Document chunk is outside the supported size range.");
            }

            CreatedContent content;
            synchronized (createdContent) {
                content = createdContent.get(fileId);
            }
            if (content == null) throw new IOException("The selected save destination expired. Choose a destination again.");
            synchronized (content) {
                if (offset != content.bytesWritten) throw new IOException("Document chunks must be written in order without gaps.");
                if (content.bytesWritten > MAX_CREATED_DOCUMENT_BYTES - bytes.length) {
                    throw new IOException("The selected file exceeds the 512 KiB transfer limit.");
                }
                if (content.output == null) {
                    content.output = getContext().getContentResolver().openOutputStream(content.uri, "w");
                    if (content.output == null) throw new IOException("The document provider returned no output stream.");
                }
                content.output.write(bytes);
                content.bytesWritten += bytes.length;
            }
            call.resolve(new JSObject()
                    .put("fileId", fileId)
                    .put("offset", content.bytesWritten)
                    .put("bytesWritten", bytes.length));
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "The selected document could not be written." : error.getMessage(),
                    "DOCUMENT_WRITE_FAILED", error);
        }
    }

    @PluginMethod
    public void completeCreatedDocument(PluginCall call) {
        String fileId = call.getString("fileId");
        long expectedBytes;
        try {
            expectedBytes = requiredNonNegativeLong(call, "expectedBytes");
        } catch (IOException error) {
            call.reject("The saved document byte count is invalid.", "DOCUMENT_COMPLETE_INVALID", error);
            return;
        }
        if (fileId == null || expectedBytes < 0 || expectedBytes > MAX_CREATED_DOCUMENT_BYTES) {
            call.reject("The saved document byte count is invalid.", "DOCUMENT_COMPLETE_INVALID");
            return;
        }
        CreatedContent content = removeCreatedContent(fileId);
        if (content == null) {
            call.reject("The selected save destination expired. Choose a destination again.", "DOCUMENT_WRITE_EXPIRED");
            return;
        }
        try {
            synchronized (content) {
                if (content.bytesWritten != expectedBytes) {
                    closeQuietly(content.output);
                    content.output = null;
                    throw new IOException("The saved document byte count did not match the requested file.");
                }
                if (content.output != null) {
                    content.output.flush();
                    content.output.close();
                    content.output = null;
                }
            }
            call.resolve(new JSObject()
                    .put("fileId", fileId)
                    .put("name", content.displayName)
                    .put("bytesWritten", content.bytesWritten)
                    .put("complete", true));
        } catch (Exception error) {
            synchronized (content) {
                closeQuietly(content.output);
                content.output = null;
            }
            call.reject(error.getMessage() == null ? "The selected document could not be completed." : error.getMessage(),
                    "DOCUMENT_COMPLETE_FAILED", error);
        }
    }

    @PluginMethod
    public void abortCreatedDocument(PluginCall call) {
        String fileId = call.getString("fileId");
        if (fileId == null || fileId.trim().isEmpty()) {
            call.resolve(new JSObject().put("aborted", false).put("deleted", false));
            return;
        }
        CreatedContent content = removeCreatedContent(fileId);
        if (content == null) {
            call.resolve(new JSObject().put("aborted", false).put("deleted", false));
            return;
        }
        synchronized (content) {
            closeQuietly(content.output);
            content.output = null;
        }
        // A create-document provider may have returned a pre-existing user file
        // after an overwrite choice; close safely and leave removal to the user.
        call.resolve(new JSObject().put("aborted", true).put("deleted", false));
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (pickedContent) {
            pickedContent.clear();
        }
        synchronized (createdContent) {
            for (CreatedContent content : createdContent.values()) {
                closeQuietly(content.output);
            }
            createdContent.clear();
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;

        JSObject share = new JSObject()
                .put("requestId", UUID.randomUUID().toString())
                .put("action", action)
                .put("mimeType", intent.getType());
        CharSequence subject = safeCharSequenceExtra(intent, Intent.EXTRA_SUBJECT);
        CharSequence text = safeCharSequenceExtra(intent, Intent.EXTRA_TEXT);
        if (subject != null) share.put("subject", subject.toString());
        if (text != null) share.put("text", text.toString());

        try {
            share.put("files", describeUris(extractUris(intent)));
        } catch (Exception error) {
            share.put("files", new JSArray());
            share.put("fileError", "Shared document metadata could not be read.");
        }
        notifyListeners("shareReceived", share, true);

        // The same Activity intent is visible again after WebView/process recreation.
        // Clearing its share payload after capture prevents duplicate delivery.
        Intent consumed = new Intent(getActivity(), MainActivity.class);
        getActivity().setIntent(consumed);
    }

    static ArrayList<Uri> extractUris(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        addUnique(uris, intent.getData());
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                addUnique(uris, clipData.getItemAt(index).getUri());
            }
        }

        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<?> streams;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                } else {
                    //noinspection deprecation
                    streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                }
                if (streams != null) {
                    for (Object stream : streams) {
                        if (stream instanceof Uri) addUnique(uris, (Uri) stream);
                    }
                }
            } else {
                Uri stream;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                } else {
                    //noinspection deprecation
                    stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                }
                addUnique(uris, stream);
            }
        } catch (RuntimeException ignored) {
            // Malformed or incompatible EXTRA_STREAM values do not discard valid data/ClipData URIs.
        }
        return uris;
    }

    private static void addUnique(List<Uri> uris, Uri uri) {
        if (uri != null && !uris.contains(uri)) uris.add(uri);
    }

    private JSArray describeUris(List<Uri> uris) throws IOException {
        if (uris.size() > MAX_OPEN_DOCUMENTS) {
            throw new IOException("Too many documents were selected. Select fewer files and try again.");
        }
        List<JSObject> files = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new IOException("The selected document does not provide a readable content URI.");
            }
            PickedContent content = remember(uri);
            JSObject item = new JSObject()
                    .put("fileId", content.fileId)
                    .put("name", content.displayName)
                    .put("mimeType", content.mimeType);
            if (content.sizeBytes >= 0) item.put("sizeBytes", content.sizeBytes);
            else item.put("sizeBytes", org.json.JSONObject.NULL);
            files.add(item);
        }
        return new JSArray(files);
    }

    private PickedContent remember(Uri uri) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        String name = displayName(uri);
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (RuntimeException error) {
            throw new IOException("Document provider metadata is unavailable.", error);
        }
        String mimeType;
        try {
            mimeType = resolver.getType(uri);
        } catch (RuntimeException ignored) {
            mimeType = null;
        }
        PickedContent content = new PickedContent(UUID.randomUUID().toString(), uri, name, mimeType, size);
        synchronized (pickedContent) {
            pickedContent.put(content.fileId, content);
            while (pickedContent.size() > MAX_OPEN_DOCUMENTS) {
                String oldest = pickedContent.keySet().iterator().next();
                pickedContent.remove(oldest);
            }
        }
        return content;
    }

    private String displayName(Uri uri) throws IOException {
        String name = null;
        try (Cursor cursor = getContext().getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
            }
        } catch (RuntimeException error) {
            throw new IOException("Document provider metadata is unavailable.", error);
        }
        if (name == null || name.trim().isEmpty()) name = "Shared file";
        return name;
    }

    private CreatedContent removeCreatedContent(String fileId) {
        synchronized (createdContent) {
            return createdContent.remove(fileId);
        }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) return;
        try {
            output.close();
        } catch (IOException ignored) {
            // Closing during cancellation is best-effort. Keep the user-selected URI intact.
        }
    }

    private static long requiredNonNegativeLong(PluginCall call, String name) throws IOException {
        Object raw = call.getData().opt(name);
        if (!(raw instanceof Number)) throw new IOException(name + " must be a non-negative integer.");
        Number number = (Number) raw;
        double exactValue = number.doubleValue();
        long value = number.longValue();
        if (!Double.isFinite(exactValue) || value < 0 || exactValue != (double) value) {
            throw new IOException(name + " must be a non-negative integer.");
        }
        return value;
    }

    private CharSequence safeCharSequenceExtra(Intent intent, String key) {
        try {
            return intent.getCharSequenceExtra(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private byte[] readAtOffset(Uri uri, long offset, int requestedBytes) throws IOException {
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Document provider returned no input stream.");
            long skipped = 0;
            while (skipped < offset) {
                long amount = input.skip(offset - skipped);
                if (amount > 0) {
                    skipped += amount;
                    continue;
                }
                if (input.read() < 0) throw new IOException("Document ended before the requested offset.");
                skipped++;
            }

            byte[] bytes = new byte[requestedBytes];
            int count = 0;
            while (count < requestedBytes) {
                int read = input.read(bytes, count, requestedBytes - count);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
            }
            if (count == bytes.length) return bytes;
            byte[] result = new byte[count];
            System.arraycopy(bytes, 0, result, 0, count);
            return result;
        } catch (SecurityException error) {
            throw new IOException("Document read permission expired. Pick the file again.", error);
        }
    }

    private static final class PickedContent {
        final String fileId;
        final Uri uri;
        final String displayName;
        final String mimeType;
        final long sizeBytes;

        PickedContent(String fileId, Uri uri, String displayName, String mimeType, long sizeBytes) {
            this.fileId = fileId;
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }
    }

    private static final class CreatedContent {
        final String fileId;
        final Uri uri;
        final String displayName;
        OutputStream output;
        long bytesWritten;

        CreatedContent(String fileId, Uri uri, String displayName) {
            this.fileId = fileId;
            this.uri = uri;
            this.displayName = displayName;
        }
    }
}
