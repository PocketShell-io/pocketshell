package com.pocketshell.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class SftpWriteChunksTest {
    @Test public void wholeSupportedFileUsesBoundedPacketsAndPreservesEveryByteAndOffset() throws Exception {
        byte[] source = new byte[512 * 1024];
        for (int index = 0; index < source.length; index++) source[index] = (byte) ((index * 31) % 251);
        ByteArrayOutputStream remote = new ByteArrayOutputStream();
        List<Long> offsets = new ArrayList<>();
        SftpWriteChunks.write((fileOffset, bytes, sourceOffset, length) -> {
            assertTrue("each real SFTP request must fit a bounded packet", length > 0 && length <= 32 * 1024);
            assertEquals("the next packet starts exactly after the acknowledged data", remote.size(), fileOffset);
            assertEquals("each packet reads its matching source position", fileOffset, sourceOffset);
            offsets.add(fileOffset);
            remote.write(bytes, sourceOffset, length);
        }, source);
        assertEquals(16, offsets.size());
        assertArrayEquals(source, remote.toByteArray());
    }

    @Test public void finalPartialPacketAndEmptyFileDoNotAddPaddingOrEmptyRequests() throws Exception {
        List<Integer> lengths = new ArrayList<>();
        SftpWriteChunks.write((fileOffset, bytes, sourceOffset, length) -> lengths.add(length), new byte[32 * 1024 + 7]);
        assertEquals(java.util.Arrays.asList(32 * 1024, 7), lengths);
        lengths.clear();
        SftpWriteChunks.write((fileOffset, bytes, sourceOffset, length) -> lengths.add(length), new byte[0]);
        assertTrue(lengths.isEmpty());
    }

    @Test public void failedPacketStopsTheUploadAndPropagatesTheOriginalError() {
        IOException failure = new IOException("remote refused packet");
        int[] count = {0};
        IOException observed = assertThrows(IOException.class, () -> SftpWriteChunks.write((offset, bytes, from, length) -> {
            count[0]++;
            if (count[0] == 2) throw failure;
        }, new byte[96 * 1024]));
        assertEquals(failure, observed);
        assertEquals("later bytes must not be sent after an unacknowledged write", 2, count[0]);
    }
}
