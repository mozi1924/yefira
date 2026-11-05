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

    public static final byte[] MAGIC = new byte[]{(byte) 0x4D, (byte) 0x54}; // 'M', 'T'
    public static final byte PROTOCOL_VERSION = 0x03;

    // RFC v3.1 Opcodes
    public static final byte OPCODE_HELLO = 0x01;
    public static final byte OPCODE_WELCOME = 0x02;
    public static final byte OPCODE_BASELINE_BEGIN = 0x03;
    public static final byte OPCODE_BASELINE_CHUNK = 0x04;
    public static final byte OPCODE_BASELINE_END = 0x05;
    public static final byte OPCODE_BASELINE_ACK = 0x06;
    public static final byte OPCODE_DELTA_OP = 0x07;
    public static final byte OPCODE_PALETTE_UPDATE = 0x08;
    public static final byte OPCODE_CANCEL = 0x0B;
    public static final byte OPCODE_RESYNC_REQUEST = 0x0D;
    public static final byte OPCODE_PING = 0x0E;
    public static final byte OPCODE_PONG = 0x0F;
    public static final byte OPCODE_ERROR = 0x10;
    // RFC v3.2 Sparse BlockEntity Opcodes
    public static final byte OPCODE_BASELINE_ENTITIES = 0x11;
    public static final byte OPCODE_DELTA_ENTITY = 0x12;

    // RFC v3.1 C2S Control Opcodes
    public static final byte OPCODE_SYNC_CONFIG = (byte) 0x82;

    /** RFC v3.1 hard selection volume limit (voxels). */
    public static final long MAX_SELECTION_VOLUME = 33_554_432L;

    /** RFC v3.1 diagnostic error code: UnsupportedVersion. */
    public static final short ERROR_UNSUPPORTED_VERSION = 0x0001;

    /** RFC v3.1 diagnostic error code: InvalidBounds. */
    public static final short ERROR_INVALID_BOUNDS = 0x0002;

    /** RFC v3.1 diagnostic error code: UnknownOpcode. */
    public static final short ERROR_UNKNOWN_OPCODE = 0x0007;

    /** RFC v3.1 diagnostic error code: ResourceLimitExceeded. */
    public static final short ERROR_RESOURCE_LIMIT_EXCEEDED = 0x0005;

    // RFC v3.1 SyncDecision Constants (RFC Section 4.2)
    public static final byte DECISION_VERIFIED_SKIP_REBUILD = 0x00;
    public static final byte DECISION_FULL_BASELINE_REQUIRED = 0x02;
    public static final byte DECISION_REJECTED = (byte) 0xFF;

    // RFC v3.1 Data Structures
    public static class ResyncRequestPacket {
        public long epoch;
        public long missingFromSeq;
        public long missingToSeq;
        public byte reason;
    }

    public static class HelloPacket {
        public short minVersion;
        public short maxVersion;
        public int capabilities;
        public int flags;
        public byte[] cachedWorldUuid;
        public long cachedEpoch;
        public long cachedSeq;
        public int cachedMinX, cachedMinY, cachedMinZ;
        public int cachedSizeX, cachedSizeY, cachedSizeZ;
        public long cachedStateHash;
        public String authToken;
        public String cachedDimension;
    }

    public static class DeltaEntry {
        public final int relX, relY, relZ;
        public final int stateId;
        public DeltaEntry(int relX, int relY, int relZ, int stateId) {
            this.relX = relX;
            this.relY = relY;
            this.relZ = relZ;
            this.stateId = stateId;
        }
    }

    public static class EntityEntry {
        public final int relX, relY, relZ;
        public final byte[] payload;

        public EntityEntry(int relX, int relY, int relZ, byte[] payload) {
            this.relX = relX;
            this.relY = relY;
            this.relZ = relZ;
            this.payload = payload != null ? payload : new byte[0];
        }

        public EntityEntry(int relX, int relY, int relZ, String json) {
            this(relX, relY, relZ, json != null ? json.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        }
    }

    public static HelloPacket decodeHello(ByteBuffer buf) {
        if (buf.remaining() < 2 + 2 + 4 + 4 + 16 + 8 + 8 + 4 * 3 + 4 * 3 + 8 + 2) {
            return null;
        }
        buf.order(ByteOrder.LITTLE_ENDIAN);
        HelloPacket p = new HelloPacket();
        p.minVersion = buf.getShort();
        p.maxVersion = buf.getShort();
        p.capabilities = buf.getInt();
        p.flags = buf.getInt();
        p.cachedWorldUuid = new byte[16];
        buf.get(p.cachedWorldUuid);
        p.cachedEpoch = buf.getLong();
        p.cachedSeq = buf.getLong();
        p.cachedMinX = buf.getInt();
        p.cachedMinY = buf.getInt();
        p.cachedMinZ = buf.getInt();
        p.cachedSizeX = buf.getInt();
        p.cachedSizeY = buf.getInt();
        p.cachedSizeZ = buf.getInt();
        p.cachedStateHash = buf.getLong();
        p.authToken = readString(buf);
        p.cachedDimension = readString(buf);
        return p;
    }

    public static ResyncRequestPacket decodeResyncRequest(ByteBuffer buf) {
        if (buf == null || buf.remaining() < 25) {
            return null;
        }
        ResyncRequestPacket p = new ResyncRequestPacket();
        p.epoch = buf.getLong();
        p.missingFromSeq = buf.getLong();
        p.missingToSeq = buf.getLong();
        p.reason = buf.get();
        return p;
    }

    public static byte[] encodeWelcome(
            short negotiatedVersion,
            byte[] serverUuid,
            long epoch,
            long serverSeq,
            BlockPos currentMin,
            int sizeX, int sizeY, int sizeZ,
            long currentStateHash,
            byte decision,
            String messageOrDimension
    ) {
        byte[] dimBytes = (messageOrDimension != null ? messageOrDimension : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 16 + 8 + 8 + 12 + 12 + 8 + 1 + 2 + dimBytes.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_WELCOME);
        buf.putShort(negotiatedVersion);
        if (serverUuid != null && serverUuid.length >= 16) {
            buf.put(serverUuid, 0, 16);
        } else {
            buf.put(new byte[16]);
        }
        buf.putLong(epoch);
        buf.putLong(serverSeq);
        buf.putInt(currentMin != null ? currentMin.getX() : 0);
        buf.putInt(currentMin != null ? currentMin.getY() : 0);
        buf.putInt(currentMin != null ? currentMin.getZ() : 0);
        buf.putInt(sizeX);
        buf.putInt(sizeY);
        buf.putInt(sizeZ);
        buf.putLong(currentStateHash);
        buf.put(decision);
        buf.putShort((short) dimBytes.length);
        buf.put(dimBytes);
        return buf.array();
    }

    public static byte[] encodeBaselineBegin(
            long epoch,
            byte[] snapshotUuid,
            long snapshotSeq,
            BlockPos minPos,
            int sizeX, int sizeY, int sizeZ,
            long stateHash,
            int paletteRevision,
            int totalChunks,
            long totalEncodedBytes,
            long totalDecodedBytes,
            byte compressionMode,
            byte paletteBitsPerBlock,
            byte biomeBitsPerQuart,
            String dimension,
            List<String> blockPalette,
            List<String> biomePalette
    ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(baos);
            baos.write(MAGIC);
            baos.write(PROTOCOL_VERSION);
            baos.write(OPCODE_BASELINE_BEGIN);

            writeLongLE(out, epoch);
            if (snapshotUuid != null && snapshotUuid.length >= 16) {
                out.write(snapshotUuid, 0, 16);
            } else {
                out.write(new byte[16]);
            }
            writeLongLE(out, snapshotSeq);
            writeIntLE(out, minPos != null ? minPos.getX() : 0);
            writeIntLE(out, minPos != null ? minPos.getY() : 0);
            writeIntLE(out, minPos != null ? minPos.getZ() : 0);
            writeIntLE(out, sizeX);
            writeIntLE(out, sizeY);
            writeIntLE(out, sizeZ);
            writeLongLE(out, stateHash);
            writeIntLE(out, paletteRevision);
            writeIntLE(out, totalChunks);
            writeLongLE(out, totalEncodedBytes);
            writeLongLE(out, totalDecodedBytes);
            out.writeByte(compressionMode);
            out.writeByte(paletteBitsPerBlock);
            out.writeByte(biomeBitsPerQuart);
            writeString(out, dimension);

            writeIntLE(out, blockPalette.size());
            for (String p : blockPalette) {
                writeString(out, p);
            }

            writeShortLE(out, biomePalette.size());
            for (String b : biomePalette) {
                writeString(out, b);
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return baos.toByteArray();
    }

    public static byte[] encodeBaselineChunk(
            long epoch,
            byte[] snapshotUuid,
            int chunkIndex,
            int totalChunks,
            long firstVoxelIndex,
            int voxelCount,
            int decodedTargetBytes,
            long chunkXxHash64,
            byte[] payload
    ) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 16 + 4 + 4 + 8 + 4 + 4 + 4 + 8 + payload.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_BASELINE_CHUNK);
        buf.putLong(epoch);
        if (snapshotUuid != null && snapshotUuid.length >= 16) {
            buf.put(snapshotUuid, 0, 16);
        } else {
            buf.put(new byte[16]);
        }
        buf.putInt(chunkIndex);
        buf.putInt(totalChunks);
        buf.putLong(firstVoxelIndex);
        buf.putInt(voxelCount);
        buf.putInt(payload.length);
        buf.putInt(decodedTargetBytes);
        buf.putLong(chunkXxHash64);
        buf.put(payload);
        return buf.array();
    }

    public static byte[] encodeBaselineEnd(
            long epoch,
            byte[] snapshotUuid,
            long snapshotSeq,
            int totalChunksSent,
            long finalStateHash,
            byte status
    ) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 16 + 8 + 4 + 8 + 1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_BASELINE_END);
        buf.putLong(epoch);
        if (snapshotUuid != null && snapshotUuid.length >= 16) {
            buf.put(snapshotUuid, 0, 16);
        } else {
            buf.put(new byte[16]);
        }
        buf.putLong(snapshotSeq);
        buf.putInt(totalChunksSent);
        buf.putLong(finalStateHash);
        buf.put(status);
        return buf.array();
    }

    public static byte[] encodeDeltaOp(
            long epoch,
            long seqId,
            int paletteRevision,
            List<DeltaEntry> entries
    ) {
        XXHash64 hasher = new XXHash64(0);
        for (DeltaEntry e : entries) {
            hasher.updateLE32(e.relX);
            hasher.updateLE32(e.relY);
            hasher.updateLE32(e.relZ);
            hasher.updateLE16((short) e.stateId);
        }
        long batchXxHash64 = hasher.digest();

        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 8 + 4 + 2 + 8 + entries.size() * (4 * 3 + 2));
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_DELTA_OP);
        buf.putLong(epoch);
        buf.putLong(seqId);
        buf.putInt(paletteRevision);
        buf.putShort((short) entries.size());
        buf.putLong(batchXxHash64);
        for (DeltaEntry e : entries) {
            buf.putInt(e.relX);
            buf.putInt(e.relY);
            buf.putInt(e.relZ);
            buf.putShort((short) e.stateId);
        }
        return buf.array();
    }

    public static byte[] encodeBaselineEntities(
            long epoch,
            byte[] snapshotUuid,
            List<EntityEntry> entities
    ) {
        int totalLen = 4 + 8 + 16 + 4;
        if (entities != null) {
            for (EntityEntry e : entities) {
                totalLen += 4 + 4 + 4 + 4 + e.payload.length;
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_BASELINE_ENTITIES);
        buf.putLong(epoch);
        if (snapshotUuid != null && snapshotUuid.length >= 16) {
            buf.put(snapshotUuid, 0, 16);
        } else {
            buf.put(new byte[16]);
        }
        int count = entities != null ? entities.size() : 0;
        buf.putInt(count);
        if (entities != null) {
            for (EntityEntry e : entities) {
                buf.putInt(e.relX);
                buf.putInt(e.relY);
                buf.putInt(e.relZ);
                buf.putInt(e.payload.length);
                buf.put(e.payload);
            }
        }
        return buf.array();
    }

    public static byte[] encodeDeltaEntity(
            long epoch,
            long seqId,
            int relX,
            int relY,
            int relZ,
            byte[] payload
    ) {
        int payloadLen = payload != null ? payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 8 + 4 + 4 + 4 + 4 + payloadLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_DELTA_ENTITY);
        buf.putLong(epoch);
        buf.putLong(seqId);
        buf.putInt(relX);
        buf.putInt(relY);
        buf.putInt(relZ);
        buf.putInt(payloadLen);
        if (payloadLen > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    public static byte[] encodePaletteUpdate(
            long epoch,
            long seqId,
            int baseRevision,
            int newRevision,
            List<Map.Entry<Integer, String>> entries
    ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(baos);
            baos.write(MAGIC);
            baos.write(PROTOCOL_VERSION);
            baos.write(OPCODE_PALETTE_UPDATE);
            writeLongLE(out, epoch);
            writeLongLE(out, seqId);
            writeIntLE(out, baseRevision);
            writeIntLE(out, newRevision);
            writeShortLE(out, entries.size());
            for (Map.Entry<Integer, String> entry : entries) {
                writeShortLE(out, entry.getKey());
                writeString(out, entry.getValue());
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return baos.toByteArray();
    }

    public static byte[] encodeError(long epoch, short errorCode, String message) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(baos);
            writeHeader(out, OPCODE_ERROR);
            writeLongLE(out, epoch);
            writeShortLE(out, errorCode);
            writeString(out, message);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return baos.toByteArray();
    }

    /** RFC v3.1 CANCEL payload; a zero UUID cancels the current epoch. */
    public static byte[] encodeCancel(long epoch, byte[] snapshotUuid, byte reason) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 16 + 1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_CANCEL);
        buf.putLong(epoch);
        if (snapshotUuid != null && snapshotUuid.length >= 16) {
            buf.put(snapshotUuid, 0, 16);
        } else {
            buf.put(new byte[16]);
        }
        buf.put(reason);
        return buf.array();
    }

    public static byte[] encodePong(long timestamp) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.put(PROTOCOL_VERSION);
        buf.put(OPCODE_PONG);
        buf.putLong(timestamp);
        return buf.array();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        writeShortLE(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteBuffer buf) {
        if (buf.remaining() < 2) return "";
        int len = buf.getShort() & 0xFFFF;
        if (buf.remaining() < len) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }


    private static final Map<BlockState, byte[]> STATE_UTF8_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 判断方块是否为空气或应被忽略的无网格方块（如结构空位、未含水的光源/屏障等）
     */
    public static boolean isAirOrIgnored(BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) {
            return true;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.LIGHT) || state.is(net.minecraft.world.level.block.Blocks.BARRIER)) {
            return !state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    || !Boolean.TRUE.equals(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        }
        return false;
    }

    /**
     * 将 BlockState 序列化为规范字符串标识，例如 "minecraft:oak_log[axis=y,facing=north]"
     */
    public static String serializeBlockState(BlockState state) {
        if (isAirOrIgnored(state)) {
            return "minecraft:air";
        }
        if (state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) {
            return "minecraft:water";
        }
        if ((state.is(net.minecraft.world.level.block.Blocks.LIGHT) || state.is(net.minecraft.world.level.block.Blocks.BARRIER))
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && Boolean.TRUE.equals(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))) {
            return "minecraft:water";
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Collection<Property<?>> properties = state.getProperties();
        if (properties.isEmpty()) {
            return blockId;
        }

        List<Property<?>> sorted = new ArrayList<>(properties);
        sorted.sort(Comparator.comparing(Property::getName));

        StringBuilder sb = new StringBuilder(blockId).append("[");
        boolean first = true;
        for (Property<?> property : sorted) {
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

    private static void writeLongLE(DataOutputStream out, long value) throws IOException {
        out.writeByte((int) (value & 0xFF));
        out.writeByte((int) ((value >> 8) & 0xFF));
        out.writeByte((int) ((value >> 16) & 0xFF));
        out.writeByte((int) ((value >> 24) & 0xFF));
        out.writeByte((int) ((value >> 32) & 0xFF));
        out.writeByte((int) ((value >> 40) & 0xFF));
        out.writeByte((int) ((value >> 48) & 0xFF));
        out.writeByte((int) ((value >> 56) & 0xFF));
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

