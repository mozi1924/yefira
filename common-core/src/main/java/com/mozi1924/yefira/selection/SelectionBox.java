package com.mozi1924.yefira.selection;

import net.minecraft.core.BlockPos;

public class SelectionBox {
    private final BlockPos pos1;
    private final BlockPos pos2;
    private final BlockPos min;
    private final BlockPos max;

    public SelectionBox(BlockPos pos1, BlockPos pos2) {
        this.pos1 = pos1;
        this.pos2 = pos2;

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());

        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        this.min = new BlockPos(minX, minY, minZ);
        this.max = new BlockPos(maxX, maxY, maxZ);
    }

    public BlockPos getPos1() {
        return pos1;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public BlockPos getMin() {
        return min;
    }

    public BlockPos getMax() {
        return max;
    }

    public int getSizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int getSizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int getSizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    public long getVolume() {
        return (long) getSizeX() * getSizeY() * getSizeZ();
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
               pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
               pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= min.getX() && x <= max.getX() &&
               y >= min.getY() && y <= max.getY() &&
               z >= min.getZ() && z <= max.getZ();
    }

    public net.minecraft.world.phys.Vec3 getCenter() {
        return new net.minecraft.world.phys.Vec3(
            (min.getX() + max.getX() + 1) / 2.0,
            (min.getY() + max.getY() + 1) / 2.0,
            (min.getZ() + max.getZ() + 1) / 2.0
        );
    }

    public boolean isOversized() {
        com.mozi1924.yefira.config.YefiraConfig cfg = com.mozi1924.yefira.config.YefiraConfig.getInstance();
        return isOversized(cfg.getMaxVolumeSoftLimit(), cfg.getMaxSideSoftLimit());
    }

    public boolean isOversized(long maxVolume, int maxSide) {
        if (getVolume() > maxVolume) {
            return true;
        }
        return getSizeX() > maxSide || getSizeY() > maxSide || getSizeZ() > maxSide;
    }
}
