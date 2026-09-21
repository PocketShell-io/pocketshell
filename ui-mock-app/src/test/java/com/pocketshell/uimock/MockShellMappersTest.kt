package com.pocketshell.uimock

import com.pocketshell.next.mockapp.MockAppEvent
import com.pocketshell.next.mockapp.MockAppState
import com.pocketshell.next.mockapp.MockDestination
import com.pocketshell.next.mockapp.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runnable shell's routing + projection seam (#2636 slice D16), plain JVM.
 *
 * Pins the two contracts the shell's `when` over [MockDestination] relies on:
 * which destinations render a REAL `:shared:ui-screens` screen, and that every
 * remaining destination falls to an explicitly labeled placeholder that names
 * its remaining work — never a silent blank and never a lookalike copy. The
 * state→screen projections the shell feeds are folded through the pure
 * reducer, so a shell render is always one deterministic fold from launch.
 */
class MockShellMappersTest {

    private val allDestinations: Set<MockDestination> = setOf(
        MockDestination.Hosts,
        MockDestination.HostForm,
        MockDestination.SshKeys,
        MockDestination.Workspaces,
        MockDestination.WorkspaceStart,
        MockDestination.Session,
        MockDestination.Services,
        MockDestination.Settings,
        MockDestination.Usage,
    )

    private val expectedUnwired: Set<MockDestination> = setOf(
        MockDestination.Workspaces,
        MockDestination.WorkspaceStart,
        MockDestination.Session,
    )

    // ── SshKeys: the real shared screen state, projected from mock state ──────

    @Test
    fun `populated ssh keys project as loaded with no message and no fabricated keys`() {
        val ui = MockAppState.populated().toSshKeysScreenState()
        assertTrue("navigation into SshKeys must read as loaded", ui.loaded)
        assertNull(ui.message)
        assertTrue(
            "the mock models no keys yet — the list must stay empty, never fabricated",
            ui.keys.isEmpty(),
        )
    }

    @Test
    fun `a one-shot ssh keys message survives projection and dismiss clears it`() {
        val withMessage = MockAppState.populated().copy(sshKeyMessage = "Key imported.")
        assertEquals("Key imported.", withMessage.toSshKeysScreenState().message)
        val dismissed = reduce(withMessage, MockAppEvent.SshKeysMessageDismiss)
        assertNull(dismissed.toSshKeysScreenState().message)
    }

    // ── Routing: wired set is exactly the destinations with shared screens ────

    @Test
    fun `the shell wires exactly the six destinations whose screens crossed the boundary`() {
        assertEquals(
            setOf(
                MockDestination.Hosts,
                MockDestination.HostForm,
                MockDestination.SshKeys,
                MockDestination.Services,
                MockDestination.Settings,
                MockDestination.Usage,
            ),
            wiredDestinations,
        )
    }

    @Test
    fun `wired plus unwired partitions every MockDestination exactly`() {
        assertEquals(allDestinations, wiredDestinations + expectedUnwired)
        assertTrue(
            "wired and unwired must not overlap",
            wiredDestinations.intersect(expectedUnwired).isEmpty(),
        )
    }

    @Test
    fun `each unwired destination stays out of the wired set and labels its remaining work`() {
        expectedUnwired.forEach { destination ->
            assertTrue(
                "$destination gained a wired claim without a shared screen — wire it in the shell and update this pin together",
                destination !in wiredDestinations,
            )
            val note = unwiredDestinationNote(destination)
            assertTrue("placeholder must state its remaining work: $note", note.contains("remaining work"))
        }
    }

    @Test
    fun `placeholder titles name the production destination verbatim`() {
        assertEquals("Workspaces", unwiredDestinationTitle(MockDestination.Workspaces))
        assertEquals("WorkspaceStart", unwiredDestinationTitle(MockDestination.WorkspaceStart))
        assertEquals("Session", unwiredDestinationTitle(MockDestination.Session))
    }

    // ── The fold the shell dispatches: routes land where the pins say ─────────

    @Test
    fun `opening a host lands on the not-yet-wired workspaces placeholder route`() {
        val opened = reduce(MockAppState.populated(), MockAppEvent.OpenHost(1L))
        assertEquals(MockDestination.Workspaces, opened.destination)
        assertTrue(opened.destination !in wiredDestinations)
    }

    @Test
    fun `settings navigation lands on the wired real settings screen route`() {
        val navigated = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Settings))
        assertTrue(navigated.destination in wiredDestinations)
    }

    @Test
    fun `a host-form edit folded through the reducer reaches the form state the screen renders`() {
        val draft = MockAppState.populated().hostForm.copy(name = "hetzner-2", hostname = "10.0.0.9")
        val edited = reduce(MockAppState.populated(), MockAppEvent.HostFormChange(draft))
        assertEquals("hetzner-2", edited.hostForm.name)
        assertEquals("10.0.0.9", edited.hostForm.hostname)
    }
}
