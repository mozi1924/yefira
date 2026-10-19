package com.mozi1924.yefira.compat;

import java.util.concurrent.Executor;

/**
 * Host-provided client-thread executor.
 *
 * <p>Local export mode scans chunks from the client's {@code ClientLevel}. Those
 * chunks are owned by the render/client thread, so the scan must run there
 * instead of on the WebSocket streaming worker. The client entrypoint registers
 * {@code Minecraft::execute} here while a world is loaded.</p>
 */
public final class ClientThread {
    private static volatile Executor executor;

    private ClientThread() {
    }

    public static void setExecutor(Executor clientExecutor) {
        executor = clientExecutor;
    }

    public static void clear() {
        executor = null;
    }

    public static boolean isAvailable() {
        return executor != null;
    }

    /**
     * Runs the task on the registered client thread, or on the caller when no
     * client executor is registered. Callers that require client-thread
     * semantics should check {@link #isAvailable()} first.
     */
    public static void execute(Runnable task) {
        Executor current = executor;
        if (current != null) {
            current.execute(task);
        } else {
            task.run();
        }
    }

    /**
     * Strict variant for code that must never run on the calling thread
     * (e.g. Level/chunk access). Throws instead of falling back.
     */
    public static void executeChecked(Runnable task) {
        Executor current = executor;
        if (current == null) {
            throw new IllegalStateException("client executor is not registered");
        }
        current.execute(task);
    }
}
