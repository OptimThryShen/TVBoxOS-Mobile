package com.github.tvbox.osc.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TsTimestampNormalizerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final long MASK = (1L << 33) - 1;

    @Test public void repairsThreeTwelveSecondEpochsAndPreservesCompositionOffsets() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int epoch = 0; epoch < 3; epoch++) {
            for (int frame = 0; frame < 300; frame++) bytes.write(packet(frame * 3600L, true));
        }
        File source = write("input.ts", bytes.toByteArray());
        File output = folder.newFile("fixed.ts");
        assertEquals(2, TsTimestampNormalizer.normalize(source, output));
        byte[] result = Files.readAllBytes(output.toPath());
        for (int i = 0; i < 900; i++) {
            int start = i * 188;
            assertEquals(i * 3600L, pcr(result, start + 6));
            assertEquals(i * 3600L + 7200, TsTimestampNormalizer.readPts(result, start + 21));
            assertEquals(i * 3600L, TsTimestampNormalizer.readPts(result, start + 26));
            assertEquals(i & 15, result[start + 3] & 15);
        }
        assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(source.toPath()));
        assertEquals(0, TsTimestampNormalizer.normalize(output, folder.newFile("twice.ts")));
    }

    @Test public void interleavedAudioAndVideoShareOneEpochOffset() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int epoch = 0; epoch < 3; epoch++) {
            for (int i = 0; i < 300; i++) {
                bytes.write(packet(i * 3600L, true));
                byte[] audio = packet(i * 3600L + 9000, false);
                audio[2] = 1; // A separate audio PID, with a stable 100 ms clock offset.
                audio[7] = (byte) 0xc0;
                bytes.write(audio);
            }
        }
        File output = folder.newFile("av-out.ts");
        assertEquals(2, TsTimestampNormalizer.normalize(write("av.ts", bytes.toByteArray()), output));
        byte[] result = Files.readAllBytes(output.toPath());
        for (int i = 0; i < 900; i++) {
            assertEquals(i * 3600L, TsTimestampNormalizer.readPts(result, i * 376 + 26));
            assertEquals(i * 3600L + 9000, TsTimestampNormalizer.readPts(result, i * 376 + 188 + 18));
        }
    }

    @Test public void cancellationDeletesOnlyTheTemporaryOutput() throws Exception {
        File source = write("cancel.ts", packet(90000, true));
        File output = folder.newFile("cancel-out.ts");
        Thread.currentThread().interrupt();
        try {
            TsTimestampNormalizer.normalize(source, output);
            fail("Interrupted scan must stop");
        } catch (java.io.InterruptedIOException expected) {
            assertEquals(188, source.length());
            assertFalse(output.exists());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void unwrapAndBFrameReorderingAreNotDiscontinuities() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            byte[] packet = packet((MASK - 7200 + i * 3600) & MASK, true);
            // Decode clock still increases, while presentation order changes by two frames.
            TsTimestampNormalizer.writePts(packet, 21, (MASK - 7200 + (i % 2 == 0 ? i + 2 : i) * 3600) & MASK);
            bytes.write(packet);
        }
        assertEquals(0, TsTimestampNormalizer.normalize(write("wrap.ts", bytes.toByteArray()), folder.newFile("wrap-out.ts")));
    }

    @Test public void pesDecodeClockCanRepairStreamsWithoutPcr() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int epoch = 0; epoch < 2; epoch++) {
            for (int i = 0; i < 300; i++) bytes.write(packet(i * 3600L, false));
        }
        File output = folder.newFile("no-pcr-out.ts");
        assertEquals(1, TsTimestampNormalizer.normalize(write("no-pcr.ts", bytes.toByteArray()), output));
        byte[] result = Files.readAllBytes(output.toPath());
        assertEquals(599 * 3600L, TsTimestampNormalizer.readPts(result, 599 * 188 + 18));
    }

    @Test public void pesHeaderAcrossPacketBoundaryIsPatched() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int epoch = 0; epoch < 2; epoch++) {
            for (int i = 0; i < 300; i++) {
                byte[] pes = Arrays.copyOfRange(packet(i * 3600L, false), 4, 23);
                byte[] first = new byte[188];
                Arrays.fill(first, (byte) 0xff);
                first[0] = 0x47; first[1] = 0x41; first[2] = 0; first[3] = 0x30;
                first[4] = (byte) 171; first[5] = 0;
                System.arraycopy(pes, 0, first, 176, 12);
                byte[] next = new byte[188];
                Arrays.fill(next, (byte) 0xff);
                next[0] = 0x47; next[1] = 1; next[2] = 0; next[3] = 0x11;
                System.arraycopy(pes, 12, next, 4, 7);
                bytes.write(first); bytes.write(next);
            }
        }
        File output = folder.newFile("split-out.ts");
        assertEquals(1, TsTimestampNormalizer.normalize(write("split.ts", bytes.toByteArray()), output));
        byte[] result = Files.readAllBytes(output.toPath());
        byte[] last = new byte[19];
        int first = 599 * 376;
        System.arraycopy(result, first + 176, last, 0, 12);
        System.arraycopy(result, first + 188 + 4, last, 12, 7);
        assertEquals(599 * 3600L, TsTimestampNormalizer.readPts(last, 14));
    }

    @Test public void malformedInputNeverChangesSourceOrPublishesPartialOutput() throws Exception {
        File source = write("bad.ts", new byte[189]);
        File output = folder.newFile("bad-out.ts");
        try {
            TsTimestampNormalizer.normalize(source, output);
            fail("Expected invalid TS rejection");
        } catch (java.io.IOException expected) {
            assertEquals(189, source.length());
            assertFalse(output.exists());
        }
        try {
            TsTimestampNormalizer.normalize(source, source);
            fail("Must not overwrite source");
        } catch (java.io.IOException expected) { assertEquals(189, source.length()); }
    }

    @Test public void cacheChecksSourceIdentityAndDoesNotRewriteHealthyStreams() throws Exception {
        File source = write("healthy.ts", packet(90000, true));
        File cache = folder.newFolder("cache");
        assertEquals(source, TsTimestampNormalizer.prepare(source, cache));
        assertEquals(source, TsTimestampNormalizer.prepare(source, cache));
        ByteArrayOutputStream reset = new ByteArrayOutputStream();
        for (int epoch = 0; epoch < 2; epoch++) {
            for (int i = 0; i < 300; i++) reset.write(packet(i * 3600L, true));
        }
        Files.write(source.toPath(), reset.toByteArray());
        File prepared = TsTimestampNormalizer.prepare(source, cache);
        assertNotEquals(source, prepared);
        assertEquals(source.length(), prepared.length());
        assertEquals(prepared, TsTimestampNormalizer.prepare(source, cache));
    }

    private File write(String name, byte[] bytes) throws Exception {
        File file = folder.newFile(name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static byte[] packet(long time, boolean pcr) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47; packet[1] = 0x41; packet[2] = 0; packet[3] = (byte) (pcr ? 0x30 : 0x10);
        int start = pcr ? 12 : 4;
        if (pcr) {
            packet[4] = 7; packet[5] = 0x10;
            packet[6] = (byte) (time >> 25); packet[7] = (byte) (time >> 17);
            packet[8] = (byte) (time >> 9); packet[9] = (byte) (time >> 1);
            packet[10] = (byte) (0x7e | ((time & 1) << 7)); packet[11] = 0;
        }
        packet[start] = 0; packet[start + 1] = 0; packet[start + 2] = 1; packet[start + 3] = (byte) 0xe0;
        packet[start + 4] = 0; packet[start + 5] = 0; packet[start + 6] = (byte) 0x80;
        packet[start + 7] = (byte) 0xc0; packet[start + 8] = 10;
        packet[start + 9] = 0x31; packet[start + 14] = 0x11;
        TsTimestampNormalizer.writePts(packet, start + 9, (time + 7200) & MASK);
        TsTimestampNormalizer.writePts(packet, start + 14, time & MASK);
        return packet;
    }

    private static long pcr(byte[] b, int i) {
        return ((long) (b[i] & 255) << 25) | ((long) (b[i + 1] & 255) << 17)
                | ((long) (b[i + 2] & 255) << 9) | ((long) (b[i + 3] & 255) << 1) | ((b[i + 4] & 128) >> 7);
    }
}
