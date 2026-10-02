package com.pocketshell.app.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** #3017: deterministic selection drift at the real ScriptedIme system boundary. */
@RunWith(AndroidJUnit4.class)
public final class ScriptedImeSelectionTest {
    private static final String SCRIPTED = "test.package/com.pocketshell.app.ime.ScriptedTestIme";
    private static final String STOCK = "stock.package/LatinIME";

    private static final class System implements ScriptedIme.SelectionEnvironment {
        long now;
        long driftVisibleAt;
        long commandLatency;
        boolean clean;
        boolean persistent;
        boolean conflicting;
        int enableCalls;
        int setCalls;

        public long uptimeMillis() { return now; }
        public void sleep(long millis) { now += millis; }
        public String shell(String command) {
            if (command.startsWith("ime enable ")) {
                enableCalls++;
                now += commandLatency;
                return "enabled";
            }
            if (command.startsWith("ime set ")) {
                setCalls++;
                now += commandLatency;
                return "selected";
            }
            boolean recovered = clean || (!persistent && setCalls == 2);
            if (command.startsWith("settings get ")) {
                return recovered || conflicting ? SCRIPTED : STOCK;
            }
            if (command.equals("dumpsys input_method")) {
                if (now < driftVisibleAt) return "mCurMethodId=null\n";
                return "mCurMethodId=" + (recovered && !conflicting ? SCRIPTED : STOCK) + "\n";
            }
            throw new AssertionError("unexpected shell command " + command);
        }
    }

    @Test public void cleanSelectionNeedsNoRebind() {
        System system = new System();
        system.clean = true;
        assertEquals(SCRIPTED, ScriptedIme.select(SCRIPTED, STOCK, system).id());
        assertEquals(1, system.setCalls);
        assertEquals(1, system.enableCalls);
    }

    @Test public void observedStockSelectionGetsExactlyOneRebind() {
        for (int iteration = 0; iteration < 20; iteration++) {
            System system = new System();
            assertEquals(SCRIPTED, ScriptedIme.select(SCRIPTED, STOCK, system).id());
            assertEquals(2, system.setCalls);
            assertEquals(2, system.enableCalls);
        }
    }

    @Test public void delayedDriftRecoversBeforeOriginalDeadline() {
        for (long delay : new long[] {100, 500, 5_000, 9_800}) {
            for (int iteration = 0; iteration < 10; iteration++) {
                System system = new System();
                system.driftVisibleAt = delay;
                assertEquals(SCRIPTED, ScriptedIme.select(SCRIPTED, STOCK, system).id());
                assertTrue(system.now < 10_000);
                assertEquals(2, system.setCalls);
            }
        }
    }

    @Test public void persistentDriftFailsWithinOriginalDeadline() {
        System system = new System();
        system.persistent = true;
        expectFailure(system);
        assertEquals(10_000, system.now);
        assertEquals(2, system.setCalls);
        assertEquals(2, system.enableCalls);
    }

    @Test public void conflictingSettingsAndMethodNeverPass() {
        System system = new System();
        system.conflicting = true;
        expectFailure(system);
        assertEquals(10_000, system.now);
        assertEquals(1, system.setCalls);
    }

    @Test public void driftAfterDeadlineCannotExtendSelectionBudget() {
        System system = new System();
        system.driftVisibleAt = 10_000;
        expectFailure(system);
        assertEquals(10_000, system.now);
        assertEquals(1, system.setCalls);
    }

    @Test public void slowRebindCommandsCannotCreateANewDeadline() {
        System system = new System();
        system.commandLatency = 400;
        system.driftVisibleAt = 10_200;
        expectFailure(system);
        assertTrue(system.now < 12_000);
        assertEquals(2, system.setCalls);
    }

    private static void expectFailure(System system) {
        try {
            ScriptedIme.select(SCRIPTED, STOCK, system);
            fail("selection must fail unless both system observations name the scripted IME");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("could not select the scripted test IME"));
        }
    }
}
