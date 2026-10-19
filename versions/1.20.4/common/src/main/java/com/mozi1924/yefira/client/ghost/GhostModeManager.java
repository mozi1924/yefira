package com.mozi1924.yefira.client.ghost;

import com.mojang.blaze3d.platform.InputConstants;
import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class GhostModeManager {
    private static final GhostModeManager INSTANCE = new GhostModeManager();

    public static GhostModeManager getInstance() {
        return INSTANCE;
    }

    private boolean active = false;

    // Camera transform
    private Vec3 cameraPos = Vec3.ZERO;
    private float yaw = 0.0f;
    private float pitch = 0.0f;

    // DCC Navigation state
    private boolean isMmbOrbiting = false;
    private boolean isMmbPanning = false;
    private boolean isMmbZooming = false;
    private Vec3 pivotPos = Vec3.ZERO;

    // Mouse tracking for free cursor mode
    private double lastMouseX = -1.0;
    private double lastMouseY = -1.0;

    // Gizmo interaction constants
    public static final int CORNER_NONE = 0;
    public static final int CORNER_POS1 = 1;
    public static final int CORNER_POS2 = 2;
    public static final int CORNER_CENTER = 3;

    public static final int AXIS_NONE = -1;
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;
    public static final int AXIS_CENTER = 3;

    private int hoveredCorner = CORNER_NONE;
    private int hoveredAxis = AXIS_NONE;

    private int draggingCorner = CORNER_NONE;
    private int draggingAxis = AXIS_NONE;

    private BlockPos initialPos1 = null;
    private BlockPos initialPos2 = null;
    private Vec3 dragStartOrigin = null;
    private Vec3 dragStartHitPoint = null;
    private double dragStartParam = 0.0;

    // Direct Block Raycasting & Box Creation
    private BlockPos hoveredBlockPos = null;
    private boolean isBoxCreating = false;
    private BlockPos boxCreateStartPos = null;
    private BlockPos boxCreateCurrentPos = null;

    public boolean isActive() {
        return active;
    }

    public BlockPos getHoveredBlockPos() {
        return hoveredBlockPos;
    }

    public boolean isBoxCreating() {
        return active && isBoxCreating;
    }

    public SelectionBox getBoxCreateSelection() {
        if (isBoxCreating && boxCreateStartPos != null && boxCreateCurrentPos != null) {
            return new SelectionBox(boxCreateStartPos, boxCreateCurrentPos);
        }
        return null;
    }

    public void toggle() {
        if (active) {
            disable();
        } else {
            enable();
        }
    }

    public void enable() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::enable);
            return;
        }
        if (mc.player == null || mc.level == null) return;

        Player player = mc.player;
        // The server-side Yefira mod anchors and protects the body (survival
        // included). If the server does not have Yefira installed this command
        // fails in chat, but the camera itself still works.
        if (mc.player.connection != null) {
            try {
                mc.player.connection.sendCommand("yefira ghost on");
            } catch (Exception e) {
                Yefira.LOGGER.debug("Failed to request ghost anchor: {}", e.getMessage());
            }
        }
        if (!mc.hasSingleplayerServer() && !player.isCreative() && !player.isSpectator()) {
            player.displayClientMessage(Component.translatable("yefira.ghost.anchor_hint"), false);
        }

        this.active = true;
        this.cameraPos = player.getEyePosition();
        this.yaw = player.getYRot();
        this.pitch = player.getXRot();

        initPivotOnEnable(player);

        this.isMmbOrbiting = false;
        this.isMmbPanning = false;
        this.isMmbZooming = false;
        this.hoveredCorner = CORNER_NONE;
        this.hoveredAxis = AXIS_NONE;
        this.draggingCorner = CORNER_NONE;
        this.draggingAxis = AXIS_NONE;
        this.dragStartOrigin = null;
        this.hoveredBlockPos = null;
        this.isBoxCreating = false;
        this.boxCreateStartPos = null;
        this.boxCreateCurrentPos = null;
        this.lastMouseX = mc.mouseHandler.xpos();
        this.lastMouseY = mc.mouseHandler.ypos();

        // Release mouse cursor for DCC interaction
        mc.mouseHandler.releaseMouse();
        Yefira.LOGGER.info("Ghost Mode ENABLED at {} with Pivot at {}", cameraPos, pivotPos);
    }

    public void disable() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::disable);
            return;
        }
        this.active = false;
        this.isMmbOrbiting = false;
        this.isMmbPanning = false;
        this.isMmbZooming = false;
        this.draggingCorner = CORNER_NONE;
        this.draggingAxis = AXIS_NONE;
        this.dragStartOrigin = null;
        this.hoveredBlockPos = null;
        this.isBoxCreating = false;
        this.boxCreateStartPos = null;
        this.boxCreateCurrentPos = null;

        if (mc.player != null && mc.player.connection != null) {
            try {
                mc.player.connection.sendCommand("yefira ghost off");
            } catch (Exception e) {
                Yefira.LOGGER.debug("Failed to release ghost anchor: {}", e.getMessage());
            }
        }

        // Only recapture the cursor when no screen is open. On disconnect the
        // disconnect screen is already visible, and grabbing the mouse there
        // steals it from the UI.
        if (mc.screen == null) {
            mc.mouseHandler.grabMouse();
        }
        Yefira.LOGGER.info("Ghost Mode DISABLED");
    }

    private void initPivotOnEnable(Player player) {
        Vec3 look = getForwardVector(yaw, pitch);
        Vec3 maxReach = cameraPos.add(look.scale(128.0));
        net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
            cameraPos, maxReach,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player
        );
        net.minecraft.world.phys.BlockHitResult hit = player.level().clip(clipContext);
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            this.pivotPos = hit.getLocation();
        } else {
            SelectionManager mgr = SelectionManager.getInstance();
            if (mgr.hasSelection()) {
                this.pivotPos = mgr.getCurrentSelection().getCenter();
            } else {
                this.pivotPos = cameraPos.add(look.scale(10.0));
            }
        }
    }

    public void recalculatePivotFromLook() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 look = getForwardVector(yaw, pitch);
        if (mc.level != null && mc.player != null) {
            Vec3 maxReach = cameraPos.add(look.scale(128.0));
            net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
                cameraPos, maxReach,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                mc.player
            );
            net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(clipContext);
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                this.pivotPos = hit.getLocation();
                return;
            }
        }
        this.pivotPos = cameraPos.add(look.scale(10.0));
    }

    public void focusSelection() {
        SelectionManager mgr = SelectionManager.getInstance();
        if (mgr.hasSelection()) {
            this.pivotPos = mgr.getCurrentSelection().getCenter();
            SelectionBox sel = mgr.getCurrentSelection();
            double radius = Math.max(5.0, Math.max(sel.getSizeX(), Math.max(sel.getSizeY(), sel.getSizeZ())) * 1.5);
            Vec3 look = getForwardVector(yaw, pitch);
            this.cameraPos = this.pivotPos.subtract(look.scale(radius));
            Yefira.LOGGER.info("Ghost Mode focused on selection: center={}, dist={}", pivotPos, radius);
        }
    }

    public Vec3 getCameraPos() {
        return cameraPos;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public int getHoveredCorner() {
        return hoveredCorner;
    }

    public int getHoveredAxis() {
        return hoveredAxis;
    }

    public int getDraggingCorner() {
        return draggingCorner;
    }

    public int getDraggingAxis() {
        return draggingAxis;
    }

    public boolean isDragging() {
        return active && draggingCorner != CORNER_NONE;
    }

    public BlockPos getEffectivePos1() {
        if (isDragging() && dragPreviewPos1 != null) {
            return dragPreviewPos1;
        }
        return SelectionManager.getInstance().getPos1();
    }

    public BlockPos getEffectivePos2() {
        if (isDragging() && dragPreviewPos2 != null) {
            return dragPreviewPos2;
        }
        return SelectionManager.getInstance().getPos2();
    }

    public SelectionBox getDragPreviewSelection() {
        if (isDragging() && dragPreviewPos1 != null && dragPreviewPos2 != null) {
            return new SelectionBox(dragPreviewPos1, dragPreviewPos2);
        }
        return null;
    }

    /**
     * Called on client tick for Ghost Mode.
     */
    public void tickMovement() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            disable();
            return;
        }
        if (mc.screen != null) return;

        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
        if (draggingCorner == CORNER_NONE) {
            updateGizmoHover();
        }
    }

    /**
     * Called on mouse move in Ghost Mode
     */
    public void onMouseMove(double mouseX, double mouseY) {
        if (!active) return;

        if (lastMouseX < 0 || lastMouseY < 0) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return;
        }

        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (isMmbOrbiting) {
            // Natural Viewport Orbit:
            // Drag LEFT (dx < 0) -> view turns LEFT (yaw decreases)
            // Drag RIGHT (dx > 0) -> view turns RIGHT (yaw increases)
            // Drag UP (dy < 0) -> view tilts UP (pitch decreases)
            // Drag DOWN (dy > 0) -> view tilts DOWN (pitch increases)
            yaw += (float) (dx * 0.35);
            pitch += (float) (dy * 0.35);
            pitch = Math.max(-89.9f, Math.min(89.9f, pitch));

            double dist = cameraPos.distanceTo(pivotPos);
            if (dist < 0.5) dist = 0.5;
            Vec3 look = getForwardVector(yaw, pitch);
            cameraPos = pivotPos.subtract(look.scale(dist));
        } else if (isMmbPanning) {
            // Natural Viewport Pan: Shift + MMB drag
            Vec3 right = getRightVector(yaw);
            Vec3 up = getUpVector(yaw, pitch);

            double dist = cameraPos.distanceTo(pivotPos);
            double panScale = 0.002 * Math.max(2.0, dist);
            // Drag RIGHT (dx > 0) -> scene shifts right (camera moves left)
            // Drag UP (dy < 0) -> scene shifts up (camera moves down)
            Vec3 delta = right.scale(-dx * panScale).add(up.scale(dy * panScale));
            cameraPos = cameraPos.add(delta);
            pivotPos = pivotPos.add(delta);
        } else if (isMmbZooming) {
            // Natural Viewport Zoom: Ctrl + MMB drag
            // Drag UP (dy < 0) -> Zoom IN
            // Drag DOWN (dy > 0) -> Zoom OUT
            double dist = cameraPos.distanceTo(pivotPos);
            double zoomFactor = Math.exp(dy * 0.008);
            double newDist = Math.max(0.5, Math.min(500.0, dist * zoomFactor));
            Vec3 look = getForwardVector(yaw, pitch);
            cameraPos = pivotPos.subtract(look.scale(newDist));
        } else if (isBoxCreating) {
            net.minecraft.world.phys.BlockHitResult hit = raycastBlockFromMouse(256.0);
            if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                boxCreateCurrentPos = hit.getBlockPos();
            }
        } else if (draggingCorner != CORNER_NONE && draggingAxis != AXIS_NONE) {
            handleGizmoDrag();
        } else {
            updateGizmoHover();
        }
    }

    /**
     * Mouse button event handler. Returns true if consumed.
     */
    public boolean onMouseButton(int button, int action) {
        if (!active) return false;
        Minecraft mc = Minecraft.getInstance();

        // Free Cursor mode interaction
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action == GLFW.GLFW_PRESS) {
                lastMouseX = mc.mouseHandler.xpos();
                lastMouseY = mc.mouseHandler.ypos();

                long window = mc.getWindow().getWindow();
                boolean shiftHeld = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) ||
                                    InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
                boolean ctrlHeld = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
                                   InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);

                if (ctrlHeld) {
                    isMmbZooming = true;
                } else if (shiftHeld) {
                    isMmbPanning = true;
                } else {
                    isMmbOrbiting = true;
                }
                return true;
            } else if (action == GLFW.GLFW_RELEASE) {
                isMmbOrbiting = false;
                isMmbPanning = false;
                isMmbZooming = false;
                return true;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS) {
                // Perform fresh hover hit-test on click
                updateGizmoHover();
                if (hoveredCorner != CORNER_NONE && hoveredAxis != AXIS_NONE) {
                    startGizmoDrag(hoveredCorner, hoveredAxis);
                    return true;
                } else if (!SelectionManager.getInstance().hasSelection() && hoveredBlockPos != null) {
                    // Anti-accidental touch: Start 3D Drag-to-Select ONLY when no active selection exists
                    isBoxCreating = true;
                    boxCreateStartPos = hoveredBlockPos;
                    boxCreateCurrentPos = hoveredBlockPos;
                    return true;
                }
            } else if (action == GLFW.GLFW_RELEASE) {
                if (draggingCorner != CORNER_NONE) {
                    finishGizmoDrag();
                    return true;
                }
                if (isBoxCreating) {
                    if (boxCreateStartPos != null && boxCreateCurrentPos != null && mc.level != null) {
                        SelectionManager.getInstance().setPositions(selectionLevel(), boxCreateStartPos, boxCreateCurrentPos);
                        syncSelectionToServer(boxCreateStartPos, boxCreateCurrentPos);
                    }
                    isBoxCreating = false;
                    boxCreateStartPos = null;
                    boxCreateCurrentPos = null;
                    return true;
                }
            }
        }

        // Right-click: cancel drag if active, otherwise consumed
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (action == GLFW.GLFW_PRESS) {
                if (isBoxCreating) {
                    // Cancel active box drag selection
                    cancelBoxDrag();
                    return true;
                }
            }
            return true;
        }

        return true;
    }


    /** Apply the current local selection on the authoritative server. */
    private void syncSelectionToServer(BlockPos p1, BlockPos p2) {
        if (p1 == null || p2 == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        // Local export mode keeps the selection in the client's loaded chunks;
        // only server/team mode pushes it to the server (and only in multiplayer,
        // where the integrated server has not already been updated directly).
        if (SelectionManager.getInstance().getSelectionSource()
                != SelectionManager.SelectionSource.SERVER_AUTHORITATIVE) {
            return;
        }
        if (mc.hasSingleplayerServer()) {
            return;
        }
        try {
            mc.player.connection.sendCommand(
                "yefira box " + p1.getX() + " " + p1.getY() + " " + p1.getZ()
                    + " " + p2.getX() + " " + p2.getY() + " " + p2.getZ());
        } catch (Exception e) {
            Yefira.LOGGER.debug("Failed to sync selection to server: {}", e.getMessage());
        }
    }

    /** Clear both the local preview and the server-authoritative selection. */
    public void clearSelectionAndSync() {
        boolean serverSelection = SelectionManager.getInstance().getSelectionSource()
                == SelectionManager.SelectionSource.SERVER_AUTHORITATIVE;
        SelectionManager.getInstance().clearSelection();
        Minecraft mc = Minecraft.getInstance();
        if (!serverSelection || mc.hasSingleplayerServer()) return;
        if (mc.player == null || mc.player.connection == null) return;
        try {
            mc.player.connection.sendCommand("yefira clear");
        } catch (Exception e) {
            Yefira.LOGGER.debug("Failed to clear server selection: {}", e.getMessage());
        }
    }

    /** In singleplayer the integrated server owns the authoritative Level. */
    private Level selectionLevel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null && mc.level != null) {
            ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
            if (serverLevel != null) {
                return serverLevel;
            }
        }
        return mc.level;
    }

    public void cancelBoxDrag() {
        if (isBoxCreating) {
            isBoxCreating = false;
            boxCreateStartPos = null;
            boxCreateCurrentPos = null;
        }
    }

    /**
     * Mouse scroll event handler. Returns true if consumed.
     */
    public boolean onMouseScroll(double yoffset) {
        if (!active) return false;

        // Zoom towards pivot
        double dist = cameraPos.distanceTo(pivotPos);
        double zoomFactor = yoffset > 0 ? 0.85 : 1.15;
        double newDist = Math.max(0.5, Math.min(500.0, dist * zoomFactor));
        Vec3 look = getForwardVector(yaw, pitch);
        cameraPos = pivotPos.subtract(look.scale(newDist));
        return true;
    }

    // ==========================================
    // Minecraft Camera Coordinate Basis Vectors
    // ==========================================

    public static Vec3 getForwardVector(float yRot, float xRot) {
        float pitchRad = (float) Math.toRadians(xRot);
        float yawRad = (float) Math.toRadians(yRot);
        return new Vec3(
            -Math.sin(yawRad) * Math.cos(pitchRad),
            -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)
        ).normalize();
    }

    public static Vec3 getRightVector(float yRot) {
        float yawRad = (float) Math.toRadians(yRot);
        // In Minecraft coords: looking South (yaw=0), East is Left (+X), West is Right (-X)
        // When facing yaw, Right is (-cos(yaw), 0, -sin(yaw))
        return new Vec3(
            -Math.cos(yawRad),
            0,
            -Math.sin(yawRad)
        ).normalize();
    }

    public static Vec3 getUpVector(float yRot, float xRot) {
        Vec3 forward = getForwardVector(yRot, xRot);
        Vec3 right = getRightVector(yRot);
        return right.cross(forward).normalize();
    }

    // ==========================================================
    // Screen Projection & Robust DCC Plane Gizmo Dragging Math
    // ==========================================================

    private record Ray(Vec3 origin, Vec3 dir) {}

    private Ray getMouseRay() {
        Minecraft mc = Minecraft.getInstance();
        return getScreenRay(mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
    }

    private Ray getScreenRay(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();

        if (width <= 0 || height <= 0) {
            return new Ray(cameraPos, getForwardVector(yaw, pitch));
        }

        float nx = (float) ((2.0 * mouseX / width) - 1.0);
        float ny = (float) (1.0 - (2.0 * mouseY / height));

        float fov = (float) mc.options.fov().get().intValue();

        float aspect = (float) width / (float) height;
        float tanHalfFovY = (float) Math.tan(Math.toRadians(fov / 2.0));
        float tanHalfFovX = tanHalfFovY * aspect;

        Vec3 forward = getForwardVector(yaw, pitch);
        Vec3 right = getRightVector(yaw);
        Vec3 up = getUpVector(yaw, pitch);

        Vec3 rayDir = forward.add(right.scale(nx * tanHalfFovX)).add(up.scale(ny * tanHalfFovY)).normalize();
        return new Ray(cameraPos, rayDir);
    }

    /**
     * Projects a 3D world position to 2D Screen pixel coordinates.
     * Returns null if point is behind the camera.
     */
    private Vec2 projectToScreen(Vec3 worldPos) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();
        if (width <= 0 || height <= 0) return null;

        Vec3 forward = getForwardVector(yaw, pitch);
        Vec3 right = getRightVector(yaw);
        Vec3 up = getUpVector(yaw, pitch);

        Vec3 rel = worldPos.subtract(cameraPos);
        double depth = rel.dot(forward);
        if (depth <= 0.1) {
            return null; // Behind camera
        }

        float fov = (float) mc.options.fov().get().intValue();

        float aspect = (float) width / (float) height;
        float tanHalfFovY = (float) Math.tan(Math.toRadians(fov / 2.0));
        float tanHalfFovX = tanHalfFovY * aspect;

        double xView = rel.dot(right);
        double yView = rel.dot(up);

        double ndcX = xView / (depth * tanHalfFovX);
        double ndcY = yView / (depth * tanHalfFovY);

        float screenX = (float) ((ndcX + 1.0) / 2.0 * width);
        float screenY = (float) ((1.0 - ndcY) / 2.0 * height);

        return new Vec2(screenX, screenY);
    }

    private double distancePointToSegment2D(double px, double py, Vec2 segStart, Vec2 segEnd) {
        double vx = segEnd.x - segStart.x;
        double vy = segEnd.y - segStart.y;
        double wx = px - segStart.x;
        double wy = py - segStart.y;

        double lenSq = vx * vx + vy * vy;
        if (lenSq < 1e-6) {
            return Math.hypot(px - segStart.x, py - segStart.y);
        }

        double t = (wx * vx + wy * vy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double projX = segStart.x + t * vx;
        double projY = segStart.y + t * vy;

        return Math.hypot(px - projX, py - projY);
    }

    /**
     * Screen-space hit test: 24-pixel threshold makes clicking super reliable at any distance.
     */
    private void updateGizmoHover() {
        BlockPos pos1 = getEffectivePos1();
        BlockPos pos2 = getEffectivePos2();

        Minecraft mc = Minecraft.getInstance();
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();

        double closestDist = 24.0; // 24 pixel hit radius
        int bestCorner = CORNER_NONE;
        int bestAxis = AXIS_NONE;

        // Test Pos1
        if (pos1 != null) {
            Vec3 origin1 = new Vec3(pos1.getX() + 0.5, pos1.getY() + 0.5, pos1.getZ() + 0.5);
            Vec2 screenOrigin = projectToScreen(origin1);

            if (screenOrigin != null) {
                double dist1 = Math.max(0.5, origin1.distanceTo(cameraPos));
                float axisLength1 = (float) (dist1 * 0.15);

                double centerDist = Math.hypot(mouseX - screenOrigin.x, mouseY - screenOrigin.y);
                if (centerDist < 20.0 && centerDist < closestDist) {
                    closestDist = centerDist;
                    bestCorner = CORNER_POS1;
                    bestAxis = AXIS_CENTER;
                }

                for (int axis = 0; axis < 3; axis++) {
                    Vec3 axisEnd = origin1.add(getAxisDirection(axis).scale(axisLength1));
                    Vec2 screenEnd = projectToScreen(axisEnd);
                    if (screenEnd != null) {
                        double d = distancePointToSegment2D(mouseX, mouseY, screenOrigin, screenEnd);
                        if (d < 18.0 && d < closestDist) {
                            closestDist = d;
                            bestCorner = CORNER_POS1;
                            bestAxis = axis;
                        }
                    }
                }
            }
        }

        // Test Pos2
        if (pos2 != null) {
            Vec3 origin2 = new Vec3(pos2.getX() + 0.5, pos2.getY() + 0.5, pos2.getZ() + 0.5);
            Vec2 screenOrigin = projectToScreen(origin2);

            if (screenOrigin != null) {
                double dist2 = Math.max(0.5, origin2.distanceTo(cameraPos));
                float axisLength2 = (float) (dist2 * 0.15);

                double centerDist = Math.hypot(mouseX - screenOrigin.x, mouseY - screenOrigin.y);
                if (centerDist < 20.0 && centerDist < closestDist) {
                    closestDist = centerDist;
                    bestCorner = CORNER_POS2;
                    bestAxis = AXIS_CENTER;
                }

                for (int axis = 0; axis < 3; axis++) {
                    Vec3 axisEnd = origin2.add(getAxisDirection(axis).scale(axisLength2));
                    Vec2 screenEnd = projectToScreen(axisEnd);
                    if (screenEnd != null) {
                        double d = distancePointToSegment2D(mouseX, mouseY, screenOrigin, screenEnd);
                        if (d < 18.0 && d < closestDist) {
                            closestDist = d;
                            bestCorner = CORNER_POS2;
                            bestAxis = axis;
                        }
                    }
                }
            }
        }

        // Test Center bounding box move gizmo
        if (pos1 != null && pos2 != null) {
            Vec3 center = new Vec3(
                (pos1.getX() + pos2.getX() + 1) / 2.0,
                (pos1.getY() + pos2.getY() + 1) / 2.0,
                (pos1.getZ() + pos2.getZ() + 1) / 2.0
            );
            Vec2 screenCenter = projectToScreen(center);

            if (screenCenter != null) {
                double distCenter = Math.max(0.5, center.distanceTo(cameraPos));
                float axisLengthCenter = (float) (distCenter * 0.15);

                double centerDist = Math.hypot(mouseX - screenCenter.x, mouseY - screenCenter.y);
                if (centerDist < 22.0 && centerDist < closestDist) {
                    closestDist = centerDist;
                    bestCorner = CORNER_CENTER;
                    bestAxis = AXIS_CENTER;
                }

                for (int axis = 0; axis < 3; axis++) {
                    Vec3 axisEnd = center.add(getAxisDirection(axis).scale(axisLengthCenter));
                    Vec2 screenEnd = projectToScreen(axisEnd);
                    if (screenEnd != null) {
                        double d = distancePointToSegment2D(mouseX, mouseY, screenCenter, screenEnd);
                        if (d < 18.0 && d < closestDist) {
                            closestDist = d;
                            bestCorner = CORNER_CENTER;
                            bestAxis = axis;
                        }
                    }
                }
            }
        }

        hoveredCorner = bestCorner;
        hoveredAxis = bestAxis;

        if (hoveredCorner == CORNER_NONE) {
            if (!SelectionManager.getInstance().hasSelection()) {
                net.minecraft.world.phys.BlockHitResult hit = raycastBlockFromMouse(256.0);
                if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    hoveredBlockPos = hit.getBlockPos();
                } else {
                    hoveredBlockPos = null;
                }
            } else {
                hoveredBlockPos = null;
            }
        } else {
            hoveredBlockPos = null;
        }
    }

    public net.minecraft.world.phys.BlockHitResult raycastBlockFromMouse(double maxDist) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        Ray ray = getMouseRay();
        Vec3 end = ray.origin().add(ray.dir().scale(maxDist));
        net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
            ray.origin(), end,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            mc.player
        );
        return mc.level.clip(clipContext);
    }

    public void createPresetBoxAtCursorOrPivot(int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockPos basePos;
        if (hoveredBlockPos != null) {
            basePos = hoveredBlockPos;
        } else {
            basePos = BlockPos.containing(pivotPos);
        }
        SelectionBox preset = SelectionBox.presetAbove(basePos, size);
        SelectionManager.getInstance().setPositions(mc.level, preset.getPos1(), preset.getPos2());
        syncSelectionToServer(preset.getPos1(), preset.getPos2());
        focusSelection();
    }

    private BlockPos dragPreviewPos1 = null;
    private BlockPos dragPreviewPos2 = null;

    private void startGizmoDrag(int corner, int axis) {
        SelectionManager mgr = SelectionManager.getInstance();
        this.draggingCorner = corner;
        this.draggingAxis = axis;
        this.initialPos1 = mgr.getPos1();
        this.initialPos2 = mgr.getPos2();
        this.dragPreviewPos1 = this.initialPos1;
        this.dragPreviewPos2 = this.initialPos2;

        Vec3 origin = getGizmoOrigin(corner);
        if (origin != null) {
            this.dragStartOrigin = origin;
            Ray ray = getMouseRay();
            if (axis == AXIS_CENTER) {
                this.dragStartHitPoint = intersectRayWithCameraPlane(ray, this.dragStartOrigin);
            } else {
                Vec3 axisDir = getAxisDirection(axis);
                this.dragStartParam = intersectRayWithAxisPlane(ray, this.dragStartOrigin, axisDir);
            }
        }
    }

    private void handleGizmoDrag() {
        if (draggingCorner == CORNER_NONE || draggingAxis == AXIS_NONE || dragStartOrigin == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Ray ray = getMouseRay();

        if (draggingAxis == AXIS_CENTER) {
            // Dragging on camera plane
            Vec3 hit = intersectRayWithCameraPlane(ray, dragStartOrigin);
            if (hit == null || dragStartHitPoint == null) return;

            Vec3 offset = hit.subtract(dragStartHitPoint);
            int dx = (int) Math.round(offset.x);
            int dy = (int) Math.round(offset.y);
            int dz = (int) Math.round(offset.z);

            if (draggingCorner == CORNER_POS1 && initialPos1 != null) {
                dragPreviewPos1 = initialPos1.offset(dx, dy, dz);
            } else if (draggingCorner == CORNER_POS2 && initialPos2 != null) {
                dragPreviewPos2 = initialPos2.offset(dx, dy, dz);
            } else if (draggingCorner == CORNER_CENTER && initialPos1 != null && initialPos2 != null) {
                dragPreviewPos1 = initialPos1.offset(dx, dy, dz);
                dragPreviewPos2 = initialPos2.offset(dx, dy, dz);
            }
        } else {
            // Dragging along single axis (X, Y, or Z) using DCC plane projection
            Vec3 axisDir = getAxisDirection(draggingAxis);
            double currentParam = intersectRayWithAxisPlane(ray, dragStartOrigin, axisDir);
            double deltaParam = currentParam - dragStartParam;
            int blockDelta = (int) Math.round(deltaParam);

            int dx = (draggingAxis == AXIS_X) ? blockDelta : 0;
            int dy = (draggingAxis == AXIS_Y) ? blockDelta : 0;
            int dz = (draggingAxis == AXIS_Z) ? blockDelta : 0;

            if (draggingCorner == CORNER_POS1 && initialPos1 != null) {
                dragPreviewPos1 = initialPos1.offset(dx, dy, dz);
            } else if (draggingCorner == CORNER_POS2 && initialPos2 != null) {
                dragPreviewPos2 = initialPos2.offset(dx, dy, dz);
            } else if (draggingCorner == CORNER_CENTER && initialPos1 != null && initialPos2 != null) {
                dragPreviewPos1 = initialPos1.offset(dx, dy, dz);
                dragPreviewPos2 = initialPos2.offset(dx, dy, dz);
            }
        }
    }

    private void finishGizmoDrag() {
        if (draggingCorner != CORNER_NONE) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && dragPreviewPos1 != null && dragPreviewPos2 != null) {
                if (!dragPreviewPos1.equals(initialPos1) || !dragPreviewPos2.equals(initialPos2)) {
                    SelectionManager.getInstance().setPositions(selectionLevel(), dragPreviewPos1, dragPreviewPos2);
                    syncSelectionToServer(dragPreviewPos1, dragPreviewPos2);
                }
            }
        }
        this.draggingCorner = CORNER_NONE;
        this.draggingAxis = AXIS_NONE;
        this.initialPos1 = null;
        this.initialPos2 = null;
        this.dragPreviewPos1 = null;
        this.dragPreviewPos2 = null;
        this.dragStartHitPoint = null;
        this.dragStartOrigin = null;
    }

    private Vec3 getGizmoOrigin(int corner) {
        SelectionManager mgr = SelectionManager.getInstance();
        BlockPos p1 = (draggingCorner != CORNER_NONE && dragPreviewPos1 != null) ? dragPreviewPos1 : mgr.getPos1();
        BlockPos p2 = (draggingCorner != CORNER_NONE && dragPreviewPos2 != null) ? dragPreviewPos2 : mgr.getPos2();

        if (corner == CORNER_POS1 && p1 != null) {
            return new Vec3(p1.getX() + 0.5, p1.getY() + 0.5, p1.getZ() + 0.5);
        } else if (corner == CORNER_POS2 && p2 != null) {
            return new Vec3(p2.getX() + 0.5, p2.getY() + 0.5, p2.getZ() + 0.5);
        } else if (corner == CORNER_CENTER && p1 != null && p2 != null) {
            BlockPos min = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ())
            );
            BlockPos max = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ())
            );
            return new Vec3(
                (min.getX() + max.getX() + 1) / 2.0,
                (min.getY() + max.getY() + 1) / 2.0,
                (min.getZ() + max.getZ() + 1) / 2.0
            );
        }
        return null;
    }

    public static Vec3 getAxisDirection(int axis) {
        return switch (axis) {
            case AXIS_X -> new Vec3(1, 0, 0);
            case AXIS_Y -> new Vec3(0, 1, 0);
            case AXIS_Z -> new Vec3(0, 0, 1);
            default -> new Vec3(0, 1, 0);
        };
    }

    /**
     * Intersects ray with a plane containing the axis line and facing the camera.
     * Returns parameter t along the axis.
     */
    private double intersectRayWithAxisPlane(Ray ray, Vec3 axisOrigin, Vec3 axisDir) {
        Vec3 camLook = getForwardVector(yaw, pitch);
        // Plane normal containing axisDir, facing camera
        Vec3 normal = axisDir.cross(camLook).cross(axisDir);
        if (normal.lengthSqr() < 1e-6) {
            normal = camLook;
        } else {
            normal = normal.normalize();
        }

        double denom = ray.dir.dot(normal);
        if (Math.abs(denom) < 1e-6) {
            return 0.0;
        }

        double t = axisOrigin.subtract(ray.origin).dot(normal) / denom;
        Vec3 hitPoint = ray.origin.add(ray.dir.scale(t));
        return hitPoint.subtract(axisOrigin).dot(axisDir);
    }

    /**
     * Intersects ray with a plane passing through origin and facing camera.
     */
    private Vec3 intersectRayWithCameraPlane(Ray ray, Vec3 planeOrigin) {
        Vec3 normal = getForwardVector(yaw, pitch);
        double denom = ray.dir.dot(normal);
        if (Math.abs(denom) < 1e-6) {
            return planeOrigin;
        }
        double t = planeOrigin.subtract(ray.origin).dot(normal) / denom;
        return ray.origin.add(ray.dir.scale(t));
    }
}
