package com.github.tvbox.osc.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AdtsSampleSplitterTest {
    @Test public void splitsGroupedAacAndRemovesSevenAndNineByteHeaders() throws Exception {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(frame(false, 4));
        joined.write(frame(true, 6));
        ByteBuffer data = ByteBuffer.wrap(joined.toByteArray());
        List<Long> times = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        assertTrue(AdtsSampleSplitter.split(data, data.remaining(), 1000000, (offset, size, time) -> {
            assertEquals(0x55, data.get(offset) & 255);
            lengths.add(size);
            times.add(time);
        }));
        assertEquals(java.util.Arrays.asList(4, 6), lengths);
        assertEquals(java.util.Arrays.asList(1000000L, 1000000L + 1024 * 1000000L / 48000), times);
    }

    @Test public void rawAacIsLeftUntouched() throws Exception {
        assertFalse(AdtsSampleSplitter.split(ByteBuffer.wrap(new byte[]{1, 2, 3}), 3, 0,
                (offset, size, time) -> fail("Raw AAC must not be split")));
    }

    @Test public void truncatedAdtsFailsInsteadOfPublishingAnIncompleteMp4() throws Exception {
        byte[] bytes = frame(false, 4);
        try {
            AdtsSampleSplitter.split(ByteBuffer.wrap(bytes), bytes.length - 1, 0, (o, s, t) -> fail("Incomplete frame"));
            fail("Truncated frame accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("不完整")); }
    }

    private static byte[] frame(boolean crc, int payloadSize) {
        int header = crc ? 9 : 7;
        int length = header + payloadSize;
        byte[] result = new byte[length];
        result[0] = (byte) 255; result[1] = (byte) (crc ? 0xf0 : 0xf1);
        result[2] = (byte) ((1 << 6) | (3 << 2)); // AAC LC, 48 kHz
        result[3] = (byte) (0x80 | (length >> 11));
        result[4] = (byte) (length >> 3); result[5] = (byte) ((length << 5) | 31); result[6] = (byte) 0xfc;
        java.util.Arrays.fill(result, header, length, (byte) 0x55);
        return result;
    }
}
