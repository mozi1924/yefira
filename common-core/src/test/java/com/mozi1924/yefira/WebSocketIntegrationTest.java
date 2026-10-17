package com.mozi1924.yefira;

import com.mozi1924.yefira.network.WebSocketServerManager;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WebSocketIntegrationTest {

    private static final int TEST_PORT = 18765;
    private static final String TEST_HOST = "127.0.0.1";

    @BeforeEach
    public void setUp() throws Exception {
        WebSocketServerManager.getInstance().startServer(TEST_HOST, TEST_PORT);
        Thread.sleep(200);
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

        Assertions.assertTrue(server.getConnectedCount() >= 1, "Server should register connected client");

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
