package com.pocketshell.app.smoke;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Screenshot frame staleness detector (#3067).
 *
 * Verifies that a newly captured screenshot frame represents a distinct UI state
 * rather than a stale capture of the previous screen whose byte-identical
 * check was defeated by status-bar clock/icon updates.
 */
public final class ScreenshotGuard {
    private ScreenshotGuard() {}

    public static final class Frame {
        public final byte[] bytes;
        public final byte[] contentDigest;

        public Frame(byte[] bytes, byte[] contentDigest) {
            this.bytes = bytes;
            this.contentDigest = contentDigest;
        }

        public boolean isStaleRelativeTo(Frame previous) {
            if (previous == null) return false;
            if (Arrays.equals(this.bytes, previous.bytes)) return true;
            if (this.contentDigest != null && previous.contentDigest != null
                    && Arrays.equals(this.contentDigest, previous.contentDigest)) {
                return true;
            }
            return false;
        }
    }

    public static byte[] computeContentDigest(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int topInset = Math.max(1, (int) (height * 0.10));
            int contentHeight = height - topInset;
            if (width <= 0 || contentHeight <= 0) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int chunkRows = 64;
            int[] buffer = new int[width * chunkRows];
            ByteBuffer byteBuf = ByteBuffer.allocate(width * chunkRows * 4);
            for (int y = topInset; y < height; y += chunkRows) {
                int currentChunk = Math.min(chunkRows, height - y);
                bitmap.getPixels(buffer, 0, width, 0, y, width, currentChunk);
                byteBuf.clear();
                byteBuf.asIntBuffer().put(buffer, 0, width * currentChunk);
                md.update(byteBuf.array(), 0, width * currentChunk * 4);
            }
            return md.digest();
        } catch (Exception e) {
            return null;
        }
    }
}
