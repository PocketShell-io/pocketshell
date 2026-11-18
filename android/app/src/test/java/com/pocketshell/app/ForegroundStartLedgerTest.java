package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A stop never strands a foreground start Android is waiting on (#3160 review, #2483 class). */
public final class ForegroundStartLedgerTest {
    @Test
    public void aStopWaitsForEveryPendingStartToGoForeground() {
        ForegroundStartLedger ledger = new ForegroundStartLedger();
        assertTrue("nothing pending: stopping is safe", ledger.mayStop());
        // HOME opens the hold; the return closes it before the service ran.
        ledger.requested();
        assertFalse("a fast return must not stop a start still owed startForeground()", ledger.mayStop());
        ledger.requested();
        ledger.answered();
        assertFalse("one of two starts is still owed", ledger.mayStop());
        ledger.answered();
        assertTrue(ledger.mayStop());
    }

    @Test
    public void anUnrequestedAnswerNeverGoesNegative() {
        ForegroundStartLedger ledger = new ForegroundStartLedger();
        ledger.answered();
        assertEquals(0, ledger.pending());
        ledger.requested();
        assertFalse("a stray answer must not hide a later pending start", ledger.mayStop());
    }
}
