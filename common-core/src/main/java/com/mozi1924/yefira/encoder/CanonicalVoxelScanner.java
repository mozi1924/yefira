package com.mozi1924.yefira.encoder;

import com.mozi1924.yefira.selection.SelectionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Authoritative 3D continuous voxel and biome scanner.
 * Conforms strictly to RFC v3.1 Section 3:
 * - Traversal: Bottom-up horizontal slice scanning (Y slowest, Z next, X fastest).
 * - Hash: xxHash64 (seed = 0) authoritative fingerprint.
 */
public class CanonicalVoxelScanner {

    /**
     * Hard upper bound for a single authoritative scan. The WebSocket manager
     * also guards the scan future, but this deadline lives inside the scan loop
     * so a flat/large selection aborts on the thread that is actually touching
     * chunk data instead of pinning the server thread until the future expires.
     */
    public static final long DEFAULT_MAX_SCAN_MILLIS = 120_000L;

    /** Thrown when a scan exceeds {@link #DEFAULT_MAX_SCAN_MILLIS}. */
    public static final class ScanTimeoutException extends RuntimeException {
        public ScanTimeoutException(String message) {
            super(message);
        }
    }

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
        public final List<BlockDataEncoder.EntityEntry> entities;

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
            this(minPos, sizeX, sizeY, sizeZ, totalVoxels, dimension, blockPalette, biomePalette, payload, paletteBitsPerBlock, biomeBitsPerQuart, stateHash, Collections.emptyList());
        }

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
                long stateHash,
                List<BlockDataEncoder.EntityEntry> entities
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
            this.entities = entities != null ? entities : Collections.emptyList();
        }
    }

    public static class CanonicalBlock {
        public final String string;
        public final byte[] utf8Bytes;

        public CanonicalBlock(String string, byte[] utf8Bytes) {
            this.string = string;
            this.utf8Bytes = utf8Bytes;
        }
    }

    private static final byte[] AIR_UTF8 = "minecraft:air".getBytes(StandardCharsets.UTF_8);
    private static final CanonicalBlock CANONICAL_AIR = new CanonicalBlock("minecraft:air", AIR_UTF8);
    private static final Map<BlockState, CanonicalBlock> CANONICAL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static CanonicalBlock getCanonicalBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return CANONICAL_AIR;
        }
        if (state.is(Blocks.BUBBLE_COLUMN)
                || state.is(Blocks.STRUCTURE_VOID)
                || state.is(Blocks.CAVE_AIR)
                || state.is(Blocks.VOID_AIR)) {
            return CANONICAL_AIR;
        }
        CanonicalBlock cached = CANONICAL_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        String serialized = BlockDataEncoder.serializeBlockState(state);
        byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
        cached = new CanonicalBlock(serialized, bytes);
        CANONICAL_CACHE.put(state, cached);
        return cached;
    }

    /**
     * Normalizes a block state string representation for hashing according to RFC Section 3.1.
     */
    public static String normalizeCanonicalBlockState(BlockState state) {
        return getCanonicalBlock(state).string;
    }

    /**
     * Computes the authoritative canonical state_hash across the 3D bounding volume.
     * Traversal strictly adheres to RFC Section 3.1:
     * - X is fastest-varying axis
     * - Z is next-fastest axis
     * - Y is slowest-varying axis (bottom-up scanning of horizontal slices)
     */
    public static long computeCanonicalStateHash(Level level, SelectionBox box, String dimension) {
        BaselineScanResult result = scanBaseline(level, box, dimension);
        return result != null ? result.stateHash : 0L;
    }

    /**
     * Performs a single continuous scan of the selection volume to extract the palette,
     * indices, and compute the canonical state hash simultaneously.
     * High-performance chunk-grid accelerated implementation.
     */
    public static BaselineScanResult scanBaseline(Level level, SelectionBox box, String dimension) {
        return scanBaseline(level, box, dimension, () -> false);
    }

    /**
     * Cancellable scan variant. When {@code cancelled} becomes true (e.g. the user moved
     * the selection while a large baseline was being scanned) the scan aborts early and
     * returns {@code null}. This prevents unbounded concurrent scans and stale snapshots
     * from saturating CPU / heap on rapid selection drags.
     */
    public static BaselineScanResult scanBaseline(
            Level level,
            SelectionBox box,
            String dimension,
            java.util.function.BooleanSupplier cancelled
    ) {
        return scanBaseline(level, box, dimension, cancelled, DEFAULT_MAX_SCAN_MILLIS);
    }

    /**
     * Cancellable, time-bounded scan variant.
     *
     * <p>Cancellation is polled per chunk-column span (never more than 16 blocks)
     * and once per Z row, so even a flat selection such as 2048x8x2048 aborts
     * promptly. Thread interruption is also honoured. When {@code maxDurationMillis}
     * is positive, exceeding it throws {@link ScanTimeoutException}.</p>
     */
    public static BaselineScanResult scanBaseline(
            Level level,
            SelectionBox box,
            String dimension,
            java.util.function.BooleanSupplier cancelled,
            long maxDurationMillis
    ) {
        long scanStartNanos = System.nanoTime();
        long maxScanNanos = maxDurationMillis > 0
                ? TimeUnit.MILLISECONDS.toNanos(maxDurationMillis)
                : Long.MAX_VALUE;
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
        Map<BlockState, Integer> stateToPaletteId = new IdentityHashMap<>();

        // Air is always entry 0
        blockPalette.add("minecraft:air");
        blockPaletteLookup.put("minecraft:air", 0);

        int[] voxelIndices = new int[(int) totalVoxels];
        int vIdx = 0;

        int maxX = minX + sizeX;
        int maxY = minY + sizeY;
        int maxZ = minZ + sizeZ;

        // Pre-fetch chunk grid across selection bounding box
        int minSecX = minX >> 4;
        int maxSecX = (maxX - 1) >> 4;
        int minSecZ = minZ >> 4;
        int maxSecZ = (maxZ - 1) >> 4;
        int chunksW = maxSecX - minSecX + 1;
        int chunksD = maxSecZ - minSecZ + 1;

        LevelChunk[][] chunkGrid = new LevelChunk[chunksW][chunksD];
        for (int cz = minSecZ; cz <= maxSecZ; cz++) {
            for (int cx = minSecX; cx <= maxSecX; cx++) {
                if (checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                    return null;
                }
                try {
                    chunkGrid[cx - minSecX][cz - minSecZ] = level.getChunk(cx, cz);
                } catch (Exception ignored) {
                }
            }
        }

        // 2. Voxel Scan (Y bottom-up, Z next, X fastest)
        for (int y = minY; y < maxY; y++) {
            int secY = y >> 4;
            int sectionIdx = level.getSectionIndexFromSectionY(secY);
            int ly = y & 15;

            for (int z = minZ; z < maxZ; z++) {
                // One check per Z row: flat selections never advance Y, so the
                // old once-per-16-Y check could never fire for them.
                if (checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                    return null;
                }
                int cz = (z >> 4) - minSecZ;
                int lz = z & 15;

                int x = minX;
                while (x < maxX) {
                    int cx = (x >> 4) - minSecX;
                    LevelChunk chunk = (cx >= 0 && cx < chunksW && cz >= 0 && cz < chunksD) ? chunkGrid[cx][cz] : null;
                    LevelChunkSection section = null;
                    if (chunk != null) {
                        LevelChunkSection[] sections = chunk.getSections();
                        if (sectionIdx >= 0 && sectionIdx < sections.length) {
                            section = sections[sectionIdx];
                        }
                    }

                    int nextBoundaryX = (x & ~15) + 16;
                    int nextX = Math.min(maxX, nextBoundaryX);
                    int span = nextX - x;

                    // Poll per chunk-column span (<= 16 voxels) so a single wide
                    // row cannot monopolise cancellation latency.
                    if (checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                        return null;
                    }

                    if (section == null || section.hasOnlyAir()) {
                        for (int i = 0; i < span; i++) {
                            hasher.updateLE16((short) CANONICAL_AIR.utf8Bytes.length);
                            hasher.update(CANONICAL_AIR.utf8Bytes);
                            voxelIndices[vIdx++] = 0;
                        }
                        x = nextX;
                    } else {
                        for (; x < nextX; x++) {
                            BlockState state = section.getBlockState(x & 15, ly, lz);
                            CanonicalBlock cb = getCanonicalBlock(state);

                            // Hash update directly with precomputed UTF-8 bytes
                            hasher.updateLE16((short) cb.utf8Bytes.length);
                            hasher.update(cb.utf8Bytes);

                            // IdentityHashMap lookup by BlockState reference
                            Integer idx = stateToPaletteId.get(state);
                            if (idx == null) {
                                idx = blockPaletteLookup.get(cb.string);
                                if (idx == null) {
                                    idx = blockPalette.size();
                                    blockPalette.add(cb.string);
                                    blockPaletteLookup.put(cb.string, idx);
                                }
                                stateToPaletteId.put(state, idx);
                            }
                            voxelIndices[vIdx++] = idx;
                        }
                    }
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
        Map<String, byte[]> biomeUtf8Cache = new HashMap<>();

        int[] biomeIndices = new int[totalQuarts];
        int bIdx = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int qy = 0; qy < qh; qy++) {
            if (checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                return null;
            }
            for (int qz = 0; qz < qd; qz++) {
                for (int qx = 0; qx < qw; qx++) {
                    pos.set(minX + (qx * 4), minY + (qy * 4), minZ + (qz * 4));
                    String biomeStr = BlockDataEncoder.getBiomeId(level, pos);
                    String trimmed = (biomeStr == null || biomeStr.trim().isEmpty()) ? "minecraft:plains" : biomeStr.trim();

                    // Hash update
                    byte[] bytes = biomeUtf8Cache.computeIfAbsent(trimmed, s -> s.getBytes(StandardCharsets.UTF_8));
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

        if (biomePalette.isEmpty()) {
            biomePalette.add("minecraft:plains");
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

        if (bitsPerBlock == 8) {
            for (int i = 0; i < totalVoxels; i++) {
                if ((i & 0xFFFFF) == 0
                        && checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                    return null;
                }
                payload[i] = (byte) voxelIndices[i];
            }
        } else {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < totalVoxels; i++) {
                if ((i & 0xFFFFF) == 0
                        && checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                    return null;
                }
                buf.putShort((short) (voxelIndices[i] & 0xFFFF));
            }
        }

        if (hasMultipleBiomes) {
            int offset = voxelBytesLen;
            for (int i = 0; i < totalQuarts; i++) {
                if ((i & 0xFFFFF) == 0
                        && checkAbort(cancelled, scanStartNanos, maxScanNanos, maxDurationMillis)) {
                    return null;
                }
                payload[offset + i] = (byte) biomeIndices[i];
            }
        }

        // 4. BlockEntity Scan (Sparse RFC v3.2)
        List<BlockDataEncoder.EntityEntry> entities = new ArrayList<>();
        try {
            com.mozi1924.yefira.compat.ILevelCompat levelCompat = com.mozi1924.yefira.compat.VersionCompat.level();
            for (int cz = 0; cz < chunksD; cz++) {
                for (int cx = 0; cx < chunksW; cx++) {
                    LevelChunk chunk = chunkGrid[cx][cz];
                    if (chunk == null) continue;
                    for (Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos p = entry.getKey();
                        if (box.contains(p)) {
                            String json = levelCompat.serializeBlockEntityJson(entry.getValue());
                            if (json != null && !json.isEmpty()) {
                                int rx = p.getX() - minX;
                                int ry = p.getY() - minY;
                                int relZ = p.getZ() - minZ;
                                entities.add(new BlockDataEncoder.EntityEntry(rx, ry, relZ, json));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
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
                finalStateHash,
                entities
        );
    }

    /**
     * Returns {@code true} when the scan must abort because the caller cancelled
     * it or the worker thread was interrupted. Throws when the wall-clock budget
     * is exhausted so callers can distinguish a timeout from a superseded scan.
     */
    private static boolean checkAbort(
            java.util.function.BooleanSupplier cancelled,
            long scanStartNanos,
            long maxScanNanos,
            long maxDurationMillis
    ) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (maxScanNanos != Long.MAX_VALUE
                && System.nanoTime() - scanStartNanos >= maxScanNanos) {
            throw new ScanTimeoutException(
                    "Canonical scan exceeded " + maxDurationMillis + " ms");
        }
        return false;
    }
}
