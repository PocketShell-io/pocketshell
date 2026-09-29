package com.pocketshell.app;

/** Writes bounded SFTP chunks and reports only chunks the transport acknowledged. */
final class SftpWriteProgress {
    static final int CHUNK_BYTES = 16 * 1024;

    interface ChunkWriter {
        void write(long remoteOffset, byte[] data, int dataOffset, int length) throws Exception;
    }

    interface ProgressListener {
        void onAcknowledged(long bytesWritten, long totalBytes);
    }

    interface ConnectionStatus {
        boolean isConnected();
    }

    interface EventPoster {
        void post(Runnable callback);
    }

    private SftpWriteProgress() {}

    static void write(byte[] data, ChunkWriter writer, ProgressListener progress) throws Exception {
        for (int offset = 0; offset < data.length; ) {
            int length = Math.min(CHUNK_BYTES, data.length - offset);
            writer.write(offset, data, offset, length);
            offset += length;
            progress.onAcknowledged(offset, data.length);
        }
    }

    /** Check transport state both before posting and when the callback is delivered. */
    static void postIfConnected(
        ConnectionStatus connection,
        EventPoster poster,
        ProgressListener progress,
        long bytesWritten,
        long totalBytes
    ) {
        if (!connection.isConnected()) return;
        poster.post(() -> {
            if (connection.isConnected()) progress.onAcknowledged(bytesWritten, totalBytes);
        });
    }
}
