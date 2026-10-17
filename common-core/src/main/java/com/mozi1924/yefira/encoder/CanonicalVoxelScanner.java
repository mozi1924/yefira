package com.mozi1924.yefira.encoder;

import com.mozi1924.yefira.selection.SelectionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Authoritative 3D continuous voxel and biome scanner.
 * Conforms strictly to RFC v3.1 Section 3:
 * - Traversal: Bottom-up horizontal slice scanning (Y slowest, Z next, X fastest).
 * - Hash: xxHash64 (seed = 0) authoritative fingerprint.
 */
public class CanonicalVoxelScanner {

    public static class BaselineScanResult {
        public final BlockPos minPos;
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;
        public final long totalVoxels;
        public final String dimension;
        public final List<String> blockPalette;
        public final List<String> biomePalette;
        public final byte[] payload;
        public final int paletteBitsPerBlock;
        public final int biomeBitsPerQuart;
        public final long stateHash;

        public BaselineScanResult(
                BlockPos minPos,
                int sizeX, int sizeY, int sizeZ,
                long totalVoxels,
                String dimension,
                List<String> blockPalette,
                List<String> biomePalette,
                byte[] payload,
                int paletteBitsPerBlock,
                int biomeBitsPerQuart,
                long stateHash
        ) {
            this.minPos = minPos;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.totalVoxels = totalVoxels;
            this.dimension = dimension;
            this.blockPalette = blockPalette;
            this.biomePalette = biomePalette;
            this.payload = payload;
            this.paletteBitsPerBlock = paletteBitsPerBlock;
            this.biomeBitsPerQuart = biomeBitsPerQuart;
            this.stateHash = stateHash;
        }
    }

    /**
     * Normalizes a block state string representation for hashing according to RFC Section 3.1.
     */
    public static String normalizeCanonicalBlockState(BlockState state) {
        if (state == null || state.isAir()) {
            return "minecraft:air";
        }
        if (state.is(Blocks.BUBBLE_COLUMN)
                || state.is(Blocks.STRUCTURE_VOID)
                || state.is(Blocks.CAVE_AIR)
                || state.is(Blocks.VOID_AIR)) {
            return "minecraft:air";
        }
        return BlockDataEncoder.serializeBlockState(state);
    }

