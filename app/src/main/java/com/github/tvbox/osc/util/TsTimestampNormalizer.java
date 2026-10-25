package com.github.tvbox.osc.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Repairs clock resets in byte-concatenated MPEG-TS without decoding or modifying the source. */
public final class TsTimestampNormalizer {
    private static final int PACKET_SIZE = 188;
    private static final long WRAP = 1L << 33;
    private static final long MASK = WRAP - 1;
    // PTS may go backwards for B-frames. Only a large decode/PCR clock jump starts a new epoch.
    private static final long RESET_THRESHOLD = 90000;

    private final Clock[] pcrClocks = new Clock[8192];
    private final Clock[] decodeClocks = new Clock[8192];
    private final int[] continuity = new int[8192];
    private final Header[] pending = new Header[8192];
    private final List<Patch> patches = new ArrayList<>();
    private long offset;
    private int resets;

    private TsTimestampNormalizer() {
        java.util.Arrays.fill(continuity, -1);
    }

    /** Cache is keyed by source identity, size and mtime. Never overwrite an existing download. */
    public static synchronized File prepare(File source, File cacheDir) throws IOException {
        if (!source.isFile()) throw new IOException("TS 文件不存在");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IOException("无法创建 TS 时间轴缓存目录");
        String key;
        try {
            String identity = "v2:" + source.getCanonicalPath() + ":" + source.length() + ":" + source.lastModified();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            key = hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        File cached = new File(cacheDir, key + ".ts");
        File checked = new File(cacheDir, key + ".ok");
        if (cached.isFile() && cached.length() == source.length()) return cached;
        if (checked.isFile()) return source;
        long free = cacheDir.getUsableSpace();
        if (free > 0 && free < source.length()) throw new IOException("修复 TS 时间轴需要临时空间，请先清理存储");
        File temp = File.createTempFile("timeline-", ".tmp", cacheDir);
        try {
            int count = normalize(source, temp);
            if (count == 0) {
                if (!checked.createNewFile() && !checked.isFile()) throw new IOException("无法保存 TS 检查结果");
                return source;
            }
            if (!temp.renameTo(cached)) throw new IOException("无法保存 TS 时间轴缓存");
            return cached;
        } finally {
            temp.delete();
        }
    }

    /** Returns the number of clock resets repaired. Output must not be the source. */
    public static int normalize(File source, File output) throws IOException {
        if (source.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("不能覆盖原 TS 文件");
        TsTimestampNormalizer normalizer = new TsTimestampNormalizer();
        boolean complete = false;
        try {
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), 256 * 1024);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output), 256 * 1024)) {
                byte[] packet = new byte[PACKET_SIZE];
                long position = 0;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TS 时间轴检查已取消");
                    int size = 0;
                    int read;
                    while (size < packet.length && (read = in.read(packet, size, packet.length - size)) != -1) size += read;
                    if (size == 0) break;
                    if (size != PACKET_SIZE || packet[0] != 0x47) throw new IOException("不是完整的 188 字节 TS 数据包，已保留原文件");
                    normalizer.packet(packet, position);
                    out.write(packet);
                    position += PACKET_SIZE;
                }
                if (position == 0) throw new IOException("TS 文件为空");
            }
            // PES headers can straddle TS packets; apply only those sparse fixes after buffered I/O.
            if (!normalizer.patches.isEmpty()) {
                try (RandomAccessFile file = new RandomAccessFile(output, "rw")) {
                    for (Patch patch : normalizer.patches) {
                        for (int i = 0; i < patch.positions.length; i++) {
                            file.seek(patch.positions[i]);
                            file.write(patch.bytes[i]);
                        }
                    }
                }
            }
            complete = true;
            return normalizer.resets;
        } finally {
            if (!complete) output.delete();
        }
    }

    private void packet(byte[] data, long position) throws IOException {
        int pid = ((data[1] & 31) << 8) | (data[2] & 255);
        if ((data[1] & 0x80) != 0 || (data[3] & 0xc0) != 0) throw new IOException("TS 数据损坏或仍然加密，已保留原文件");
        int control = (data[3] >> 4) & 3;
        boolean payload = (control & 1) != 0;
        if (control == 0) throw new IOException("无效的 TS 包头");
        if (payload) continuity[pid] = (continuity[pid] + 1) & 15;
        if (continuity[pid] >= 0) data[3] = (byte) ((data[3] & 0xf0) | continuity[pid]);
        int start = 4;
        if ((control & 2) != 0) {
            int length = data[4] & 255;
            start = 5 + length;
            if (start > PACKET_SIZE) throw new IOException("TS adaptation field 长度错误");
            if (length > 0) {
                int flags = data[5] & 255;
                if ((flags & 0x10) != 0) {
                    if (length < 7) throw new IOException("TS PCR 长度错误");
                    observe(pcrClocks, pid, readPcr(data, 6));
                    writePcr(data, 6, (readPcr(data, 6) + offset) & MASK);
                }
                if ((flags & 0x08) != 0) {
                    int opcr = 6 + ((flags & 0x10) != 0 ? 6 : 0);
                    if (opcr + 6 > start) throw new IOException("TS OPCR 长度错误");
                    writePcr(data, opcr, (readPcr(data, opcr) + offset) & MASK);
                }
                // The output has continuous counters/clocks, so it is no longer discontinuous.
                data[5] = (byte) (flags & ~0x80);
            }
        }
        if (!payload || start >= PACKET_SIZE || pid == 8191) return;
        boolean first = (data[1] & 0x40) != 0;
        if (first) {
            pending[pid] = null;
            // PSI and arbitrary payloads are not PES headers.
            if (start + 3 > PACKET_SIZE || data[start] != 0 || data[start + 1] != 0 || data[start + 2] != 1) return;
            if (start + 19 <= PACKET_SIZE) {
                rewritePes(data, start, pid);
                return;
            }
            pending[pid] = new Header();
        }
        Header header = pending[pid];
        if (header == null) return;
        for (int i = start; i < PACKET_SIZE && header.size < 19; i++) {
            header.positions[header.size] = position + i;
            header.bytes[header.size++] = data[i];
        }
        if (header.size < 9) return;
        int flags = (header.bytes[7] >> 6) & 3;
        int need = flags == 3 ? 19 : flags == 2 ? 14 : 9;
        if (header.size < need) return;
        rewritePes(header.bytes, 0, pid);
        if (need > 9) {
            long[] positions = java.util.Arrays.copyOfRange(header.positions, 9, need);
            byte[] bytes = java.util.Arrays.copyOfRange(header.bytes, 9, need);
            patches.add(new Patch(positions, bytes));
        }
        pending[pid] = null;
    }

    private void rewritePes(byte[] data, int start, int pid) throws IOException {
        // MPEG-2 PES marker bits and optional timestamp header.
        if ((data[start + 6] & 0xc0) != 0x80) return;
        int flags = (data[start + 7] >> 6) & 3;
        if (flags != 2 && flags != 3) return;
        int length = data[start + 8] & 255;
        if (length < (flags == 3 ? 10 : 5)) throw new IOException("TS PES 时间戳长度错误");
        long pts = readPts(data, start + 9);
        long dts = flags == 3 ? readPts(data, start + 14) : pts;
        observe(decodeClocks, pid, dts);
        writePts(data, start + 9, (pts + offset) & MASK);
        if (flags == 3) writePts(data, start + 14, (dts + offset) & MASK);
    }

    private void observe(Clock[] clocks, int pid, long raw) {
        Clock clock = clocks[pid];
        if (clock == null) {
            clock = new Clock();
            clocks[pid] = clock;
            clock.raw = raw;
            clock.unwrapped = raw;
            clock.output = raw + offset;
            return;
        }
        long delta = (raw - clock.raw + WRAP / 2) & MASK;
        delta -= WRAP / 2;
        clock.unwrapped += delta;
        long next = clock.unwrapped + offset;
        if (next < clock.output - RESET_THRESHOLD) {
            offset += clock.output + clock.step - next;
            next = clock.unwrapped + offset;
            resets++;
        } else if (delta > 0 && delta < RESET_THRESHOLD) {
            clock.step = delta;
        }
        clock.raw = raw;
        clock.output = next;
    }

    private static long readPcr(byte[] b, int i) {
        return ((long) (b[i] & 255) << 25) | ((long) (b[i + 1] & 255) << 17)
                | ((long) (b[i + 2] & 255) << 9) | ((long) (b[i + 3] & 255) << 1) | ((b[i + 4] & 128) >> 7);
    }

    private static void writePcr(byte[] b, int i, long value) {
        b[i] = (byte) (value >> 25);
        b[i + 1] = (byte) (value >> 17);
        b[i + 2] = (byte) (value >> 9);
        b[i + 3] = (byte) (value >> 1);
        b[i + 4] = (byte) ((b[i + 4] & 127) | ((value & 1) << 7));
    }

    static long readPts(byte[] b, int i) {
        return ((long) (b[i] & 14) << 29) | ((long) (b[i + 1] & 255) << 22)
                | ((long) (b[i + 2] & 254) << 14) | ((long) (b[i + 3] & 255) << 7) | ((b[i + 4] & 254) >> 1);
    }

    static void writePts(byte[] b, int i, long value) {
        b[i] = (byte) ((b[i] & 0xf1) | ((value >> 29) & 14));
        b[i + 1] = (byte) (value >> 22);
        b[i + 2] = (byte) (1 | ((value >> 14) & 254));
        b[i + 3] = (byte) (value >> 7);
        b[i + 4] = (byte) (1 | ((value << 1) & 254));
    }

    private static final class Clock {
        long raw, unwrapped, output;
        long step = 1800; // 20 ms until a real PCR/frame interval is observed.
    }

    private static final class Header {
        final byte[] bytes = new byte[19];
        final long[] positions = new long[19];
        int size;
    }

    private static final class Patch {
        final long[] positions;
        final byte[] bytes;
        Patch(long[] positions, byte[] bytes) { this.positions = positions; this.bytes = bytes; }
    }
}
