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
}
