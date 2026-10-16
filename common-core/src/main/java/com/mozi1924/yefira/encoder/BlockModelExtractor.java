package com.mozi1924.yefira.encoder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockModelExtractor {

    public interface IModelProvider {
        BlockStateModelData getModelData(BlockState state);
    }

    private static IModelProvider customProvider = null;
    private static final Map<BlockState, BlockStateModelData> CACHE = new ConcurrentHashMap<>();

    public static void setProvider(IModelProvider provider) {
        customProvider = provider;
        CACHE.clear();
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static BlockStateModelData get(BlockState state) {
        return CACHE.computeIfAbsent(state, BlockModelExtractor::extractInternal);
    }

    public static Map<String, BlockStateModelData> getAllDirectionalModels() {
        Map<String, BlockStateModelData> result = new java.util.LinkedHashMap<>();
        for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                boolean isDirectional = false;
                for (Property<?> prop : state.getProperties()) {
                    String propName = prop.getName();
                    if (propName.equals("facing") || propName.equals("axis") || propName.equals("orientation") || propName.equals("half") || propName.equals("type") || propName.equals("part")) {
                        isDirectional = true;
                        break;
                    }
                }
                if (isDirectional) {
                    String key = BlockDataEncoder.serializeBlockState(state);
                    result.put(key, get(state));
                }
            }
        }
        return result;
    }

    private static BlockStateModelData extractInternal(BlockState state) {
        if (customProvider != null) {
            try {
                BlockStateModelData data = customProvider.getModelData(state);
                if (data != null) {
                    return data;
                }
            } catch (Throwable t) {
                // Fallback to internal extractor if provider fails
            }
        }
        return FallbackModelProvider.extract(state);
    }

    public static class FallbackModelProvider {
        public static BlockStateModelData extract(BlockState state) {
            String stateStr = BlockDataEncoder.serializeBlockState(state);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String name = blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId;

            // Check block type
            int blockType = 0; // CUBE
            if (name.equals("air") || name.equals("cave_air") || name.equals("void_air") || name.equals("bubble_column") || name.equals("structure_void")) {
                blockType = 7;
            } else if (name.equals("water") || name.equals("flowing_water") || name.equals("lava") || name.equals("flowing_lava")) {
                blockType = 6;
            } else if (name.endsWith("_stairs")) {
                blockType = 3;
            } else if (name.endsWith("_slab")) {
                blockType = 2;
            } else if (name.contains("torch") || name.contains("lantern")) {
                blockType = 4;
            }

            boolean isOpaque = !name.contains("glass") && !name.contains("leaves") && !name.contains("ice") && !name.contains("water") && !name.contains("air") && !name.equals("bubble_column") && !name.equals("structure_void");
            boolean isEmissive = name.contains("glowstone") || name.contains("sea_lantern") || name.contains("shroomlight") || name.contains("magma") || name.contains("lava") || name.contains("fire") || name.contains("lantern");
            float emissiveLevel = isEmissive ? 1.0f : 0.0f;

            // Extract property map
            java.util.Map<String, String> props = new java.util.HashMap<>();
            for (Property<?> property : state.getProperties()) {
                props.put(property.getName(), getPropVal(state, property));
            }

            if ("true".equals(props.get("lit"))) {
                isEmissive = true;
                emissiveLevel = 1.0f;
            }

            // Directional face resolution
            // Face indices: 0: East (+X), 1: West (-X), 2: Top (+Y), 3: Bottom (-Y), 4: South (+Z), 5: North (-Z)
            BlockFaceData[] faces = new BlockFaceData[6];

            String axis = props.get("axis");
            String facing = props.get("facing");

            if (axis != null) {
                String topTex = "minecraft:block/" + name + "_top";
                String sideTex = "minecraft:block/" + name;
                if ("x".equals(axis)) {
                    faces[0] = BlockFaceData.simple(topTex);
                    faces[1] = BlockFaceData.simple(topTex);
                    faces[2] = BlockFaceData.simple(sideTex, 90.0f);
                    faces[3] = BlockFaceData.simple(sideTex, 90.0f);
                    faces[4] = BlockFaceData.simple(sideTex, 90.0f);
                    faces[5] = BlockFaceData.simple(sideTex, 90.0f);
                } else if ("z".equals(axis)) {
                    faces[0] = BlockFaceData.simple(sideTex, 90.0f);
                    faces[1] = BlockFaceData.simple(sideTex, 90.0f);
                    faces[2] = BlockFaceData.simple(sideTex);
                    faces[3] = BlockFaceData.simple(sideTex);
                    faces[4] = BlockFaceData.simple(topTex);
                    faces[5] = BlockFaceData.simple(topTex);
                } else {
                    faces[0] = BlockFaceData.simple(sideTex);
                    faces[1] = BlockFaceData.simple(sideTex);
                    faces[2] = BlockFaceData.simple(topTex);
                    faces[3] = BlockFaceData.simple(topTex);
                    faces[4] = BlockFaceData.simple(sideTex);
                    faces[5] = BlockFaceData.simple(sideTex);
                }
            } else if (facing != null && (name.contains("furnace") || name.contains("smoker") || name.contains("dispenser") || name.contains("dropper") || name.contains("observer"))) {
                String frontTex = "minecraft:block/" + name + ("true".equals(props.get("lit")) ? "_front_on" : "_front");
                String sideTex = "minecraft:block/" + name + "_side";
                String topTex = "minecraft:block/" + name + "_top";
                String bottomTex = topTex;

                for (int i = 0; i < 6; i++) {
                    faces[i] = BlockFaceData.simple(sideTex);
                }
                faces[2] = BlockFaceData.simple(topTex);
                faces[3] = BlockFaceData.simple(bottomTex);

                int frontIdx = 5; // North
                float frontRot = 0.0f;
                if ("south".equals(facing)) frontIdx = 4;
                else if ("east".equals(facing)) frontIdx = 0;
                else if ("west".equals(facing)) frontIdx = 1;
                else if ("up".equals(facing)) { frontIdx = 2; frontRot = 180.0f; }
                else if ("down".equals(facing)) { frontIdx = 3; frontRot = 180.0f; }

                faces[frontIdx] = BlockFaceData.simple(frontTex, frontRot);
            } else if (name.contains("banner")) {
                for (int i = 0; i < 6; i++) {
                    faces[i] = BlockFaceData.simple("minecraft:entity/banner/banner_base");
                }
            } else {
                String baseTex = "minecraft:block/" + name;
                for (int i = 0; i < 6; i++) {
                    faces[i] = BlockFaceData.simple(baseTex);
                }
            }

            return new BlockStateModelData(stateStr, blockType, isOpaque, isEmissive, emissiveLevel, faces);
        }

        @SuppressWarnings("unchecked")
        private static <T extends Comparable<T>> String getPropVal(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }
}
