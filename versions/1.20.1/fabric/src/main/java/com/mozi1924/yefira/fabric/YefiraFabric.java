package com.mozi1924.yefira.fabric;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.command.SelectionCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class YefiraFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Yefira.init();

        // Register Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SelectionCommand.register(dispatcher);
        });

        // Server lifecycle & tick events
        ServerTickEvents.END_SERVER_TICK.register(Yefira::onServerTick);
        ServerLifecycleEvents.SERVER_STARTED.register(Yefira::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(Yefira::onServerStopping);

        // Player connection lifecycle events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Yefira.onPlayerJoin(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Yefira.onPlayerDisconnect(handler.getPlayer());
        });
    }
}
