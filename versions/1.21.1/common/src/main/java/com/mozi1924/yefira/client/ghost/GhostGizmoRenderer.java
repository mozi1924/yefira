package com.mozi1924.yefira.client.ghost;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mozi1924.yefira.client.compat.RenderCompat;
import com.mozi1924.yefira.client.render.YefiraRenderTypes;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStyle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class GhostGizmoRenderer {

    public static final int COLOR_X = SelectionStyle.COLOR_HANDLE_X;
    public static final int COLOR_Y = SelectionStyle.COLOR_HANDLE_Y;
    public static final int COLOR_Z = SelectionStyle.COLOR_HANDLE_Z;
    public static final int COLOR_CENTER = SelectionStyle.COLOR_HANDLE_CENTER;
    public static final int COLOR_HIGHLIGHT = SelectionStyle.COLOR_HANDLE_HIGHLIGHT;

    private static class GizmoData {
        final AABB centerBox;
        final float cr, cg, cb;
        final float fillAlpha;
        final Vec3 relOrigin;
        final float axisLength;
        final int colorX, colorY, colorZ;

        GizmoData(AABB centerBox, float cr, float cg, float cb, float fillAlpha,
                  Vec3 relOrigin, float axisLength, int colorX, int colorY, int colorZ) {
            this.centerBox = centerBox;
            this.cr = cr;
            this.cg = cg;
            this.cb = cb;
            this.fillAlpha = fillAlpha;
            this.relOrigin = relOrigin;
            this.axisLength = axisLength;
            this.colorX = colorX;
            this.colorY = colorY;
            this.colorZ = colorZ;
        }
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        GhostModeManager ghost = GhostModeManager.getInstance();
        if (!ghost.isActive()) return;

        SelectionManager mgr = SelectionManager.getInstance();
        Vec3 camPos = camera.getPosition();

        // 1. Render Hovered Block Outline in Free Cursor Mode (when not dragging and no selection exists)
        if (!ghost.isDragging() && !ghost.isBoxCreating() && !mgr.hasSelection() && ghost.getHoveredBlockPos() != null) {
            BlockPos hPos = ghost.getHoveredBlockPos();
            AABB blockBox = new AABB(
                hPos.getX() - camPos.x, hPos.getY() - camPos.y, hPos.getZ() - camPos.z,
                hPos.getX() + 1.0 - camPos.x, hPos.getY() + 1.0 - camPos.y, hPos.getZ() + 1.0 - camPos.z
            );
            RenderCompat.renderSelectionFilledBox(
                poseStack, bufferSource, blockBox,
                SelectionStyle.r(SelectionStyle.COLOR_HOVER_BLOCK_FILL),
                SelectionStyle.g(SelectionStyle.COLOR_HOVER_BLOCK_FILL),
                SelectionStyle.b(SelectionStyle.COLOR_HOVER_BLOCK_FILL),
                SelectionStyle.a(SelectionStyle.COLOR_HOVER_BLOCK_FILL)
            );
            VertexConsumer lineBuffer = bufferSource.getBuffer(YefiraRenderTypes.gizmoLines());
            RenderCompat.renderLineBox(
                poseStack, lineBuffer, blockBox,
                SelectionStyle.r(SelectionStyle.COLOR_HOVER_BLOCK_STROKE),
                SelectionStyle.g(SelectionStyle.COLOR_HOVER_BLOCK_STROKE),
                SelectionStyle.b(SelectionStyle.COLOR_HOVER_BLOCK_STROKE),
                SelectionStyle.a(SelectionStyle.COLOR_HOVER_BLOCK_STROKE)
            );
            RenderCompat.endLastBatch(bufferSource);
            return;
        }

        // 2. Render Box Creation Preview when dragging to create
        if (ghost.isBoxCreating()) {
            SelectionBox boxCreate = ghost.getBoxCreateSelection();
            if (boxCreate != null) {
                BlockPos bMin = boxCreate.getMin();
                BlockPos bMax = boxCreate.getMax();
                AABB createBox = new AABB(
                    bMin.getX() - camPos.x, bMin.getY() - camPos.y, bMin.getZ() - camPos.z,
                    bMax.getX() + 1.0 - camPos.x, bMax.getY() + 1.0 - camPos.y, bMax.getZ() + 1.0 - camPos.z
                );
                boolean oversized = boxCreate.isOversized();
                int strokeColor = oversized ? SelectionStyle.COLOR_BOX_CREATE_OVERSIZED_STROKE : SelectionStyle.COLOR_BOX_CREATE_NORMAL_STROKE;
                int fillColor = oversized ? SelectionStyle.COLOR_BOX_CREATE_OVERSIZED_FILL : SelectionStyle.COLOR_BOX_CREATE_NORMAL_FILL;

                RenderCompat.renderSelectionFilledBox(
                    poseStack, bufferSource, createBox,
                    SelectionStyle.r(fillColor),
                    SelectionStyle.g(fillColor),
                    SelectionStyle.b(fillColor),
                    SelectionStyle.a(fillColor)
                );
                VertexConsumer lineBuffer = bufferSource.getBuffer(YefiraRenderTypes.gizmoLines());
                RenderCompat.renderLineBox(
                    poseStack, lineBuffer, createBox,
                    SelectionStyle.r(strokeColor),
                    SelectionStyle.g(strokeColor),
                    SelectionStyle.b(strokeColor),
                    SelectionStyle.a(strokeColor)
                );

                // Corner indicators
                double pt = 0.08;
                AABB startPoint = new AABB(
                    bMin.getX() + 0.5 - camPos.x - pt, bMin.getY() + 0.5 - camPos.y - pt, bMin.getZ() + 0.5 - camPos.z - pt,
                    bMin.getX() + 0.5 - camPos.x + pt, bMin.getY() + 0.5 - camPos.y + pt, bMin.getZ() + 0.5 - camPos.z + pt
                );
                int cStart = SelectionStyle.COLOR_BOX_CREATE_CORNER_START;
                RenderCompat.renderGizmoFilledBox(
                    poseStack, bufferSource, startPoint,
                    SelectionStyle.r(cStart), SelectionStyle.g(cStart), SelectionStyle.b(cStart), 1.0f
                );

                int cEnd = oversized ? SelectionStyle.COLOR_BOX_CREATE_CORNER_END_OVERSIZED : SelectionStyle.COLOR_BOX_CREATE_CORNER_END;
                AABB endPoint = new AABB(
                    bMax.getX() + 0.5 - camPos.x - pt, bMax.getY() + 0.5 - camPos.y - pt, bMax.getZ() + 0.5 - camPos.z - pt,
                    bMax.getX() + 0.5 - camPos.x + pt, bMax.getY() + 0.5 - camPos.y + pt, bMax.getZ() + 0.5 - camPos.z + pt
                );
                RenderCompat.renderGizmoFilledBox(
                    poseStack, bufferSource, endPoint,
                    SelectionStyle.r(cEnd), SelectionStyle.g(cEnd), SelectionStyle.b(cEnd), 1.0f
                );

                RenderCompat.endLastBatch(bufferSource);
            }
            return;
        }

        // Dimension check for committed selection gizmos
        if (mgr.hasSelection() && mgr.getDimension() != null && !mc.level.dimension().equals(mgr.getDimension())) {
            return;
        }

        BlockPos pos1 = ghost.getEffectivePos1();
        BlockPos pos2 = ghost.getEffectivePos2();

        List<GizmoData> gizmos = new ArrayList<>(3);

        // Prepare Pos1 Gizmo
        if (pos1 != null) {
            Vec3 origin1 = new Vec3(pos1.getX() + 0.5, pos1.getY() + 0.5, pos1.getZ() + 0.5);
            gizmos.add(prepareCornerGizmo(camPos, origin1, GhostModeManager.CORNER_POS1));
        }

        // Prepare Pos2 Gizmo
        if (pos2 != null) {
            Vec3 origin2 = new Vec3(pos2.getX() + 0.5, pos2.getY() + 0.5, pos2.getZ() + 0.5);
            gizmos.add(prepareCornerGizmo(camPos, origin2, GhostModeManager.CORNER_POS2));
        }

        // Prepare Center Move Gizmo if both pos1 and pos2 are set
        if (pos1 != null && pos2 != null) {
            BlockPos min = new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
            );
            BlockPos max = new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
            );
            Vec3 center = new Vec3(
                (min.getX() + max.getX() + 1) / 2.0,
                (min.getY() + max.getY() + 1) / 2.0,
                (min.getZ() + max.getZ() + 1) / 2.0
            );
            gizmos.add(prepareCenterGizmo(camPos, center));
        }

        if (gizmos.isEmpty()) {
            return;
        }

        // Pass 1: Render Always-On-Top Translucent Filled Boxes (No depth test)
        for (GizmoData gizmo : gizmos) {
            RenderCompat.renderGizmoFilledBox(poseStack, bufferSource, gizmo.centerBox, gizmo.cr, gizmo.cg, gizmo.cb, gizmo.fillAlpha);
        }

        // Pass 2: Render Always-On-Top Lines (Center box wireframes & 3D Axis Arrows)
        VertexConsumer lineBuffer = bufferSource.getBuffer(YefiraRenderTypes.gizmoLines());
        for (GizmoData gizmo : gizmos) {
            RenderCompat.renderLineBox(poseStack, lineBuffer, gizmo.centerBox, gizmo.cr, gizmo.cg, gizmo.cb, 1.0f);

            // X Axis (Red)
            drawAxisArrow(poseStack, lineBuffer, gizmo.relOrigin, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), gizmo.axisLength, gizmo.colorX);
            // Y Axis (Green)
            drawAxisArrow(poseStack, lineBuffer, gizmo.relOrigin, new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), gizmo.axisLength, gizmo.colorY);
            // Z Axis (Blue)
            drawAxisArrow(poseStack, lineBuffer, gizmo.relOrigin, new Vec3(0, 0, 1), new Vec3(1, 0, 0), new Vec3(0, 1, 0), gizmo.axisLength, gizmo.colorZ);
        }

        RenderCompat.endLastBatch(bufferSource);
    }

    private static GizmoData prepareCornerGizmo(Vec3 camPos, Vec3 origin, int cornerId) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        Vec3 cameraPos = ghost.getCameraPos();
        double dist = Math.max(0.5, origin.distanceTo(cameraPos));
        float axisLength = (float) (dist * SelectionStyle.HANDLE_AXIS_RATIO);
        float centerHalf = (float) (dist * SelectionStyle.HANDLE_CENTER_RATIO);

        int hoveredCorner = ghost.getHoveredCorner();
        int hoveredAxis = ghost.getHoveredAxis();
        int draggingCorner = ghost.getDraggingCorner();
        int draggingAxis = ghost.getDraggingAxis();

        boolean isCurrentCorner = (draggingCorner == cornerId) || (draggingCorner == GhostModeManager.CORNER_NONE && hoveredCorner == cornerId);

        Vec3 relOrigin = origin.subtract(camPos);

        // Center handle cube
        boolean centerHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_CENTER);
        int centerColor = centerHighlighted ? COLOR_HIGHLIGHT : COLOR_CENTER;
        AABB centerBox = new AABB(
            relOrigin.x - centerHalf, relOrigin.y - centerHalf, relOrigin.z - centerHalf,
            relOrigin.x + centerHalf, relOrigin.y + centerHalf, relOrigin.z + centerHalf
        );
        float cr = SelectionStyle.r(centerColor);
        float cg = SelectionStyle.g(centerColor);
        float cb = SelectionStyle.b(centerColor);

        // X Axis (Red)
        boolean xHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_X);
        int colorX = xHighlighted ? COLOR_HIGHLIGHT : COLOR_X;

        // Y Axis (Green)
        boolean yHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Y);
        int colorY = yHighlighted ? COLOR_HIGHLIGHT : COLOR_Y;

        // Z Axis (Blue)
        boolean zHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Z);
        int colorZ = zHighlighted ? COLOR_HIGHLIGHT : COLOR_Z;

        return new GizmoData(centerBox, cr, cg, cb, 0.5f, relOrigin, axisLength, colorX, colorY, colorZ);
    }

    private static GizmoData prepareCenterGizmo(Vec3 camPos, Vec3 center) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        Vec3 cameraPos = ghost.getCameraPos();
        double dist = Math.max(0.5, center.distanceTo(cameraPos));
        float axisLength = (float) (dist * SelectionStyle.HANDLE_AXIS_RATIO);
        float centerHalf = (float) (dist * SelectionStyle.HANDLE_CENTER_RATIO);

        int hoveredCorner = ghost.getHoveredCorner();
        int hoveredAxis = ghost.getHoveredAxis();
        int draggingCorner = ghost.getDraggingCorner();
        int draggingAxis = ghost.getDraggingAxis();

        boolean isCenterGizmo = (draggingCorner == GhostModeManager.CORNER_CENTER) ||
                                (draggingCorner == GhostModeManager.CORNER_NONE && hoveredCorner == GhostModeManager.CORNER_CENTER);

        Vec3 relCenter = center.subtract(camPos);

        // Center cube
        boolean centerHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_CENTER);
        int centerColor = centerHighlighted ? COLOR_HIGHLIGHT : COLOR_CENTER;
        AABB centerBox = new AABB(
            relCenter.x - centerHalf, relCenter.y - centerHalf, relCenter.z - centerHalf,
            relCenter.x + centerHalf, relCenter.y + centerHalf, relCenter.z + centerHalf
        );
        float cr = SelectionStyle.r(centerColor);
        float cg = SelectionStyle.g(centerColor);
        float cb = SelectionStyle.b(centerColor);

        // X Axis
        boolean xHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_X);
        int colorX = xHighlighted ? COLOR_HIGHLIGHT : COLOR_X;

        // Y Axis
        boolean yHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Y);
        int colorY = yHighlighted ? COLOR_HIGHLIGHT : COLOR_Y;

        // Z Axis
        boolean zHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Z);
        int colorZ = zHighlighted ? COLOR_HIGHLIGHT : COLOR_Z;

        return new GizmoData(centerBox, cr, cg, cb, 0.6f, relCenter, axisLength, colorX, colorY, colorZ);
    }

    private static void drawAxisArrow(PoseStack poseStack, VertexConsumer lines,
                                      Vec3 origin, Vec3 dir, Vec3 u, Vec3 v,
                                      float axisLength, int color) {
        Vec3 tip = origin.add(dir.scale(axisLength));
        float headLen = axisLength * 0.28f;
        float headRadius = headLen * 0.42f;
        Vec3 base = tip.subtract(dir.scale(headLen));

        // 1. Shaft line from origin to tip
        drawLine(poseStack, lines, origin, tip, color);

        // 2. Base perimeter vertices on the plane orthogonal to dir
        Vec3 b0 = base.add(u.scale(headRadius));
        Vec3 b1 = base.add(v.scale(headRadius));
        Vec3 b2 = base.subtract(u.scale(headRadius));
        Vec3 b3 = base.subtract(v.scale(headRadius));

        // 3. Four side edges pointing to tip (forming 3D pyramid arrowhead)
        drawLine(poseStack, lines, b0, tip, color);
        drawLine(poseStack, lines, b1, tip, color);
        drawLine(poseStack, lines, b2, tip, color);
        drawLine(poseStack, lines, b3, tip, color);

        // 4. Closed base ring
        drawLine(poseStack, lines, b0, b1, color);
        drawLine(poseStack, lines, b1, b2, color);
        drawLine(poseStack, lines, b2, b3, color);
        drawLine(poseStack, lines, b3, b0, color);

        // 5. Cross diagonals on base
        drawLine(poseStack, lines, b0, b2, color);
        drawLine(poseStack, lines, b1, b3, color);
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer lines, Vec3 p1, Vec3 p2, int argb) {
        float a = SelectionStyle.a(argb);
        float r = SelectionStyle.r(argb);
        float g = SelectionStyle.g(argb);
        float b = SelectionStyle.b(argb);

        RenderCompat.drawGizmoLine(poseStack, lines, p1, p2, r, g, b, a);
    }
}
