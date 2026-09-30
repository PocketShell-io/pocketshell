package com.pocketshell.app;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only, bounded bridge for importing app-private data from the legacy Android app. */
@CapacitorPlugin(name = "InstalledDataMigration")
public final class InstalledDataMigrationPlugin extends Plugin {
    private static final int MAX_CHUNK_BYTES = 32 * 1024;
    private final Map<String, AssetDescriptor> assets = new ConcurrentHashMap<>();

    @PluginMethod
    public void readLegacyInstalledData(PluginCall call) {
        assets.clear();
        try {
            JSObject result = new LegacyInstalledDataReader(getContext(), assets).read();
            call.resolve(result);
        } catch (LegacyInstalledDataReader.MigrationReadException error) {
            assets.clear();
            call.reject(error.getMessage(), "INSTALLED_DATA_INVALID", error);
        } catch (Exception error) {
            assets.clear();
            call.reject("Installed data could not be read safely. Original files were left untouched.",
                "INSTALLED_DATA_READ_FAILED", error);
        }
    }

    @PluginMethod
    public void readAssetChunk(PluginCall call) {
        try {
            JSObject data = call.getData();
            String assetId = data.getString("assetId");
            long offset = data.getLong("offset");
            int requestedBytes = data.getInt("maxBytes");
            if (assetId == null || assetId.isEmpty()) throw new IOException("Asset reference is missing.");
            if (offset < 0 || requestedBytes < 1 || requestedBytes > MAX_CHUNK_BYTES) {
                throw new IOException("Asset chunk request is outside the supported range.");
            }
            AssetDescriptor asset = assets.get(assetId);
            if (asset == null) throw new IOException("Asset reference expired. Retry the installed-data read.");
            if (Files.isSymbolicLink(asset.file.toPath()) ||
                !asset.file.getCanonicalFile().equals(asset.file) ||
                !asset.file.isFile() || asset.file.length() != asset.byteLength || asset.file.lastModified() != asset.lastModified) {
                throw new IOException("A private data file changed during migration. Retry without deleting the source.");
            }
            if (offset > asset.byteLength) throw new IOException("Asset chunk offset is outside the source file.");
            int count = (int) Math.min(requestedBytes, asset.byteLength - offset);
            byte[] bytes = new byte[count];
            try (FileInputStream input = new FileInputStream(asset.file)) {
                long skipped = 0;
                while (skipped < offset) {
                    long amount = input.skip(offset - skipped);
                    if (amount <= 0) throw new IOException("Could not seek within a private data file.");
                    skipped += amount;
                }
                int read = 0;
                while (read < count) {
                    int amount = input.read(bytes, read, count - read);
                    if (amount < 0) throw new IOException("A private data file ended during migration.");
                    read += amount;
                }
            }
            call.resolve(new JSObject()
                .put("assetId", assetId)
                .put("offset", offset)
                .put("byteLength", count)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)));
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "Private data file could not be read." : error.getMessage(),
                "INSTALLED_DATA_ASSET_READ_FAILED", error);
        }
    }

    static final class AssetDescriptor {
        final File file;
        final long byteLength;
        final long lastModified;
        final String sha256;

        AssetDescriptor(File file, long byteLength, long lastModified, String sha256) {
            this.file = file;
            this.byteLength = byteLength;
            this.lastModified = lastModified;
            this.sha256 = sha256;
        }
    }
}
