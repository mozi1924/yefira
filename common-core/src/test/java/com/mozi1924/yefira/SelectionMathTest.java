package com.mozi1924.yefira;

import com.mozi1924.yefira.selection.SelectionBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SelectionMathTest {

    @Test
    public void testSelectionBoxCalculation() {
        BlockPos p1 = new BlockPos(10, 20, 30);
        BlockPos p2 = new BlockPos(0, 5, 15);

        SelectionBox box = new SelectionBox(p1, p2);

        Assertions.assertEquals(new BlockPos(0, 5, 15), box.getMin());
        Assertions.assertEquals(new BlockPos(10, 20, 30), box.getMax());

        Assertions.assertEquals(11, box.getSizeX());
        Assertions.assertEquals(16, box.getSizeY());
        Assertions.assertEquals(16, box.getSizeZ());

        Assertions.assertEquals(11L * 16L * 16L, box.getVolume());

        Assertions.assertTrue(box.contains(new BlockPos(5, 10, 20)));
        Assertions.assertFalse(box.contains(new BlockPos(12, 10, 20)));
    }

    @Test
    public void testSelectionBoxOversized() {
        // Small box (16x16x16 = 4096 blocks) -> not oversized with default limits
        SelectionBox small = new SelectionBox(new BlockPos(0, 0, 0), new BlockPos(15, 15, 15));
        Assertions.assertFalse(small.isOversized(262144L, 256));

        // Volume oversized box (100x100x100 = 1,000,000 blocks > 262,144)
        SelectionBox largeVolume = new SelectionBox(new BlockPos(0, 0, 0), new BlockPos(99, 99, 99));
        Assertions.assertTrue(largeVolume.isOversized(262144L, 256));

        // Single side oversized box (300x1x1 = 300 blocks <= 262144, but side 300 > 256)
        SelectionBox longSide = new SelectionBox(new BlockPos(0, 0, 0), new BlockPos(299, 0, 0));
        Assertions.assertTrue(longSide.isOversized(262144L, 256));
    }
}
