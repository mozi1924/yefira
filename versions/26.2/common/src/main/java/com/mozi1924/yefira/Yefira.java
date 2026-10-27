package com.mozi1924.yefira;

import com.mozi1924.yefira.compat.CommonServerHooks;
import com.mozi1924.yefira.config.YefiraConfig;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Yefira {
    public static final String MOD_ID = "yefira";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing Yefira Common for Minecraft 26.2...");
        com.mozi1924.yefira.compat.VersionCompat.setLevelCompat(new com.mozi1924.yefira.compat.v26.LevelCompat26());
        YefiraConfig.load();
        LOGGER.info("Yefira Common initialized successfully!");
    }

    public static void onServerTick(MinecraftServer server) {
        CommonServerHooks.onServerTick(server);
    }

    public static void onServerStarted(MinecraftServer server) {
        CommonServerHooks.onServerStarted(server);
    }

    public static void onPlayerJoin(ServerPlayer player) {
        CommonServerHooks.onPlayerJoin(player);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        CommonServerHooks.onPlayerDisconnect(player);
    }

    public static void onServerStopping(MinecraftServer server) {
        CommonServerHooks.onServerStopping(server);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
