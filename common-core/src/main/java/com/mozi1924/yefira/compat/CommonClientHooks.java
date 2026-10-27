package com.mozi1924.yefira.compat;

import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionManager;
import com.mozi1924.yefira.selection.SelectionStorageManager;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * Shared client connection lifecycle hooks across all supported Minecraft versions.
 */
public final class CommonClientHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("yefira");

    private CommonClientHooks() {}

    /**
     * Common client joined server handler.
     */
    public static void onClientJoin(Executor clientExecutor, boolean isSinglePlayer, String serverIp, Level level) {
        ClientThread.setExecutor(clientExecutor);
        boolean multi = !isSinglePlayer;
        SelectionManager.getInstance().setSelectionSource(multi
                ? SelectionManager.SelectionSource.LOCAL_CLIENT
                : SelectionManager.SelectionSource.SERVER_AUTHORITATIVE);
        if (multi) {
            if (serverIp != null && !serverIp.isEmpty() && level != null) {
                Path serverPath = SelectionStorageManager.getServerStoragePath(serverIp);
                boolean loaded = SelectionManager.getInstance().loadSavedSelection(serverPath, level);
                if (loaded) {
                    LOGGER.info("Loaded server selection for IP: {}", serverIp);
                }
            }
            LOGGER.info(
                "Remote server detected; local WebSocket auto-start is disabled. "
                    + "Start it from the GUI for local export, or connect the DCC to the server's endpoint.");
        }
    }

    /**
     * Common client disconnected from server handler.
     */
    public static void onClientDisconnect(boolean isSinglePlayer) {
        ClientThread.clear();
        SelectionManager.getInstance().setSelectionSource(SelectionManager.SelectionSource.SERVER_AUTHORITATIVE);
        if (!isSinglePlayer) {
            SelectionManager.getInstance().setActiveStoragePath(null);
            WebSocketServerManager.getInstance().stopServer();
        }
    }
}
