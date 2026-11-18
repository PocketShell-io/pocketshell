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

    @Test public void resumedChunkWritesAtTheRequestedFileOffsetWhileReadingFromTheChunkStart() throws Exception {
        // #3191: a chunked attachment upload appends its next chunk at the
        // byte the host already holds; packets must keep that file offset.
        byte[] chunk = new byte[40 * 1024];
        for (int index = 0; index < chunk.length; index++) chunk[index] = (byte) (index % 253);
        List<long[]> calls = new ArrayList<>();
        ByteArrayOutputStream remote = new ByteArrayOutputStream();
        SftpWriteChunks.write((fileOffset, bytes, sourceOffset, length) -> {
            calls.add(new long[] {fileOffset, sourceOffset, length});
            remote.write(bytes, sourceOffset, length);
        }, 512L * 1024L * 3L, chunk);
        assertEquals(2, calls.size());
        assertArrayEquals(new long[] {1572864L, 0L, 32768L}, calls.get(0));
        assertArrayEquals(new long[] {1572864L + 32768L, 32768L, 8192L}, calls.get(1));
        assertArrayEquals(chunk, remote.toByteArray());
        assertThrows(IOException.class, () -> SftpWriteChunks.write((a, b, c, d) -> { }, -1L, chunk));
    }

    @Test public void chunkRefusalsGuardEveryResumableUploadState() {
        // #3191: offset 0 never overwrites; a later chunk needs its .part, as a
        // regular file, ending exactly where the chunk starts.
        assertEquals(null, SftpWriteChunks.chunkRefusal(0, false, false, 0));
        assertEquals("SFTP_FILE_EXISTS", SftpWriteChunks.chunkRefusal(0, true, true, 0));
        assertEquals(null, SftpWriteChunks.chunkRefusal(524_288, true, true, 524_288));
        assertEquals("SFTP_NOT_FOUND", SftpWriteChunks.chunkRefusal(524_288, false, false, 0));
        assertEquals("SFTP_NOT_FILE", SftpWriteChunks.chunkRefusal(524_288, true, false, 524_288));
        assertEquals("a lost chunk", "SFTP_OFFSET_MISMATCH", SftpWriteChunks.chunkRefusal(1_048_576, true, true, 524_288));
        assertEquals("a duplicated chunk", "SFTP_OFFSET_MISMATCH", SftpWriteChunks.chunkRefusal(524_288, true, true, 1_048_576));
        assertEquals("INVALID_ARGUMENT", SftpWriteChunks.chunkRefusal(-1, false, false, 0));
    }
}
