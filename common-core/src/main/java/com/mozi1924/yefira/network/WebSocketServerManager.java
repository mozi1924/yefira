package com.mozi1924.yefira.network;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.compat.ClientThread;
import com.mozi1924.yefira.compat.ServerThread;
import com.mozi1924.yefira.compat.VersionCompat;
import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.encoder.BlockDataEncoder;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.framing.PongFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.mozi1924.yefira.encoder.CanonicalVoxelScanner;
import com.mozi1924.yefira.encoder.XXHash64;


public class WebSocketServerManager implements SelectionManager.SelectionChangeListener {

    private static final WebSocketServerManager INSTANCE = new WebSocketServerManager();
    private static String HOST = "127.0.0.1";
    private static int PORT = 8765;

    /** RFC v3.1 message limit; Java-WebSocket defaults to Integer.MAX_VALUE. */
    private static final int MAX_FRAME_SIZE = 512 * 1024;
    private static final int MAX_CONNECTIONS = 8;
    private static final long HANDSHAKE_TIMEOUT_MS = 5_000L;

    private Impl serverImpl;
    private volatile boolean serverStarted = false;
    private volatile CountDownLatch startLatch = new CountDownLatch(1);
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Connections that completed the WebSocket handshake but not HELLO yet. */
    private final Set<WebSocket> pendingClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<WebSocket, Long> pendingSince = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockDataEncoder.BlockChangeEntry> pendingDeltaChanges = new LinkedHashMap<>();
    private SelectionBox pendingDeltaSelection;
    private long lastManifestBroadcastTick = 0;
    private final AtomicLong globalSeqId = new AtomicLong(1);

    // RFC v3.1 Session State
    private volatile byte[] serverUuidBytes = new byte[16];
    private volatile long epoch = 1;
    private volatile long cachedStateHash = 0;
    private final Object paletteLock = new Object();
    private final Map<String, Integer> blockPaletteLookup = new HashMap<>();
    private final List<String> blockPaletteList = new ArrayList<>();
    private int paletteRevision = 1;

    public static class ClientConfig {
        public byte throttleMode = 0; // 0 = NORMAL, 1 = ECO, 2 = PAUSED
        public byte targetFps = 60;
        public boolean isActive = true;
        public long lastRefreshAt = 0L;
        public long lastResyncAt = 0L;
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

    /**
     * Grace period added to the scanner's own deadline before the waiting worker
     * gives up. The scanner normally throws {@link CanonicalVoxelScanner.ScanTimeoutException}
     * first, which lets us report a precise timeout to the client.
     */
    private static final long BASELINE_SCAN_TIMEOUT_SECONDS =
            TimeUnit.MILLISECONDS.toSeconds(CanonicalVoxelScanner.DEFAULT_MAX_SCAN_MILLIS) + 10L;

    /**
     * Local export scans run on the render/client thread, so they get a much
     * smaller budget than server scans. A local selection that needs longer
     * than this should be split into smaller boxes.
     */
    private static final long CLIENT_SCAN_TIMEOUT_MILLIS = 30_000L;

    /** Raised when a local export selection spans client chunks that are not loaded. */
    private static final class MissingClientChunksException extends RuntimeException {
        MissingClientChunksException(String message) {
            super(message);
        }
    }

    /** Immutable identity of the baseline currently being streamed to one client. */
    private static final class BaselineToken {
        final long epoch;
        final byte[] snapshotUuid;
        final long snapshotSeq;
        final long streamGen;

        BaselineToken(long epoch, byte[] snapshotUuid, long snapshotSeq, long streamGen) {
            this.epoch = epoch;
            this.snapshotUuid = snapshotUuid;
            this.snapshotSeq = snapshotSeq;
            this.streamGen = streamGen;
        }
    }

    private final Map<WebSocket, BaselineToken> clientSnapshots = new ConcurrentHashMap<>();
    private final Map<WebSocket, Long> clientStreamEpochs = new ConcurrentHashMap<>();

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
        return serverImpl != null && serverStarted;
    }

    public int getConnectedCount() {
        return clients.size();
    }

