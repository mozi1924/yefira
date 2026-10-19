package com.mozi1924.yefira;

import com.mozi1924.yefira.compat.ServerThread;
import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.GhostAnchorManager;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStorageManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Yefira {
    public static final String MOD_ID = "yefira";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing Yefira Common for Minecraft 1.20.4...");
        com.mozi1924.yefira.compat.VersionCompat.setLevelCompat(new com.mozi1924.yefira.compat.v1204.LevelCompat1204());
        YefiraConfig.load();
        LOGGER.info("Yefira Common initialized successfully!");
    }

    /**
     * Common server tick hook.
     */
    public static void onServerTick(MinecraftServer server) {
        WebSocketServerManager.getInstance().flushQueuedDeltaUpdates();
        WebSocketServerManager.getInstance().tickValidationHeartbeat(server.getTickCount());
        GhostAnchorManager.tick(server);
    }

    /**
     * Common server started lifecycle hook.
     */
    public static void onServerStarted(MinecraftServer server) {
        ServerThread.setExecutor(server::execute);

        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        Path storagePath = SelectionStorageManager.getWorldStoragePath(worldDir);
        SelectionStorageManager.SelectionData saved = SelectionStorageManager.loadFromPath(storagePath);
        ServerLevel level = saved != null && saved.dimension() != null
                ? server.getLevel(saved.dimension())
                : server.overworld();
        if (level == null) {
            LOGGER.warn("Saved selection dimension is not loaded; ignoring saved selection.");
            SelectionManager.getInstance().resetOnWorldUnload();
        } else {
            boolean loaded = SelectionManager.getInstance().loadSavedSelection(storagePath, level);
            if (loaded) {
                LOGGER.info("Loaded saved selection for world: {}", worldDir.getFileName());
            }
        }

        YefiraConfig cfg = YefiraConfig.getInstance();
        if (cfg.isAutoStartOnWorldLoad()) {
            boolean started = WebSocketServerManager.getInstance().startServer(cfg.getHost(), cfg.getPort());
            if (!started) {
                LOGGER.error("WebSocket Server auto-start failed; check the configured host/port/auth token.");
            }
        } else {
            LOGGER.info("WebSocket Server auto-start is disabled (on-demand mode).");
        }
    }

    /**
     * Common server stopping lifecycle hook.
     */
    public static void onServerStopping(MinecraftServer server) {
        ServerThread.clear();
        GhostAnchorManager.clearAll();
        WebSocketServerManager.getInstance().stopServer();
        SelectionManager.getInstance().resetOnWorldUnload();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.tryParse(MOD_ID + ":" + path);
    }
}
