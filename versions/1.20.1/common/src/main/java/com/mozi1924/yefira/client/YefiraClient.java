package com.mozi1924.yefira.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.client.compat.KeyMappingCompat;
import com.mozi1924.yefira.client.ghost.GhostModeManager;
import com.mozi1924.yefira.client.gui.YefiraScreen;
import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStorageManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public class YefiraClient {

    public static final String YEFIRA_CATEGORY = "key.categories.yefira";

    public static KeyMapping keyGhostMode;
    public static KeyMapping keyOpenGui;
    public static KeyMapping keyFocus;
    public static KeyMapping keyClear;
    public static KeyMapping keyPresetBox;

    public static void init() {
    }


    public static void createKeyMappings() {
        keyGhostMode = KeyMappingCompat.createKeyMapping(
            "key.yefira.ghost_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            YEFIRA_CATEGORY
        );

        keyOpenGui = KeyMappingCompat.createKeyMapping(
            "key.yefira.open_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            YEFIRA_CATEGORY
        );

        keyFocus = KeyMappingCompat.createKeyMapping(
            "key.yefira.focus",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            YEFIRA_CATEGORY
        );

        keyClear = KeyMappingCompat.createKeyMapping(
            "key.yefira.clear",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            YEFIRA_CATEGORY
        );

        keyPresetBox = KeyMappingCompat.createKeyMapping(
            "key.yefira.preset_box",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            YEFIRA_CATEGORY
        );
    }

    public static void onClientJoinServer(Minecraft client) {
        if (!client.hasSingleplayerServer()) {
            ServerData serverData = client.getCurrentServer();
            if (serverData != null && serverData.ip != null) {
                Path serverPath = SelectionStorageManager.getServerStoragePath(serverData.ip);
                boolean loaded = SelectionManager.getInstance().loadSavedSelection(serverPath, client.level);
                if (loaded) {
                    Yefira.LOGGER.info("Loaded server selection for IP: {}", serverData.ip);
                }
            }
            YefiraConfig cfg = YefiraConfig.getInstance();
            if (cfg.isAutoStartOnWorldLoad()) {
                WebSocketServerManager.getInstance().startServer(cfg.getHost(), cfg.getPort());
            }
        }
    }

    public static void onClientDisconnectServer(Minecraft client) {
        if (GhostModeManager.getInstance().isActive()) {
            GhostModeManager.getInstance().disable();
        }
        if (!client.hasSingleplayerServer()) {
            SelectionManager.getInstance().clearSelection();
            WebSocketServerManager.getInstance().stopServer();
        }
    }

    public static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }

        // Global shortcuts
        if (keyGhostMode != null) {
            while (keyGhostMode.consumeClick()) {
                GhostModeManager.getInstance().toggle();
            }
        }

        if (keyOpenGui != null) {
            while (keyOpenGui.consumeClick()) {
                client.setScreen(new YefiraScreen());
            }
        }

        // Shortcuts strictly active ONLY in Ghost Mode
        if (!GhostModeManager.getInstance().isActive()) {
            // Drain any pending clicks to avoid accidental triggering on mode entry
            if (keyFocus != null) while (keyFocus.consumeClick()) {}
            if (keyClear != null) while (keyClear.consumeClick()) {}
            if (keyPresetBox != null) while (keyPresetBox.consumeClick()) {}
            return;
        }

        // Ghost Mode active logic
        GhostModeManager.getInstance().tickMovement();

        if (keyFocus != null) {
            while (keyFocus.consumeClick()) {
                GhostModeManager.getInstance().focusSelection();
            }
        }

        if (keyClear != null) {
            while (keyClear.consumeClick()) {
                SelectionManager.getInstance().clearSelection();
            }
        }

        if (keyPresetBox != null) {
            while (keyPresetBox.consumeClick()) {
                GhostModeManager.getInstance().createPresetBoxAtCursorOrPivot(16);
            }
        }
    }
}
