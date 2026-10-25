package com.github.tvbox.osc.util;

import java.io.IOException;
import java.nio.ByteBuffer;

/** MediaExtractor may return several ADTS frames in one TS access unit. MP4 needs raw AAC frames. */
public final class AdtsSampleSplitter {
    private static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350};

    public interface Consumer {
        void sample(int offset, int size, long timeUs) throws IOException;
    }

    private AdtsSampleSplitter() { }

    /** False means the extractor already supplied raw AAC; caller should write it unchanged. */
    public static boolean split(ByteBuffer buffer, int size, long timeUs, Consumer consumer) throws IOException {
        if (size < 2 || (buffer.get(0) & 255) != 255 || (buffer.get(1) & 0xf6) != 0xf0) return false;
        int offset = 0;
        long samples = 0;
        int rate = 0;
        while (offset < size) {
            if (size - offset < 7 || (buffer.get(offset) & 255) != 255
                    || (buffer.get(offset + 1) & 0xf6) != 0xf0) throw new IOException("AAC ADTS 帧头损坏");
            int header = (buffer.get(offset + 1) & 1) == 1 ? 7 : 9;
            int index = (buffer.get(offset + 2) >> 2) & 15;
            if (index >= SAMPLE_RATES.length) throw new IOException("AAC 采样率不受支持");
            int currentRate = SAMPLE_RATES[index];
            if (rate != 0 && rate != currentRate) throw new IOException("AAC 分片的采样率不一致，不能直接转封装");
            rate = currentRate;
            int length = ((buffer.get(offset + 3) & 3) << 11)
                    | ((buffer.get(offset + 4) & 255) << 3) | ((buffer.get(offset + 5) & 0xe0) >> 5);
            if (length <= header || offset + length > size) throw new IOException("AAC ADTS 帧不完整");
            if ((buffer.get(offset + 6) & 3) != 0) throw new IOException("暂不支持多 raw_data_block 的 AAC ADTS 帧");
            consumer.sample(offset + header, length - header, timeUs + samples * 1000000L / rate);
            samples += 1024;
            offset += length;
        }
        return true;
    }
}
