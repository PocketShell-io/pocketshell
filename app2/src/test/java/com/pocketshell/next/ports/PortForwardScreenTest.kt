package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered port-forward screen on the host JVM (Robolectric), the same way
 * `:shared:ui-kit` tests its primitives.
 *
 * Since #2636 D11 the screen composable and its state live in
 * `shared:ui-screens` painting the pure display mirrors — this test stays in
 * app2 as the rendered-tree proof that the app-routed surface still paints
 * every state, and the moved pure helpers' string/derivation assertions now
 * run in the shared module's `PortForwardDisplayFamilyTest`, next to the code
 * they pin. The core ↔ mirror equivalence itself is swept by
 * [PortForwardDisplayMappingTest].
 *
 * Every assertion is on the RENDERED tree: which message appears for which state
 * (off vs unreachable vs scanning vs "all rows are hidden" are four DIFFERENT
 * situations that must not paint the same blank), that a row's action carries its
 * own remote port, and that a duplicate remote port cannot crash the list.
 */
@RunWith(AndroidJUnit4::class)
class PortForwardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `forwarding rows render remote, local, process, status and traffic`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(
                    forwarding(remotePort = 3_000, localPort = 3_001, process = "vite", bytes = 2_048),
                ),
            ),
        )

        composeRule.onNodeWithTag(portRowTag(3_000)).assertIsDisplayed()
        composeRule.onNodeWithText("3000").assertIsDisplayed()
        composeRule.onNodeWithText("3001").assertIsDisplayed()
        composeRule.onNodeWithText("vite").assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding").assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `a discovered but unforwarded row offers Start and hides its local port`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(available(remotePort = 8_080)),
            ),
        )

        composeRule.onNodeWithText("Available").assertIsDisplayed()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("8080").assertIsDisplayed()
        // Exactly two dashes: the local port (no socket is bound yet, so printing
        // one would be a lie) and the traffic cell (no bytes have flowed). Read on
        // the UNMERGED tree — the clickable row merges its children into one
        // semantics node, so the merged tree would report a single hit for both.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("-", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a forwarding row whose local port differs from remote says so`() {
        // #2498: when local 3000 is busy, the bind walks up to 3003 — the row
        // must surface that the binding differs from the remote port instead
        // of silently showing 3003 next to remote 3000.
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(
                    forwarding(remotePort = 3_000, localPort = 3_003, process = "vite", bytes = 0),
                ),
            ),
        )

        composeRule.onNodeWithText("3003").assertIsDisplayed()
        composeRule.onNodeWithText("differs from remote").assertIsDisplayed()
    }

    @Test
    fun `a mirrored forwarding row does not claim a differing local port`() {
        // #2498: the walked-up marker belongs ONLY where the bound local port
        // is not the mirrored/remote one — a plain mirror must render no note.
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(
                    forwarding(remotePort = 3_000, localPort = 3_000, process = "vite", bytes = 0),
                ),
            ),
        )

        composeRule.onNodeWithText("3000").assertIsDisplayed()
        composeRule.onNodeWithText("differs from remote").assertDoesNotExist()
    }

    @Test
    fun `tapping a row's action reports that row's remote port`() {
        val toggled = mutableListOf<Int>()
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(available(8_080), available(9_000)),
            ),
            onTogglePort = { toggled += it },
        )

        composeRule
            .onNodeWithTag(PORT_TABLE_TAG)
            .performScrollToNode(hasText("9000"))
        composeRule.onNodeWithTag(portRowTag(9_000)).performClick()

        assertEquals(listOf(9_000), toggled)
    }

    @Test
    fun `the On segment turns forwarding on`() {
        val requested = mutableListOf<Boolean>()
        setContent(state(enabled = false), onSetEnabled = { requested += it })

        composeRule.onNodeWithTag("${FORWARDING_TOGGLE_TAG}_on").performClick()

        assertEquals(listOf(true), requested)
    }

    @Test
    fun `forwarding off says so instead of rendering an empty table`() {
        setContent(state(enabled = false))

        composeRule
            .onNodeWithText("Forwarding is off. Turn it on to discover listening ports.")
            .assertIsDisplayed()
    }

    @Test
    fun `an unreachable host is distinguishable from an empty one`() {
        setContent(state(enabled = true, connection = ConnectionStateDisplay.Lost))

        composeRule
            .onNodeWithText("Forwarding paused. This host could not be reached.")
            .assertIsDisplayed()
    }

    @Test
    fun `a host that stopped retrying reads as needing attention, not reconnecting`() {
        // #2491: the terminal state has to be visibly distinct from
        // "Reconnecting" AND must not claim a retry is coming — nothing will
        // change until the user confirms the key.
        setContent(
            state(
                enabled = true,
                connection = ConnectionStateDisplay.Lost,
                attention = ForwardingController.NEEDS_TRUST_ATTENTION,
            ),
        )

        composeRule
            .onNodeWithText(
                "Forwarding paused. ${ForwardingController.NEEDS_TRUST_ATTENTION}",
            )
            .assertIsDisplayed()
        // The reconnecting rendering (a spinner that says work is in flight)
        // must be absent — that is the state this one is distinguished from.
        composeRule.onNodeWithText("Scanning ports…").assertDoesNotExist()
        composeRule.onNodeWithText("Retrying…", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a reconnecting host still renders as work in flight`() {
        // The other half of the distinction: a transient failure keeps the
        // spinner and never shows the terminal message, so the two states can
        // never read the same.
        setContent(state(enabled = true, connection = ConnectionStateDisplay.Reconnecting))

        composeRule.onNodeWithText("Scanning ports…").assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding paused.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the header state label separates a parked host from a reconnecting one`() {
        // The header falls back to the connection label when the host has no
        // subtitle. "Needs attention" is terminal wording; "Reconnecting" is not.
        setContent(
            state(enabled = true, connection = ConnectionStateDisplay.Lost, hostSubtitle = ""),
        )
        composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnecting").assertDoesNotExist()
    }

    @Test
    fun `a table emptied purely by the filter says the rows are hidden`() {
        setContent(
            state(enabled = true, connection = ConnectionStateDisplay.Connected, rows = emptyList(), hiddenCount = 3),
        )

        composeRule.onNodeWithText("3 noisy ports hidden.").assertIsDisplayed()
        composeRule.onNodeWithTag(SHOW_ALL_PORTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Show hidden/noisy ports (3 hidden)").assertIsDisplayed()
    }

    @Test
    fun `the show-all checkbox reports the user's choice`() {
        val requested = mutableListOf<Boolean>()
        setContent(state(enabled = true, hiddenCount = 2), onSetShowAllPorts = { requested += it })

        composeRule.onNodeWithTag(SHOW_ALL_PORTS_TAG).performClick()

        assertEquals(listOf(true), requested)
    }

    @Test
    fun `two rows sharing a remote port render without a duplicate-key crash`() {
        // The old client crashed here (`Key "22" already used`): the same remote
        // port can legitimately appear twice — discovered on two interfaces, or a
        // forwarded row beside its still-AVAILABLE twin. (The keys' uniqueness
        // itself is asserted on the pure helper by the shared module's
        // `PortForwardDisplayFamilyTest`; this test proves the rendered list
        // survives the collision.)
        val duplicated = listOf(
            forwarding(remotePort = 3_000, localPort = 3_000, process = "vite", bytes = 0),
            available(remotePort = 3_000),
        )

        setContent(state(enabled = true, connection = ConnectionStateDisplay.Connected, rows = duplicated))

        composeRule.onNodeWithTag(PORT_TABLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding").assertIsDisplayed()
        composeRule.onNodeWithTag(PORT_TABLE_TAG).performScrollToNode(hasText("Available"))
        composeRule.onNodeWithText("Available").assertIsDisplayed()
    }

    /**
     * Issue #2532: Ports used the status dot as its only leading chrome, so
     * there was no on-screen way back to the tree. Back must exist, be the
     * word `Back` (not `‹`), and fire `onBack`.
     */
    @Test
    fun `tapping Back in the header fires onBack`() {
        var backs = 0
        setContent(state(enabled = false), onBack = { backs += 1 })

        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    // ------------------------------------------------------------------ helpers

    private fun setContent(
        state: PortForwardDisplayState,
        onSetEnabled: (Boolean) -> Unit = {},
        onTogglePort: (Int) -> Unit = {},
        onSetShowAllPorts: (Boolean) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                PortForwardScreen(
                    state = state,
                    onSetEnabled = onSetEnabled,
                    onTogglePort = onTogglePort,
                    onSetShowAllPorts = onSetShowAllPorts,
                    onBack = onBack,
                )
            }
        }
    }

    private fun state(
        enabled: Boolean = false,
        connection: ConnectionStateDisplay = ConnectionStateDisplay.Idle,
        attention: String? = null,
        rows: List<TunnelDisplay> = emptyList(),
        hiddenCount: Int = 0,
        showAllPorts: Boolean = false,
        hostSubtitle: String = "alexey@rmthz:22",
    ) = PortForwardDisplayState(
        hostName = "rmthz",
        hostSubtitle = hostSubtitle,
        enabled = enabled,
        connection = connection,
        attention = attention,
        rows = rows,
        showAllPorts = showAllPorts,
        hiddenCount = hiddenCount,
        loading = false,
    )

    private fun forwarding(remotePort: Int, localPort: Int, process: String, bytes: Long) = TunnelDisplay(
        remotePort = remotePort,
        localPort = localPort,
        process = process,
        status = TunnelStatusDisplay.FORWARDING,
        bytesIn = bytes,
        bytesOut = 0,
        speedBps = 0,
    )

    private fun available(remotePort: Int) = TunnelDisplay(
        remotePort = remotePort,
        localPort = remotePort,
        process = "sshd",
        status = TunnelStatusDisplay.AVAILABLE,
    )
}
