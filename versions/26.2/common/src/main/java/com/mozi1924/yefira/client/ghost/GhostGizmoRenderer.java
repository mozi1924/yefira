package com.mozi1924.yefira.client.ghost;

import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GhostGizmoRenderer {

    public static final int COLOR_X = SelectionStyle.COLOR_HANDLE_X;
    public static final int COLOR_Y = SelectionStyle.COLOR_HANDLE_Y;
    public static final int COLOR_Z = SelectionStyle.COLOR_HANDLE_Z;
    public static final int COLOR_CENTER = SelectionStyle.COLOR_HANDLE_CENTER;
    public static final int COLOR_HIGHLIGHT = SelectionStyle.COLOR_HANDLE_HIGHLIGHT;

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        GhostModeManager ghost = GhostModeManager.getInstance();
        if (!ghost.isActive()) return;

        SelectionManager mgr = SelectionManager.getInstance();

        // 1. Render Hovered Block Outline in Free Cursor Mode (when not dragging and no selection exists)
        if (!ghost.isDragging() && !ghost.isBoxCreating() && !mgr.hasSelection() && ghost.getHoveredBlockPos() != null) {
            BlockPos hPos = ghost.getHoveredBlockPos();
            AABB blockBox = new AABB(
                hPos.getX(), hPos.getY(), hPos.getZ(),
                hPos.getX() + 1.0, hPos.getY() + 1.0, hPos.getZ() + 1.0
            );
            // Glowing Cyan Stroke with subtle fill
            Gizmos.cuboid(blockBox, GizmoStyle.strokeAndFill(
                SelectionStyle.COLOR_HOVER_BLOCK_STROKE,
                SelectionStyle.WIDTH_HOVER_BLOCK_STROKE,
                SelectionStyle.COLOR_HOVER_BLOCK_FILL
            ));
        }

        // 2. Render Box Creation Preview when dragging to create
        if (ghost.isBoxCreating()) {
            SelectionBox boxCreate = ghost.getBoxCreateSelection();
            if (boxCreate != null) {
                BlockPos bMin = boxCreate.getMin();
                BlockPos bMax = boxCreate.getMax();
                AABB createBox = new AABB(
                    bMin.getX(), bMin.getY(), bMin.getZ(),
                    bMax.getX() + 1.0, bMax.getY() + 1.0, bMax.getZ() + 1.0
                );
                boolean oversized = boxCreate.isOversized();
                int strokeColor = oversized ? SelectionStyle.COLOR_BOX_CREATE_OVERSIZED_STROKE : SelectionStyle.COLOR_BOX_CREATE_NORMAL_STROKE;
                int fillColor = oversized ? SelectionStyle.COLOR_BOX_CREATE_OVERSIZED_FILL : SelectionStyle.COLOR_BOX_CREATE_NORMAL_FILL;
                Gizmos.cuboid(createBox, GizmoStyle.strokeAndFill(strokeColor, SelectionStyle.WIDTH_BOX_CREATE_STROKE, fillColor));

                // Corner indicators
                Vec3 startCorner = new Vec3(bMin.getX() + 0.5, bMin.getY() + 0.5, bMin.getZ() + 0.5);
                Vec3 endCorner = new Vec3(bMax.getX() + 0.5, bMax.getY() + 0.5, bMax.getZ() + 0.5);
                Gizmos.point(startCorner, strokeColor, SelectionStyle.CORNER_POINT_SIZE);
                Gizmos.point(endCorner, oversized ? SelectionStyle.COLOR_BOX_CREATE_CORNER_END_OVERSIZED : SelectionStyle.COLOR_BOX_CREATE_CORNER_END, SelectionStyle.CORNER_POINT_SIZE);
            }
            return; // Do not render old gizmos while actively dragging a new box
        }

        // Dimension check for committed selection gizmos
        if (mgr.hasSelection() && mgr.getDimension() != null && !mc.level.dimension().equals(mgr.getDimension())) {
            return;
        }

        BlockPos pos1 = ghost.getEffectivePos1();
        BlockPos pos2 = ghost.getEffectivePos2();

        // Render Pos1 Gizmo
        if (pos1 != null) {
            Vec3 origin1 = new Vec3(pos1.getX() + 0.5, pos1.getY() + 0.5, pos1.getZ() + 0.5);
            renderCornerGizmo(origin1, GhostModeManager.CORNER_POS1, "Pos 1", SelectionStyle.COLOR_HANDLE_POS1);
        }

        // Render Pos2 Gizmo
        if (pos2 != null) {
            Vec3 origin2 = new Vec3(pos2.getX() + 0.5, pos2.getY() + 0.5, pos2.getZ() + 0.5);
            renderCornerGizmo(origin2, GhostModeManager.CORNER_POS2, "Pos 2", SelectionStyle.COLOR_HANDLE_POS2);
        }

        // Render Center Move Gizmo if both pos1 and pos2 are set
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
            renderCenterGizmo(center);
        }
    }

    private static void renderCornerGizmo(Vec3 origin, int cornerId, String label, int labelColor) {
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

        // Center handle cube (constant screen size, always on top)
        boolean centerHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_CENTER);
        int centerColor = centerHighlighted ? COLOR_HIGHLIGHT : COLOR_CENTER;
        AABB centerBox = new AABB(
            origin.x - centerHalf, origin.y - centerHalf, origin.z - centerHalf,
            origin.x + centerHalf, origin.y + centerHalf, origin.z + centerHalf
        );
        Gizmos.cuboid(centerBox, GizmoStyle.strokeAndFill(centerColor, 3.0f, centerColor & 0x88FFFFFF)).setAlwaysOnTop();

        // X Axis (Red)
        boolean xHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_X);
        int colorX = xHighlighted ? COLOR_HIGHLIGHT : COLOR_X;
        Vec3 endX = origin.add(axisLength, 0, 0);
        Gizmos.arrow(origin, endX, colorX, xHighlighted ? 5.0f : 3.0f).setAlwaysOnTop();

        // Y Axis (Green)
        boolean yHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Y);
        int colorY = yHighlighted ? COLOR_HIGHLIGHT : COLOR_Y;
        Vec3 endY = origin.add(0, axisLength, 0);
        Gizmos.arrow(origin, endY, colorY, yHighlighted ? 5.0f : 3.0f).setAlwaysOnTop();

        // Z Axis (Blue)
        boolean zHighlighted = isCurrentCorner && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Z);
        int colorZ = zHighlighted ? COLOR_HIGHLIGHT : COLOR_Z;
        Vec3 endZ = origin.add(0, 0, axisLength);
        Gizmos.arrow(origin, endZ, colorZ, zHighlighted ? 5.0f : 3.0f).setAlwaysOnTop();
    }

    private static void renderCenterGizmo(Vec3 center) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        Vec3 cameraPos = ghost.getCameraPos();
        double dist = Math.max(0.5, center.distanceTo(cameraPos));
        float axisLength = (float) (dist * SelectionStyle.HANDLE_AXIS_RATIO);

        int hoveredCorner = ghost.getHoveredCorner();
        int hoveredAxis = ghost.getHoveredAxis();
        int draggingCorner = ghost.getDraggingCorner();
        int draggingAxis = ghost.getDraggingAxis();

        boolean isCenterGizmo = (draggingCorner == GhostModeManager.CORNER_CENTER) ||
                                (draggingCorner == GhostModeManager.CORNER_NONE && hoveredCorner == GhostModeManager.CORNER_CENTER);

        // Center sphere/point (always on top)
        Gizmos.point(center, COLOR_CENTER, 7.0f).setAlwaysOnTop();

        // X Axis
        boolean xHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_X);
        int colorX = xHighlighted ? COLOR_HIGHLIGHT : (COLOR_X & 0xAAFFFFFF);
        Gizmos.arrow(center, center.add(axisLength, 0, 0), colorX, xHighlighted ? 4.0f : 2.0f).setAlwaysOnTop();

        // Y Axis
        boolean yHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Y);
        int colorY = yHighlighted ? COLOR_HIGHLIGHT : (COLOR_Y & 0xAAFFFFFF);
        Gizmos.arrow(center, center.add(0, axisLength, 0), colorY, yHighlighted ? 4.0f : 2.0f).setAlwaysOnTop();

        // Z Axis
        boolean zHighlighted = isCenterGizmo && ((draggingCorner != GhostModeManager.CORNER_NONE ? draggingAxis : hoveredAxis) == GhostModeManager.AXIS_Z);
        int colorZ = zHighlighted ? COLOR_HIGHLIGHT : (COLOR_Z & 0xAAFFFFFF);
        Gizmos.arrow(center, center.add(0, 0, axisLength), colorZ, zHighlighted ? 4.0f : 2.0f).setAlwaysOnTop();
    }
}
