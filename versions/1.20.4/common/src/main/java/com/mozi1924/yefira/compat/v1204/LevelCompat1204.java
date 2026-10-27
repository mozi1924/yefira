package com.mozi1924.yefira.compat.v1204;

import com.mozi1924.yefira.compat.ILevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class LevelCompat1204 implements ILevelCompat {
    @Override
    public String getDimensionId(Level level) {
        return level.dimension().location().toString();
    }

    @Override
    public String getDimensionId(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }

    @Override
    public ResourceKey<Level> parseDimensionKey(String dimStr) {
        ResourceLocation dimId = ResourceLocation.tryParse(dimStr);
        if (dimId != null) {
            return ResourceKey.create(Registries.DIMENSION, dimId);
        }
        return null;
    }

    @Override
    public String getBiomeId(Level level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
    }

    @Override
    public int getMinSectionY(Level level) {
        return level.getMinSection();
    }

    @Override
    public int getMaxSectionY(Level level) {
        return level.getMaxSection();
    }

    @Override
    public boolean hasAdminPermission(net.minecraft.commands.CommandSourceStack source) {
        if (source.getServer() != null && !source.getServer().isDedicatedServer()) {
            if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                return source.getServer().isSingleplayerOwner(player.getGameProfile());
            }
        }
        return source.hasPermission(2);
    }

    @Override
    public String[] getSectionQuartBiomes(Level level, int secX, int secY, int secZ) {
        String[] biomes = new String[64];
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(secX, secZ);
        net.minecraft.world.level.chunk.LevelChunkSection section = null;
        if (chunk != null) {
            int secIdx = level.getSectionIndexFromSectionY(secY);
            net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
            if (secIdx >= 0 && secIdx < sections.length) {
                section = sections[secIdx];
            }
        }

        if (section != null && section.getBiomes() != null) {
            var palettedContainer = section.getBiomes();
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        int idx = (qy << 4) | (qz << 2) | qx;
                        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder = palettedContainer.get(qx, qy, qz);
                        String biomeId = holder.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
                        biomes[idx] = biomeId;
                    }
                }
            }
        } else {
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        int idx = (qy << 4) | (qz << 2) | qx;
                        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder = level.getNoiseBiome((secX << 2) + qx, (secY << 2) + qy, (secZ << 2) + qz);
                        String biomeId = holder.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
                        biomes[idx] = biomeId;
                    }
                }
            }
        }
        return biomes;
    }

    @Override
    public String serializeBlockEntityJson(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be instanceof net.minecraft.world.level.block.entity.DecoratedPotBlockEntity pot) {
            var dec = pot.getDecorations();
            return com.mozi1924.yefira.compat.visual.VisualBlockEntity.serializeDecoratedPot(
                    getItemKey(dec.back()),
                    getItemKey(dec.left()),
                    getItemKey(dec.right()),
                    getItemKey(dec.front())
            );
        }
        if (be instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull) {
            String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath();
            String kind = com.mozi1924.yefira.compat.visual.VisualBlockEntity.resolveSkullKind(path);
            Integer rot = null;
            if (be.getBlockState().hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)) {
                rot = be.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16);
            }
            com.mojang.authlib.GameProfile profile = skull.getOwnerProfile();
            String name = null;
            String id = null;
            String skinBase64 = null;
            if (profile != null) {
                name = profile.getName();
                if (profile.getId() != null) {
                    id = profile.getId().toString();
                }
                var props = profile.getProperties();
                if (props != null) {
                    var textures = props.get("textures");
                    if (textures != null && !textures.isEmpty()) {
                        for (var p : textures) {
                            skinBase64 = p.value();
                            break;
                        }
                    }
                }
            }
            return com.mozi1924.yefira.compat.visual.VisualBlockEntity.serializeSkull(
                    kind,
                    rot,
                    name,
                    id,
                    skinBase64
            );
        }
        return null;
    }

    private static String getItemKey(net.minecraft.world.item.Item item) {
        if (item != null && item != net.minecraft.world.item.Items.AIR && item != net.minecraft.world.item.Items.BRICK) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        }
        return "";
    }
}
