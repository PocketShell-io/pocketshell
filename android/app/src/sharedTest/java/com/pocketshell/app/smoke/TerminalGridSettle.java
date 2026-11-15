package com.pocketshell.app.smoke;

/** Waits for the rendered grid and its SSH acknowledgement to stop changing. */
public final class TerminalGridSettle {
    public interface Clock {
        long now();
        void pause();
    }
    public interface Probe { Sample sample() throws Exception; }
    public static final class Sample {
        final int cols, rows, acks;
        final boolean matched;
        public Sample(int cols, int rows, int acks, int pending, int failures, String accepted) {
            this.cols = cols;
            this.rows = rows;
            this.acks = acks;
            matched = cols > 0 && rows > 0 && acks > 0 && pending == 0 && failures == 0
                    && (cols + " × " + rows + " accepted by SSH").equals(accepted);
        }
    }
    private TerminalGridSettle() {}

    public static void await(Probe probe, Clock clock, long timeoutMillis) throws Exception {
        long deadline = clock.now() + timeoutMillis;
        long stableSince = -1;
        Sample previous = null;
        while (clock.now() < deadline) {
            Sample current = probe.sample();
            if (!current.matched) stableSince = -1;
            else if (previous == null || !previous.matched || current.cols != previous.cols
                    || current.rows != previous.rows || current.acks != previous.acks) stableSince = clock.now();
            else if (stableSince >= 0 && clock.now() - stableSince >= 750) return;
            previous = current;
            clock.pause();
        }
        throw new AssertionError("terminal grid did not remain matched to its SSH acknowledgement for 750ms");
    }
}
