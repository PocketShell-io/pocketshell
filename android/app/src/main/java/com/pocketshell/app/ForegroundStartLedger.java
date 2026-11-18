package com.pocketshell.app;

/**
 * Foreground-service starts that Android is still waiting on (#3160 review,
 * the #2483 race class).
 *
 * Every {@code startForegroundService()} obliges the service to call
 * {@code startForeground()}; if the service is stopped before it does, Android
 * kills the whole app with {@code ForegroundServiceDidNotStartInTimeException}.
 * A fast HOME then return does exactly that: the stop opens a start, and the
 * start of the return asks to stop it a few hundred milliseconds later. So a
 * stop is only ever issued when no start is pending; otherwise the pending
 * start goes foreground first and then, finding nothing to hold, stops itself.
 */
final class ForegroundStartLedger {
    private int pending;

    /** A {@code startForegroundService()} was issued. */
    synchronized void requested() {
        pending++;
    }

    /** The service called {@code startForeground()} for one requested start. */
    synchronized void answered() {
        if (pending > 0) pending--;
    }

    /** True when stopping now cannot strand a start Android is waiting on. */
    synchronized boolean mayStop() {
        return pending == 0;
    }

    synchronized int pending() {
        return pending;
    }
}
