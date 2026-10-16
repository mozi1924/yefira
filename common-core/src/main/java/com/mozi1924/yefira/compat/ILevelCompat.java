package com.mozi1924.yefira.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Version-specific abstraction interface for Level and Registry operations.
 * Handles discrepancies such as ResourceLocation vs Identifier,
 * getMinSection() vs getMinSectionY(), and Biome registry extraction.
 */
public interface ILevelCompat {

    /**
     * Extracts the namespaced ID (e.g. "minecraft:overworld") from an active Level.
     */
    String getDimensionId(Level level);

    /**
     * Extracts the namespaced ID from a Level ResourceKey.
     */
    String getDimensionId(ResourceKey<Level> dimension);

    /**
     * Parses a dimension string into a Level ResourceKey.
     */
    ResourceKey<Level> parseDimensionKey(String dimStr);

    /**
     * Retrieves the namespaced ID of the biome at the given BlockPos.
     */
    String getBiomeId(Level level, BlockPos pos);

    /**
     * Returns the minimum chunk section coordinate on the Y axis.
     */
    int getMinSectionY(Level level);

    /**
     * Returns the maximum chunk section coordinate on the Y axis.
     */
    int getMaxSectionY(Level level);

    /**
     * Checks if the command source has administrative/operator permissions.
     */
    boolean hasAdminPermission(net.minecraft.commands.CommandSourceStack source);

    /**
     * Retrieves the 64 quart biomes (4x4x4) for the given section (secX, secY, secZ).
     * Returns an array of 64 namespaced biome IDs indexed as (qy &lt;&lt; 4) | (qz &lt;&lt; 2) | qx.
     */
    String[] getSectionQuartBiomes(Level level, int secX, int secY, int secZ);
}
