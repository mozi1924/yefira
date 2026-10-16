package com.mozi1924.yefira.compat.v1214;

import com.mozi1924.yefira.compat.ILevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class LevelCompat1214 implements ILevelCompat {
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
        return level.getMinSectionY();
    }

    @Override
    public int getMaxSectionY(Level level) {
        return level.getMaxSectionY();
    }

    @Override
    public boolean hasAdminPermission(net.minecraft.commands.CommandSourceStack source) {
        return (source.getServer() != null && !source.getServer().isDedicatedServer()) || source.hasPermission(2);
    }
}
