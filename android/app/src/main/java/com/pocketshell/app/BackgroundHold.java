package com.pocketshell.app;

import java.util.HashMap;
import java.util.Map;

/**
 * When the background grace hold is up, for how many connections, and until
 * when (#3160). Pure state, no Android types, so the whole decision is
 * JVM-testable.
 *
 * <p>The grace POLICY (how long, what happens at the end, reconnect on return)
 * belongs to the shared core's ConnectionController, which arms one native
 * delayed close per live connection when the app backgrounds. Android only has
 * to keep the process and its network alive until those closes run: without a
 * foreground service a backgrounded app is frozen and Android blocks its
 * network ({@code FIREWALL_CHAIN_BACKGROUND}), which kills the SSH transport
 * the grace window exists to keep.
 *
 * <p>The hold must start on the activity-stop boundary itself, because Android
 * only lets an app start a foreground service from the background for a few
 * seconds after it leaves the screen, and the WebView's own background
 * handling reaches native code later than that. So the hold opens at
 * {@link #onBackground} with a provisional deadline from the last grace value
 * the app pushed ({@link #setGraceMs}); once core schedules its closes, their
 * deadlines are the authoritative end.
 */
final class BackgroundHold {
    /** Core's default window (DEFAULT_BACKGROUND_GRACE_MS), used until the app pushes the setting. */
    static final long DEFAULT_GRACE_MS = 90_000L;
    /** Core's longest offered window (DEFAULT_MAX_BACKGROUND_GRACE_MS). */
    static final long MAX_GRACE_MS = 10 * 60_000L;

    /** What the service should show right now. {@code count == 0} means no hold. */
    static final class Decision {
        static final Decision NONE = new Decision(0, 0L);
        final int count;
        /** Elapsed-realtime deadline of the hold; 0 when there is none. */
        final long deadlineElapsedMs;

        Decision(int count, long deadlineElapsedMs) {
            this.count = count;
            this.deadlineElapsedMs = deadlineElapsedMs;
        }

        boolean holding() {
            return count > 0;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Decision)) return false;
            Decision that = (Decision) other;
            return count == that.count && deadlineElapsedMs == that.deadlineElapsedMs;
        }

        @Override public int hashCode() {
            return 31 * count + Long.hashCode(deadlineElapsedMs);
        }

        @Override public String toString() {
            return "Decision{count=" + count + ", deadline=" + deadlineElapsedMs + "}";
        }
    }

    private long graceMs = DEFAULT_GRACE_MS;
    private boolean backgrounded;
    private long backgroundedAtElapsedMs;
    /** Core's scheduled grace closes by connection id (elapsed realtime). */
    private final Map<String, Long> scheduled = new HashMap<>();

    /** The user's grace window, pushed by the app; 0 turns the hold off. */
    synchronized void setGraceMs(long value) {
        graceMs = Math.max(0L, Math.min(MAX_GRACE_MS, value));
    }

    synchronized long graceMs() {
        return graceMs;
    }

    synchronized boolean backgrounded() {
        return backgrounded;
    }

    synchronized void onBackground(long nowElapsedMs) {
        if (backgrounded) return;
        backgrounded = true;
        backgroundedAtElapsedMs = nowElapsedMs;
    }

    synchronized void onForeground() {
        backgrounded = false;
        scheduled.clear();
    }

    /** Core armed (or re-armed) the delayed close of one connection. */
    synchronized void onGraceScheduled(String connectionId, long deadlineElapsedMs) {
        scheduled.put(connectionId, deadlineElapsedMs);
    }

    /** A connection's delayed close was cancelled, or the connection is gone. */
    synchronized void onGraceCleared(String connectionId) {
        scheduled.remove(connectionId);
    }

    /**
     * The hold for {@code liveConnections} open SSH connections at
     * {@code nowElapsedMs}: none in the foreground, with grace off, with
     * nothing to keep, or once the window has passed.
     */
    synchronized Decision decide(int liveConnections, long nowElapsedMs) {
        if (!backgrounded || graceMs <= 0L || liveConnections <= 0) return Decision.NONE;
        long deadline;
        if (scheduled.isEmpty()) {
            deadline = backgroundedAtElapsedMs + graceMs;
        } else {
            deadline = Long.MIN_VALUE;
            for (long value : scheduled.values()) deadline = Math.max(deadline, value);
        }
        // Never longer than the longest window core offers, whatever was scheduled.
        deadline = Math.min(deadline, backgroundedAtElapsedMs + MAX_GRACE_MS);
        if (nowElapsedMs >= deadline) return Decision.NONE;
        return new Decision(liveConnections, deadline);
    }
}
