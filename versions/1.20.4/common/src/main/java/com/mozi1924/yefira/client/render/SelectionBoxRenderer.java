package com.mozi1924.yefira.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mozi1924.yefira.client.compat.RenderCompat;
import com.mozi1924.yefira.client.ghost.GhostModeManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStyle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class SelectionBoxRenderer {

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        SelectionManager mgr = SelectionManager.getInstance();
        if (!mgr.hasSelection()) return;

        // Dimension check
        if (mgr.getDimension() != null && !mc.level.dimension().equals(mgr.getDimension())) {
            return;
        }

        SelectionBox selection = mgr.getCurrentSelection();
        if (selection == null) return;

        Vec3 camPos = camera.getPosition();
        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();

        AABB box = new AABB(
            min.getX() - camPos.x, min.getY() - camPos.y, min.getZ() - camPos.z,
            max.getX() + 1.0 - camPos.x, max.getY() + 1.0 - camPos.y, max.getZ() + 1.0 - camPos.z
        );

        GhostModeManager ghost = GhostModeManager.getInstance();
        AABB previewBox = null;
        SelectionBox previewSel = null;
        if (ghost.isDragging()) {
            previewSel = ghost.getDragPreviewSelection();
            if (previewSel != null) {
                BlockPos pMin = previewSel.getMin();
                BlockPos pMax = previewSel.getMax();
                previewBox = new AABB(
                    pMin.getX() - camPos.x, pMin.getY() - camPos.y, pMin.getZ() - camPos.z,
                    pMax.getX() + 1.0 - camPos.x, pMax.getY() + 1.0 - camPos.y, pMax.getZ() + 1.0 - camPos.z
                );
            }
        }
        boolean isOversized = selection.isOversized();
        boolean previewOversized = previewSel != null && previewSel.isOversized();

        // Pass 1: Translucent fills (depth test on, depth write off, no culling)
        if (isOversized) {
            RenderCompat.renderSelectionFilledBox(
                poseStack, bufferSource, box,
                SelectionStyle.r(SelectionStyle.COLOR_OVERSIZED_FILL),
                SelectionStyle.g(SelectionStyle.COLOR_OVERSIZED_FILL),
                SelectionStyle.b(SelectionStyle.COLOR_OVERSIZED_FILL),
                SelectionStyle.a(SelectionStyle.COLOR_OVERSIZED_FILL)
            );
        } else {
            RenderCompat.renderSelectionFilledBox(
                poseStack, bufferSource, box,
                SelectionStyle.r(SelectionStyle.COLOR_NORMAL_FILL),
                SelectionStyle.g(SelectionStyle.COLOR_NORMAL_FILL),
                SelectionStyle.b(SelectionStyle.COLOR_NORMAL_FILL),
                SelectionStyle.a(SelectionStyle.COLOR_NORMAL_FILL)
            );
        }

        if (previewBox != null) {
            if (previewOversized) {
                RenderCompat.renderSelectionFilledBox(
                    poseStack, bufferSource, previewBox,
                    SelectionStyle.r(SelectionStyle.COLOR_PREVIEW_OVERSIZED_FILL),
                    SelectionStyle.g(SelectionStyle.COLOR_PREVIEW_OVERSIZED_FILL),
                    SelectionStyle.b(SelectionStyle.COLOR_PREVIEW_OVERSIZED_FILL),
                    SelectionStyle.a(SelectionStyle.COLOR_PREVIEW_OVERSIZED_FILL)
                );
            } else {
                RenderCompat.renderSelectionFilledBox(
                    poseStack, bufferSource, previewBox,
                    SelectionStyle.r(SelectionStyle.COLOR_PREVIEW_NORMAL_FILL),
                    SelectionStyle.g(SelectionStyle.COLOR_PREVIEW_NORMAL_FILL),
                    SelectionStyle.b(SelectionStyle.COLOR_PREVIEW_NORMAL_FILL),
                    SelectionStyle.a(SelectionStyle.COLOR_PREVIEW_NORMAL_FILL)
                );
            }
        }

        // Pass 2: Line strokes (depth test on, depth write off, matching 26.2 stroke widths)
        if (isOversized) {
            VertexConsumer lineBuffer = bufferSource.getBuffer(YefiraRenderTypes.selectionLinesThick());
            RenderCompat.renderLineBox(
                poseStack, lineBuffer, box,
                SelectionStyle.r(SelectionStyle.COLOR_OVERSIZED_STROKE),
                SelectionStyle.g(SelectionStyle.COLOR_OVERSIZED_STROKE),
                SelectionStyle.b(SelectionStyle.COLOR_OVERSIZED_STROKE),
                SelectionStyle.a(SelectionStyle.COLOR_OVERSIZED_STROKE)
            );
        } else {
            VertexConsumer lineBuffer = bufferSource.getBuffer(YefiraRenderTypes.selectionLinesNormal());
            RenderCompat.renderLineBox(
                poseStack, lineBuffer, box,
                SelectionStyle.r(SelectionStyle.COLOR_NORMAL_STROKE),
                SelectionStyle.g(SelectionStyle.COLOR_NORMAL_STROKE),
                SelectionStyle.b(SelectionStyle.COLOR_NORMAL_STROKE),
                SelectionStyle.a(SelectionStyle.COLOR_NORMAL_STROKE)
            );
        }

        if (previewBox != null) {
            VertexConsumer previewLineBuffer = bufferSource.getBuffer(YefiraRenderTypes.selectionLinesThick());
            if (previewOversized) {
                RenderCompat.renderLineBox(
                    poseStack, previewLineBuffer, previewBox,
                    SelectionStyle.r(SelectionStyle.COLOR_PREVIEW_OVERSIZED_STROKE),
                    SelectionStyle.g(SelectionStyle.COLOR_PREVIEW_OVERSIZED_STROKE),
                    SelectionStyle.b(SelectionStyle.COLOR_PREVIEW_OVERSIZED_STROKE),
                    SelectionStyle.a(SelectionStyle.COLOR_PREVIEW_OVERSIZED_STROKE)
                );
            } else {
                RenderCompat.renderLineBox(
                    poseStack, previewLineBuffer, previewBox,
                    SelectionStyle.r(SelectionStyle.COLOR_PREVIEW_NORMAL_STROKE),
                    SelectionStyle.g(SelectionStyle.COLOR_PREVIEW_NORMAL_STROKE),
                    SelectionStyle.b(SelectionStyle.COLOR_PREVIEW_NORMAL_STROKE),
                    SelectionStyle.a(SelectionStyle.COLOR_PREVIEW_NORMAL_STROKE)
                );
            }
        }

        // Flush selection box batch immediately so gizmos render cleanly on top
        RenderCompat.endLastBatch(bufferSource);
    }
}
