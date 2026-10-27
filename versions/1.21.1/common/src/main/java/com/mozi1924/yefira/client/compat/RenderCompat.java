package com.mozi1924.yefira.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Compatibility layer for Minecraft 1.21.x rendering pipeline.
 */
public class RenderCompat {

    /**
     * Draws a line segment using Minecraft 1.21.x VertexConsumer API with auto-computed normal.
     */
    public static void drawGizmoLine(PoseStack poseStack, VertexConsumer lines,
                                     Vec3 p1, Vec3 p2,
                                     float r, float g, float b, float a) {
        float dx = (float) (p2.x - p1.x);
        float dy = (float) (p2.y - p1.y);
        float dz = (float) (p2.z - p1.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-5f) {
            dx /= len;
            dy /= len;
            dz /= len;
        } else {
            dy = 1.0f;
        }
        drawGizmoLine(poseStack, lines, p1, p2, r, g, b, a, dx, dy, dz);
    }

    /**
     * Draws a line segment using Minecraft 1.21.x VertexConsumer API.
     */
    public static void drawGizmoLine(PoseStack poseStack, VertexConsumer lines,
                                     Vec3 p1, Vec3 p2,
                                     float r, float g, float b, float a,
                                     float dx, float dy, float dz) {
        lines.addVertex(poseStack.last(), (float) p1.x, (float) p1.y, (float) p1.z)
             .setColor(r, g, b, a)
             .setNormal(poseStack.last(), dx, dy, dz);
        lines.addVertex(poseStack.last(), (float) p2.x, (float) p2.y, (float) p2.z)
             .setColor(r, g, b, a)
             .setNormal(poseStack.last(), dx, dy, dz);
    }

    /**
     * Renders a wireframe box.
     */
    public static void renderLineBox(PoseStack poseStack, VertexConsumer lineBuffer, AABB box,
                                     float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(poseStack, lineBuffer, box, r, g, b, a);
    }

    /**
     * Renders a translucent filled box.
     */
    public static void renderFilledBox(PoseStack poseStack, MultiBufferSource bufferSource, AABB box,
                                       float r, float g, float b, float a) {
        DebugRenderer.renderFilledBox(poseStack, bufferSource, box, r, g, b, a);
    }

    /**
     * Renders a translucent selection filled box (depth test on, depth write disabled, no backface culling).
     */
    public static void renderSelectionFilledBox(PoseStack poseStack, MultiBufferSource bufferSource, AABB box,
                                                float r, float g, float b, float a) {
        VertexConsumer consumer = bufferSource.getBuffer(com.mozi1924.yefira.client.render.YefiraRenderTypes.selectionFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(
            poseStack, consumer,
            box.minX, box.minY, box.minZ,
            box.maxX, box.maxY, box.maxZ,
            r, g, b, a
        );
    }

    /**
     * Renders an Always-On-Top translucent filled box (no depth test).
     */
    public static void renderGizmoFilledBox(PoseStack poseStack, MultiBufferSource bufferSource, AABB box,
                                           float r, float g, float b, float a) {
        VertexConsumer consumer = bufferSource.getBuffer(com.mozi1924.yefira.client.render.YefiraRenderTypes.gizmoFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(
            poseStack, consumer,
            box.minX, box.minY, box.minZ,
            box.maxX, box.maxY, box.maxZ,
            r, g, b, a
        );
    }

    /**
     * Flushes the last batch in bufferSource immediately.
     */
    public static void endLastBatch(MultiBufferSource bufferSource) {
        if (bufferSource instanceof MultiBufferSource.BufferSource bs) {
            bs.endLastBatch();
        }
    }
}
