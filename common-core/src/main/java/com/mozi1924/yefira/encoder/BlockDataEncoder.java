package com.mozi1924.yefira.encoder;

import com.mozi1924.yefira.compat.VersionCompat;
import com.mozi1924.yefira.selection.SelectionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BlockDataEncoder {

    public static final byte[] MAGIC = new byte[]{(byte) 0x4D, (byte) 0x43}; // 'M', 'C'
    public static final byte PROTOCOL_VERSION = 0x02;

    public static final byte PACKET_SELECTION_INFO = 0x01;
    public static final byte PACKET_FULL_SNAPSHOT = 0x02;
    public static final byte PACKET_DELTA_UPDATE = 0x03;
    public static final byte PACKET_SECTION_MANIFEST = 0x05;
    public static final byte PACKET_SECTION_SNAPSHOT = 0x06;
    public static final byte PACKET_HANDSHAKE_INFO = 0x07;
    public static final byte PACKET_STREAM_BEGIN = 0x08;
    public static final byte PACKET_STREAM_END = 0x09;

    // Stream status codes
    public static final short STREAM_STATUS_SUCCESS = 0;
    public static final short STREAM_STATUS_CANCELLED = 1;
    public static final short STREAM_STATUS_ERROR = 2;

    // C2S Request Packet Types
    public static final byte PACKET_C2S_REQ_FULL_SYNC = (byte) 0x80;
    public static final byte PACKET_C2S_REQ_SECTION_SYNC = (byte) 0x81;
    public static final byte PACKET_C2S_SYNC_CONFIG = (byte) 0x82;

    private static final Map<BlockState, byte[]> STATE_UTF8_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 判断方块是否为空气或应被忽略的无网格方块（如气泡柱、结构空位等）
     */
    public static boolean isAirOrIgnored(BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        return state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)
                || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID);
    }

    /**
     * 将 BlockState 序列化为规范字符串标识，例如 "minecraft:oak_log[axis=y,facing=north]"
     */
    public static String serializeBlockState(BlockState state) {
        if (isAirOrIgnored(state)) {
            return "minecraft:air";
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Collection<Property<?>> properties = state.getProperties();
        if (properties.isEmpty()) {
            return blockId;
        }

        StringBuilder sb = new StringBuilder(blockId).append("[");
        boolean first = true;
        for (Property<?> property : properties) {
            if (!first) {
                sb.append(",");
            }
            sb.append(property.getName()).append("=").append(getPropertyValueName(state, property));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropertyValueName(BlockState state, Property<T> property) {
        T val = state.getValue(property);
        return property.getName(val);
    }

    /**
     * 编码 0x01 Selection Info 字节数据包
     */
    public static byte[] encodeSelectionInfo(SelectionBox selection) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 6 * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(PACKET_SELECTION_INFO);

        // Min Pos
        buf.putInt(selection.getMin().getX());
        buf.putInt(selection.getMin().getY());
        buf.putInt(selection.getMin().getZ());

        // Size
        buf.putInt(selection.getSizeX());
        buf.putInt(selection.getSizeY());
        buf.putInt(selection.getSizeZ());

        return buf.array();
    }

    /**
     * 获取指定位置的规范生物群系标识，例如 "minecraft:plains"
     */
    public static String getBiomeId(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return "minecraft:plains";
        }
        try {
            return VersionCompat.level().getBiomeId(level, pos);
        } catch (Exception e) {
            return "minecraft:plains";
        }
    }

    /**
     * 编码 0x02 Full Snapshot 全量快照字节数据包
     */
    public static byte[] encodeFullSnapshot(Level level, SelectionBox selection) {
        BlockPos min = selection.getMin();
        int sizeX = selection.getSizeX();
        int sizeY = selection.getSizeY();
        int sizeZ = selection.getSizeZ();

        // 收集所有 BlockState 建立 Palette（基于 BlockState 实例快速缓存，杜绝重复序列化 JSON）
        List<String> palette = new ArrayList<>();
        Map<BlockState, Integer> stateToPaletteIdx = new IdentityHashMap<>();

        List<String> biomePalette = new ArrayList<>();
        Map<String, Integer> biomeToIdx = new HashMap<>();

        // 预处理建立 Palette 和 三维 State 数组
        int totalBlocks = sizeX * sizeY * sizeZ;
        int[] gridIndices = new int[totalBlocks];
        int[] biomeIndices = new int[totalBlocks];

        int index = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    mutablePos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    BlockState state = level.getBlockState(mutablePos);
                    Integer paletteIdx = stateToPaletteIdx.get(state);
                    if (paletteIdx == null) {
                        String encodedEntry = BlockModelExtractor.get(state).toJson();
                        paletteIdx = palette.size();
                        palette.add(encodedEntry);
                        stateToPaletteIdx.put(state, paletteIdx);
                    }

                    String biomeId = getBiomeId(level, mutablePos);
                    Integer biomeIdx = biomeToIdx.get(biomeId);
                    if (biomeIdx == null) {
                        biomeIdx = biomePalette.size();
                        biomePalette.add(biomeId);
                        biomeToIdx.put(biomeId, biomeIdx);
                    }

                    gridIndices[index] = paletteIdx;
                    biomeIndices[index] = biomeIdx;
                    index++;
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            // Little Endian 流辅助写入
            writeHeader(out, PACKET_FULL_SNAPSHOT);

            // Bounds
            writeIntLE(out, min.getX());
            writeIntLE(out, min.getY());
            writeIntLE(out, min.getZ());

            writeIntLE(out, sizeX);
            writeIntLE(out, sizeY);
            writeIntLE(out, sizeZ);

            // Palette Count
            writeShortLE(out, palette.size());
            for (String item : palette) {
                byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
                writeShortLE(out, bytes.length);
                out.write(bytes);
            }

            // Grid Indices (use uint8 if palette.size() <= 256, else uint16)
            boolean isBytePalette = palette.size() <= 256;
            out.writeByte(isBytePalette ? 1 : 2); // 1 = byte index, 2 = short index

            for (int idx : gridIndices) {
                if (isBytePalette) {
                    out.writeByte(idx & 0xFF);
                } else {
                    writeShortLE(out, idx);
                }
            }

            // Biome Palette Count
            writeShortLE(out, biomePalette.size());
            for (String bItem : biomePalette) {
                byte[] bytes = bItem.getBytes(StandardCharsets.UTF_8);
                writeShortLE(out, bytes.length);
                out.write(bytes);
            }

            // Biome Indices (only write index stream if palette > 1)
            if (biomePalette.size() > 1) {
                boolean isByteBiome = biomePalette.size() <= 256;
                out.writeByte(isByteBiome ? 1 : 2);
                for (int bIdx : biomeIndices) {
                    if (isByteBiome) {
                        out.writeByte(bIdx & 0xFF);
                    } else {
                        writeShortLE(out, bIdx);
                    }
                }
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    /**
     * 编码 0x03 Delta Update 增量更新字节数据包 (带 SeqID)
     */
    public static byte[] encodeDeltaUpdate(SelectionBox selection, List<BlockChangeEntry> changes, long seqId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeHeader(out, PACKET_DELTA_UPDATE);

            // Sequence ID (uint32 LE)
            writeIntLE(out, (int) seqId);

            // Bounds min pos (for reference)
            writeIntLE(out, selection.getMin().getX());
            writeIntLE(out, selection.getMin().getY());
            writeIntLE(out, selection.getMin().getZ());

            // Count
            writeShortLE(out, changes.size());

            BlockPos min = selection.getMin();
            for (BlockChangeEntry change : changes) {
                int relX = change.pos().getX() - min.getX();
                int relY = change.pos().getY() - min.getY();
                int relZ = change.pos().getZ() - min.getZ();

                writeShortLE(out, relX);
                writeShortLE(out, relY);
                writeShortLE(out, relZ);

                String encodedEntry = BlockModelExtractor.get(change.state()).toJson();
                byte[] bytes = encodedEntry.getBytes(StandardCharsets.UTF_8);
                writeShortLE(out, bytes.length);
                out.write(bytes);
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    /**
     * 编码 0x05 Section Manifest 校验清单数据包
     */
    public static byte[] encodeSectionManifest(Level level, SelectionBox selection, long currentSeqId) {
        List<SectionPos> sections = getCoveredSections(selection);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeHeader(out, PACKET_SECTION_MANIFEST);

            // Current Sequence ID (uint32 LE)
            writeIntLE(out, (int) currentSeqId);

            // Count of sections (uint32 LE)
            writeIntLE(out, sections.size());

            for (SectionPos sec : sections) {
                writeIntLE(out, sec.x);
                writeIntLE(out, sec.y);
                writeIntLE(out, sec.z);

                long crc32 = calculateSectionCRC32(level, selection, sec);
                writeIntLE(out, (int) crc32);
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return baos.toByteArray();
    }

    /**
     * 编码 0x06 Section Snapshot 单区块快照数据包
     */
    public static byte[] encodeSectionSnapshot(Level level, SelectionBox selection, SectionPos secPos) {
        int startX = Math.max(selection.getMin().getX(), secPos.x << 4);
        int endX = Math.min(selection.getMax().getX(), (secPos.x << 4) + 15);

        int startY = Math.max(selection.getMin().getY(), secPos.y << 4);
        int endY = Math.min(selection.getMax().getY(), (secPos.y << 4) + 15);

        int startZ = Math.max(selection.getMin().getZ(), secPos.z << 4);
        int endZ = Math.min(selection.getMax().getZ(), (secPos.z << 4) + 15);

        int sizeX = Math.max(0, endX - startX + 1);
        int sizeY = Math.max(0, endY - startY + 1);
        int sizeZ = Math.max(0, endZ - startZ + 1);

        List<String> palette = new ArrayList<>();
        Map<BlockState, Integer> stateToPaletteIdx = new IdentityHashMap<>();

        List<String> biomePalette = new ArrayList<>();
        Map<String, Integer> biomeToIdx = new HashMap<>();

        int totalBlocks = sizeX * sizeY * sizeZ;
        int[] gridIndices = new int[totalBlocks];
        int[] biomeIndices = new int[totalBlocks];

        int index = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    mutablePos.set(startX + x, startY + y, startZ + z);
                    BlockState state = level.getBlockState(mutablePos);
                    Integer paletteIdx = stateToPaletteIdx.get(state);
                    if (paletteIdx == null) {
                        String encodedEntry = BlockModelExtractor.get(state).toJson();
                        paletteIdx = palette.size();
                        palette.add(encodedEntry);
                        stateToPaletteIdx.put(state, paletteIdx);
                    }

                    String biomeId = getBiomeId(level, mutablePos);
                    Integer biomeIdx = biomeToIdx.get(biomeId);
                    if (biomeIdx == null) {
                        biomeIdx = biomePalette.size();
                        biomePalette.add(biomeId);
                        biomeToIdx.put(biomeId, biomeIdx);
                    }

                    gridIndices[index] = paletteIdx;
                    biomeIndices[index] = biomeIdx;
                    index++;
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeHeader(out, PACKET_SECTION_SNAPSHOT);

            // Section coordinates
            writeIntLE(out, secPos.x);
            writeIntLE(out, secPos.y);
            writeIntLE(out, secPos.z);

            // Bounds min pos
            writeIntLE(out, startX);
            writeIntLE(out, startY);
            writeIntLE(out, startZ);

            // Bounds sizes
            writeIntLE(out, sizeX);
            writeIntLE(out, sizeY);
            writeIntLE(out, sizeZ);

            // Palette Count
            writeShortLE(out, palette.size());
            for (String item : palette) {
                byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
                writeShortLE(out, bytes.length);
                out.write(bytes);
            }

            // Grid Indices
            boolean isBytePalette = palette.size() <= 256;
            out.writeByte(isBytePalette ? 1 : 2);

            for (int idx : gridIndices) {
                if (isBytePalette) {
                    out.writeByte(idx & 0xFF);
                } else {
                    writeShortLE(out, idx);
                }
            }

            // Biome Palette Count
            writeShortLE(out, biomePalette.size());
            for (String bItem : biomePalette) {
                byte[] bytes = bItem.getBytes(StandardCharsets.UTF_8);
                writeShortLE(out, bytes.length);
                out.write(bytes);
            }

            // Biome Indices (only write index stream if palette > 1)
            if (biomePalette.size() > 1) {
                boolean isByteBiome = biomePalette.size() <= 256;
                out.writeByte(isByteBiome ? 1 : 2);
                for (int bIdx : biomeIndices) {
                    if (isByteBiome) {
                        out.writeByte(bIdx & 0xFF);
                    } else {
                        writeShortLE(out, bIdx);
                    }
                }
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    public static class SectionPos {
        public final int x;
        public final int y;
        public final int z;

        public SectionPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SectionPos that = (SectionPos) o;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }

        @Override
        public String toString() {
            return "SectionPos[" + x + ", " + y + ", " + z + "]";
        }
    }

    public static List<SectionPos> getCoveredSections(SelectionBox selection) {
        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();

        int minSecX = min.getX() >> 4;
        int maxSecX = max.getX() >> 4;
        int minSecY = min.getY() >> 4;
        int maxSecY = max.getY() >> 4;
        int minSecZ = min.getZ() >> 4;
        int maxSecZ = max.getZ() >> 4;

        List<SectionPos> list = new ArrayList<>();
        for (int sx = minSecX; sx <= maxSecX; sx++) {
            for (int sy = minSecY; sy <= maxSecY; sy++) {
                for (int sz = minSecZ; sz <= maxSecZ; sz++) {
                    list.add(new SectionPos(sx, sy, sz));
                }
            }
        }
        return list;
    }

    private static final long[] EMPTY_CRC_TABLE = new long[4097];
    static {
        byte[] airBytes = "minecraft:air".getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        EMPTY_CRC_TABLE[0] = 0L;
        for (int i = 1; i <= 4096; i++) {
            crc.update(airBytes);
            EMPTY_CRC_TABLE[i] = crc.getValue();
        }
    }

    public static long getEmptySectionCRC(int blockCount) {
        if (blockCount >= 0 && blockCount < EMPTY_CRC_TABLE.length) {
            return EMPTY_CRC_TABLE[blockCount];
        }
        byte[] airBytes = "minecraft:air".getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        for (int i = 0; i < blockCount; i++) {
            crc.update(airBytes);
        }
        return crc.getValue();
    }

    private static boolean isChunkSectionAllAir(Level level, int secX, int secY, int secZ) {
        if (level == null) return false;
        int minSectionY = VersionCompat.level().getMinSectionY(level);
        int maxSectionY = VersionCompat.level().getMaxSectionY(level);
        if (secY < minSectionY || secY > maxSectionY) return true;

        try {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(secX, secZ);
            if (chunk != null) {
                int sectionIndex = level.getSectionIndexFromSectionY(secY);
                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                if (sectionIndex >= 0 && sectionIndex < sections.length) {
                    net.minecraft.world.level.chunk.LevelChunkSection section = sections[sectionIndex];
                    if (section != null && section.hasOnlyAir()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean isSectionNonEmpty(Level level, SelectionBox selection, SectionPos secPos) {
        int startX = Math.max(selection.getMin().getX(), secPos.x << 4);
        int endX = Math.min(selection.getMax().getX(), (secPos.x << 4) + 15);
        int startY = Math.max(selection.getMin().getY(), secPos.y << 4);
        int endY = Math.min(selection.getMax().getY(), (secPos.y << 4) + 15);
        int startZ = Math.max(selection.getMin().getZ(), secPos.z << 4);
        int endZ = Math.min(selection.getMax().getZ(), (secPos.z << 4) + 15);

        if (startX > endX || startY > endY || startZ > endZ) return false;

        // O(1) Fast path: pure air chunk section skips scanning 4096 blocks
        if (isChunkSectionAllAir(level, secPos.x, secPos.y, secPos.z)) {
            return false;
        }

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (!isAirOrIgnored(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static List<SectionPos> getNonEmptySections(Level level, SelectionBox selection) {
        List<SectionPos> covered = getCoveredSections(selection);
        List<SectionPos> nonEmpty = new ArrayList<>();
        for (SectionPos sec : covered) {
            if (isSectionNonEmpty(level, selection, sec)) {
                nonEmpty.add(sec);
            }
        }
        return nonEmpty;
    }

    public static int countNonEmptySections(Level level, SelectionBox selection) {
        return getNonEmptySections(level, selection).size();
    }

    public static byte[] encodeStreamBegin(long streamId, int totalSections, int flags) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(PACKET_STREAM_BEGIN);

        buf.putInt((int) streamId);
        buf.putInt(totalSections);
        buf.putShort((short) flags);

        return buf.array();
    }

    public static byte[] encodeStreamEnd(long streamId, int sentSections, int status) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(PACKET_STREAM_END);

        buf.putInt((int) streamId);
        buf.putInt(sentSections);
        buf.putShort((short) status);

        return buf.array();
    }

    public static byte[] encodeHandshakeInfo(int totalSections, int nonEmptySections, long totalVolume, String dimension, int flags) {
        byte[] dimBytes = (dimension != null ? dimension : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 4 + 2 + dimBytes.length + 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(PACKET_HANDSHAKE_INFO);

        // Section & Volume metrics
        buf.putInt(totalSections);
        buf.putInt(nonEmptySections);
        buf.putInt((int) totalVolume);

        // Dimension & Flags
        buf.putShort((short) dimBytes.length);
        buf.put(dimBytes);
        buf.putShort((short) flags);

        return buf.array();
    }

    public static void streamNonEmptySectionSnapshots(
            Level level,
            SelectionBox selection,
            long streamId,
            java.util.function.BooleanSupplier isCancelled,
            java.util.function.Predicate<byte[]> sender
    ) {
        List<SectionPos> nonEmpty = getNonEmptySections(level, selection);
        byte[] beginPacket = encodeStreamBegin(streamId, nonEmpty.size(), 0);
        if (!sender.test(beginPacket)) {
            return;
        }

        int sent = 0;
        for (SectionPos sec : nonEmpty) {
            if ((isCancelled != null && isCancelled.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
                byte[] cancelPacket = encodeStreamEnd(streamId, sent, STREAM_STATUS_CANCELLED);
                sender.test(cancelPacket);
                return;
            }
            byte[] sectionSnapshot = encodeSectionSnapshot(level, selection, sec);
            if (!sender.test(sectionSnapshot)) {
                return;
            }
            sent++;
            if ((sent % 16) == 0) {
                Thread.yield();
            }
        }

        byte[] endPacket = encodeStreamEnd(streamId, sent, STREAM_STATUS_SUCCESS);
        sender.test(endPacket);
    }

    public static void streamNonEmptySectionSnapshots(Level level, SelectionBox selection, long streamId, java.util.function.Predicate<byte[]> sender) {
        streamNonEmptySectionSnapshots(level, selection, streamId, () -> false, sender);
    }

    public static void streamNonEmptySectionSnapshots(Level level, SelectionBox selection, java.util.function.Consumer<byte[]> sender) {
        streamNonEmptySectionSnapshots(level, selection, 0L, () -> false, bytes -> {
            sender.accept(bytes);
            return true;
        });
    }

    private static final Map<SectionPos, Long> SECTION_CRC_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void clearSectionCRCCache() {
        SECTION_CRC_CACHE.clear();
    }

    public static void invalidateSectionCRC(SectionPos secPos) {
        if (secPos != null) {
            SECTION_CRC_CACHE.remove(secPos);
        }
    }

    public static long calculateSectionCRC32(Level level, SelectionBox selection, SectionPos secPos) {
        Long cached = SECTION_CRC_CACHE.get(secPos);
        if (cached != null) {
            return cached;
        }

        int startX = Math.max(selection.getMin().getX(), secPos.x << 4);
        int endX = Math.min(selection.getMax().getX(), (secPos.x << 4) + 15);
        int startY = Math.max(selection.getMin().getY(), secPos.y << 4);
        int endY = Math.min(selection.getMax().getY(), (secPos.y << 4) + 15);
        int startZ = Math.max(selection.getMin().getZ(), secPos.z << 4);
        int endZ = Math.min(selection.getMax().getZ(), (secPos.z << 4) + 15);

        int totalBlocks = Math.max(0, endX - startX + 1) * Math.max(0, endY - startY + 1) * Math.max(0, endZ - startZ + 1);
        if (totalBlocks == 0) {
            return 0L;
        }

        // O(1) Fast path: pure air chunk section returns precomputed canonical CRC
        if (isChunkSectionAllAir(level, secPos.x, secPos.y, secPos.z)) {
            long result = getEmptySectionCRC(totalBlocks);
            SECTION_CRC_CACHE.put(secPos, result);
            return result;
        }

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    byte[] bytes = STATE_UTF8_CACHE.computeIfAbsent(state, s -> serializeBlockState(s).getBytes(StandardCharsets.UTF_8));
                    crc.update(bytes);
                }
            }
        }
        long result = crc.getValue();
        SECTION_CRC_CACHE.put(secPos, result);
        return result;
    }

    public static class BlockChangeEntry {
        private final BlockPos pos;
        private final BlockState state;

        public BlockChangeEntry(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockState state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockChangeEntry that = (BlockChangeEntry) o;
            return Objects.equals(pos, that.pos) && Objects.equals(state, that.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pos, state);
        }

        @Override
        public String toString() {
            return "BlockChangeEntry[" + "pos=" + pos + ", " + "state=" + state + ']';
        }
    }

    private static void writeHeader(DataOutputStream out, byte packetType) throws IOException {
        out.write(MAGIC);
        out.writeByte(PROTOCOL_VERSION);
        out.writeByte(packetType);
    }

    private static void writeIntLE(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
        out.writeByte((value >> 16) & 0xFF);
        out.writeByte((value >> 24) & 0xFF);
    }

    private static void writeShortLE(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
    }
}
