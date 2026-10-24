package com.mozi1924.yefira.neoforge;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.command.SelectionCommand;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Yefira.MOD_ID)
public class YefiraNeoForge {

    public YefiraNeoForge(IEventBus modEventBus) {
        Yefira.init();

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SelectionCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        Yefira.onServerStarted(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        Yefira.onServerStopping(event.getServer());
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer() != null) {
            Yefira.onServerTick(event.getServer());
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Yefira.onPlayerJoin(player);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Yefira.onPlayerDisconnect(player);
        }
    }
}
