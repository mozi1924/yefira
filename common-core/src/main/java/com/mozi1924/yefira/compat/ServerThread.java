package com.mozi1924.yefira.compat;

import java.util.concurrent.Executor;

/**
 * Host-provided server-thread executor.
 *
 * The common-core WebSocket manager must never read Level/chunk data from its
 * own worker pool. Each version's server lifecycle registers the Minecraft
 * server executor here so baseline scans can be scheduled on the server thread.
 */
public final class ServerThread {
    private static volatile Executor executor;

    private ServerThread() {
    }

    public static void setExecutor(Executor serverExecutor) {
        executor = serverExecutor;
    }

    public static void clear() {
        executor = null;
    }

    public static boolean isAvailable() {
        return executor != null;
    }

    /**
     * Runs the task on the registered server thread, or on the caller when no
     * server is active. Callers that require server-thread semantics should
     * check {@link #isAvailable()} first.
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
            throw new IllegalStateException("server executor is not registered");
        }
        current.execute(task);
    }
}
