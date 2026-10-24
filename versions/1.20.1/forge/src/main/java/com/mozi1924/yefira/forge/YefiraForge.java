package com.mozi1924.yefira.forge;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.command.SelectionCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(Yefira.MOD_ID)
public class YefiraForge {

    public YefiraForge() {
        Yefira.init();

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
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
