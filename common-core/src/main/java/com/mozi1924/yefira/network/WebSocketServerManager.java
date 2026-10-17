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
    private volatile long cachedStateHash = 0;
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

    /**
     * Baseline scanning/serialization pool. All worker threads are daemons and each
     * task cooperatively aborts as soon as a newer selection/stream generation is
     * published, so rapid selection drags cannot pile up expensive scans forever.
     */
    private volatile java.util.concurrent.ExecutorService streamingExecutor = createStreamingExecutor();

    /** Incremented whenever the selection changes so stale scans stop immediately. */
    private final AtomicLong selectionGeneration = new AtomicLong(1);

    /** Per-client stream generation; a newer baseline for the same client supersedes older ones. */
    private final Map<WebSocket, AtomicLong> clientStreamGenerations = new ConcurrentHashMap<>();

    /** Per-client frame lock guarantees a single interleaving-free baseline stream. */
    private final Map<WebSocket, Object> clientSendLocks = new ConcurrentHashMap<>();

    /**
     * Number of baseline streams that are currently scanning/serializing. Delta
     * broadcasts are deferred while > 0 so that every delta is encoded against the
     * palette that belongs to the snapshot the client is about to receive.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeBaselineStreams =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Set when block changes overflowed the deferred queue during a long baseline. */
    private boolean pendingDeltaOverflow = false;
    private static final int MAX_PENDING_DELTA_CHANGES = 262_144;
    private static final int MAX_DELTA_BATCH_CHANGES = 16_384;

    private static java.util.concurrent.ExecutorService createStreamingExecutor() {
        // Bounded worker count: baseline scans allocate int[] voxel buffers, so an
        // unbounded cached pool on rapid selection drags could exhaust the heap.
        // Superseded tasks abort at their first generation check and free the slot
        // almost immediately.
        return java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Yefira-LiveSync-Stream");
            t.setDaemon(true);
            return t;
        });
    }

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

        // Abort every in-flight baseline scan/stream before tearing the server down.
        selectionGeneration.incrementAndGet();
        for (WebSocket client : List.copyOf(clients)) {
            cancelClientStream(client);
        }
        clientConfigs.clear();
        clients.clear();
        clientStreamGenerations.clear();
        clientSendLocks.clear();

        java.util.concurrent.ExecutorService executor = streamingExecutor;
        streamingExecutor = createStreamingExecutor();
        executor.shutdownNow();

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
            cleanupClient(conn);
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
                    cleanupClient(conn);
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
                cleanupClient(conn);
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
                cleanupClient(conn);
            }
            return false;
        }
        try {
            conn.send(data);
            return true;
        } catch (WebsocketNotConnectedException e) {
            cleanupClient(conn);
            return false;
        } catch (Exception e) {
            Yefira.LOGGER.warn("Failed to send data to client {}: {}", conn.getRemoteSocketAddress(), e.getMessage());
            cleanupClient(conn);
            return false;
        }
    }

    /**
     * Removes all per-connection bookkeeping (clients, config, stream generations and send locks).
     */
    private void cleanupClient(WebSocket conn) {
        if (conn == null) return;
        clients.remove(conn);
        clientConfigs.remove(conn);
        clientStreamGenerations.remove(conn);
        clientSendLocks.remove(conn);
    }

    /**
     * Invalidates any in-flight baseline stream for a single client.
     */
    private void cancelClientStream(WebSocket conn) {
        AtomicLong gen = clientStreamGenerations.get(conn);
        if (gen != null) {
            gen.incrementAndGet();
        }
    }

    private AtomicLong streamGenerationOf(WebSocket conn) {
        return clientStreamGenerations.computeIfAbsent(conn, k -> new AtomicLong(0));
    }

    private Object sendLockOf(WebSocket conn) {
        return clientSendLocks.computeIfAbsent(conn, k -> new Object());
    }

    /**
     * Starts a new baseline stream generation for one client. Any previously running
     * stream for the same client is invalidated before the task is enqueued.
     */
    public void startBaselineStream(WebSocket conn, Level level, SelectionBox selection) {
        if (conn == null || !conn.isOpen() || level == null || selection == null) {
            return;
        }
        long streamGen = streamGenerationOf(conn).incrementAndGet();
        long selectionGen = selectionGeneration.get();
        activeBaselineStreams.incrementAndGet();
        try {
            streamingExecutor.submit(() -> {
                try {
                    streamBaseline(conn, level, selection, streamGen, selectionGen);
                } finally {
                    activeBaselineStreams.decrementAndGet();
                }
            });
        } catch (Exception e) {
            activeBaselineStreams.decrementAndGet();
            Yefira.LOGGER.error("Failed to schedule baseline stream", e);
        }
    }

    private boolean isStreamCurrent(WebSocket conn, long streamGen, long selectionGen) {
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        if (selectionGeneration.get() != selectionGen) {
            return false;
        }
        AtomicLong gen = clientStreamGenerations.get(conn);
        return gen != null && gen.get() == streamGen;
    }

    private boolean sendStreamFrame(WebSocket conn, long streamGen, long selectionGen, byte[] data) {
        Object lock = sendLockOf(conn);
        synchronized (lock) {
            if (!isStreamCurrent(conn, streamGen, selectionGen)) {
                return false;
            }
            return sendSafe(conn, data);
        }
    }

    /**
     * Atomically and safely sends text frame data to a client, gracefully handling disconnections and cleaning up state.
     */
    public boolean sendSafe(WebSocket conn, String data) {
        if (conn == null || !conn.isOpen()) {
            if (conn != null) {
                cleanupClient(conn);
            }
            return false;
        }
        try {
            conn.send(data);
            return true;
        } catch (WebsocketNotConnectedException e) {
            cleanupClient(conn);
            return false;
        } catch (Exception e) {
            Yefira.LOGGER.warn("Failed to send data to client {}: {}", conn.getRemoteSocketAddress(), e.getMessage());
            cleanupClient(conn);
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
                    startBaselineStream(conn, selectionManager.getCurrentLevel(), selectionManager.getCurrentSelection());
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
                if (message.remaining() >= 1) {
                    // epoch(8) + snapshot_uuid(16) + committed_seq(8) + result_code(1)
                    if (message.remaining() >= 33) {
                        message.position(message.position() + 32);
                        byte resultCode = message.get();
                        if (resultCode != 0) {
                            Yefira.LOGGER.warn("Client {} rejected the baseline (result code {}); next RESYNC will stream a fresh snapshot",
                                    conn.getRemoteSocketAddress(), resultCode);
                        } else {
                            Yefira.LOGGER.info("Client {} committed baseline snapshot", conn.getRemoteSocketAddress());
                        }
                    } else {
                        Yefira.LOGGER.info("Client {} acknowledged baseline commit", conn.getRemoteSocketAddress());
                    }
                }
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_CANCEL) {
                // The DCC client aborted the current baseline (e.g. selection too large).
                // Stop streaming immediately instead of pushing the rest of the snapshot.
                cancelClientStream(conn);
                Yefira.LOGGER.info("Client {} cancelled the baseline stream", conn.getRemoteSocketAddress());
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_RESYNC_REQUEST || packetType == BlockDataEncoder.PACKET_C2S_REQ_FULL_SYNC) {
                SelectionManager sm = SelectionManager.getInstance();
                if (sm.hasSelection() && sm.getCurrentLevel() != null) {
                    // Supersede any in-flight stream for this client before starting a new one.
                    startBaselineStream(conn, sm.getCurrentLevel(), sm.getCurrentSelection());
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
        boolean matchesDimension = hello.cachedDimension != null
                && !hello.cachedDimension.isEmpty()
                && hello.cachedDimension.equals(dimension);

        if (hasClientCache && matchesUuid && matchesEpoch && matchesSeq && matchesBounds
                && matchesDimension && cachedStateHash != 0 && hello.cachedStateHash == cachedStateHash) {
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
                startBaselineStream(conn, level, selection);
            }
        }
    }

    // --- SelectionChangeListener 接口实现 ---

    @Override
    public void onSelectionChanged(Level level, SelectionBox selection) {
        clearPendingDeltaChanges();
        cachedStateHash = 0;
        // Invalidate every scan/stream that is still working on the previous selection.
        selectionGeneration.incrementAndGet();
        if (clients.isEmpty()) return;

        Yefira.LOGGER.info("Broadcasting baseline for new selection to {} clients...", clients.size());
        broadcastBaseline(level, selection);
    }

    @Override
    public void onSelectionCleared() {
        clearPendingDeltaChanges();
        cachedStateHash = 0;
        selectionGeneration.incrementAndGet();
    }

    // --- 数据发送辅助方法 ---

    /**
     * Asynchronously streams the current selection baseline to every connected client.
     * Never blocks the Minecraft main/command thread.
     */
    public void broadcastBaseline(Level level, SelectionBox selection) {
        if (clients.isEmpty() || level == null || selection == null) return;
        for (WebSocket client : List.copyOf(clients)) {
            startBaselineStream(client, level, selection);
        }
    }

    private void streamBaseline(WebSocket conn, Level level, SelectionBox selection,
                                long streamGen, long selectionGen) {
        try {
            if (!isStreamCurrent(conn, streamGen, selectionGen)) return;

            // Hard protocol guard: never scan/allocate a selection that the DCC
            // client will reject, and never OOM the Minecraft process on a runaway box.
            long volume = selection.getVolume();
            if (volume > BlockDataEncoder.MAX_SELECTION_VOLUME) {
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_RESOURCE_LIMIT_EXCEEDED,
                        "Selection volume " + volume + " exceeds the "
                                + BlockDataEncoder.MAX_SELECTION_VOLUME
                                + " voxel Live Sync limit; shrink the selection"));
                Yefira.LOGGER.warn("Refusing to stream oversized selection ({} voxels) to {}",
                        volume, conn.getRemoteSocketAddress());
                return;
            }

            // Capture the snapshot barrier BEFORE scanning. Any block change applied
            // while the (potentially long) scan is running then receives a sequence
            // number greater than the snapshot and is replayed by the client.
            // Serialized with delta broadcasts so no delta can be encoded against the
            // old palette with a sequence > this snapshot barrier.
            long snapshotSeq;
            synchronized (paletteLock) {
                snapshotSeq = globalSeqId.get();
            }

            String dimension = VersionCompat.level().getDimensionId(level);
            CanonicalVoxelScanner.BaselineScanResult scan = CanonicalVoxelScanner.scanBaseline(
                    level, selection, dimension,
                    () -> !isStreamCurrent(conn, streamGen, selectionGen)
            );
            if (scan == null) {
                Yefira.LOGGER.debug("Baseline scan for {} aborted (superseded)", conn.getRemoteSocketAddress());
                return;
            }
            if (!isStreamCurrent(conn, streamGen, selectionGen)) return;

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
            if (!sendStreamFrame(conn, streamGen, selectionGen, beginPkt)) return;

            int bytesPerVoxel = Math.max(1, scan.paletteBitsPerBlock / 8);
            int voxelsPerChunk = chunkSize / bytesPerVoxel;

            for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (!isStreamCurrent(conn, streamGen, selectionGen)) {
                    return;
                }
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
                if (!sendStreamFrame(conn, streamGen, selectionGen, chunkPkt)) return;
            }

            byte[] endPkt = BlockDataEncoder.encodeBaselineEnd(
                    epoch,
                    snapshotUuid,
                    snapshotSeq,
                    totalChunks,
                    scan.stateHash,
                    (byte) 0
            );
            if (sendStreamFrame(conn, streamGen, selectionGen, endPkt)) {
                Yefira.LOGGER.info("Baseline streamed to client {} ({} bytes, {} chunks, hash=0x{})",
                        conn.getRemoteSocketAddress(), totalBytes, totalChunks, Long.toHexString(scan.stateHash));
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to stream baseline to client {}", conn.getRemoteSocketAddress(), e);
        }
    }

    public void tickValidationHeartbeat(long currentTick) {
        // Heartbeats handled via native WebSocket ping/pong
    }

    /**
     * Broadcasts a delta batch to all clients.
     * @return false when the batch was deferred because a baseline palette is being
     *         (re)installed; the caller must keep the changes queued and retry later.
     */
    public boolean broadcastDeltaUpdate(SelectionBox selection, List<BlockDataEncoder.BlockChangeEntry> changes) {
        if (clients.isEmpty() || changes.isEmpty()) return true;
        try {
            synchronized (paletteLock) {
                // Re-check under the palette lock: a baseline may have started between
                // the caller's fast-path check and acquiring this lock. Encoding with
                // the old palette would corrupt replay on the client.
                if (activeBaselineStreams.get() > 0) {
                    return false;
                }

                BlockPos min = selection.getMin();

                // RFC v3.1 §2.2: one DELTA_OP may carry at most 16,384 changes and
                // every batch must occupy its own monotonically increasing sequence ID.
                // Split the flush into contiguous batches instead of overflowing the
                // uint16 change counter in the wire format.
                for (int start = 0; start < changes.size(); start += MAX_DELTA_BATCH_CHANGES) {
                    int end = Math.min(changes.size(), start + MAX_DELTA_BATCH_CHANGES);
                    List<BlockDataEncoder.BlockChangeEntry> batch = changes.subList(start, end);

                    long seqId = globalSeqId.incrementAndGet();
                    List<Map.Entry<Integer, String>> newPaletteEntries = new ArrayList<>();
                    List<BlockDataEncoder.DeltaEntry> deltaEntries = new ArrayList<>(batch.size());

                    for (BlockDataEncoder.BlockChangeEntry c : batch) {
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

                    byte[] palPkt = null;
                    if (!newPaletteEntries.isEmpty()) {
                        int oldRev = paletteRevision;
                        paletteRevision++;
                        palPkt = BlockDataEncoder.encodePaletteUpdate(epoch, seqId, oldRev, paletteRevision, newPaletteEntries);
                    }
                    byte[] deltaPkt = BlockDataEncoder.encodeDeltaOp(epoch, seqId, paletteRevision, deltaEntries);

                    // Send while still holding paletteLock so a concurrently starting
                    // baseline can neither capture its snapshot barrier before this batch
                    // nor reset the palette between encoding and transmission. WebSocket
                    // send() only enqueues the frame, so the lock is held briefly.
                    if (palPkt != null) {
                        for (WebSocket client : List.copyOf(clients)) {
                            sendSafe(client, palPkt);
                        }
                    }
                    for (WebSocket client : List.copyOf(clients)) {
                        sendSafe(client, deltaPkt);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Yefira.LOGGER.error("Error broadcasting delta update", e);
            return false;
        }
    }

    public void queueDeltaUpdate(SelectionBox selection, BlockDataEncoder.BlockChangeEntry change) {
        // Any world mutation invalidates the zero-rebuild fast path: the server's
        // cached snapshot hash no longer describes the authoritative world state.
        cachedStateHash = 0;
        if (clients.isEmpty()) return;
        synchronized (pendingDeltaChanges) {
            if (pendingDeltaSelection != null && !sameBounds(pendingDeltaSelection, selection)) {
                pendingDeltaChanges.clear();
            }
            pendingDeltaSelection = selection;
            pendingDeltaChanges.put(change.pos().immutable(), change);
            if (pendingDeltaChanges.size() > MAX_PENDING_DELTA_CHANGES) {
                // Extremely long baseline + heavy building: fall back to a fresh
                // full snapshot instead of silently dropping block edits.
                pendingDeltaOverflow = true;
                pendingDeltaChanges.clear();
            }
        }
    }

    public void flushQueuedDeltaUpdates() {
        try {
            // While a baseline is being scanned/streamed, defer deltas: their palette
            // must match the snapshot palette that the client will install on BEGIN.
            if (activeBaselineStreams.get() > 0) {
                return;
            }
            synchronized (pendingDeltaChanges) {
                if (pendingDeltaOverflow) {
                    pendingDeltaOverflow = false;
                    pendingDeltaChanges.clear();
                    pendingDeltaSelection = null;
                    SelectionManager sm = SelectionManager.getInstance();
                    if (sm.hasSelection() && sm.getCurrentLevel() != null) {
                        Yefira.LOGGER.warn("Deferred delta queue overflowed; resending a full baseline");
                        broadcastBaseline(sm.getCurrentLevel(), sm.getCurrentSelection());
                    }
                    return;
                }
                if (pendingDeltaChanges.isEmpty() || pendingDeltaSelection == null) return;
                SelectionBox selection = pendingDeltaSelection;
                List<BlockDataEncoder.BlockChangeEntry> changes = List.copyOf(pendingDeltaChanges.values());

                if (!broadcastDeltaUpdate(selection, changes)) {
                    // A baseline started concurrently; keep the queued changes and
                    // retry once its palette is installed.
                    return;
                }
                pendingDeltaChanges.clear();
                pendingDeltaSelection = null;
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Error flushing queued delta updates", e);
        }
    }

    private void clearPendingDeltaChanges() {
        synchronized (pendingDeltaChanges) {
            pendingDeltaChanges.clear();
            pendingDeltaSelection = null;
            pendingDeltaOverflow = false;
        }
    }

    private static boolean sameBounds(SelectionBox a, SelectionBox b) {
        return a.getMin().equals(b.getMin()) && a.getMax().equals(b.getMax());
    }
}

