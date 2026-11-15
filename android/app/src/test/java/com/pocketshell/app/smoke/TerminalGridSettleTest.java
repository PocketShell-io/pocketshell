package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class TerminalGridSettleTest {
    private static final class FakeClock implements TerminalGridSettle.Clock {
        long time;
        public long now() { return time; }
        public void pause() { time += 100; }
    }

    private static TerminalGridSettle.Sample sample(int cols, int rows, int acks, int pending, String accepted) {
        return new TerminalGridSettle.Sample(cols, rows, acks, pending, 0, accepted);
    }

    @Test
    public void delayedImeResizeAndAcknowledgementMustSettleBeforeReceiverRelease() throws Exception {
        FakeClock clock = new FakeClock();
        TerminalGridSettle.await(() -> {
            // Captured sequence: 38x30 -> 37x12 -> 38x11. A quiet old
            // acknowledgement alone does not establish a matching render.
            if (clock.time < 300) return sample(38, 30, 1, 0, "38 × 30 accepted by SSH");
            if (clock.time < 1100) return sample(37, 12, 1, 0, "38 × 30 accepted by SSH");
            if (clock.time < 1400) return sample(38, 11, 2, 1, "37 × 12 accepted by SSH");
            return sample(38, 11, 3, 0, "38 × 11 accepted by SSH");
        }, clock, 5000);
        assertEquals("release only after 750ms on the final matching grid", 2200, clock.time);
    }

    @Test
    public void gridChangeWithoutAckCountChangeRestartsTheSettleWindow() throws Exception {
        FakeClock clock = new FakeClock();
        TerminalGridSettle.await(() -> clock.time < 500
                ? sample(38, 30, 1, 0, "38 × 30 accepted by SSH")
                : sample(38, 11, 1, 0, "38 × 11 accepted by SSH"), clock, 5000);
        assertEquals(1300, clock.time);
    }

    @Test
    public void persistentMismatchedGridFailsWithinTheOriginalDeadline() throws Exception {
        FakeClock clock = new FakeClock();
        try {
            TerminalGridSettle.await(() -> sample(37, 12, 1, 0, "38 × 11 accepted by SSH"), clock, 1000);
            fail("a missing matching resize must never release the receiver");
        } catch (AssertionError error) {
            assertTrue(error.getMessage().contains("did not remain matched"));
            assertEquals(1000, clock.time);
        }
    }

    @Test
    public void failedResizeCannotReleaseTheReceiver() throws Exception {
        FakeClock clock = new FakeClock();
        try {
            TerminalGridSettle.await(() -> new TerminalGridSettle.Sample(38, 11, 3, 0, 1,
                    "38 × 11 accepted by SSH"), clock, 1000);
            fail("failed resize must remain red");
        } catch (AssertionError error) {
            assertEquals(1000, clock.time);
        }
    }
}
