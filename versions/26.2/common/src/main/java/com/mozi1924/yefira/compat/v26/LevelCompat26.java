package com.mozi1924.yefira.compat.v26;

import com.mozi1924.yefira.compat.ILevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class LevelCompat26 implements ILevelCompat {
    @Override
    public String getDimensionId(Level level) {
        return level.dimension().identifier().toString();
    }

    @Override
    public String getDimensionId(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    @Override
    public ResourceKey<Level> parseDimensionKey(String dimStr) {
        Identifier dimId = Identifier.parse(dimStr);
        if (dimId != null) {
            return ResourceKey.create(Registries.DIMENSION, dimId);
        }
        return null;
    }

    @Override
    public String getBiomeId(Level level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
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
        if (source.getServer() != null && !source.getServer().isDedicatedServer()) {
            return true;
        }
        return source.permissions() != null &&
                source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }
}
