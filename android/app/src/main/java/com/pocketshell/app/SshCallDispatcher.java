package com.pocketshell.app;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs SSH plugin calls off Capacitor's single plugin thread (#3141).
 *
 * <p>Every call is submitted with a lane key. Calls that share a lane run one
 * at a time in submission order (FIFO) - this is what preserves the protocol
 * ordering the plugin used to get for free from one thread: a PTY's writes,
 * resizes and close in order, one connection's SFTP work in order, one
 * connection's close/grace-schedule calls in order. Calls on different lanes,
 * and calls with a null lane (connect, exec, cancel, snapshots), run
 * concurrently, so a slow {@code exec} or a 500 ms {@code readPty} wait can
 * never delay an unrelated call such as {@code cancelOperation} or another
 * plugin's {@code getState}.
 *
 * <p>Workers come from a cached daemon pool: a thread exists only while a
 * lane or a free call is actually running, and concurrency is bounded by
 * live resources (connections, PTYs) rather than a fixed pool that a few
 * blocked reads could exhaust.
 */
final class SshCallDispatcher {
    private final ExecutorService pool;
    private final Map<String, ArrayDeque<Runnable>> lanes = new HashMap<>();

    SshCallDispatcher(ExecutorService pool) {
        this.pool = pool;
    }

    static SshCallDispatcher withDaemonWorkers(String namePrefix) {
        return new SshCallDispatcher(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        }));
    }

    static SshCallDispatcher withFixedDaemonWorkers(String namePrefix, int threads) {
        return new SshCallDispatcher(Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Run {@code task}; {@code lane == null} means no ordering constraint. */
    void submit(String lane, Runnable task) {
        if (lane == null) {
            pool.execute(task);
            return;
        }
        boolean start;
        synchronized (lanes) {
            ArrayDeque<Runnable> queue = lanes.get(lane);
            start = queue == null;
            if (start) {
                queue = new ArrayDeque<>();
                lanes.put(lane, queue);
            }
            queue.addLast(task);
        }
        if (start) pool.execute(() -> drain(lane));
    }

    private void drain(String lane) {
        while (true) {
            Runnable next;
            synchronized (lanes) {
                ArrayDeque<Runnable> queue = lanes.get(lane);
                next = queue == null ? null : queue.peekFirst();
                if (next == null) {
                    lanes.remove(lane);
                    return;
                }
            }
            try {
                next.run();
            } catch (Throwable ignored) {
                // The task owns resolving its PluginCall; a throw must not wedge the lane.
            }
            synchronized (lanes) {
                ArrayDeque<Runnable> queue = lanes.get(lane);
                if (queue != null) queue.pollFirst();
            }
        }
    }

    /** Lanes with queued or running work; tests assert lanes do not leak. */
    int activeLaneCount() {
        synchronized (lanes) { return lanes.size(); }
    }
}
