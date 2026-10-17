package com.mozi1924.yefira;

import com.mozi1924.yefira.encoder.XXHash64;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

public class XXHash64Test {

    @Test
    public void testEmptyDigest() {
        XXHash64 hasher = new XXHash64(0);
        long digest = hasher.digest();
        // Standard xxHash64(seed=0) of empty input is 0xef46db3751d8e999L
        Assertions.assertEquals(0xef46db3751d8e999L, digest);
    }

    @Test
    public void testIncrementalVersusBulk() {
        byte[] data = "Hello Antigravity Live Sync RFC v3.1 World! Testing xxHash64 chunking streaming consistency across boundaries."
                .getBytes(StandardCharsets.UTF_8);

        XXHash64 bulk = new XXHash64(0);
        bulk.update(data);
        long bulkDigest = bulk.digest();

        XXHash64 chunked = new XXHash64(0);
        // Feed byte-by-byte or in small slices
        for (byte b : data) {
            chunked.update(b);
        }
        long chunkedDigest = chunked.digest();

        Assertions.assertEquals(bulkDigest, chunkedDigest);
    }

    @Test
    public void testCanonicalStateHashMatchesRust() {
        // Reproduce compute_canonical_state_hash for min_pos=(0, 64, 0), size=(2, 2, 2)
        // 8 x "minecraft:stone", 1 x "minecraft:plains", dimension="minecraft:overworld"
        XXHash64 hasher = new XXHash64(0);

        // Header: 0x01, dim_len=19 (LE), "minecraft:overworld", min=(0, 64, 0), size=(2, 2, 2)
        hasher.update((byte) 0x01);
        byte[] dimBytes = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
        hasher.updateLE16((short) dimBytes.length);
        hasher.update(dimBytes);
        hasher.updateLE32(0);
        hasher.updateLE32(64);
        hasher.updateLE32(0);
        hasher.updateLE32(2);
        hasher.updateLE32(2);
        hasher.updateLE32(2);

        // 8 blocks of "minecraft:stone"
        byte[] stoneBytes = "minecraft:stone".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 8; i++) {
            hasher.updateLE16((short) stoneBytes.length);
            hasher.update(stoneBytes);
        }

        // 1 quart biome of "minecraft:plains"
        byte[] plainsBytes = "minecraft:plains".getBytes(StandardCharsets.UTF_8);
        hasher.updateLE16((short) plainsBytes.length);
        hasher.update(plainsBytes);

        long digest = hasher.digest();
        // Exact reference value verified against libmtk_py / mtk-sync
        Assertions.assertEquals(0x917e864ccc3ba009L, digest);
    }
}