    /**
     * Computes the authoritative canonical state_hash across the 3D bounding volume.
     * Traversal strictly adheres to RFC Section 3.1:
     * - X is fastest-varying axis
     * - Z is next-fastest axis
     * - Y is slowest-varying axis (bottom-up scanning of horizontal slices)
     */
    public static long computeCanonicalStateHash(Level level, SelectionBox box, String dimension) {
        XXHash64 hasher = new XXHash64(0);

        BlockPos min = box.getMin();
        int minX = min.getX();
        int minY = min.getY();
        int minZ = min.getZ();
        int sizeX = box.getSizeX();
        int sizeY = box.getSizeY();
        int sizeZ = box.getSizeZ();

        // 1. Metadata Header
        hasher.update((byte) 0x01); // Canonical Encoding Version: u8
        byte[] dimBytes = dimension.getBytes(StandardCharsets.UTF_8);
        hasher.updateLE16((short) dimBytes.length);
        hasher.update(dimBytes);
        hasher.updateLE32(minX);
        hasher.updateLE32(minY);
        hasher.updateLE32(minZ);
        hasher.updateLE32(sizeX);
        hasher.updateLE32(sizeY);
        hasher.updateLE32(sizeZ);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 2. Continuous 3D Voxel Stream (Y-slowest, Z-next, X-fastest)
        int maxX = minX + sizeX;
        int maxY = minY + sizeY;
        int maxZ = minZ + sizeZ;

        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    String stateStr = normalizeCanonicalBlockState(state);
                    byte[] bytes = stateStr.getBytes(StandardCharsets.UTF_8);
                    hasher.updateLE16((short) bytes.length);
                    hasher.update(bytes);
                }
            }
        }

        // 3. Continuous 3D Quart Biome Stream (4x4x4 downsampled grid)
        int qw = (sizeX + 3) / 4;
        int qh = (sizeY + 3) / 4;
        int qd = (sizeZ + 3) / 4;

        for (int qy = 0; qy < qh; qy++) {
            for (int qz = 0; qz < qd; qz++) {
                for (int qx = 0; qx < qw; qx++) {
                    pos.set(minX + (qx * 4), minY + (qy * 4), minZ + (qz * 4));
                    String biomeStr = BlockDataEncoder.getBiomeId(level, pos);
                    String trimmed = (biomeStr == null || biomeStr.trim().isEmpty()) ? "minecraft:plains" : biomeStr.trim();
                    byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
                    hasher.updateLE16((short) bytes.length);
                    hasher.update(bytes);
                }
            }
        }

        return hasher.digest();
    }

    /**
     * Performs a single continuous scan of the selection volume to extract the palette,
     * indices, and compute the canonical state hash simultaneously.
     */
    public static BaselineScanResult scanBaseline(Level level, SelectionBox box, String dimension) {
        BlockPos min = box.getMin();
        int minX = min.getX();
        int minY = min.getY();
        int minZ = min.getZ();
        int sizeX = box.getSizeX();
        int sizeY = box.getSizeY();
        int sizeZ = box.getSizeZ();
        long totalVoxels = (long) sizeX * sizeY * sizeZ;

        XXHash64 hasher = new XXHash64(0);

        // 1. Header Hash
        hasher.update((byte) 0x01);
        byte[] dimBytes = dimension.getBytes(StandardCharsets.UTF_8);
        hasher.updateLE16((short) dimBytes.length);
        hasher.update(dimBytes);
        hasher.updateLE32(minX);
        hasher.updateLE32(minY);
        hasher.updateLE32(minZ);
        hasher.updateLE32(sizeX);
        hasher.updateLE32(sizeY);
        hasher.updateLE32(sizeZ);

        List<String> blockPalette = new ArrayList<>();
        Map<String, Integer> blockPaletteLookup = new HashMap<>();
        // Air is always entry 0
        blockPalette.add("minecraft:air");
        blockPaletteLookup.put("minecraft:air", 0);

        int[] voxelIndices = new int[(int) totalVoxels];
        int vIdx = 0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxX = minX + sizeX;
        int maxY = minY + sizeY;
        int maxZ = minZ + sizeZ;

        // 2. Voxel Scan (Y bottom-up, Z next, X fastest)
        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    String stateStr = normalizeCanonicalBlockState(state);

                    // Hash update
                    byte[] bytes = stateStr.getBytes(StandardCharsets.UTF_8);
                    hasher.updateLE16((short) bytes.length);
                    hasher.update(bytes);

                    // Palette lookup
                    Integer idx = blockPaletteLookup.get(stateStr);
                    if (idx == null) {
                        idx = blockPalette.size();
                        blockPalette.add(stateStr);
                        blockPaletteLookup.put(stateStr, idx);
                    }
                    voxelIndices[vIdx++] = idx;
                }
            }
        }

        // 3. Biome Scan (4x4x4 quart grid)
        int qw = (sizeX + 3) / 4;
        int qh = (sizeY + 3) / 4;
        int qd = (sizeZ + 3) / 4;
        int totalQuarts = qw * qh * qd;

        List<String> biomePalette = new ArrayList<>();
        Map<String, Integer> biomePaletteLookup = new HashMap<>();
        biomePalette.add("minecraft:plains");
        biomePaletteLookup.put("minecraft:plains", 0);

        int[] biomeIndices = new int[totalQuarts];
        int bIdx = 0;

        for (int qy = 0; qy < qh; qy++) {
            for (int qz = 0; qz < qd; qz++) {
                for (int qx = 0; qx < qw; qx++) {
                    pos.set(minX + (qx * 4), minY + (qy * 4), minZ + (qz * 4));
                    String biomeStr = BlockDataEncoder.getBiomeId(level, pos);
                    String trimmed = (biomeStr == null || biomeStr.trim().isEmpty()) ? "minecraft:plains" : biomeStr.trim();

                    // Hash update
                    byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
                    hasher.updateLE16((short) bytes.length);
                    hasher.update(bytes);

                    Integer bId = biomePaletteLookup.get(trimmed);
                    if (bId == null) {
                        bId = biomePalette.size();
                        biomePalette.add(trimmed);
                        biomePaletteLookup.put(trimmed, bId);
                    }
                    biomeIndices[bIdx++] = bId;
                }
            }
        }

        long finalStateHash = hasher.digest();

        // 4. Pack payload (LSB packed indices)
        int bitsPerBlock = (blockPalette.size() <= 256) ? 8 : 16;
        int voxelBytesLen = (int) totalVoxels * (bitsPerBlock / 8);

        // Biome packing (if multiple biomes)
        boolean hasMultipleBiomes = biomePalette.size() > 1;
        int biomeBits = hasMultipleBiomes ? 8 : 0;
        int biomeBytesLen = hasMultipleBiomes ? totalQuarts : 0;

        byte[] payload = new byte[voxelBytesLen + biomeBytesLen];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        if (bitsPerBlock == 8) {
            for (int i = 0; i < totalVoxels; i++) {
                buf.put((byte) (voxelIndices[i] & 0xFF));
            }
        } else {
            for (int i = 0; i < totalVoxels; i++) {
                buf.putShort((short) (voxelIndices[i] & 0xFFFF));
            }
        }

        if (hasMultipleBiomes) {
            for (int i = 0; i < totalQuarts; i++) {
                buf.put((byte) (biomeIndices[i] & 0xFF));
            }
        }

        return new BaselineScanResult(
                min,
                sizeX, sizeY, sizeZ,
                totalVoxels,
                dimension,
                blockPalette,
                biomePalette,
                payload,
                bitsPerBlock,
                biomeBits,
                finalStateHash
        );
    }
}
