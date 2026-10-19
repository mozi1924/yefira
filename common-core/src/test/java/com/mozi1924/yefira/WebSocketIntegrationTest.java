package com.mozi1924.yefira;

import com.mozi1924.yefira.config.YefiraConfig;
import com.mozi1924.yefira.network.WebSocketServerManager;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WebSocketIntegrationTest {

    private static final int TEST_PORT = 18765;
    private static final String TEST_HOST = "127.0.0.1";

    private static byte[] helloBytes(String token) {
        byte[] auth = token == null ? new byte[0] : token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] dim = "minecraft:overworld".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 2 + 4 + 4 + 16 + 8 + 8 + 24 + 8 + 2 + auth.length + 2 + dim.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(com.mozi1924.yefira.encoder.BlockDataEncoder.MAGIC);
        buf.put(com.mozi1924.yefira.encoder.BlockDataEncoder.PROTOCOL_VERSION);
        buf.put(com.mozi1924.yefira.encoder.BlockDataEncoder.OPCODE_HELLO);
        buf.putShort((short) 3);
        buf.putShort((short) 3);
        buf.putInt(0);
        buf.putInt(0);
        buf.put(new byte[16]);
        buf.putLong(0L);
        buf.putLong(0L);
        for (int i = 0; i < 6; i++) buf.putInt(0);
        buf.putLong(0L);
        buf.putShort((short) auth.length);
        buf.put(auth);
        buf.putShort((short) dim.length);
        buf.put(dim);
        return buf.array();
    }

    @BeforeEach
    public void setUp() throws Exception {
        WebSocketServerManager.getInstance().startServer(TEST_HOST, TEST_PORT);
        Thread.sleep(200);
    }

    @Test
    public void testWrongAuthTokenIsRejected() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        server.stopServer();
        YefiraConfig cfg = YefiraConfig.getInstance();
        String previous = cfg.getAuthToken();
        boolean previousBypass = cfg.isAllowLoopbackWithoutToken();
        cfg.setAllowLoopbackWithoutToken(false);
        cfg.setAuthToken("secret-token");
        java.util.concurrent.CompletableFuture<Boolean> closed = new java.util.concurrent.CompletableFuture<>();
        WebSocketClient client = null;
        try {
            Assertions.assertTrue(server.startServer(TEST_HOST, 18767));
            client = new WebSocketClient(new URI("ws://" + TEST_HOST + ":18767")) {
                @Override public void onOpen(ServerHandshake handshakedata) {
                    send(helloBytes("wrong-token"));
                }
                @Override public void onMessage(String message) { }
                @Override public void onClose(int code, String reason, boolean remote) {
                    closed.complete(true);
                }
                @Override public void onError(Exception ex) {
                    closed.complete(false);
                }
            };
            Assertions.assertTrue(client.connectBlocking());
            Assertions.assertTrue(closed.get(5, TimeUnit.SECONDS), "Server must close a client with a wrong token");
            Thread.sleep(100);
            Assertions.assertEquals(0, server.getConnectedCount());
        } finally {
            if (client != null) {
                try { client.closeBlocking(); } catch (Exception ignored) { }
            }
            cfg.setAuthToken(previous);
            cfg.setAllowLoopbackWithoutToken(previousBypass);
            server.stopServer();
        }
    }

    @Test
    public void testMatchingAuthTokenIsAccepted() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        server.stopServer();
        YefiraConfig cfg = YefiraConfig.getInstance();
        String previous = cfg.getAuthToken();
        boolean previousBypass = cfg.isAllowLoopbackWithoutToken();
        cfg.setAllowLoopbackWithoutToken(false);
        cfg.setAuthToken("secret-token");
        WebSocketClient client = null;
        try {
            Assertions.assertTrue(server.startServer(TEST_HOST, 18768));
            client = new WebSocketClient(new URI("ws://" + TEST_HOST + ":18768")) {
                @Override public void onOpen(ServerHandshake handshakedata) {
                    // Surrounding whitespace is normalised by both sides.
                    send(helloBytes("  secret-token  "));
                }
                @Override public void onMessage(String message) { }
                @Override public void onClose(int code, String reason, boolean remote) { }
                @Override public void onError(Exception ex) { }
            };
            Assertions.assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            Assertions.assertEquals(1, server.getConnectedCount(),
                    "A matching token must authenticate the DCC client");
        } finally {
            if (client != null) {
                try { client.closeBlocking(); } catch (Exception ignored) { }
            }
            cfg.setAuthToken(previous);
            cfg.setAllowLoopbackWithoutToken(previousBypass);
            server.stopServer();
        }
    }

    @Test
    public void testLoopbackTokenBypassAcceptsEmptyToken() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        server.stopServer();
        YefiraConfig cfg = YefiraConfig.getInstance();
        String previous = cfg.getAuthToken();
        boolean previousBypass = cfg.isAllowLoopbackWithoutToken();
        cfg.setAuthToken("secret-token");
        cfg.setAllowLoopbackWithoutToken(true);
        WebSocketClient client = null;
        try {
            Assertions.assertTrue(server.startServer(TEST_HOST, 18769));
            client = new WebSocketClient(new URI("ws://" + TEST_HOST + ":18769")) {
                @Override public void onOpen(ServerHandshake handshakedata) {
                    send(helloBytes(""));
                }
                @Override public void onMessage(String message) { }
                @Override public void onClose(int code, String reason, boolean remote) { }
                @Override public void onError(Exception ex) { }
            };
            Assertions.assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            Assertions.assertEquals(1, server.getConnectedCount(),
                    "Loopback binds may opt out of token validation");
        } finally {
            if (client != null) {
                try { client.closeBlocking(); } catch (Exception ignored) { }
            }
            cfg.setAuthToken(previous);
            cfg.setAllowLoopbackWithoutToken(previousBypass);
            server.stopServer();
        }
    }

    @Test
    public void testNonLoopbackWithoutTokenIsRejected() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        server.stopServer();
        YefiraConfig cfg = YefiraConfig.getInstance();
        String previous = cfg.getAuthToken();
        cfg.setAuthToken("");
        try {
            Assertions.assertFalse(server.startServer("0.0.0.0", 18766),
                    "A non-loopback bind must be refused without an auth token");
        } finally {
            cfg.setAuthToken(previous);
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        WebSocketServerManager.getInstance().stopServer();
        Thread.sleep(200);
    }

    @Test
    public void testServerLifecycleAndPingPong() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        Assertions.assertTrue(server.isRunning(), "Server should be running");
        Assertions.assertEquals(TEST_PORT, server.getPort());
        Assertions.assertEquals(TEST_HOST, server.getHost());

        CompletableFuture<String> pongFuture = new CompletableFuture<>();

        URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT);
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                send(helloBytes(""));
                send("PING");
            }

            @Override
            public void onMessage(String message) {
                if ("PONG".equalsIgnoreCase(message.trim())) {
                    pongFuture.complete(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {
                pongFuture.completeExceptionally(ex);
            }
        };

        boolean connected = client.connectBlocking(5, TimeUnit.SECONDS);
        Assertions.assertTrue(connected, "Client should connect successfully to WebSocket server");

        String response = pongFuture.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals("PONG", response);

        Thread.sleep(150);
        Assertions.assertTrue(server.getConnectedCount() >= 1, "Server should register HELLO-authenticated client");

        client.closeBlocking();
    }

    @Test
    public void testProtocolPingAndDisconnectionResilience() throws Exception {
        WebSocketServerManager server = WebSocketServerManager.getInstance();
        URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT);

        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {}

            @Override
            public void onMessage(String message) {}

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };

        boolean connected = client.connectBlocking(5, TimeUnit.SECONDS);
        Assertions.assertTrue(connected, "Client should connect successfully");

        // Send standard WebSocket ping frame
        client.sendPing();
        Thread.sleep(100);

        // Test safe send while client is alive
        boolean sent = server.sendSafe(client, "TEST_SAFE_SEND");
        // Note: client is WebSocketClient not server's WebSocket, test server's safe broadcast/send directly:
        server.broadcastBaseline(null, null); // should safely no-op without crash

        // Abrupt close
        client.close();
        Thread.sleep(100);

        // Server should still be healthy and running
        Assertions.assertTrue(server.isRunning(), "Server should remain running and healthy after client disconnect");
    }
}
