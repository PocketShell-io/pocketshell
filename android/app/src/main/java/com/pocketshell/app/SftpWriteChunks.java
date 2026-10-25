package com.pocketshell.app;

import java.io.IOException;

/** Bounded SFTP WRITE packets; the bridge separately bounds the whole file. */
final class SftpWriteChunks {
    static final int PACKET_BYTES = 32 * 1024;
    interface Writer {
        void write(long fileOffset, byte[] bytes, int sourceOffset, int length) throws IOException;
    }
    static void write(Writer writer, byte[] bytes) throws IOException {
        for (int offset = 0; offset < bytes.length;) {
            int length = Math.min(PACKET_BYTES, bytes.length - offset);
            writer.write(offset, bytes, offset, length);
            offset += length;
        }
    }
}
