package com.pocketshell.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class SftpWriteProgressTest {
    @Test
    public void reportsCumulativeBytesOnlyAfterEachAcknowledgedChunk() throws Exception {
        byte[] payload = new byte[SftpWriteProgress.CHUNK_BYTES * 2 + 5];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index % 251);
        byte[] written = new byte[payload.length];
        List<Long> offsets = new ArrayList<>();
        List<Long> reported = new ArrayList<>();

        SftpWriteProgress.write(payload, (offset, data, dataOffset, length) -> {
            offsets.add(offset);
            assertEquals(offset, dataOffset);
            assertEquals(Math.min(SftpWriteProgress.CHUNK_BYTES, payload.length - offset), length);
            System.arraycopy(data, dataOffset, written, (int) offset, length);
        }, (bytesWritten, totalBytes) -> {
            assertEquals(payload.length, totalBytes);
            reported.add(bytesWritten);
        });

        assertEquals(Arrays.asList(0L, (long) SftpWriteProgress.CHUNK_BYTES, SftpWriteProgress.CHUNK_BYTES * 2L), offsets);
        assertEquals(Arrays.asList((long) SftpWriteProgress.CHUNK_BYTES,
            SftpWriteProgress.CHUNK_BYTES * 2L, (long) payload.length), reported);
        assertArrayEquals(payload, written);
    }

    @Test
    public void emptyFilesDoNotEmitProgressTicks() throws Exception {
        List<Long> reported = new ArrayList<>();
        SftpWriteProgress.write(new byte[0], (offset, data, dataOffset, length) -> {
            throw new AssertionError("An empty payload must not issue a write.");
        }, (bytesWritten, totalBytes) -> reported.add(bytesWritten));
        assertEquals(Arrays.asList(), reported);
    }

    @Test
    public void aClosedTransportSuppressesProgressBeforePostingAndBeforeDelivery() {
        AtomicBoolean connected = new AtomicBoolean(false);
        List<Runnable> posted = new ArrayList<>();
        List<Long> reported = new ArrayList<>();

        SftpWriteProgress.postIfConnected(connected::get, callback -> posted.add(callback),
            (bytesWritten, totalBytes) -> reported.add(bytesWritten), 10, 20);
        assertEquals(0, posted.size());

        connected.set(true);
        SftpWriteProgress.postIfConnected(connected::get, callback -> posted.add(callback),
            (bytesWritten, totalBytes) -> reported.add(bytesWritten), 15, 20);
        assertEquals(1, posted.size());
        connected.set(false);
        posted.get(0).run();

        assertEquals(Arrays.asList(), reported);
    }

    @Test
    public void anOpenTransportDeliversTheAcknowledgedProgressCallback() {
        List<Runnable> posted = new ArrayList<>();
        List<Long> reported = new ArrayList<>();

        SftpWriteProgress.postIfConnected(() -> true, callback -> posted.add(callback),
            (bytesWritten, totalBytes) -> {
                assertEquals(20, totalBytes);
                reported.add(bytesWritten);
            }, 10, 20);
        posted.get(0).run();

        assertEquals(Arrays.asList(10L), reported);
    }

    @Test
    public void aWriteFailureStopsLaterProgressTicks() throws Exception {
        byte[] payload = new byte[SftpWriteProgress.CHUNK_BYTES * 3];
        List<Long> reported = new ArrayList<>();

        try {
            SftpWriteProgress.write(payload, (offset, data, dataOffset, length) -> {
                if (offset == SftpWriteProgress.CHUNK_BYTES) throw new java.io.IOException("transport closed");
            }, (bytesWritten, totalBytes) -> reported.add(bytesWritten));
        } catch (java.io.IOException expected) {
            assertEquals("transport closed", expected.getMessage());
        }

        assertEquals(Arrays.asList((long) SftpWriteProgress.CHUNK_BYTES), reported);
    }
}
