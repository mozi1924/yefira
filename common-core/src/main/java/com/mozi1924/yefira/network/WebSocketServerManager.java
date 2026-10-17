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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.mozi1924.yefira.encoder.CanonicalVoxelScanner;
import com.mozi1924.yefira.encoder.XXHash64;


public class WebSocketServerManager implements SelectionManager.SelectionChangeListener {

    private static final WebSocketServerManager INSTANCE = new WebSocketServerManager();
    private static String HOST = "0.0.0.0";
    private static int PORT = 8765;

    private Impl serverImpl;
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<BlockPos, BlockDataEncoder.BlockChangeEntry> pendingDeltaChanges = new LinkedHashMap<>();
    private SelectionBox pendingDeltaSelection;
    private long lastManifestBroadcastTick = 0;
    private final AtomicLong globalSeqId = new AtomicLong(1);

    // RFC v3.1 Session State
    private final byte[] serverUuidBytes = new byte[16];
    private long epoch = 1;
    private long cachedStateHash = 0;
    private final Object paletteLock = new Object();
    private final Map<String, Integer> blockPaletteLookup = new HashMap<>();
    private final List<String> blockPaletteList = new ArrayList<>();
    private int paletteRevision = 1;

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
        new Random().nextBytes(serverUuidBytes);
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
        protected boolean onConnect(java.nio.channels.SelectionKey key) {
            java.nio.channels.SelectableChannel channel = key.channel();
            if (channel instanceof java.nio.channels.SocketChannel sc) {
                try {
                    sc.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                } catch (Exception ignored) {
                }
            }
            return super.onConnect(key);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clients.add(conn);
            clientConfigs.put(conn, new ClientConfig());
            Yefira.LOGGER.info("New DCC client connected: {}", conn.getRemoteSocketAddress());
            // RFC v3.1: Client initiates handshake by sending HELLO (0x01).
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
                    streamingExecutor.submit(() -> sendBaselineToClient(conn, selectionManager.getCurrentLevel(), selectionManager.getCurrentSelection()));
                }
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

