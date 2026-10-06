package com.mozi1924.yefira.network;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.compat.VersionCompat;
import com.mozi1924.yefira.encoder.BlockDataEncoder;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.java_websocket.WebSocket;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.framing.PongFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WebSocketServerManager implements SelectionManager.SelectionChangeListener {

    private static final WebSocketServerManager INSTANCE = new WebSocketServerManager();
    private static String HOST = "0.0.0.0";
    private static int PORT = 8765;

    private Impl serverImpl;
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<BlockPos, BlockDataEncoder.BlockChangeEntry> pendingDeltaChanges = new LinkedHashMap<>();
    private SelectionBox pendingDeltaSelection;
    private long lastManifestBroadcastTick = 0;
    private boolean hasEditsSinceLastManifest = false;
    private final AtomicLong globalSeqId = new AtomicLong(1);

    public static class ClientConfig {
        public byte throttleMode = 0; // 0 = NORMAL, 1 = ECO, 2 = PAUSED
        public byte targetFps = 60;
        public boolean isActive = true;
    }

    private final Map<WebSocket, ClientConfig> clientConfigs = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService streamingExecutor = java.util.concurrent.Executors.newCachedThreadPool();
    private final java.util.concurrent.atomic.AtomicLong activeBroadcastStreamId = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> activeBroadcastFuture = new java.util.concurrent.atomic.AtomicReference<>();

    public static WebSocketServerManager getInstance() {
        return INSTANCE;
    }

    public static synchronized void setHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            HOST = host.trim();
        }
    }

    public static synchronized String getHost() {
        return HOST;
    }

    public static synchronized void setPort(int port) {
        if (port >= 1024 && port <= 65535) {
            PORT = port;
        }
    }

    public static synchronized int getPort() {
        return PORT;
    }

    public synchronized boolean isRunning() {
        return serverImpl != null;
    }

    public int getConnectedCount() {
        return clients.size();
    }

    private WebSocketServerManager() {
    }

    public synchronized void startServer() {
        startServer(HOST, PORT);
    }

    public synchronized void startServer(String host, int port) {
        setHost(host);
        setPort(port);

        if (serverImpl != null) {
            Yefira.LOGGER.info("WebSocket Server is already running on {}:{}", HOST, PORT);
            return;
        }
        try {
            serverImpl = new Impl(new InetSocketAddress(HOST, PORT));
            serverImpl.setReuseAddr(true);
            serverImpl.start();
            SelectionManager.getInstance().addListener(this);
            Yefira.LOGGER.info("WebSocket Server started on {}:{}", HOST, PORT);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to start WebSocket Server on " + HOST + ":" + PORT, e);
            serverImpl = null;
        }
    }

    public synchronized void restartServer(String host, int port) {
        stopServer();
        startServer(host, port);
    }

    public synchronized void restartServer() {
        stopServer();
        startServer(HOST, PORT);
    }

    public synchronized void stopServer() {
        SelectionManager.getInstance().removeListener(this);
        clearPendingDeltaChanges();
        clientConfigs.clear();
        clients.clear();

        if (serverImpl != null) {
            try {
                serverImpl.stop(1000);
                Yefira.LOGGER.info("WebSocket Server stopped.");
            } catch (Exception e) {
                Yefira.LOGGER.error("Error stopping WebSocket Server", e);
            } finally {
                serverImpl = null;
            }
        }
    }

    private class Impl extends WebSocketServer {
        public Impl(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clients.add(conn);
            clientConfigs.put(conn, new ClientConfig());
            Yefira.LOGGER.info("New DCC client connected: {}", conn.getRemoteSocketAddress());

            SelectionManager selectionManager = SelectionManager.getInstance();
            if (selectionManager.hasSelection() && selectionManager.getCurrentLevel() != null) {
                streamingExecutor.submit(() -> sendHandshakeManifestToClient(conn, selectionManager.getCurrentLevel(), selectionManager.getCurrentSelection()));
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
            clientConfigs.remove(conn);
            Yefira.LOGGER.info("DCC client disconnected: {}", conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            WebSocketServerManager.this.handleTextMessage(conn, message);
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            WebSocketServerManager.this.handleBinaryMessage(conn, message);
        }

        @Override
        public void onWebsocketPing(WebSocket conn, Framedata f) {
            if (conn != null && conn.isOpen()) {
                try {
                    PongFrame resp = f instanceof PingFrame ? new PongFrame((PingFrame) f) : new PongFrame();
                    conn.sendFrame(resp);
                } catch (WebsocketNotConnectedException ignored) {
                    clients.remove(conn);
                    clientConfigs.remove(conn);
                } catch (Exception e) {
                    Yefira.LOGGER.debug("Error sending pong response to DCC client: {}", e.getMessage());
                }
            }
        }

        @Override
        public void onWebsocketPong(WebSocket conn, Framedata f) {
            // Heartbeat pong received from client; connection is verified healthy
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn != null) {
                clients.remove(conn);
                clientConfigs.remove(conn);
            }
            if (ex instanceof WebsocketNotConnectedException) {
                Yefira.LOGGER.debug("WebSocket client disconnected: {}", conn != null ? conn.getRemoteSocketAddress() : "unknown");
            } else {
                Yefira.LOGGER.error("WebSocket error on connection: {}", conn != null ? conn.getRemoteSocketAddress() : "global", ex);
            }
        }

        @Override
        public void onStart() {
            Yefira.LOGGER.info("WebSocket Server successfully initialized.");
        }
    }

    /**
     * Atomically and safely sends binary frame data to a client, gracefully handling disconnections and cleaning up state.
     */
    public boolean sendSafe(WebSocket conn, byte[] data) {
        if (conn == null || !conn.isOpen()) {
            if (conn != null) {
                clients.remove(conn);
                clientConfigs.remove(conn);
            }
            return false;
        }
        try {
            conn.send(data);
            return true;
        } catch (WebsocketNotConnectedException e) {
            clients.remove(conn);
            clientConfigs.remove(conn);
            return false;
        } catch (Exception e) {
            Yefira.LOGGER.warn("Failed to send data to client {}: {}", conn.getRemoteSocketAddress(), e.getMessage());
            clients.remove(conn);
            clientConfigs.remove(conn);
            return false;
        }
    }

    /**
     * Atomically and safely sends text frame data to a client, gracefully handling disconnections and cleaning up state.
     */
    public boolean sendSafe(WebSocket conn, String data) {
        if (conn == null || !conn.isOpen()) {
            if (conn != null) {
                clients.remove(conn);
                clientConfigs.remove(conn);
            }
            return false;
        }
        try {
            conn.send(data);
            return true;
        } catch (WebsocketNotConnectedException e) {
            clients.remove(conn);
            clientConfigs.remove(conn);
            return false;
        } catch (Exception e) {
            Yefira.LOGGER.warn("Failed to send data to client {}: {}", conn.getRemoteSocketAddress(), e.getMessage());
            clients.remove(conn);
            clientConfigs.remove(conn);
            return false;
        }
    }

    private void handleTextMessage(WebSocket conn, String message) {
        try {
            if ("PING".equalsIgnoreCase(message.trim())) {
                sendSafe(conn, "PONG");
            } else if ("REFRESH".equalsIgnoreCase(message.trim())) {
                SelectionManager selectionManager = SelectionManager.getInstance();
                if (selectionManager.hasSelection() && selectionManager.getCurrentLevel() != null) {
                    sendSnapshotToClient(conn, selectionManager.getCurrentLevel(), selectionManager.getCurrentSelection());
                }
            } else if ("DUMP_MODELS".equalsIgnoreCase(message.trim())) {
                java.util.Map<String, com.mozi1924.yefira.encoder.BlockStateModelData> models = com.mozi1924.yefira.encoder.BlockModelExtractor.getAllDirectionalModels();
                StringBuilder sb = new StringBuilder("DUMP_MODELS:{\n");
                int count = 0;
                for (java.util.Map.Entry<String, com.mozi1924.yefira.encoder.BlockStateModelData> entry : models.entrySet()) {
                    if (count > 0) sb.append(",\n");
                    sb.append("  \"").append(entry.getKey().replace("\"", "\\\"")).append("\": ").append(entry.getValue().toJson());
                    count++;
                }
                sb.append("\n}");
                sendSafe(conn, sb.toString());
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error handling text message from DCC client", e);
        }
    }

    private void handleBinaryMessage(WebSocket conn, ByteBuffer message) {
        try {
            if (message == null || message.remaining() < 4) return;

            message.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte b0 = message.get();
            byte b1 = message.get();

            if (b0 != BlockDataEncoder.MAGIC[0] || b1 != BlockDataEncoder.MAGIC[1]) {
                return;
            }

            byte version = message.get();
            byte packetType = message.get();

            if (packetType == BlockDataEncoder.PACKET_C2S_SYNC_CONFIG) {
                byte mode = message.remaining() > 0 ? message.get() : 0;
                byte fps = message.remaining() > 0 ? message.get() : 60;
                byte flags = message.remaining() > 0 ? message.get() : 1;
                ClientConfig cfg = clientConfigs.computeIfAbsent(conn, k -> new ClientConfig());
                cfg.throttleMode = mode;
                cfg.targetFps = fps;
                cfg.isActive = (flags & 1) != 0;
                Yefira.LOGGER.info("Client {} updated sync config: mode={}, targetFps={}, active={}",
                        conn.getRemoteSocketAddress(), mode, fps, cfg.isActive);
                return;
            }

            SelectionManager selectionManager = SelectionManager.getInstance();
            if (!selectionManager.hasSelection() || selectionManager.getCurrentLevel() == null) {
                return;
            }

            Level level = selectionManager.getCurrentLevel();
            SelectionBox selection = selectionManager.getCurrentSelection();

            if (packetType == BlockDataEncoder.PACKET_C2S_REQ_FULL_SYNC) {
                Yefira.LOGGER.info("Client {} requested FULL SYNC.", conn.getRemoteSocketAddress());
                streamingExecutor.submit(() -> sendSnapshotToClient(conn, level, selection));
            } else if (packetType == BlockDataEncoder.PACKET_C2S_REQ_SECTION_SYNC) {
                if (message.remaining() < 2) return;
                int count = message.getShort() & 0xFFFF;
                Yefira.LOGGER.info("Client {} requested section sync for {} sections.", conn.getRemoteSocketAddress(), count);

                List<BlockDataEncoder.SectionPos> requestedPositions = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    if (message.remaining() < 12) break;
                    int secX = message.getInt();
                    int secY = message.getInt();
                    int secZ = message.getInt();
                    requestedPositions.add(new BlockDataEncoder.SectionPos(secX, secY, secZ));
                }

                streamingExecutor.submit(() -> {
                    long streamId = globalSeqId.incrementAndGet();
                    byte[] beginPacket = BlockDataEncoder.encodeStreamBegin(streamId, requestedPositions.size(), 0);
                    if (!sendSafe(conn, beginPacket)) return;

                    int sent = 0;
                    for (BlockDataEncoder.SectionPos secPos : requestedPositions) {
                        if (conn == null || !conn.isOpen()) break;
                        byte[] sectionSnapshot = BlockDataEncoder.encodeSectionSnapshot(level, selection, secPos);
                        if (!sendSafe(conn, sectionSnapshot)) {
                            break;
                        }
                        sent++;
                        if ((sent % 16) == 0) {
                            Thread.yield();
                        }
                    }

                    byte[] endPacket = BlockDataEncoder.encodeStreamEnd(streamId, sent, 0);
                    sendSafe(conn, endPacket);

                    // Post-stream Manifest for repaired sections
                    byte[] manifestBytes = BlockDataEncoder.encodeSectionManifest(level, selection, streamId);
                    sendSafe(conn, manifestBytes);
                });
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error handling binary message from DCC client", e);
        }
    }

    // --- SelectionChangeListener 接口实现 ---

    @Override
    public void onSelectionChanged(Level level, SelectionBox selection) {
        if (clients.isEmpty()) return;
        clearPendingDeltaChanges();
        BlockDataEncoder.clearSectionCRCCache();

        long newStreamId = globalSeqId.incrementAndGet();
        activeBroadcastStreamId.set(newStreamId);

        // Cancel previous streaming task if still running
        java.util.concurrent.Future<?> prevTask = activeBroadcastFuture.getAndSet(null);
        if (prevTask != null && !prevTask.isDone()) {
            prevTask.cancel(true);
            Yefira.LOGGER.info("Preempted previous streaming task for newer selection change.");
        }

        Yefira.LOGGER.info("Broadcasting new selection snapshot (Stream {}) to {} clients...", newStreamId, clients.size());
        java.util.concurrent.Future<?> future = streamingExecutor.submit(() -> broadcastSnapshot(level, selection, newStreamId));
        activeBroadcastFuture.set(future);
    }

    @Override
    public void onSelectionCleared() {
        clearPendingDeltaChanges();
        BlockDataEncoder.clearSectionCRCCache();
        java.util.concurrent.Future<?> prevTask = activeBroadcastFuture.getAndSet(null);
        if (prevTask != null && !prevTask.isDone()) {
            prevTask.cancel(true);
        }
    }

    // --- 数据发送辅助方法 ---

    private void sendHandshakeManifestToClient(WebSocket conn, Level level, SelectionBox selection) {
        try {
            long snapshotSeqId = globalSeqId.incrementAndGet();
            byte[] infoBytes = BlockDataEncoder.encodeSelectionInfo(selection);
            if (!sendSafe(conn, infoBytes)) return;

            int totalSections = BlockDataEncoder.getCoveredSections(selection).size();
            int nonEmptySections = BlockDataEncoder.countNonEmptySections(level, selection);
            long totalVolume = selection.getVolume();
            String dimName = VersionCompat.level().getDimensionId(level);
            byte[] handshakeBytes = BlockDataEncoder.encodeHandshakeInfo(totalSections, nonEmptySections, totalVolume, dimName, 0);
            if (!sendSafe(conn, handshakeBytes)) return;

            byte[] manifestBytes = BlockDataEncoder.encodeSectionManifest(level, selection, snapshotSeqId);
            sendSafe(conn, manifestBytes);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to send handshake manifest to client {}", conn.getRemoteSocketAddress(), e);
        }
    }

    private void sendSnapshotToClient(WebSocket conn, Level level, SelectionBox selection) {
        try {
            long snapshotSeqId = globalSeqId.incrementAndGet();
            long volume = selection.getVolume();

            byte[] infoBytes = BlockDataEncoder.encodeSelectionInfo(selection);
            if (!sendSafe(conn, infoBytes)) return;

            if (volume <= 32768) {
                byte[] snapshotBytes = BlockDataEncoder.encodeFullSnapshot(level, selection);
                sendSafe(conn, snapshotBytes);
                byte[] endPacket = BlockDataEncoder.encodeStreamEnd(snapshotSeqId, 1, BlockDataEncoder.STREAM_STATUS_SUCCESS);
                sendSafe(conn, endPacket);
            } else {
                BlockDataEncoder.streamNonEmptySectionSnapshots(level, selection, snapshotSeqId, () -> !conn.isOpen(), bytes -> sendSafe(conn, bytes));
            }

            // Post-stream Manifest for final end-to-end verification
            byte[] manifestBytes = BlockDataEncoder.encodeSectionManifest(level, selection, snapshotSeqId);
            sendSafe(conn, manifestBytes);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to send snapshot to client {}", conn.getRemoteSocketAddress(), e);
        }
    }

    public void broadcastSnapshot(Level level, SelectionBox selection) {
        broadcastSnapshot(level, selection, globalSeqId.incrementAndGet());
    }

    public void broadcastSnapshot(Level level, SelectionBox selection, long snapshotSeqId) {
        if (clients.isEmpty()) return;
        try {
            long volume = selection.getVolume();

            byte[] infoBytes = BlockDataEncoder.encodeSelectionInfo(selection);
            for (WebSocket client : List.copyOf(clients)) {
                sendSafe(client, infoBytes);
            }

            if (volume <= 32768) {
                byte[] snapshotBytes = BlockDataEncoder.encodeFullSnapshot(level, selection);
                byte[] endPacket = BlockDataEncoder.encodeStreamEnd(snapshotSeqId, 1, BlockDataEncoder.STREAM_STATUS_SUCCESS);
                for (WebSocket client : List.copyOf(clients)) {
                    sendSafe(client, snapshotBytes);
                    sendSafe(client, endPacket);
                }
            } else {
                BlockDataEncoder.streamNonEmptySectionSnapshots(
                        level,
                        selection,
                        snapshotSeqId,
                        () -> activeBroadcastStreamId.get() != snapshotSeqId,
                        bytes -> {
                            boolean anySuccess = false;
                            for (WebSocket client : List.copyOf(clients)) {
                                if (sendSafe(client, bytes)) {
                                    anySuccess = true;
                                }
                            }
                            return anySuccess || clients.isEmpty();
                        }
                );
            }

            // Post-stream Manifest for final verification across all clients
            byte[] manifestBytes = BlockDataEncoder.encodeSectionManifest(level, selection, snapshotSeqId);
            for (WebSocket client : List.copyOf(clients)) {
                sendSafe(client, manifestBytes);
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error broadcasting snapshot", e);
        }
    }

    public void broadcastManifest(Level level, SelectionBox selection) {
        if (clients.isEmpty() || level == null || selection == null) return;
        try {
            long seqId = globalSeqId.incrementAndGet();
            byte[] manifestBytes = BlockDataEncoder.encodeSectionManifest(level, selection, seqId);
            for (WebSocket client : List.copyOf(clients)) {
                sendSafe(client, manifestBytes);
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error broadcasting section manifest", e);
        }
    }

    public void tickValidationHeartbeat(long currentTick) {
        if (clients.isEmpty()) return;
        SelectionManager selectionManager = SelectionManager.getInstance();
        if (!selectionManager.hasSelection() || selectionManager.getCurrentLevel() == null) return;

        if (hasEditsSinceLastManifest && currentTick - lastManifestBroadcastTick >= 20) {
            lastManifestBroadcastTick = currentTick;
            hasEditsSinceLastManifest = false;
            broadcastManifest(selectionManager.getCurrentLevel(), selectionManager.getCurrentSelection());
        }
    }

    public void broadcastDeltaUpdate(SelectionBox selection, List<BlockDataEncoder.BlockChangeEntry> changes) {
        if (clients.isEmpty() || changes.isEmpty()) return;
        try {
            hasEditsSinceLastManifest = true;
            long seqId = globalSeqId.incrementAndGet();
            byte[] deltaBytes = BlockDataEncoder.encodeDeltaUpdate(selection, changes, seqId);
            for (WebSocket client : List.copyOf(clients)) {
                sendSafe(client, deltaBytes);
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error broadcasting delta update", e);
        }
    }

    public void queueDeltaUpdate(SelectionBox selection, BlockDataEncoder.BlockChangeEntry change) {
        if (clients.isEmpty()) return;
        BlockPos pos = change.pos();
        BlockDataEncoder.invalidateSectionCRC(new BlockDataEncoder.SectionPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        synchronized (pendingDeltaChanges) {
            if (pendingDeltaSelection != null && !sameBounds(pendingDeltaSelection, selection)) {
                pendingDeltaChanges.clear();
            }
            pendingDeltaSelection = selection;
            pendingDeltaChanges.put(change.pos().immutable(), change);
        }
    }

    public void flushQueuedDeltaUpdates() {
        try {
            synchronized (pendingDeltaChanges) {
                if (pendingDeltaChanges.isEmpty() || pendingDeltaSelection == null) return;
                SelectionBox selection = pendingDeltaSelection;
                List<BlockDataEncoder.BlockChangeEntry> changes = List.copyOf(pendingDeltaChanges.values());
                pendingDeltaChanges.clear();
                pendingDeltaSelection = null;
                hasEditsSinceLastManifest = true;

                SelectionManager selectionManager = SelectionManager.getInstance();
                Level level = selectionManager.getCurrentLevel();

                if (changes.size() > 64 && level != null) {
                    Map<BlockDataEncoder.SectionPos, List<BlockDataEncoder.BlockChangeEntry>> sectionGroups = new HashMap<>();
                    for (BlockDataEncoder.BlockChangeEntry change : changes) {
                        BlockPos pos = change.pos();
                        BlockDataEncoder.SectionPos secPos = new BlockDataEncoder.SectionPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
                        sectionGroups.computeIfAbsent(secPos, k -> new ArrayList<>()).add(change);
                    }

                    List<BlockDataEncoder.BlockChangeEntry> remainingMicroDeltas = new ArrayList<>();
                    for (Map.Entry<BlockDataEncoder.SectionPos, List<BlockDataEncoder.BlockChangeEntry>> entry : sectionGroups.entrySet()) {
                        BlockDataEncoder.SectionPos secPos = entry.getKey();
                        List<BlockDataEncoder.BlockChangeEntry> secChanges = entry.getValue();

                        if (secChanges.size() >= 32 || changes.size() > 256) {
                            byte[] secBytes = BlockDataEncoder.encodeSectionSnapshot(level, selection, secPos);
                            for (WebSocket client : List.copyOf(clients)) {
                                sendSafe(client, secBytes);
                            }
                        } else {
                            remainingMicroDeltas.addAll(secChanges);
                        }
                    }

                    if (!remainingMicroDeltas.isEmpty()) {
                        broadcastDeltaUpdate(selection, remainingMicroDeltas);
                    }
                } else {
                    broadcastDeltaUpdate(selection, changes);
                }
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error flushing queued delta updates", e);
        }
    }

    private void clearPendingDeltaChanges() {
        synchronized (pendingDeltaChanges) {
            pendingDeltaChanges.clear();
            pendingDeltaSelection = null;
        }
    }

    private static boolean sameBounds(SelectionBox a, SelectionBox b) {
        return a.getMin().equals(b.getMin()) && a.getMax().equals(b.getMax());
    }
}
