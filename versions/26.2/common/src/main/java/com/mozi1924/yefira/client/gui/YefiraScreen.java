package com.mozi1924.yefira.client.gui;

import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class YefiraScreen extends Screen {

    public enum SyncMode {
        CLIENT,
        SERVER
    }

    private SyncMode currentMode = SyncMode.CLIENT;
    private Button clientModeBtn;
    private Button serverModeBtn;

    private EditBox hostEdit;
    private EditBox portEdit;
    private Checkbox autoStartCheckbox;
    private Button startStopButton;
    private Button restartButton;
    private Button clearSelectionButton;

    public YefiraScreen() {
        super(Component.translatable("yefira.gui.title"));
    }

    private boolean isMultiplayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.hasSingleplayerServer();
    }

    private boolean hasOpPermission() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.permissions() != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        boolean multi = isMultiplayer();
        int startY = multi ? 50 : 35;

        YefiraConfig cfg = YefiraConfig.getInstance();

        // 1. Dual Mode Pills (Only rendered in multiplayer)
        if (multi) {
            this.clientModeBtn = Button.builder(Component.translatable("yefira.gui.mode.client"), btn -> {
                this.currentMode = SyncMode.CLIENT;
                this.rebuildWidgets();
            }).bounds(centerX - 125, startY - 25, 120, 20).build();
            this.clientModeBtn.active = (currentMode != SyncMode.CLIENT);
            this.addRenderableWidget(this.clientModeBtn);

            this.serverModeBtn = Button.builder(Component.translatable("yefira.gui.mode.server"), btn -> {
                this.currentMode = SyncMode.SERVER;
                this.rebuildWidgets();
            }).bounds(centerX + 5, startY - 25, 120, 20).build();
            this.serverModeBtn.active = (currentMode != SyncMode.SERVER);
            this.addRenderableWidget(this.serverModeBtn);
        } else {
            this.currentMode = SyncMode.CLIENT;
        }

        boolean isServerMode = (currentMode == SyncMode.SERVER);
        boolean op = hasOpPermission();

        // Host EditBox
        this.hostEdit = new EditBox(this.font, centerX - 100, startY + 20, 200, 20, Component.translatable("yefira.gui.host"));
        this.hostEdit.setValue(cfg.getHost());
        this.hostEdit.setEditable(!isServerMode);
        this.addRenderableWidget(this.hostEdit);

        // Port EditBox
        this.portEdit = new EditBox(this.font, centerX - 100, startY + 60, 200, 20, Component.translatable("yefira.gui.port"));
        this.portEdit.setValue(String.valueOf(cfg.getPort()));
        this.portEdit.setEditable(!isServerMode);
        this.addRenderableWidget(this.portEdit);

        // AutoStart Checkbox
        this.autoStartCheckbox = Checkbox.builder(Component.translatable("yefira.gui.autostart"), this.font)
                .pos(centerX - 100, startY + 90)
                .selected(cfg.isAutoStartOnWorldLoad())
                .onValueChange((cb, val) -> {
                    cfg.setAutoStartOnWorldLoad(val);
                    YefiraConfig.save();
                })
                .build();
        this.autoStartCheckbox.active = !isServerMode;
        this.addRenderableWidget(this.autoStartCheckbox);

        // Server Start / Stop Button
        WebSocketServerManager localServer = WebSocketServerManager.getInstance();
        Component startStopText = localServer.isRunning()
                ? Component.translatable("yefira.gui.server.stop")
                : Component.translatable("yefira.gui.server.start");

        this.startStopButton = Button.builder(startStopText, btn -> {
            applyConfigFromFields();
            Minecraft mc = Minecraft.getInstance();
            if (isServerMode) {
                if (op && mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand("yefira server start");
                }
            } else {
                if (localServer.isRunning()) {
                    localServer.stopServer();
                } else {
                    localServer.startServer(cfg.getHost(), cfg.getPort());
                }
                updateButtonLabels();
            }
        }).bounds(centerX - 100, startY + 120, 95, 20).build();
        this.startStopButton.active = !isServerMode || op;
        this.addRenderableWidget(this.startStopButton);

        // Server Restart Button
        this.restartButton = Button.builder(Component.translatable("yefira.gui.server.restart"), btn -> {
            applyConfigFromFields();
            Minecraft mc = Minecraft.getInstance();
            if (isServerMode) {
                if (op && mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand("yefira server restart");
                }
            } else {
                localServer.restartServer(cfg.getHost(), cfg.getPort());
                updateButtonLabels();
            }
        }).bounds(centerX + 5, startY + 120, 95, 20).build();
        this.restartButton.active = !isServerMode || op;
        this.addRenderableWidget(this.restartButton);

        // Clear Selection Button
        this.clearSelectionButton = Button.builder(Component.translatable("yefira.gui.selection.clear"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (isServerMode) {
                if (op && mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand("yefira clear");
                }
            } else {
                SelectionManager.getInstance().clearSelection();
            }
        }).bounds(centerX - 100, startY + 148, 200, 20).build();
        this.clearSelectionButton.active = !isServerMode || op;
        this.addRenderableWidget(this.clearSelectionButton);

        // Done / Close Button
        Button doneButton = Button.builder(Component.translatable("gui.done"), btn -> {
            applyConfigFromFields();
            this.onClose();
        }).bounds(centerX - 100, this.height - 28, 200, 20).build();
        this.addRenderableWidget(doneButton);
    }

    private void applyConfigFromFields() {
        if (currentMode == SyncMode.SERVER) return;
        YefiraConfig cfg = YefiraConfig.getInstance();
        if (hostEdit != null) {
            String host = hostEdit.getValue().trim();
            if (!host.isEmpty()) {
                cfg.setHost(host);
            }
        }
        if (portEdit != null) {
            try {
                int port = Integer.parseInt(portEdit.getValue().trim());
                if (port >= 1024 && port <= 65535) {
                    cfg.setPort(port);
                }
            } catch (NumberFormatException ignored) {}
        }
        YefiraConfig.save();
    }

    private void updateButtonLabels() {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        if (startStopButton != null && currentMode == SyncMode.CLIENT) {
            startStopButton.setMessage(server.isRunning()
                    ? Component.translatable("yefira.gui.server.stop")
                    : Component.translatable("yefira.gui.server.start"));
        }
    }

    @Override
    public void onClose() {
        applyConfigFromFields();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        boolean multi = isMultiplayer();
        int startY = multi ? 50 : 35;

        // Title
        graphics.centeredText(this.font, this.title, centerX, multi ? 8 : 12, 0xFFFFFF);

        // Labels
        graphics.text(this.font, Component.translatable("yefira.gui.host.label"), centerX - 100, startY + 8, 0xAAAAAA);
        graphics.text(this.font, Component.translatable("yefira.gui.port.label"), centerX - 100, startY + 48, 0xAAAAAA);

        if (currentMode == SyncMode.SERVER) {
            boolean op = hasOpPermission();
            if (!op) {
                Component noOpText = Component.translatable("yefira.gui.mode.server.no_op");
                graphics.centeredText(this.font, noOpText, centerX, startY + 138, 0xFF5555);
            }
        }

        // Server Status display
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        boolean running = server.isRunning();
        String host = server.getHost() != null ? server.getHost() : "0.0.0.0";
        Component statusComponent = running
                ? Component.translatable("yefira.gui.status.running", host, String.valueOf(server.getPort()), String.valueOf(server.getConnectedCount()))
                : Component.translatable("yefira.gui.status.stopped");
        int statusColor = running ? 0x55FF55 : 0xFF5555;
        graphics.centeredText(this.font, statusComponent, centerX, startY + 202, statusColor);

        // Selection info display
        SelectionManager mgr = SelectionManager.getInstance();
        SelectionBox sel = mgr.getCurrentSelection();
        if (sel != null && sel.getMin() != null && sel.getMax() != null) {
            boolean oversized = sel.isOversized();
            String key = oversized ? "yefira.gui.selection.oversized" : "yefira.gui.selection.info";
            int color = oversized ? 0xFF5555 : 0x55FFFF;
            Component selText = Component.translatable(key,
                    sel.getMin().toShortString(), sel.getMax().toShortString(),
                    String.valueOf(sel.getSizeX()), String.valueOf(sel.getSizeY()),
                    String.valueOf(sel.getSizeZ()), String.valueOf(sel.getVolume()));
            graphics.centeredText(this.font, selText, centerX, startY + 218, color);
        } else {
            graphics.centeredText(this.font, Component.translatable("yefira.gui.selection.none"), centerX, startY + 218, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