            if (packetType == BlockDataEncoder.OPCODE_HELLO) {
                BlockDataEncoder.HelloPacket hello = BlockDataEncoder.decodeHello(message);
                if (hello != null) {
                    handleClientHello(conn, hello);
                }
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_BASELINE_ACK) {
                Yefira.LOGGER.info("Client {} acknowledged baseline commit", conn.getRemoteSocketAddress());
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_RESYNC_REQUEST || packetType == BlockDataEncoder.PACKET_C2S_REQ_FULL_SYNC) {
                SelectionManager sm = SelectionManager.getInstance();
                if (sm.hasSelection() && sm.getCurrentLevel() != null) {
                    streamingExecutor.submit(() -> sendBaselineToClient(conn, sm.getCurrentLevel(), sm.getCurrentSelection()));
                }
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_PING) {
                if (message.remaining() >= 8) {
                    long ts = message.getLong();
                    sendSafe(conn, BlockDataEncoder.encodePong(ts));
                }
                return;
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error handling binary message from DCC client", e);
        }
    }

    private void handleClientHello(WebSocket conn, BlockDataEncoder.HelloPacket hello) {
        SelectionManager sm = SelectionManager.getInstance();
        if (!sm.hasSelection() || sm.getCurrentLevel() == null) {
            byte[] welcome = BlockDataEncoder.encodeWelcome(
                    (short) 3,
                    serverUuidBytes,
                    epoch,
                    globalSeqId.get(),
                    null,
                    0, 0, 0,
                    0,
                    BlockDataEncoder.DECISION_REJECTED,
                    "No active selection"
            );
            sendSafe(conn, welcome);
            return;
        }

        Level level = sm.getCurrentLevel();
        SelectionBox selection = sm.getCurrentSelection();
        String dimension = VersionCompat.level().getDimensionId(level);

        boolean hasClientCache = (hello.cachedStateHash != 0) && !Arrays.equals(hello.cachedWorldUuid, new byte[16]);
        boolean matchesUuid = Arrays.equals(hello.cachedWorldUuid, serverUuidBytes);
        boolean matchesEpoch = hello.cachedEpoch == epoch;
        boolean matchesSeq = hello.cachedSeq == globalSeqId.get();
        boolean matchesBounds = (hello.cachedMinX == selection.getMin().getX())
                && (hello.cachedMinY == selection.getMin().getY())
                && (hello.cachedMinZ == selection.getMin().getZ())
                && (hello.cachedSizeX == selection.getSizeX())
                && (hello.cachedSizeY == selection.getSizeY())
                && (hello.cachedSizeZ == selection.getSizeZ());

        if (hasClientCache && matchesUuid && matchesEpoch && matchesSeq && matchesBounds && cachedStateHash != 0 && hello.cachedStateHash == cachedStateHash) {
            Yefira.LOGGER.info("Client {} verified 100% in sync with scene! Sending VERIFIED_SKIP_REBUILD.", conn.getRemoteSocketAddress());
            byte[] welcome = BlockDataEncoder.encodeWelcome(
                    (short) 3,
                    serverUuidBytes,
                    epoch,
                    globalSeqId.get(),
                    selection.getMin(),
                    selection.getSizeX(), selection.getSizeY(), selection.getSizeZ(),
                    cachedStateHash,
                    BlockDataEncoder.DECISION_VERIFIED_SKIP_REBUILD,
                    dimension
            );
            sendSafe(conn, welcome);
        } else {
            Yefira.LOGGER.info("Client {} cache mismatch or initial connect -> sending FULL_BASELINE_REQUIRED.", conn.getRemoteSocketAddress());
            byte[] welcome = BlockDataEncoder.encodeWelcome(
                    (short) 3,
                    serverUuidBytes,
                    epoch,
                    globalSeqId.get(),
                    selection.getMin(),
                    selection.getSizeX(), selection.getSizeY(), selection.getSizeZ(),
                    cachedStateHash,
                    BlockDataEncoder.DECISION_FULL_BASELINE_REQUIRED,
                    dimension
            );
            if (sendSafe(conn, welcome)) {
                streamingExecutor.submit(() -> sendBaselineToClient(conn, level, selection));
            }
        }
    }

    // --- SelectionChangeListener 接口实现 ---

    @Override
    public void onSelectionChanged(Level level, SelectionBox selection) {
        clearPendingDeltaChanges();
        cachedStateHash = 0;
        if (clients.isEmpty()) return;

        long newStreamId = globalSeqId.incrementAndGet();
        activeBroadcastStreamId.set(newStreamId);

        java.util.concurrent.Future<?> prevTask = activeBroadcastFuture.getAndSet(null);
        if (prevTask != null && !prevTask.isDone()) {
            prevTask.cancel(true);
        }

        Yefira.LOGGER.info("Broadcasting baseline for new selection (Stream {}) to {} clients...", newStreamId, clients.size());
        java.util.concurrent.Future<?> future = streamingExecutor.submit(() -> broadcastBaseline(level, selection));
        activeBroadcastFuture.set(future);
    }

    @Override
    public void onSelectionCleared() {
        clearPendingDeltaChanges();
        cachedStateHash = 0;
        if (clients.isEmpty()) return;
        java.util.concurrent.Future<?> prevTask = activeBroadcastFuture.getAndSet(null);
        if (prevTask != null && !prevTask.isDone()) {
            prevTask.cancel(true);
        }
    }

    // --- 数据发送辅助方法 ---

    public void broadcastBaseline(Level level, SelectionBox selection) {
        if (clients.isEmpty() || level == null || selection == null) return;
        for (WebSocket client : List.copyOf(clients)) {
            sendBaselineToClient(client, level, selection);
        }
    }

    private void sendBaselineToClient(WebSocket conn, Level level, SelectionBox selection) {
        try {
            String dimension = VersionCompat.level().getDimensionId(level);
            CanonicalVoxelScanner.BaselineScanResult scan = CanonicalVoxelScanner.scanBaseline(level, selection, dimension);
            cachedStateHash = scan.stateHash;

            synchronized (paletteLock) {
                blockPaletteList.clear();
                blockPaletteLookup.clear();
                for (int i = 0; i < scan.blockPalette.size(); i++) {
                    String p = scan.blockPalette.get(i);
                    blockPaletteList.add(p);
                    blockPaletteLookup.put(p, i);
                }
                paletteRevision = 1;
            }

            long snapshotSeq = globalSeqId.get();
            byte[] snapshotUuid = new byte[16];
            new Random().nextBytes(snapshotUuid);

            byte[] payload = scan.payload;
            int totalBytes = payload.length;
            int chunkSize = 256 * 1024;
            int totalChunks = Math.max(1, (totalBytes + chunkSize - 1) / chunkSize);

            byte[] beginPkt = BlockDataEncoder.encodeBaselineBegin(
                    epoch,
                    snapshotUuid,
                    snapshotSeq,
                    scan.minPos,
                    scan.sizeX, scan.sizeY, scan.sizeZ,
                    scan.stateHash,
                    paletteRevision,
                    totalChunks,
                    totalBytes,
                    totalBytes,
                    (byte) 0, // uncompressed
                    (byte) scan.paletteBitsPerBlock,
                    (byte) scan.biomeBitsPerQuart,
                    dimension,
                    scan.blockPalette,
                    scan.biomePalette
            );
            if (!sendSafe(conn, beginPkt)) return;

            int bytesPerVoxel = Math.max(1, scan.paletteBitsPerBlock / 8);
            int voxelsPerChunk = chunkSize / bytesPerVoxel;

            for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                if (!conn.isOpen()) break;
                int offset = chunkIdx * chunkSize;
                int len = Math.min(chunkSize, totalBytes - offset);
                byte[] chunkPayload = new byte[len];
                System.arraycopy(payload, offset, chunkPayload, 0, len);

                long firstVoxelIdx = (long) chunkIdx * voxelsPerChunk;
                int voxelCnt = (int) Math.min((long) voxelsPerChunk, Math.max(0, scan.totalVoxels - firstVoxelIdx));

                XXHash64 chunkHasher = new XXHash64(0);
                chunkHasher.update(chunkPayload);
                long chunkHash = chunkHasher.digest();

                byte[] chunkPkt = BlockDataEncoder.encodeBaselineChunk(
                        epoch,
                        snapshotUuid,
                        chunkIdx,
                        totalChunks,
                        firstVoxelIdx,
                        voxelCnt,
                        len,
                        chunkHash,
                        chunkPayload
                );
                if (!sendSafe(conn, chunkPkt)) return;
            }

            byte[] endPkt = BlockDataEncoder.encodeBaselineEnd(
                    epoch,
                    snapshotUuid,
                    snapshotSeq,
                    totalChunks,
                    scan.stateHash,
                    (byte) 0
            );
            sendSafe(conn, endPkt);
            Yefira.LOGGER.info("Baseline streamed to client {} ({} bytes, {} chunks, hash=0x{})",
                    conn.getRemoteSocketAddress(), totalBytes, totalChunks, Long.toHexString(scan.stateHash));
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to stream baseline to client {}", conn.getRemoteSocketAddress(), e);
        }
    }

    public void tickValidationHeartbeat(long currentTick) {
        // Heartbeats handled via native WebSocket ping/pong
    }

    public void broadcastDeltaUpdate(SelectionBox selection, List<BlockDataEncoder.BlockChangeEntry> changes) {
        if (clients.isEmpty() || changes.isEmpty()) return;
        try {
            long seqId = globalSeqId.incrementAndGet();
            List<Map.Entry<Integer, String>> newPaletteEntries = new ArrayList<>();
            List<BlockDataEncoder.DeltaEntry> deltaEntries = new ArrayList<>(changes.size());

            BlockPos min = selection.getMin();
            synchronized (paletteLock) {
                for (BlockDataEncoder.BlockChangeEntry c : changes) {
                    int rx = c.pos().getX() - min.getX();
                    int ry = c.pos().getY() - min.getY();
                    int rz = c.pos().getZ() - min.getZ();

                    String stateStr = CanonicalVoxelScanner.normalizeCanonicalBlockState(c.state());
                    Integer stateId = blockPaletteLookup.get(stateStr);
                    if (stateId == null) {
                        stateId = blockPaletteList.size();
                        blockPaletteList.add(stateStr);
                        blockPaletteLookup.put(stateStr, stateId);
                        newPaletteEntries.add(new AbstractMap.SimpleEntry<>(stateId, stateStr));
                    }
                    deltaEntries.add(new BlockDataEncoder.DeltaEntry(rx, ry, rz, stateId));
                }

                if (!newPaletteEntries.isEmpty()) {
                    int oldRev = paletteRevision;
                    paletteRevision++;
                    byte[] palPkt = BlockDataEncoder.encodePaletteUpdate(epoch, seqId, oldRev, paletteRevision, newPaletteEntries);
                    for (WebSocket client : List.copyOf(clients)) {
                        sendSafe(client, palPkt);
                    }
                }
            }

            byte[] deltaPkt = BlockDataEncoder.encodeDeltaOp(epoch, seqId, paletteRevision, deltaEntries);
            for (WebSocket client : List.copyOf(clients)) {
                sendSafe(client, deltaPkt);
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error broadcasting delta update", e);
        }
    }

    public void queueDeltaUpdate(SelectionBox selection, BlockDataEncoder.BlockChangeEntry change) {
        if (clients.isEmpty()) return;
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

                broadcastDeltaUpdate(selection, changes);
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

