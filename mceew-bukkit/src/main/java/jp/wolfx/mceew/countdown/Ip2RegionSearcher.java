// Copyright 2022 The Ip2Region Authors. All rights reserved.
// Use of this source code is governed by a Apache2.0-style
// license that can be found in the LICENSE file.
// Adapted from lionsoul2014/ip2region v2.13.0 (in-memory search only).

package jp.wolfx.mceew.countdown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-memory ip2region v2 xdb searcher (IPv4 only).
 * Returns region strings like "中国|0|四川省|成都市|电信".
 */
final class Ip2RegionSearcher {
    static final int HeaderInfoLength = 256;
    static final int VectorIndexRows = 256;
    static final int VectorIndexCols = 256;
    static final int VectorIndexSize = 8;
    static final int SegmentIndexSize = 14;

    private final byte[] content;

    private Ip2RegionSearcher(byte[] content) {
        this.content = content;
    }

    /** Loads the whole xdb file into memory. Returns null if the file cannot be read. */
    static Ip2RegionSearcher load(Path dbFile) {
        try {
            byte[] buff = Files.readAllBytes(dbFile);
            return new Ip2RegionSearcher(buff);
        } catch (IOException e) {
            return null;
        }
    }

    /** Loads from an in-memory byte array (e.g. the bundled copy inside the plugin jar). */
    static Ip2RegionSearcher load(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        return new Ip2RegionSearcher(content);
    }

    /** Searches an IPv4 string. Returns null for IPv6 / invalid / not found. Synchronized (not thread-safe internally). */
    synchronized String search(String ipStr) {
        long ip;
        try {
            ip = checkIP(ipStr);
        } catch (Exception e) {
            return null;
        }
        try {
            return search(ip);
        } catch (IOException e) {
            return null;
        }
    }

    private String search(long ip) throws IOException {
        int il0 = (int) ((ip >> 24) & 0xFF);
        int il1 = (int) ((ip >> 16) & 0xFF);
        int idx = HeaderInfoLength + il0 * VectorIndexCols * VectorIndexSize + il1 * VectorIndexSize;
        long sPtr = getIntLong(content, idx);
        long ePtr = getIntLong(content, idx + 4);

        int dataLen = -1;
        long dataPtr = -1;
        long l = 0, h = (ePtr - sPtr) / SegmentIndexSize;
        while (l <= h) {
            long m = (l + h) >> 1;
            long p = sPtr + m * SegmentIndexSize;
            long sip = getIntLong(content, (int) p);
            if (ip < sip) {
                h = m - 1;
            } else {
                long eip = getIntLong(content, (int) p + 4);
                if (ip > eip) {
                    l = m + 1;
                } else {
                    dataLen = getInt2(content, (int) p + 8);
                    dataPtr = getIntLong(content, (int) p + 10);
                    break;
                }
            }
        }
        if (dataPtr < 0) {
            return null;
        }
        return new String(content, (int) dataPtr, dataLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long getIntLong(byte[] b, int offset) {
        return ((b[offset] & 0xFFL)) |
                ((b[offset + 1] << 8) & 0xFF00L) |
                ((b[offset + 2] << 16) & 0xFF0000L) |
                ((b[offset + 3] << 24) & 0xFF000000L);
    }

    private static int getInt2(byte[] b, int offset) {
        return ((b[offset] & 0xFF)) | ((b[offset + 1] << 8) & 0xFF00);
    }

    private static long checkIP(String ip) throws Exception {
        String[] ps = ip.split("\\.");
        if (ps.length != 4) {
            throw new Exception("invalid ip address `" + ip + "`");
        }
        long ipDst = 0;
        for (int i = 0; i < ps.length; i++) {
            int val = Integer.parseInt(ps[i]);
            if (val > 255) {
                throw new Exception("ip part `" + ps[i] + "` should be less then 256");
            }
            ipDst |= ((long) val << shiftIndex[i]);
        }
        return ipDst & 0xFFFFFFFFL;
    }

    private static final int[] shiftIndex = {24, 16, 8, 0};
}