    private WebSocketServerManager() {
        new Random().nextBytes(serverUuidBytes);
    }


    public synchronized boolean startServer() {
        return startServer(HOST, PORT);
    }

    public synchronized boolean startServer(String host, int port) {
        if (serverImpl != null && serverStarted) {
            Yefira.LOGGER.info("WebSocket Server is already running on {}:{}", HOST, PORT);
            return true;
        }
        setHost(host);
        setPort(port);

        String token = YefiraConfig.getInstance().getAuthToken();
        if (!isLoopbackHost(HOST) && (token == null || token.isEmpty())) {
            Yefira.LOGGER.error(
                "Refusing to bind WebSocket Server to non-loopback host {} without authToken. "
                    + "Set /yefira config token <secret> or bind 127.0.0.1.",
                HOST
            );
            return false;
        }

        try {
            startLatch = new CountDownLatch(1);
            serverStarted = false;
            serverImpl = new Impl(
                new InetSocketAddress(HOST, PORT),
                new Draft_6455(Collections.emptyList(), MAX_FRAME_SIZE)
            );
            serverImpl.setReuseAddr(true);
            serverImpl.setMaxPendingConnections(MAX_CONNECTIONS * 2);
            serverImpl.start();
            boolean started = startLatch.await(3, TimeUnit.SECONDS) && serverStarted;
            if (!started) {
                Yefira.LOGGER.error("WebSocket Server failed to bind {}:{} (see onError above)", HOST, PORT);
                try {
                    serverImpl.stop(500);
                } catch (Exception ignored) {
                } finally {
                    serverImpl = null;
                    serverStarted = false;
                }
                return false;
            }
            SelectionManager.getInstance().addListener(this);
            Yefira.LOGGER.info("WebSocket Server started on {}:{}", HOST, PORT);
            return true;
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to start WebSocket Server on " + HOST + ":" + PORT, e);
            serverImpl = null;
            serverStarted = false;
            return false;
        }
    }

    public synchronized boolean restartServer(String host, int port) {
        stopServer();
        return startServer(host, port);
    }

    public synchronized boolean restartServer() {
        stopServer();
        return startServer(HOST, PORT);
    }

