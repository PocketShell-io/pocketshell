package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The background grace hold decision (#3160): when it is up, for what, and until when. */
public final class BackgroundHoldTest {
    @Test
    public void foregroundNeverHolds() {
        BackgroundHold hold = new BackgroundHold();
        assertFalse(hold.decide(2, 1_000L).holding());
    }

    @Test
    public void backgroundWithLiveConnectionsHoldsForTheProvisionalWindowUntilCoreSchedules() {
        BackgroundHold hold = new BackgroundHold();
        hold.onBackground(10_000L);
        BackgroundHold.Decision decision = hold.decide(2, 10_500L);
        assertEquals(2, decision.count);
        assertEquals("the default window is core's 90 s", 10_000L + 90_000L, decision.deadlineElapsedMs);
    }

    @Test
    public void thePushedSettingSetsTheProvisionalWindow() {
        BackgroundHold hold = new BackgroundHold();
        hold.setGraceMs(30_000L);
        hold.onBackground(0L);
        assertEquals(30_000L, hold.decide(1, 1L).deadlineElapsedMs);
        assertTrue(hold.decide(1, 29_999L).holding());
        assertFalse("the hold ends with the window", hold.decide(1, 30_000L).holding());
    }

    @Test
    public void coreScheduledClosesAreTheAuthoritativeDeadline() {
        BackgroundHold hold = new BackgroundHold();
        hold.setGraceMs(30_000L);
        hold.onBackground(0L);
        hold.onGraceScheduled("a", 31_200L);
        hold.onGraceScheduled("b", 31_500L);
        assertEquals("the latest scheduled close ends the hold", 31_500L, hold.decide(2, 100L).deadlineElapsedMs);
        hold.onGraceCleared("b");
        assertEquals(31_200L, hold.decide(1, 100L).deadlineElapsedMs);
    }

    @Test
    public void graceOffOrNothingLiveMeansNoHold() {
        BackgroundHold off = new BackgroundHold();
        off.setGraceMs(0L);
        off.onBackground(0L);
        assertFalse("grace off: no hold, no notification", off.decide(3, 1L).holding());

        BackgroundHold idle = new BackgroundHold();
        idle.onBackground(0L);
        assertFalse("no live connection: no hold, no notification", idle.decide(0, 1L).holding());
    }

    @Test
    public void foregroundEndsTheHoldAndForgetsScheduledCloses() {
        BackgroundHold hold = new BackgroundHold();
        hold.onBackground(0L);
        hold.onGraceScheduled("a", 600_000L);
        assertTrue(hold.decide(1, 1L).holding());
        hold.onForeground();
        assertFalse(hold.decide(1, 2L).holding());
        hold.onBackground(1_000L);
        assertEquals("a new background starts a fresh window, not the old schedule",
            1_000L + 90_000L, hold.decide(1, 1_001L).deadlineElapsedMs);
    }

    @Test
    public void aRepeatedStopDoesNotRestartTheWindow() {
        BackgroundHold hold = new BackgroundHold();
        hold.onBackground(0L);
        hold.onBackground(50_000L);
        assertEquals(90_000L, hold.decide(1, 60_000L).deadlineElapsedMs);
    }

    @Test
    public void theHoldIsBoundedByTheLongestOfferedWindow() {
        BackgroundHold hold = new BackgroundHold();
        hold.setGraceMs(24 * 60 * 60_000L);
        assertEquals(BackgroundHold.MAX_GRACE_MS, hold.graceMs());
        hold.onBackground(0L);
        hold.onGraceScheduled("a", 60 * 60_000L);
        assertEquals(BackgroundHold.MAX_GRACE_MS, hold.decide(1, 1L).deadlineElapsedMs);
    }

    @Test
    public void notificationTextCountsConnections() {
        assertEquals("Keeping 1 connection alive", ForwardService.holdText(1));
        assertEquals("Keeping 3 connections alive", ForwardService.holdText(3));
    }
}
