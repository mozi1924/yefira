package com.mozi1924.yefira;

import com.mozi1924.yefira.encoder.BlockDataEncoder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class ProtocolV3CodecTest {

    @Test
    public void testWelcomePacketCodec() {
        byte[] uuid = new byte[16];
        uuid[0] = 0x42;
        byte[] bytes = BlockDataEncoder.encodeWelcome(
                (short) 3,
                uuid,
                1L,
                10L,
                new BlockPos(0, 64, 0),
                16, 16, 16,
                0x917e864ccc3ba009L,
                (byte) 0, // VERIFIED_SKIP_REBUILD
                "minecraft:overworld"
        );

        Assertions.assertEquals(BlockDataEncoder.MAGIC[0], bytes[0]);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[1], bytes[1]);
        Assertions.assertEquals(BlockDataEncoder.PROTOCOL_VERSION, bytes[2]);
        Assertions.assertEquals(BlockDataEncoder.OPCODE_WELCOME, bytes[3]);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4); // Skip 4-byte header
        Assertions.assertEquals(3, buf.getShort()); // negotiated_version
        byte[] readUuid = new byte[16];
        buf.get(readUuid);
        Assertions.assertEquals(0x42, readUuid[0]);
        Assertions.assertEquals(1L, buf.getLong()); // epoch
        Assertions.assertEquals(10L, buf.getLong()); // serverSeq
        Assertions.assertEquals(0, buf.getInt()); // minX
        Assertions.assertEquals(64, buf.getInt()); // minY
        Assertions.assertEquals(0, buf.getInt()); // minZ
        Assertions.assertEquals(16, buf.getInt()); // sizeX
        Assertions.assertEquals(16, buf.getInt()); // sizeY
        Assertions.assertEquals(16, buf.getInt()); // sizeZ
        Assertions.assertEquals(0x917e864ccc3ba009L, buf.getLong()); // stateHash
        Assertions.assertEquals(0, buf.get()); // decision
    }

    @Test
    public void testDeltaOpCodec() {
        List<BlockDataEncoder.DeltaEntry> changes = new ArrayList<>();
        changes.add(new BlockDataEncoder.DeltaEntry(4, 5, 6, 1));
        changes.add(new BlockDataEncoder.DeltaEntry(7, 8, 9, 2));

        byte[] bytes = BlockDataEncoder.encodeDeltaOp(1L, 55L, 1, changes);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[0], bytes[0]);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[1], bytes[1]);
        Assertions.assertEquals(BlockDataEncoder.PROTOCOL_VERSION, bytes[2]);
        Assertions.assertEquals(BlockDataEncoder.OPCODE_DELTA_OP, bytes[3]);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        Assertions.assertEquals(1L, buf.getLong()); // epoch
        Assertions.assertEquals(55L, buf.getLong()); // seqId
        Assertions.assertEquals(1, buf.getInt()); // paletteRevision
        Assertions.assertEquals(2, buf.getShort()); // count
        long batchHash = buf.getLong();
        Assertions.assertNotEquals(0, batchHash);

        // Entry 1
        Assertions.assertEquals(4, buf.getInt());
        Assertions.assertEquals(5, buf.getInt());
        Assertions.assertEquals(6, buf.getInt());
        Assertions.assertEquals(1, buf.getShort());

        // Entry 2
        Assertions.assertEquals(7, buf.getInt());
        Assertions.assertEquals(8, buf.getInt());
        Assertions.assertEquals(9, buf.getInt());
        Assertions.assertEquals(2, buf.getShort());
    }

    @Test
    public void testHelloDecode() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(BlockDataEncoder.MAGIC);
        buf.put(BlockDataEncoder.PROTOCOL_VERSION);
        buf.put(BlockDataEncoder.OPCODE_HELLO);

        buf.putShort((short) 3); // minVer
        buf.putShort((short) 3); // maxVer
        buf.putInt(0x03); // capabilities
        buf.putInt(0); // flags
        buf.put(new byte[16]); // cachedWorldUuid
        buf.putLong(1L); // cachedEpoch
        buf.putLong(20L); // cachedSeq
        buf.putInt(10); // minX
        buf.putInt(20); // minY
        buf.putInt(30); // minZ
        buf.putInt(40); // sizeX
        buf.putInt(50); // sizeY
        buf.putInt(60); // sizeZ
        buf.putLong(0x12345678L); // cachedStateHash
        // authToken string (u16 len + UTF8)
        buf.putShort((short) 0);
        // dimension string (u16 len + UTF8)
        byte[] dim = "minecraft:the_nether".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.putShort((short) dim.length);
        buf.put(dim);

        buf.position(4); // Advance past header
        BlockDataEncoder.HelloPacket hello = BlockDataEncoder.decodeHello(buf);
        Assertions.assertNotNull(hello);
        Assertions.assertEquals(3, hello.minVersion);
        Assertions.assertEquals(3, hello.maxVersion);
        Assertions.assertEquals(1L, hello.cachedEpoch);
        Assertions.assertEquals(20L, hello.cachedSeq);
        Assertions.assertEquals(10, hello.cachedMinX);
        Assertions.assertEquals(20, hello.cachedMinY);
        Assertions.assertEquals(30, hello.cachedMinZ);
        Assertions.assertEquals(40, hello.cachedSizeX);
        Assertions.assertEquals(50, hello.cachedSizeY);
        Assertions.assertEquals(60, hello.cachedSizeZ);
        Assertions.assertEquals(0x12345678L, hello.cachedStateHash);
        Assertions.assertEquals("minecraft:the_nether", hello.cachedDimension);
    }

    @Test
    public void testBaselineEntitiesCodec() {
        byte[] uuid = new byte[16];
        uuid[0] = 0x77;
        List<BlockDataEncoder.EntityEntry> entities = new ArrayList<>();
        String potJson = "{\"id\":\"minecraft:decorated_pot\",\"sherds\":[\"minecraft:angler_pottery_sherd\", \"\", \"\", \"\"]}";
        entities.add(new BlockDataEncoder.EntityEntry(1, 2, 3, potJson));
        entities.add(new BlockDataEncoder.EntityEntry(10, 20, 30, (byte[]) null));

        byte[] bytes = BlockDataEncoder.encodeBaselineEntities(5L, uuid, entities);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[0], bytes[0]);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[1], bytes[1]);
        Assertions.assertEquals(BlockDataEncoder.PROTOCOL_VERSION, bytes[2]);
        Assertions.assertEquals(BlockDataEncoder.OPCODE_BASELINE_ENTITIES, bytes[3]);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        Assertions.assertEquals(5L, buf.getLong()); // epoch
        byte[] readUuid = new byte[16];
        buf.get(readUuid);
        Assertions.assertEquals(0x77, readUuid[0]);
        Assertions.assertEquals(2, buf.getInt()); // count

        // Entity 1
        Assertions.assertEquals(1, buf.getInt()); // rx
        Assertions.assertEquals(2, buf.getInt()); // ry
        Assertions.assertEquals(3, buf.getInt()); // rz
        int len1 = buf.getInt();
        Assertions.assertEquals(potJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, len1);
        byte[] p1 = new byte[len1];
        buf.get(p1);
        Assertions.assertEquals(potJson, new String(p1, java.nio.charset.StandardCharsets.UTF_8));

        // Entity 2
        Assertions.assertEquals(10, buf.getInt());
        Assertions.assertEquals(20, buf.getInt());
        Assertions.assertEquals(30, buf.getInt());
        int len2 = buf.getInt();
        Assertions.assertEquals(0, len2);
    }

    @Test
    public void testDeltaEntityCodec() {
        String potJson = "{\"id\":\"minecraft:decorated_pot\",\"sherds\":[\"minecraft:miner_pottery_sherd\"]}";
        byte[] payload = potJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] bytes = BlockDataEncoder.encodeDeltaEntity(5L, 100L, 7, 8, 9, payload);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[0], bytes[0]);
        Assertions.assertEquals(BlockDataEncoder.MAGIC[1], bytes[1]);
        Assertions.assertEquals(BlockDataEncoder.PROTOCOL_VERSION, bytes[2]);
        Assertions.assertEquals(BlockDataEncoder.OPCODE_DELTA_ENTITY, bytes[3]);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        Assertions.assertEquals(5L, buf.getLong()); // epoch
        Assertions.assertEquals(100L, buf.getLong()); // seqId
        Assertions.assertEquals(7, buf.getInt()); // rx
        Assertions.assertEquals(8, buf.getInt()); // ry
        Assertions.assertEquals(9, buf.getInt()); // rz
        Assertions.assertEquals(payload.length, buf.getInt()); // payload_len
        byte[] readPayload = new byte[payload.length];
        buf.get(readPayload);
        Assertions.assertArrayEquals(payload, readPayload);

        // Test removal (null payload)
        byte[] removalBytes = BlockDataEncoder.encodeDeltaEntity(5L, 101L, 7, 8, 9, null);
        ByteBuffer rBuf = ByteBuffer.wrap(removalBytes).order(ByteOrder.LITTLE_ENDIAN);
        rBuf.position(4);
        Assertions.assertEquals(5L, rBuf.getLong());
        Assertions.assertEquals(101L, rBuf.getLong());
        Assertions.assertEquals(7, rBuf.getInt());
        Assertions.assertEquals(8, rBuf.getInt());
        Assertions.assertEquals(9, rBuf.getInt());
        Assertions.assertEquals(0, rBuf.getInt()); // payload_len == 0
    }
}
