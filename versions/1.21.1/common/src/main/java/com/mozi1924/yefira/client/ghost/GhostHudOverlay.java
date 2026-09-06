package com.mozi1924.yefira.client.ghost;

import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class GhostHudOverlay {

    public static void renderOverlay(GuiGraphics graphics) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        if (!ghost.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        // Top Status Bar
        int barHeight = 22;
        graphics.fill(0, 0, screenWidth, barHeight, 0xB0000000); // 70% dark background
        int accentColor = 0xFF00FFCC; // Cyan accent
        graphics.fill(0, barHeight - 1, screenWidth, barHeight, accentColor);

        // Title
        Component titleComp = Component.translatable("yefira.hud.title.ghost");
        int titleColor = 0xFF00FFFF;
        graphics.drawString(font, titleComp, 8, 6, titleColor, true);

        // Server Status & Fly Speed (top right)
        WebSocketServerManager ws = WebSocketServerManager.getInstance();
        Component serverStatusComp = ws.isRunning()
            ? Component.translatable("yefira.hud.server.running", ws.getConnectedCount())
            : Component.translatable("yefira.hud.server.stopped");
        int statusColor = ws.isRunning() ? 0x55FF55 : 0xFF7777;
        int statusWidth = font.width(serverStatusComp);
        graphics.drawString(font, serverStatusComp, screenWidth - statusWidth - 8, 6, statusColor, true);

        // Control Hints (centered if space permits, avoiding overlap with title)
        Component hintsComp = Component.translatable("yefira.hud.hints.ghost");
        int titleRight = 8 + font.width(titleComp) + 12;
        int maxRight = screenWidth - statusWidth - 12;
        int hintsWidth = font.width(hintsComp);
        int hintsX = (screenWidth - hintsWidth) / 2;
        if (hintsX < titleRight) {
            hintsX = titleRight;
        }
        if (hintsX + hintsWidth <= maxRight) {
            graphics.drawString(font, hintsComp, hintsX, 6, 0xFFE0E0E0, true);
        }

        // Selection & Dragging details (bottom left)
        SelectionManager mgr = SelectionManager.getInstance();
        int bottomY = mc.getWindow().getGuiScaledHeight() - 25;

        if (ghost.isBoxCreating()) {
            SelectionBox sel = ghost.getBoxCreateSelection();
            if (sel != null) {
                boolean oversized = sel.isOversized();
                Component dragInfo = oversized
                    ? Component.translatable("yefira.hud.dragging.oversized",
                        sel.getSizeX(), sel.getSizeY(), sel.getSizeZ(), sel.getVolume())
                    : Component.translatable("yefira.hud.dragging.info",
                        sel.getSizeX(), sel.getSizeY(), sel.getSizeZ(), sel.getVolume());
                int bgColor = oversized ? 0xDD550000 : 0xAA003300;
                int textColor = oversized ? 0xFFFF4444 : 0xFF55FF55;
                graphics.fill(6, bottomY - 3, 8 + font.width(dragInfo) + 4, bottomY + 11, bgColor);
                graphics.drawString(font, dragInfo, 8, bottomY, textColor, true);
            }
        } else {
            SelectionBox sel = mgr.getCurrentSelection();
            if (sel != null) {
                boolean oversized = sel.isOversized();
                Component selInfo = oversized
                    ? Component.translatable("yefira.hud.selection.oversized",
                        String.valueOf(sel.getSizeX()), String.valueOf(sel.getSizeY()),
                        String.valueOf(sel.getSizeZ()), String.valueOf(sel.getVolume()))
                    : Component.translatable("yefira.hud.selection.info",
                        String.valueOf(sel.getSizeX()), String.valueOf(sel.getSizeY()),
                        String.valueOf(sel.getSizeZ()), String.valueOf(sel.getVolume()));
                int bgColor = oversized ? 0xDD550000 : 0x88000000;
                int textColor = oversized ? 0xFFFF4444 : 0xFF55FFFF;
                graphics.fill(6, bottomY - 3, 8 + font.width(selInfo) + 4, bottomY + 11, bgColor);
                graphics.drawString(font, selInfo, 8, bottomY, textColor, true);
            } else if (ghost.getHoveredBlockPos() != null) {
                Component hoverInfo = Component.translatable("yefira.hud.hovered.block", ghost.getHoveredBlockPos().toShortString());
                graphics.fill(6, bottomY - 3, 8 + font.width(hoverInfo) + 4, bottomY + 11, 0x88000000);
                graphics.drawString(font, hoverInfo, 8, bottomY, 0xFFFFFF55, true);
            }
        }
    }
}
