package com.mozi1924.yefira.client.gui;

import com.mozi1924.yefira.client.compat.GuiCompat;
import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public class YefiraScreen extends Screen {

    public enum SyncMode {
        CLIENT,
        SERVER
    }

    private SyncMode currentMode = SyncMode.CLIENT;
    private Button clientModeBtn;
    private Button serverModeBtn;
    private Button loopbackAuthButton;

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

    /** Address shown to the user when the DCC must connect to a dedicated server. */
    private String remoteServerAddress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            return mc.getCurrentServer().ip;
        }
        return "";
    }

    /** Best-effort port parsed from the multiplayer address (defaults to 8765). */
    private int remoteServerPort() {
        String address = remoteServerAddress();
        int separator = address.lastIndexOf(':');
        if (separator > 0 && separator < address.length() - 1) {
            try {
                int port = Integer.parseInt(address.substring(separator + 1));
                if (port >= 1024 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 8765;
    }

    private Component loopbackAuthLabel(YefiraConfig cfg) {
        return Component.translatable("yefira.gui.loopback_auth",
                cfg.isAllowLoopbackWithoutToken() ? "ON" : "OFF");
    }

    private void applySelectionSource(boolean multi) {
        SelectionManager.getInstance().setSelectionSource(
                currentMode == SyncMode.CLIENT && multi
                        ? SelectionManager.SelectionSource.LOCAL_CLIENT
                        : SelectionManager.SelectionSource.SERVER_AUTHORITATIVE);
    }

    private boolean hasOpPermission() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        boolean multi = isMultiplayer();
        int startY = multi ? 50 : 35;

        YefiraConfig cfg = YefiraConfig.getInstance();
        boolean op = hasOpPermission();

        if (!multi) {
            this.currentMode = SyncMode.CLIENT;
        } else if (this.currentMode == SyncMode.SERVER && !op) {
            this.currentMode = SyncMode.CLIENT;
        }
        if (multi) {
            this.clientModeBtn = Button.builder(Component.translatable("yefira.gui.mode.client"), btn -> {
                this.currentMode = SyncMode.CLIENT;
                applySelectionSource(true);
                this.rebuildWidgets();
            }).bounds(centerX - 125, startY - 25, 120, 20).build();
            this.clientModeBtn.active = (currentMode != SyncMode.CLIENT);
            this.addRenderableWidget(this.clientModeBtn);

            this.serverModeBtn = Button.builder(Component.translatable("yefira.gui.mode.server"), btn -> {
                this.currentMode = SyncMode.SERVER;
                applySelectionSource(true);
                WebSocketServerManager.getInstance().stopServer();
                this.rebuildWidgets();
            }).bounds(centerX + 5, startY - 25, 120, 20).build();
            this.serverModeBtn.active = op && (currentMode != SyncMode.SERVER);
            this.addRenderableWidget(this.serverModeBtn);
        }
        applySelectionSource(multi);

        boolean isServerMode = (currentMode == SyncMode.SERVER);

        // Host EditBox
        this.hostEdit = new EditBox(this.font, centerX - 100, startY + 20, 200, 20, Component.translatable("yefira.gui.host"));
        this.hostEdit.setValue(isServerMode ? remoteServerAddress() : cfg.getHost());
        this.hostEdit.setEditable(!isServerMode);
        this.addRenderableWidget(this.hostEdit);

        // Port EditBox
        this.portEdit = new EditBox(this.font, centerX - 100, startY + 60, 200, 20, Component.translatable("yefira.gui.port"));
        this.portEdit.setValue(String.valueOf(isServerMode ? remoteServerPort() : cfg.getPort()));
        this.portEdit.setEditable(!isServerMode);
        this.addRenderableWidget(this.portEdit);

        // AutoStart Checkbox
        this.autoStartCheckbox = GuiCompat.createCheckbox(
            centerX - 100, startY + 90, 200, 20,
            Component.translatable("yefira.gui.autostart"),
            cfg.isAutoStartOnWorldLoad(),
            selected -> {
                cfg.setAutoStartOnWorldLoad(selected);
                YefiraConfig.save();
            }
        );
        this.autoStartCheckbox.active = !isServerMode && !multi;
        this.addRenderableWidget(this.autoStartCheckbox);

        this.loopbackAuthButton = Button.builder(loopbackAuthLabel(cfg), btn -> {
            cfg.setAllowLoopbackWithoutToken(!cfg.isAllowLoopbackWithoutToken());
            YefiraConfig.save();
            btn.setMessage(loopbackAuthLabel(cfg));
        }).bounds(centerX - 100, startY + 110, 200, 20).build();
        this.loopbackAuthButton.active = !isServerMode;
        this.addRenderableWidget(this.loopbackAuthButton);

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
                    if (!localServer.startServer(cfg.getHost(), cfg.getPort()) && mc.player != null) {
                        mc.player.displayClientMessage(Component.translatable(
                                "yefira.command.server.start_failed", cfg.getHost(), cfg.getPort()), false);
                    }
                }
                updateButtonLabels();
            }
        }).bounds(centerX - 100, startY + 140, 95, 20).build();
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
        }).bounds(centerX + 5, startY + 140, 95, 20).build();
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
        }).bounds(centerX - 100, startY + 168, 200, 20).build();
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        boolean multi = isMultiplayer();
        int startY = multi ? 50 : 35;

        // Title
        graphics.drawCenteredString(this.font, this.title, centerX, multi ? 8 : 12, 0xFFFFFF);

        // Labels
        graphics.drawString(this.font, Component.translatable("yefira.gui.host.label"), centerX - 100, startY + 8, 0xAAAAAA);
        graphics.drawString(this.font, Component.translatable("yefira.gui.port.label"), centerX - 100, startY + 48, 0xAAAAAA);

        if (multi && currentMode == SyncMode.CLIENT) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("yefira.gui.local.note"),
                    centerX, startY + 192, 0xAAAAAA);
        }
        if (currentMode == SyncMode.SERVER) {
            boolean op = hasOpPermission();
            if (!op) {
                Component noOpText = Component.translatable("yefira.gui.mode.server.no_op");
                graphics.drawCenteredString(this.font, noOpText, centerX, startY + 192, 0xFF5555);
            }
        }

        // Server Status display. Never present the local server as if it were
        // the remote server the DCC is actually connected to.
        Component statusComponent;
        int statusColor;
        if (currentMode == SyncMode.SERVER) {
            statusComponent = Component.translatable("yefira.gui.status.remote", remoteServerAddress());
            statusColor = 0x55FFFF;
        } else {
            WebSocketServerManager server = WebSocketServerManager.getInstance();
            boolean running = server.isRunning();
            String host = server.getHost() != null ? server.getHost() : "0.0.0.0";
            statusComponent = running
                    ? Component.translatable("yefira.gui.status.running", host, String.valueOf(server.getPort()), String.valueOf(server.getConnectedCount()))
                    : Component.translatable("yefira.gui.status.stopped");
            statusColor = running ? 0x55FF55 : 0xFF5555;
        }
        graphics.drawCenteredString(this.font, statusComponent, centerX, startY + 212, statusColor);

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
            graphics.drawCenteredString(this.font, selText, centerX, startY + 228, color);
        } else {
            graphics.drawCenteredString(this.font, Component.translatable("yefira.gui.selection.none"), centerX, startY + 228, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
