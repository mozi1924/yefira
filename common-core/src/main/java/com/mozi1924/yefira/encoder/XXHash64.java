package com.mozi1924.yefira.encoder;

/**
 * High-performance, zero-dependency pure Java implementation of 64-bit xxHash (xxHash64).
 * Matches xxhash_rust::xxh64::Xxh64 / C reference implementation byte-for-byte.
 */
public class XXHash64 {
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private final long seed;
    private long v1, v2, v3, v4;
    private long totalLen = 0;
    private final byte[] memory = new byte[32];
    private int memSize = 0;

    public XXHash64(long seed) {
        this.seed = seed;
        reset();
    }

    public void reset() {
        this.v1 = seed + PRIME64_1 + PRIME64_2;
        this.v2 = seed + PRIME64_2;
        this.v3 = seed;
        this.v4 = seed - PRIME64_1;
        this.totalLen = 0;
        this.memSize = 0;
    }

    public void update(byte[] b, int offset, int len) {
        if (len <= 0) return;
        totalLen += len;

        if (memSize + len < 32) {
            System.arraycopy(b, offset, memory, memSize, len);
            memSize += len;
            return;
        }

        int index = offset;
        int end = offset + len;

        if (memSize > 0) {
            int toCopy = 32 - memSize;
            System.arraycopy(b, index, memory, memSize, toCopy);
            v1 = round(v1, getLongLE(memory, 0));
            v2 = round(v2, getLongLE(memory, 8));
            v3 = round(v3, getLongLE(memory, 16));
            v4 = round(v4, getLongLE(memory, 24));
            index += toCopy;
            memSize = 0;
        }

        int limit = end - 32;
        while (index <= limit) {
            v1 = round(v1, getLongLE(b, index));
            v2 = round(v2, getLongLE(b, index + 8));
            v3 = round(v3, getLongLE(b, index + 16));
            v4 = round(v4, getLongLE(b, index + 24));
            index += 32;
        }

        if (index < end) {
            memSize = end - index;
            System.arraycopy(b, index, memory, 0, memSize);
        }
    }

    public void update(byte[] b) {
        update(b, 0, b.length);
    }

    public void update(byte b) {
        totalLen++;
        memory[memSize++] = b;
        if (memSize == 32) {
            v1 = round(v1, getLongLE(memory, 0));
            v2 = round(v2, getLongLE(memory, 8));
            v3 = round(v3, getLongLE(memory, 16));
            v4 = round(v4, getLongLE(memory, 24));
            memSize = 0;
        }
    }

    public void updateLE16(short val) {
        update((byte) (val & 0xFF));
        update((byte) ((val >> 8) & 0xFF));
    }

    public void updateLE32(int val) {
        update((byte) (val & 0xFF));
        update((byte) ((val >> 8) & 0xFF));
        update((byte) ((val >> 16) & 0xFF));
        update((byte) ((val >> 24) & 0xFF));
    }

    public void updateLE64(long val) {
        for (int i = 0; i < 8; i++) {
            update((byte) ((val >> (8 * i)) & 0xFF));
        }
    }

    public long digest() {
        long h64;
        if (totalLen >= 32) {
            h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME64_5;
        }

        h64 += totalLen;

        int index = 0;
        while (index + 8 <= memSize) {
            long k1 = round(0, getLongLE(memory, index));
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 27) * PRIME64_1 + PRIME64_4;
            index += 8;
        }

        if (index + 4 <= memSize) {
            long k1 = (getIntLE(memory, index) & 0xFFFFFFFFL) * PRIME64_1;
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 23) * PRIME64_2 + PRIME64_3;
            index += 4;
        }

        while (index < memSize) {
            long k1 = (memory[index] & 0xFFL) * PRIME64_5;
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 11) * PRIME64_1;
            index++;
        }

        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;

        return h64;
    }

    private static long round(long acc, long input) {
        acc += input * PRIME64_2;
        acc = Long.rotateLeft(acc, 31);
        acc *= PRIME64_1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        acc = acc * PRIME64_1 + PRIME64_4;
        return acc;
    }

    private static long getLongLE(byte[] b, int offset) {
        return ((long) b[offset] & 0xFF)
                | (((long) b[offset + 1] & 0xFF) << 8)
                | (((long) b[offset + 2] & 0xFF) << 16)
                | (((long) b[offset + 3] & 0xFF) << 24)
                | (((long) b[offset + 4] & 0xFF) << 32)
                | (((long) b[offset + 5] & 0xFF) << 40)
                | (((long) b[offset + 6] & 0xFF) << 48)
                | (((long) b[offset + 7] & 0xFF) << 56);
    }

    private static int getIntLE(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }
}
