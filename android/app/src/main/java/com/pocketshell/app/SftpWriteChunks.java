package com.pocketshell.app;

import java.io.IOException;

/** Bounded SFTP WRITE packets; the bridge separately bounds the whole file. */
final class SftpWriteChunks {
    static final int PACKET_BYTES = 32 * 1024;
    interface Writer {
        void write(long fileOffset, byte[] bytes, int sourceOffset, int length) throws IOException;
    }
    static void write(Writer writer, byte[] bytes) throws IOException {
        write(writer, 0L, bytes);
    }

    /** Write {@code bytes} starting at {@code fileOffset}: a resumable upload's next chunk (#3191). */
    static void write(Writer writer, long fileOffset, byte[] bytes) throws IOException {
        if (fileOffset < 0) throw new IOException("SFTP write offset is negative.");
        for (int offset = 0; offset < bytes.length;) {
            int length = Math.min(PACKET_BYTES, bytes.length - offset);
            writer.write(fileOffset + offset, bytes, offset, length);
            offset += length;
        }
    }

    /**
     * Why a resumable upload chunk must be refused before it is written
     * (#3191), or null when it may proceed. Offset 0 starts a fresh
     * {@code .part} and must not overwrite anything; a later chunk needs its
     * {@code .part} still present and ending exactly where the chunk starts.
     * {@code currentSize} is ignored at offset 0 and when the target is absent.
     */
    static String chunkRefusal(long offset, boolean exists, boolean regularFile, long currentSize) {
        if (offset < 0) return "INVALID_ARGUMENT";
        if (offset == 0) return exists ? "SFTP_FILE_EXISTS" : null;
        if (!exists) return "SFTP_NOT_FOUND";
        if (!regularFile) return "SFTP_NOT_FILE";
        return currentSize == offset ? null : "SFTP_OFFSET_MISMATCH";
    }
}