    public synchronized void stopServer() {
        SelectionManager.getInstance().removeListener(this);
        clearPendingDeltaChanges();

        // Abort every in-flight baseline scan/stream before tearing the server down.
        selectionGeneration.incrementAndGet();
        for (WebSocket client : List.copyOf(clients)) {
            cancelClientStream(client);
        }
        for (WebSocket client : List.copyOf(pendingClients)) {
            cancelClientStream(client);
            try {
                client.close(1001, "Server stopping");
            } catch (Exception ignored) {
            }
        }
        clientConfigs.clear();
        clients.clear();
        pendingClients.clear();
        pendingSince.clear();
        clientStreamGenerations.clear();
        clientSendLocks.clear();
        clientSnapshots.clear();
        clientStreamEpochs.clear();

        // A restart is a new authoritative session: rotate the epoch and world
        // UUID so a fast reconnect cannot present state from the previous run.
        synchronized (paletteLock) {
            epoch++;
            serverUuidBytes = new byte[16];
            new Random().nextBytes(serverUuidBytes);
            cachedStateHash = 0;
            globalSeqId.set(1);
            paletteRevision = 1;
            blockPaletteList.clear();
            blockPaletteLookup.clear();
        }

        serverStarted = false;
        startLatch.countDown();

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

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(host.trim()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLoopbackConnection(WebSocket conn) {
        if (conn == null || conn.getRemoteSocketAddress() == null) {
            return false;
        }
        InetAddress address = conn.getRemoteSocketAddress().getAddress();
        return address != null && address.isLoopbackAddress();
    }

    private class Impl extends WebSocketServer {
        public Impl(InetSocketAddress address, Draft_6455 draft) {
            super(address, Collections.singletonList(draft));
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
            // Native clients (Rust/Blender) send no Origin. Reject browser-origin
            // connections by default: this endpoint streams the selected world.
            String origin = handshake != null ? handshake.getFieldValue("Origin") : null;
            if (origin != null && !origin.isBlank()) {
                Yefira.LOGGER.warn("Rejecting DCC connection with browser Origin: {}", origin);
                conn.close(1008, "Browser origins are not allowed");
                return;
            }
            if (clients.size() + pendingClients.size() >= MAX_CONNECTIONS) {
                Yefira.LOGGER.warn("Rejecting DCC connection: max connections ({}) reached", MAX_CONNECTIONS);
                conn.close(1013, "Too many connections");
                return;
            }
            pendingClients.add(conn);
            pendingSince.put(conn, System.currentTimeMillis());
            clientConfigs.put(conn, new ClientConfig());
            Yefira.LOGGER.info("New DCC client connected (pending HELLO): {}", conn.getRemoteSocketAddress());
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
            } else {
                // Global error: bind failure, selector death, etc.
                serverStarted = false;
                startLatch.countDown();
            }
            if (ex instanceof WebsocketNotConnectedException) {
                Yefira.LOGGER.debug("WebSocket client disconnected: {}", conn != null ? conn.getRemoteSocketAddress() : "unknown");
            } else {
                Yefira.LOGGER.error("WebSocket error on connection: {}", conn != null ? conn.getRemoteSocketAddress() : "global", ex);
            }
        }

        @Override
        public void onStart() {
            serverStarted = true;
            startLatch.countDown();
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
        pendingClients.remove(conn);
        pendingSince.remove(conn);
        clientConfigs.remove(conn);
        clientStreamGenerations.remove(conn);
        clientSendLocks.remove(conn);
        clientSnapshots.remove(conn);
        clientStreamEpochs.remove(conn);
    }

    /**
     * Invalidates any in-flight baseline stream for a single client.
     */
    private void cancelClientStream(WebSocket conn) {
        AtomicLong gen = clientStreamGenerations.get(conn);
        if (gen != null) {
            gen.incrementAndGet();
        }
        // Any snapshot still awaiting ACK belongs to the cancelled stream.
        clientSnapshots.remove(conn);
        clientStreamEpochs.remove(conn);
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
        SelectionManager selectionManager = SelectionManager.getInstance();
        if (level.isClientSide()) {
            // Local export mode: the selection intentionally comes from the
            // client's loaded chunks so structures can be exported from a
            // server that does not expose its save file. This is only allowed
            // when the selection manager is in LOCAL_CLIENT mode, and the scan
            // itself is scheduled on the client thread (never a worker).
            if (!selectionManager.isLocalClientSelection()) {
                Yefira.LOGGER.warn("Refusing client-side scan for {}: selection source is not local-client",
                        conn.getRemoteSocketAddress());
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_INVALID_BOUNDS,
                        "Client-side selection is disabled in server sync mode"));
                return;
            }
            if (!ClientThread.isAvailable()) {
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_INVALID_BOUNDS,
                        "Client is not ready to scan; join a world and retry"));
                Yefira.LOGGER.warn("Refusing local scan for {}: client-thread executor is unavailable",
                        conn.getRemoteSocketAddress());
                return;
            }
        } else if (!ServerThread.isAvailable()) {
            // Do not fall back to scanning on this streaming worker: Level
            // access off the server thread is unsafe and can race the tick.
            sendSafe(conn, BlockDataEncoder.encodeError(
                    epoch,
                    BlockDataEncoder.ERROR_INVALID_BOUNDS,
                    "Server is not ready to scan; retry after the world finishes loading"));
            Yefira.LOGGER.warn("Refusing to scan for {}: server-thread executor is unavailable",
                    conn.getRemoteSocketAddress());
            return;
        }
        long streamGen = streamGenerationOf(conn).incrementAndGet();
        long selectionGen = selectionGeneration.get();
        long streamEpoch = epoch;
        // A new stream supersedes any snapshot that is still awaiting ACK.
        clientSnapshots.remove(conn);
        clientStreamEpochs.put(conn, streamEpoch);
        activeBaselineStreams.incrementAndGet();
        try {
            streamingExecutor.submit(() -> {
                try {
                    streamBaseline(conn, level, selection, streamGen, selectionGen, streamEpoch);
                } finally {
                    activeBaselineStreams.decrementAndGet();
                    clientStreamEpochs.remove(conn, streamEpoch);
                }
            });
        } catch (Exception e) {
            activeBaselineStreams.decrementAndGet();
            clientStreamEpochs.remove(conn, streamEpoch);
            Yefira.LOGGER.error("Failed to schedule baseline stream", e);
        }
    }

    private boolean isStreamCurrent(WebSocket conn, long streamGen, long selectionGen, long streamEpoch) {
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        if (epoch != streamEpoch || selectionGeneration.get() != selectionGen) {
            return false;
        }
        AtomicLong gen = clientStreamGenerations.get(conn);
        return gen != null && gen.get() == streamGen;
    }

    private boolean sendStreamFrame(WebSocket conn, long streamGen, long selectionGen, long streamEpoch, byte[] data) {
        Object lock = sendLockOf(conn);
        synchronized (lock) {
            if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) {
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
            String trimmed = message == null ? "" : message.trim();
            if ("PING".equalsIgnoreCase(trimmed)) {
                sendSafe(conn, "PONG");
                return;
            }
            if (!clients.contains(conn)) {
                return;
            }
            if ("REFRESH".equalsIgnoreCase(trimmed)) {
                ClientConfig cfg = clientConfigs.computeIfAbsent(conn, k -> new ClientConfig());
                long now = System.currentTimeMillis();
                if (now - cfg.lastRefreshAt < 1000L) {
                    return;
                }
                cfg.lastRefreshAt = now;
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
            if (message.remaining() > MAX_FRAME_SIZE) {
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_RESOURCE_LIMIT_EXCEEDED,
                        "Message exceeds the 512 KiB Live Sync limit"));
                conn.close(1009, "Message too big");
                cleanupClient(conn);
                return;
            }

            message.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte b0 = message.get();
            byte b1 = message.get();

            if (b0 != BlockDataEncoder.MAGIC[0] || b1 != BlockDataEncoder.MAGIC[1]) {
                return;
            }

            byte version = message.get();
            byte packetType = message.get();

            if (version != BlockDataEncoder.PROTOCOL_VERSION) {
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_UNSUPPORTED_VERSION,
                        "Unsupported protocol version " + (version & 0xFF)));
                conn.close(1002, "Unsupported protocol version");
                cleanupClient(conn);
                return;
            }

            // Only HELLO/PING/SYNC_CONFIG are accepted before authentication.
            if (packetType != BlockDataEncoder.OPCODE_HELLO
                    && packetType != BlockDataEncoder.OPCODE_PING
                    && packetType != BlockDataEncoder.PACKET_C2S_SYNC_CONFIG
                    && !clients.contains(conn)) {
                return;
            }

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
                // epoch(8) + snapshot_uuid(16) + committed_seq(8) + result_code(1)
                if (message.remaining() < 33) {
                    Yefira.LOGGER.warn("Ignoring malformed BASELINE_ACK from {} ({} payload bytes)",
                            conn.getRemoteSocketAddress(), message.remaining());
                    return;
                }
                long ackEpoch = message.getLong();
                byte[] ackUuid = new byte[16];
                message.get(ackUuid);
                long committedSeq = message.getLong();
                byte resultCode = message.get();

                BaselineToken token = clientSnapshots.get(conn);
                if (token == null) {
                    Yefira.LOGGER.warn("Ignoring stale BASELINE_ACK from {}: no snapshot is awaiting commit",
                            conn.getRemoteSocketAddress());
                    return;
                }
                if (ackEpoch != epoch || ackEpoch != token.epoch
                        || !Arrays.equals(ackUuid, token.snapshotUuid)
                        || committedSeq != token.snapshotSeq) {
                    Yefira.LOGGER.warn(
                            "Ignoring stale BASELINE_ACK from {}: epoch={}, seq={} (current epoch={}, expected seq={})",
                            conn.getRemoteSocketAddress(), ackEpoch, committedSeq, epoch, token.snapshotSeq);
                    return;
                }

                clientSnapshots.remove(conn, token);
                if (resultCode != 0) {
                    Yefira.LOGGER.warn("Client {} rejected the baseline (result code {}); next RESYNC will stream a fresh snapshot",
                            conn.getRemoteSocketAddress(), resultCode);
                } else {
                    Yefira.LOGGER.info("Client {} committed baseline snapshot", conn.getRemoteSocketAddress());
                }
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_CANCEL) {
                // The DCC client aborted the current baseline (e.g. selection too large).
                // Only cancel when the packet belongs to the current epoch/snapshot;
                // otherwise a delayed CANCEL from an old session could kill a fresh stream.
                if (message.remaining() < 25) {
                    Yefira.LOGGER.warn("Ignoring malformed CANCEL from {} ({} payload bytes)",
                            conn.getRemoteSocketAddress(), message.remaining());
                    return;
                }
                long cancelEpoch = message.getLong();
                byte[] cancelUuid = new byte[16];
                message.get(cancelUuid);
                byte reason = message.get();

                if (cancelEpoch != epoch) {
                    Yefira.LOGGER.debug("Ignoring stale CANCEL from {}: packet epoch={}, current epoch={}",
                            conn.getRemoteSocketAddress(), cancelEpoch, epoch);
                    return;
                }

                BaselineToken token = clientSnapshots.get(conn);
                boolean zeroUuid = true;
                for (byte b : cancelUuid) {
                    if (b != 0) {
                        zeroUuid = false;
                        break;
                    }
                }
                if (zeroUuid) {
                    // A zero UUID means "cancel whatever is running for this
                    // connection", which covers a scan that has not produced its
                    // snapshot UUID yet.
                    Long activeEpoch = clientStreamEpochs.get(conn);
                    if (activeEpoch == null || activeEpoch != epoch) {
                        Yefira.LOGGER.debug("Ignoring CANCEL from {}: no active baseline stream",
                                conn.getRemoteSocketAddress());
                        return;
                    }
                } else if (token == null
                        || token.epoch != epoch
                        || !Arrays.equals(cancelUuid, token.snapshotUuid)) {
                    Yefira.LOGGER.warn("Ignoring stale CANCEL from {}: snapshot UUID does not match the active baseline",
                            conn.getRemoteSocketAddress());
                    return;
                }

                cancelClientStream(conn);
                clientSnapshots.remove(conn);
                clientStreamEpochs.remove(conn);
                Yefira.LOGGER.info("Client {} cancelled the baseline stream (reason {})",
                        conn.getRemoteSocketAddress(), reason);
                return;
            }

            if (packetType == BlockDataEncoder.OPCODE_RESYNC_REQUEST || packetType == BlockDataEncoder.PACKET_C2S_REQ_FULL_SYNC) {
                ClientConfig cfg = clientConfigs.computeIfAbsent(conn, k -> new ClientConfig());
                long now = System.currentTimeMillis();
                if (now - cfg.lastResyncAt < 900L) {
                    return;
                }
                cfg.lastResyncAt = now;
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

    private void activateClient(WebSocket conn) {
        pendingClients.remove(conn);
        pendingSince.remove(conn);
        clients.add(conn);
        clientConfigs.computeIfAbsent(conn, k -> new ClientConfig());
        Yefira.LOGGER.info("DCC client authenticated: {}", conn.getRemoteSocketAddress());
    }

    private boolean isAuthorized(WebSocket conn, BlockDataEncoder.HelloPacket hello) {
        YefiraConfig cfg = YefiraConfig.getInstance();
        // Explicit opt-in: skip the token for connections that actually arrive
        // over a loopback socket. Checking the remote address (not the bind
        // host) also covers the common `0.0.0.0` bind used by existing configs,
        // while remote clients still have to present the token.
        if (cfg.isAllowLoopbackWithoutToken() && isLoopbackConnection(conn)) {
            return true;
        }
        String expected = cfg.getAuthToken();
        if (expected == null || expected.isEmpty()) {
            // No token configured: loopback-only binding is enforced by startServer.
            return true;
        }
        // YefiraConfig already trims the configured token; trim the presented one
        // too so copy/paste whitespace cannot make two identical-looking tokens
        // fail with "Authentication failed".
        String provided = hello.authToken == null ? "" : hello.authToken.trim();
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private void sendWelcomeRejected(WebSocket conn, String reason) {
        long currentEpoch = epoch;
        byte[] welcome = BlockDataEncoder.encodeWelcome(
                (short) 3,
                serverUuidBytes,
                currentEpoch,
                globalSeqId.get(),
                null,
                0, 0, 0,
                0,
                BlockDataEncoder.DECISION_REJECTED,
                reason
        );
        sendSafe(conn, welcome);
    }

    private void handleClientHello(WebSocket conn, BlockDataEncoder.HelloPacket hello) {
        if (hello.minVersion > BlockDataEncoder.PROTOCOL_VERSION
                || hello.maxVersion < BlockDataEncoder.PROTOCOL_VERSION) {
            sendWelcomeRejected(conn, "Unsupported protocol version");
            conn.close(1002, "Unsupported protocol version");
            cleanupClient(conn);
            return;
        }
        if (!isAuthorized(conn, hello)) {
            Yefira.LOGGER.warn("Rejected DCC client {}: invalid auth token", conn.getRemoteSocketAddress());
            sendWelcomeRejected(conn, "Authentication failed");
            conn.close(1008, "Authentication failed");
            cleanupClient(conn);
            return;
        }
        activateClient(conn);

        // Capture the session identity once so a concurrent restart cannot make
        // two reads in this handshake observe different epochs/UUIDs.
        long currentEpoch = epoch;
        byte[] currentUuid = serverUuidBytes;
        long currentSeq = globalSeqId.get();

        SelectionManager sm = SelectionManager.getInstance();
        if (!sm.hasSelection() || sm.getCurrentLevel() == null) {
            byte[] welcome = BlockDataEncoder.encodeWelcome(
                    (short) 3,
                    currentUuid,
                    currentEpoch,
                    currentSeq,
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
        boolean matchesUuid = Arrays.equals(hello.cachedWorldUuid, currentUuid);
        boolean matchesEpoch = hello.cachedEpoch == currentEpoch;
        boolean matchesSeq = hello.cachedSeq == currentSeq;
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
                    currentUuid,
                    currentEpoch,
                    currentSeq,
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
                    currentUuid,
                    currentEpoch,
                    currentSeq,
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
        for (WebSocket client : List.copyOf(clients)) {
            cancelClientStream(client);
            sendSafe(client, BlockDataEncoder.encodeCancel(epoch, new byte[16], (byte) 0x01));
        }
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
                                long streamGen, long selectionGen, long streamEpoch) {
        try {
            if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) return;

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

            // Bound the chunk footprint independently of the voxel volume: a
            // 4096x1x8192 selection is legal by volume but spans >100k chunks and
            // would force the server thread to load the entire area.
            long chunkColumns = (((long) selection.getSizeX() + 15L) / 16L)
                    * (((long) selection.getSizeZ() + 15L) / 16L);
            if (chunkColumns > 16_384L) {
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_RESOURCE_LIMIT_EXCEEDED,
                        "Selection spans " + chunkColumns + " chunk columns; the Live Sync limit is 16384"));
                Yefira.LOGGER.warn("Refusing to scan {} chunk columns for {}", chunkColumns, conn.getRemoteSocketAddress());
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

            // Level/chunk access is owner-thread-only: ServerLevel on the server
            // thread, ClientLevel on the client thread. This worker waits for the
            // immutable result and then serializes/sends it without touching Level.
            boolean clientLevel = level.isClientSide();
            long scanTimeoutMillis = clientLevel
                    ? CLIENT_SCAN_TIMEOUT_MILLIS
                    : CanonicalVoxelScanner.DEFAULT_MAX_SCAN_MILLIS;
            CompletableFuture<CanonicalVoxelScanner.BaselineScanResult> scanFuture = new CompletableFuture<>();
            Runnable scanTask = () -> {
                try {
                    if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) {
                        scanFuture.complete(null);
                        return;
                    }
                    if (clientLevel && !hasLoadedClientChunks(level, selection)) {
                        scanFuture.completeExceptionally(new MissingClientChunksException(
                                "Local export selection includes chunks that are not loaded on this client; "
                                        + "move closer or shrink the selection"));
                        return;
                    }
                    String dimension = VersionCompat.level().getDimensionId(level);
                    scanFuture.complete(CanonicalVoxelScanner.scanBaseline(
                            level, selection, dimension,
                            () -> !isStreamCurrent(conn, streamGen, selectionGen, streamEpoch),
                            scanTimeoutMillis
                    ));
                } catch (Throwable t) {
                    scanFuture.completeExceptionally(t);
                }
            };
            try {
                if (clientLevel) {
                    ClientThread.executeChecked(scanTask);
                } else {
                    ServerThread.executeChecked(scanTask);
                }
            } catch (Throwable t) {
                scanFuture.completeExceptionally(t);
            }

            CanonicalVoxelScanner.BaselineScanResult scan;
            try {
                scan = scanFuture.get(BASELINE_SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                streamGenerationOf(conn).incrementAndGet();
                sendSafe(conn, BlockDataEncoder.encodeError(
                        epoch,
                        BlockDataEncoder.ERROR_RESOURCE_LIMIT_EXCEEDED,
                        "Selection scan exceeded the time limit; shrink the selection and retry"));
                Yefira.LOGGER.warn("Baseline scan timed out for {}", conn.getRemoteSocketAddress());
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MissingClientChunksException) {
                    streamGenerationOf(conn).incrementAndGet();
                    sendSafe(conn, BlockDataEncoder.encodeError(
                            epoch,
                            BlockDataEncoder.ERROR_INVALID_BOUNDS,
                            cause.getMessage()));
                    Yefira.LOGGER.warn("Local export selection for {} spans unloaded chunks",
                            conn.getRemoteSocketAddress());
                } else if (cause instanceof CanonicalVoxelScanner.ScanTimeoutException) {
                    streamGenerationOf(conn).incrementAndGet();
                    sendSafe(conn, BlockDataEncoder.encodeError(
                            epoch,
                            BlockDataEncoder.ERROR_RESOURCE_LIMIT_EXCEEDED,
                            "Selection scan exceeded the time limit; shrink the selection and retry"));
                    Yefira.LOGGER.warn("Baseline scan watchdog fired for {}", conn.getRemoteSocketAddress());
                } else {
                    Yefira.LOGGER.error("Baseline scan failed for " + conn.getRemoteSocketAddress(), cause);
                }
                return;
            } catch (Exception e) {
                Yefira.LOGGER.error("Baseline scan failed for " + conn.getRemoteSocketAddress(), e);
                return;
            }
            if (scan == null) {
                Yefira.LOGGER.debug("Baseline scan for {} aborted (superseded)", conn.getRemoteSocketAddress());
                return;
            }
            if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) return;

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
            clientSnapshots.put(conn, new BaselineToken(streamEpoch, snapshotUuid, snapshotSeq, streamGen));

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
                    scan.dimension,
                    scan.blockPalette,
                    scan.biomePalette
            );
            if (!sendStreamFrame(conn, streamGen, selectionGen, streamEpoch, beginPkt)) return;

            int bytesPerVoxel = Math.max(1, scan.paletteBitsPerBlock / 8);
            int voxelsPerChunk = chunkSize / bytesPerVoxel;

            for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) {
                    return;
                }
                int offset = chunkIdx * chunkSize;
                int len = Math.min(chunkSize, totalBytes - offset);
                byte[] chunkPayload = new byte[len];
                System.arraycopy(payload, offset, chunkPayload, 0, len);

                // Clamp the cursor to the exact voxel count. Without this, a trailing
                // biome-only chunk reports `k * voxelsPerChunk` (> totalVoxels) and the
                // client rejects the stream as a sequence gap (endless resync).
                long firstVoxelIdx = Math.min((long) chunkIdx * voxelsPerChunk, scan.totalVoxels);
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
                if (!sendStreamFrame(conn, streamGen, selectionGen, streamEpoch, chunkPkt)) return;

                // Bounded backpressure: Java-WebSocket's outbound queue is
                // unbounded. A client that stops reading must not be allowed to
                // accumulate the whole baseline in memory.
                long deadline = System.currentTimeMillis() + 5_000L;
                while (conn.hasBufferedData() && System.currentTimeMillis() < deadline) {
                    if (!isStreamCurrent(conn, streamGen, selectionGen, streamEpoch)) return;
                    Thread.sleep(10L);
                }
                if (conn.hasBufferedData()) {
                    Yefira.LOGGER.warn("Dropping slow DCC client {}: outbound queue did not drain",
                            conn.getRemoteSocketAddress());
                    try {
                        conn.close(1008, "Slow client");
                    } catch (Exception ignored) {
                    }
                    cleanupClient(conn);
                    return;
                }
            }

            if (scan.entities != null && !scan.entities.isEmpty()) {
                byte[] entitiesPkt = BlockDataEncoder.encodeBaselineEntities(
                        epoch,
                        snapshotUuid,
                        scan.entities
                );
                if (!sendStreamFrame(conn, streamGen, selectionGen, streamEpoch, entitiesPkt)) return;
            }

            byte[] endPkt = BlockDataEncoder.encodeBaselineEnd(
                    epoch,
                    snapshotUuid,
                    snapshotSeq,
                    totalChunks,
                    scan.stateHash,
                    (byte) 0
            );
            if (sendStreamFrame(conn, streamGen, selectionGen, streamEpoch, endPkt)) {
                Yefira.LOGGER.info("Baseline streamed to client {} ({} bytes, {} chunks, hash=0x{})",
                        conn.getRemoteSocketAddress(), totalBytes, totalChunks, Long.toHexString(scan.stateHash));
            }
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to stream baseline to client {}", conn.getRemoteSocketAddress(), e);
        }
    }

    /**
     * Checks that every chunk column touched by a local export selection is
     * currently loaded on the client. Runs on the client thread only.
     */
    private static boolean hasLoadedClientChunks(Level level, SelectionBox selection) {
        int minX = selection.getMin().getX();
        int minZ = selection.getMin().getZ();
        int maxX = selection.getMax().getX();
        int maxZ = selection.getMax().getZ();
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void tickValidationHeartbeat(long currentTick) {
        // Purge connections that finished the WebSocket handshake but never sent
        // HELLO, and keep the per-client maps bounded.
        long now = System.currentTimeMillis();
        for (WebSocket client : List.copyOf(pendingClients)) {
            Long since = pendingSince.get(client);
            if (!client.isOpen()) {
                cleanupClient(client);
                continue;
            }
            if (since == null || now - since > HANDSHAKE_TIMEOUT_MS) {
                Yefira.LOGGER.warn("Closing DCC client {}: HELLO handshake timed out", client.getRemoteSocketAddress());
                pendingClients.remove(client);
                pendingSince.remove(client);
                clientConfigs.remove(client);
                try {
                    client.close(1008, "Handshake timeout");
                } catch (Exception ignored) {
                }
            }
        }
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

                    Level currentLevel = SelectionManager.getInstance().getCurrentLevel();
                    if (currentLevel != null) {
                        for (BlockDataEncoder.BlockChangeEntry c : batch) {
                            if (c.state().hasBlockEntity()) {
                                net.minecraft.world.level.block.entity.BlockEntity be = currentLevel.getBlockEntity(c.pos());
                                if (be != null) {
                                    String json = VersionCompat.level().serializeBlockEntityJson(be);
                                    if (json != null) {
                                        int rx = c.pos().getX() - min.getX();
                                        int ry = c.pos().getY() - min.getY();
                                        int rz = c.pos().getZ() - min.getZ();
                                        byte[] deltaEntPkt = BlockDataEncoder.encodeDeltaEntity(
                                                epoch,
                                                seqId,
                                                rx,
                                                ry,
                                                rz,
                                                json.getBytes(StandardCharsets.UTF_8)
                                        );
                                        for (WebSocket client : List.copyOf(clients)) {
                                            sendSafe(client, deltaEntPkt);
                                        }
                                    }
                                }
                            }
                        }
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

