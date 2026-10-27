package com.mozi1924.yefira.selection;

/**
 * Single source of truth for 3D selection box, preview, and Gizmo visual styles.
 * Baseline aligned with Minecraft 26.2 GizmoStyle definitions.
 */
public final class SelectionStyle {

    // === Selection Box (Committed) ===
    // Normal: Cyan outline (alpha 0.902, 2.0f width) + subtle cyan fill (alpha 0.200)
    public static final int COLOR_NORMAL_STROKE = 0xE600FFFF;
    public static final int COLOR_NORMAL_FILL = 0x3300FFFF;
    public static final float WIDTH_NORMAL_STROKE = 2.0f;
    public static final float LINE_WIDTH_NORMAL = WIDTH_NORMAL_STROKE;

    // Oversized: Alert red outline (alpha 0.941, 2.5f width) + subtle red fill (alpha 0.251)
    public static final int COLOR_OVERSIZED_STROKE = 0xF0FF2222;
    public static final int COLOR_OVERSIZED_FILL = 0x40FF2222;
    public static final float WIDTH_OVERSIZED_STROKE = 2.5f;
    public static final float LINE_WIDTH_OVERSIZED = WIDTH_OVERSIZED_STROKE;

    // === Drag Preview Box (Active dragging in Ghost Mode) ===
    // Preview Normal: Lime green outline (alpha 0.902, 2.5f width) + translucent green fill (alpha 0.251)
    public static final int COLOR_PREVIEW_NORMAL_STROKE = 0xE600FF00;
    public static final int COLOR_PREVIEW_NORMAL_FILL = 0x4000FF00;
    public static final float WIDTH_PREVIEW_STROKE = 2.5f;

    // Preview Oversized: Red-Orange outline (alpha 0.941, 2.5f width) + red-orange fill (alpha 0.302)
    public static final int COLOR_PREVIEW_OVERSIZED_STROKE = 0xF0FF3300;
    public static final int COLOR_PREVIEW_OVERSIZED_FILL = 0x4DFF3300;

    // === Free Cursor Hover Block Outline ===
    // Glowing cyan outline (alpha 1.0, 3.0f width) + subtle fill (alpha 0.2)
    public static final int COLOR_HOVER_BLOCK_STROKE = 0xFF00FFEE;
    public static final int COLOR_HOVER_BLOCK_FILL = 0x3300FFEE;
    public static final float WIDTH_HOVER_BLOCK_STROKE = 3.0f;

    // === Box Creation Drag Preview ===
    public static final int COLOR_BOX_CREATE_NORMAL_STROKE = 0xFF00FF55;
    public static final int COLOR_BOX_CREATE_NORMAL_FILL = 0x4000FF55;
    public static final int COLOR_BOX_CREATE_OVERSIZED_STROKE = 0xFFFF3300;
    public static final int COLOR_BOX_CREATE_OVERSIZED_FILL = 0x4DFF3300;
    public static final float WIDTH_BOX_CREATE_STROKE = 3.5f;
    public static final int COLOR_BOX_CREATE_CORNER_START = 0xFF00FF55;
    public static final int COLOR_BOX_CREATE_CORNER_END = 0xFFFFFF55;
    public static final int COLOR_BOX_CREATE_CORNER_END_OVERSIZED = 0xFFFF7755;
    public static final float CORNER_POINT_SIZE = 8.0f;

    // === Gizmo Coordinates & Handles ===
    public static final int COLOR_HANDLE_X = 0xFFFF3333; // Red
    public static final int COLOR_HANDLE_Y = 0xFF33FF33; // Green
    public static final int COLOR_HANDLE_Z = 0xFF3388FF; // Blue
    public static final int COLOR_HANDLE_CENTER = 0xFFFFCC00; // Gold
    public static final int COLOR_HANDLE_HIGHLIGHT = 0xFFFFFF55; // Bright Yellow
    public static final int COLOR_HANDLE_POS1 = 0xFF00FFCC; // Cyan-Green
    public static final int COLOR_HANDLE_POS2 = 0xFFFF00CC; // Magenta
    public static final float HANDLE_AXIS_RATIO = 0.15f;
    public static final float HANDLE_CENTER_RATIO = 0.018f;

    // ARGB Component helpers (returns 0.0f - 1.0f)
    public static float a(int argb) { return ((argb >> 24) & 0xFF) / 255.0f; }
    public static float r(int argb) { return ((argb >> 16) & 0xFF) / 255.0f; }
    public static float g(int argb) { return ((argb >> 8) & 0xFF) / 255.0f; }
    public static float b(int argb) { return (argb & 0xFF) / 255.0f; }

    private SelectionStyle() {}
}
