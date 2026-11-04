package com.mozi1924.yefira.compat;

import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.GhostAnchorManager;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStorageManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Shared server lifecycle hook implementations across all supported Minecraft versions.
 */
public final class CommonServerHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("yefira");

    private CommonServerHooks() {}

    /**
     * Common server tick hook. Flushes delta updates, sends heartbeats, and ticks ghost anchors.
     */
    public static void onServerTick(MinecraftServer server) {
        processCommandFile(server);
        WebSocketServerManager.getInstance().flushQueuedDeltaUpdates();
        WebSocketServerManager.getInstance().tickValidationHeartbeat(server.getTickCount());
        GhostAnchorManager.tick(server);
    }

    private static void processCommandFile(MinecraftServer server) {
        try {
            Path worldCmd = server.getWorldPath(LevelResource.ROOT).resolve("yefira_cmd.txt");
            Path rootCmd = Path.of("yefira_cmd.txt");
            Path cmdFile = java.nio.file.Files.exists(worldCmd) ? worldCmd : (java.nio.file.Files.exists(rootCmd) ? rootCmd : null);
            if (cmdFile != null && java.nio.file.Files.isReadable(cmdFile)) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(cmdFile);
                java.nio.file.Files.deleteIfExists(cmdFile);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    LOGGER.info("Executing external command: {}", line);
                    if (line.startsWith("/")) {
                        line = line.substring(1);
                    }
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), line);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing yefira_cmd.txt", e);
        }
    }

    /**
     * Common server started lifecycle hook. Restores saved selection and conditionally auto-starts WebSocket server.
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
     * Common player joined server lifecycle hook.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        GhostAnchorManager.onPlayerJoin(player);
    }

    /**
     * Common player disconnected server lifecycle hook.
     */
    public static void onPlayerDisconnect(ServerPlayer player) {
        GhostAnchorManager.onPlayerDisconnect(player);
    }

    /**
     * Common server stopping lifecycle hook.
     */
    public static void onServerStopping(MinecraftServer server) {
        ServerThread.clear();
        GhostAnchorManager.restoreAll(server);
        WebSocketServerManager.getInstance().stopServer();
        SelectionManager.getInstance().resetOnWorldUnload();
    }
}
