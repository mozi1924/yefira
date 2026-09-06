package com.mozi1924.yefira.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class SelectionCommand {

    private static boolean hasAdminPermission(CommandSourceStack source) {
        return (source.getServer() != null && !source.getServer().isDedicatedServer()) || source.hasPermission(2);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yefira")
            // 1. Selection Commands (Accessible to all players without cheats)
            .then(Commands.literal("pos1")
                .executes(SelectionCommand::setPos1Current)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> setPos1Specific(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
            .then(Commands.literal("pos2")
                .executes(SelectionCommand::setPos2Current)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> setPos2Specific(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
            .then(Commands.literal("box")
                .then(Commands.literal("preset")
                    .executes(ctx -> setPresetBox(ctx, 16))
                    .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> setPresetBox(ctx, IntegerArgumentType.getInteger(ctx, "size")))))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ctx -> setBox(ctx,
                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                BlockPosArgument.getLoadedBlockPos(ctx, "to"))))))
            .then(Commands.literal("clear")
                .executes(SelectionCommand::clearSelection))
            .then(Commands.literal("refresh")
                .executes(SelectionCommand::refreshSnapshot))
            .then(Commands.literal("status")
                .executes(SelectionCommand::showStatus))

            // 2. GUI Command (Opens GUI in client/singleplayer)
            .then(Commands.literal("gui")
                .executes(SelectionCommand::openGui))

            // 3. WebSocket Server Management (Matching GUI buttons)
            .then(Commands.literal("server")
                .requires(SelectionCommand::hasAdminPermission)
                .then(Commands.literal("start")
                    .executes(SelectionCommand::serverStart))
                .then(Commands.literal("stop")
                    .executes(SelectionCommand::serverStop))
                .then(Commands.literal("restart")
                    .executes(SelectionCommand::serverRestart))
                .then(Commands.literal("status")
                    .executes(SelectionCommand::serverStatus)))

            // 4. Configuration Management (Matching GUI fields)
            .then(Commands.literal("config")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfig)
                .then(Commands.literal("host")
                    .executes(SelectionCommand::showConfigHost)
                    .then(Commands.argument("host", StringArgumentType.string())
                        .executes(ctx -> setConfigHost(ctx, StringArgumentType.getString(ctx, "host")))))
                .then(Commands.literal("port")
                    .executes(SelectionCommand::showConfigPort)
                    .then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
                        .executes(ctx -> setConfigPort(ctx, IntegerArgumentType.getInteger(ctx, "port")))))
                .then(Commands.literal("autostart")
                    .executes(SelectionCommand::showConfigAutoStart)
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> setConfigAutoStart(ctx, BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("max_volume")
                    .executes(SelectionCommand::showConfigMaxVolume)
                    .then(Commands.argument("volume", IntegerArgumentType.integer(1))
                        .executes(ctx -> setConfigMaxVolume(ctx, IntegerArgumentType.getInteger(ctx, "volume")))))
                .then(Commands.literal("max_side")
                    .executes(SelectionCommand::showConfigMaxSide)
                    .then(Commands.argument("side", IntegerArgumentType.integer(1))
                        .executes(ctx -> setConfigMaxSide(ctx, IntegerArgumentType.getInteger(ctx, "side"))))))

            // Direct shortcuts matching GUI fields
            .then(Commands.literal("host")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfigHost)
                .then(Commands.argument("host", StringArgumentType.string())
                    .executes(ctx -> setConfigHost(ctx, StringArgumentType.getString(ctx, "host")))))
            .then(Commands.literal("port")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfigPort)
                .then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
                    .executes(ctx -> setConfigPort(ctx, IntegerArgumentType.getInteger(ctx, "port")))))
            .then(Commands.literal("autostart")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfigAutoStart)
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> setConfigAutoStart(ctx, BoolArgumentType.getBool(ctx, "enabled")))))
            .then(Commands.literal("max_volume")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfigMaxVolume)
                .then(Commands.argument("volume", IntegerArgumentType.integer(1))
                    .executes(ctx -> setConfigMaxVolume(ctx, IntegerArgumentType.getInteger(ctx, "volume")))))
            .then(Commands.literal("max_side")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::showConfigMaxSide)
                .then(Commands.argument("side", IntegerArgumentType.integer(1))
                    .executes(ctx -> setConfigMaxSide(ctx, IntegerArgumentType.getInteger(ctx, "side")))))

            // 5. Utility commands
            .then(Commands.literal("dump_directional_models")
                .requires(SelectionCommand::hasAdminPermission)
                .executes(SelectionCommand::dumpDirectionalModels))
        );
    }

    private static void checkAndWarnOversized(CommandSourceStack source, SelectionBox box) {
        if (box != null && box.isOversized()) {
            YefiraConfig cfg = YefiraConfig.getInstance();
            source.sendSuccess(() -> Component.translatable("yefira.command.oversized_warning",
                    box.getSizeX(), box.getSizeY(), box.getSizeZ(), box.getVolume(), cfg.getMaxVolumeSoftLimit()), false);
        }
    }

    private static int setPos1Current(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos pos = BlockPos.containing(source.getPosition());
        SelectionManager mgr = SelectionManager.getInstance();
        mgr.setPos1(source.getLevel(), pos);
        source.sendSuccess(() -> Component.translatable("yefira.command.pos1.set", pos.toShortString()), true);
        checkAndWarnOversized(source, mgr.getCurrentSelection());
        return 1;
    }

    private static int setPos1Specific(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager mgr = SelectionManager.getInstance();
        mgr.setPos1(source.getLevel(), pos);
        source.sendSuccess(() -> Component.translatable("yefira.command.pos1.set", pos.toShortString()), true);
        checkAndWarnOversized(source, mgr.getCurrentSelection());
        return 1;
    }

    private static int setPos2Current(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos pos = BlockPos.containing(source.getPosition());
        SelectionManager mgr = SelectionManager.getInstance();
        mgr.setPos2(source.getLevel(), pos);
        source.sendSuccess(() -> Component.translatable("yefira.command.pos2.set", pos.toShortString()), true);
        checkAndWarnOversized(source, mgr.getCurrentSelection());
        return 1;
    }

    private static int setPos2Specific(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager mgr = SelectionManager.getInstance();
        mgr.setPos2(source.getLevel(), pos);
        source.sendSuccess(() -> Component.translatable("yefira.command.pos2.set", pos.toShortString()), true);
        checkAndWarnOversized(source, mgr.getCurrentSelection());
        return 1;
    }

    private static int setBox(CommandContext<CommandSourceStack> ctx, BlockPos p1, BlockPos p2) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager mgr = SelectionManager.getInstance();
        mgr.setPositions(source.getLevel(), p1, p2);
        SelectionBox box = mgr.getCurrentSelection();
        source.sendSuccess(() -> Component.translatable("yefira.command.box.set",
                box.getMin().toShortString(), box.getMax().toShortString(),
                box.getSizeX(), box.getSizeY(), box.getSizeZ(), box.getVolume()), true);
        checkAndWarnOversized(source, box);
        return 1;
    }

    private static int setPresetBox(CommandContext<CommandSourceStack> ctx, int size) {
        CommandSourceStack source = ctx.getSource();
        BlockPos center = BlockPos.containing(source.getPosition());
        int half = size / 2;
        BlockPos p1 = center.offset(-half, 0, -half);
        BlockPos p2 = center.offset(half - 1, size - 1, half - 1);
        return setBox(ctx, p1, p2);
    }

    private static int clearSelection(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager.getInstance().clearSelection();
        source.sendSuccess(() -> Component.translatable("yefira.command.clear.success"), true);
        return 1;
    }

    private static int refreshSnapshot(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager mgr = SelectionManager.getInstance();
        if (mgr.hasSelection() && mgr.getCurrentLevel() != null) {
            WebSocketServerManager.getInstance().broadcastSnapshot(mgr.getCurrentLevel(), mgr.getCurrentSelection());
            source.sendSuccess(() -> Component.translatable("yefira.command.refresh.success"), true);
        } else {
            source.sendFailure(Component.translatable("yefira.command.refresh.no_selection"));
        }
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SelectionManager mgr = SelectionManager.getInstance();
        if (mgr.hasSelection()) {
            SelectionBox box = mgr.getCurrentSelection();
            source.sendSuccess(() -> Component.translatable("yefira.command.status.info",
                    box.getMin().toShortString(),
                    box.getMax().toShortString(),
                    box.getSizeX(), box.getSizeY(), box.getSizeZ(),
                    box.getVolume()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("yefira.command.status.empty"), false);
        }
        return 1;
    }

    private static int openGui(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (source.getServer().isDedicatedServer()) {
            source.sendFailure(Component.translatable("yefira.command.gui.unavailable"));
            return 0;
        }
        try {
            openGuiClient();
            source.sendSuccess(() -> Component.translatable("yefira.command.gui.opened"), false);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.translatable("yefira.command.gui.unavailable"));
            return 0;
        }
    }

    private static void openGuiClient() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    Object screen = Class.forName("com.mozi1924.yefira.client.gui.YefiraScreen")
                            .getConstructor().newInstance();
                    try {
                        mc.getClass().getMethod("setScreenAndShow", net.minecraft.client.gui.screens.Screen.class)
                                .invoke(mc, screen);
                    } catch (NoSuchMethodException e) {
                        mc.getClass().getMethod("setScreen", net.minecraft.client.gui.screens.Screen.class)
                                .invoke(mc, screen);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static int serverStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        YefiraConfig cfg = YefiraConfig.getInstance();
        if (server.isRunning()) {
            source.sendFailure(Component.translatable("yefira.command.server.already_running", server.getHost(), server.getPort()));
        } else {
            server.startServer(cfg.getHost(), cfg.getPort());
            source.sendSuccess(() -> Component.translatable("yefira.command.server.started", cfg.getHost(), cfg.getPort()), true);
        }
        return 1;
    }

    private static int serverStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        if (!server.isRunning()) {
            source.sendFailure(Component.translatable("yefira.command.server.not_running"));
        } else {
            server.stopServer();
            source.sendSuccess(() -> Component.translatable("yefira.command.server.stopped"), true);
        }
        return 1;
    }

    private static int serverRestart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        YefiraConfig cfg = YefiraConfig.getInstance();
        server.restartServer(cfg.getHost(), cfg.getPort());
        source.sendSuccess(() -> Component.translatable("yefira.command.server.restarted", cfg.getHost(), cfg.getPort()), true);
        return 1;
    }

    private static int serverStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        if (server.isRunning()) {
            source.sendSuccess(() -> Component.translatable("yefira.command.server.status.running",
                    server.getHost(), server.getPort(), server.getConnectedCount()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("yefira.command.server.status.stopped"), false);
        }
        return 1;
    }

    private static int showConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.info",
                cfg.getHost(), cfg.getPort(), String.valueOf(cfg.isAutoStartOnWorldLoad()),
                String.valueOf(cfg.getMaxVolumeSoftLimit()), String.valueOf(cfg.getMaxSideSoftLimit())), false);
        return 1;
    }

    private static int showConfigHost(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.host.set", cfg.getHost()), false);
        return 1;
    }

    private static int setConfigHost(CommandContext<CommandSourceStack> ctx, String host) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        cfg.setHost(host);
        YefiraConfig.save();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.host.set", cfg.getHost()), true);
        return 1;
    }

    private static int showConfigPort(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.port.set", cfg.getPort()), false);
        return 1;
    }

    private static int setConfigPort(CommandContext<CommandSourceStack> ctx, int port) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        cfg.setPort(port);
        YefiraConfig.save();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.port.set", cfg.getPort()), true);
        return 1;
    }

    private static int showConfigAutoStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.autostart.set", String.valueOf(cfg.isAutoStartOnWorldLoad())), false);
        return 1;
    }

    private static int setConfigAutoStart(CommandContext<CommandSourceStack> ctx, boolean autoStart) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        cfg.setAutoStartOnWorldLoad(autoStart);
        YefiraConfig.save();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.autostart.set", String.valueOf(autoStart)), true);
        return 1;
    }

    private static int showConfigMaxVolume(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.max_volume.set", String.valueOf(cfg.getMaxVolumeSoftLimit())), false);
        return 1;
    }

    private static int setConfigMaxVolume(CommandContext<CommandSourceStack> ctx, int maxVolume) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        cfg.setMaxVolumeSoftLimit(maxVolume);
        YefiraConfig.save();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.max_volume.set", String.valueOf(maxVolume)), true);
        return 1;
    }

    private static int showConfigMaxSide(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.max_side.set", String.valueOf(cfg.getMaxSideSoftLimit())), false);
        return 1;
    }

    private static int setConfigMaxSide(CommandContext<CommandSourceStack> ctx, int maxSide) {
        CommandSourceStack source = ctx.getSource();
        YefiraConfig cfg = YefiraConfig.getInstance();
        cfg.setMaxSideSoftLimit(maxSide);
        YefiraConfig.save();
        source.sendSuccess(() -> Component.translatable("yefira.command.config.max_side.set", String.valueOf(maxSide)), true);
        return 1;
    }

    private static int dumpDirectionalModels(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        java.util.Map<String, com.mozi1924.yefira.encoder.BlockStateModelData> models = com.mozi1924.yefira.encoder.BlockModelExtractor.getAllDirectionalModels();
        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (java.util.Map.Entry<String, com.mozi1924.yefira.encoder.BlockStateModelData> entry : models.entrySet()) {
            if (count > 0) sb.append(",\n");
            sb.append("  \"").append(entry.getKey().replace("\"", "\\\"")).append("\": ").append(entry.getValue().toJson());
            count++;
        }
        sb.append("\n}");
        try {
            java.nio.file.Path outPath = java.nio.file.Paths.get("directional_models_dump.json");
            java.nio.file.Files.writeString(outPath, sb.toString());
            source.sendSuccess(() -> Component.translatable("yefira.command.dump.success", models.size(), outPath.toAbsolutePath().toString()), true);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("yefira.command.dump.failed", e.getMessage()));
        }
        return count;
    }
}
