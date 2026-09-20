package com.pocketshell.next.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the app2 skeleton (task M-1).
 *
 * Pins the two things the navigation graph can silently get wrong without the
 * compiler noticing: a route template that no longer matches the route the
 * builder produces (dead screen), and an argument that isn't encoded (a session
 * name with a space or a `/` in a remote path splitting into extra segments).
 */
class DestinationsTest {

    @Test
    fun `argument-free destinations build their own pattern`() {
        assertEquals("hosts", Destination.Hosts.route())
        assertEquals("settings", Destination.Settings.route())
        assertEquals("usage", Destination.Usage.route())
        assertEquals("diagnostics", Destination.Diagnostics.route())
        assertEquals(Destination.Diagnostics.route(), Destination.CrashReports.route())
    }

    @Test
    fun `start destination is the host list`() {
        assertEquals(Destination.Hosts, Destination.start)
    }

    @Test
    fun `every destination pattern is unique and non-blank`() {
        // Touch a leaf destination BEFORE reading the aggregate, so this test
        // pins the class-initialization order too and not just the contents:
        // a nested object's initializer re-enters `Destination.<clinit>`, so an
        // eagerly-initialized `all` would capture a null for whichever
        // destination was touched first. Doing the touch here makes the check
        // independent of JUnit's method ordering.
        Destination.Files.route(hostId = 1)

        val patterns = Destination.all.map { it.pattern }
        // The fixed routes include the categorized Settings/support routes,
        // host-scoped Usage, and the Quiet Services & tunnels screens.
        // The aggregate includes both Quiet workspace routes and the
        // categorized Settings/support plus Services routes. Deprecated aliases
        // (Tree and CrashReports) intentionally do not add duplicate patterns.
        // 28 since #2814 N-3 deleted the two one-choice-group leaf pages.
        assertEquals(28, patterns.size)
        assertEquals(patterns.size, patterns.toSet().size)
        assertTrue(patterns.none { it.isBlank() })
    }

    /**
     * Issue #2814 N-3 deleted `settings/voice/language` and
     * `settings/connections/grace`; both choice groups now expand in place on
     * their parent page. Asserted by PATTERN STRING rather than by the absence
     * of a Kotlin symbol, because the symbol going away is what the compiler
     * already proves — what this pins is that neither route can come back as a
     * second way to reach the same six or five rows (D22 hard cut), and that
     * the parent pages survived the deletion.
     */
    @Test
    fun `the one-choice-group leaf settings routes are gone`() {
        Destination.Files.route(hostId = 1)

        val patterns = Destination.all.map { it.pattern }
        assertTrue(
            "settings/voice/language must not be a destination any more, got $patterns",
            "settings/voice/language" !in patterns,
        )
        assertTrue(
            "settings/connections/grace must not be a destination any more, got $patterns",
            "settings/connections/grace" !in patterns,
        )
        assertTrue("the Voice page must survive", "settings/voice" in patterns)
        assertTrue("the Connections page must survive", "settings/connections" in patterns)
    }

    @Test
    fun `built routes match their patterns`() {
        assertMatchesPattern(Destination.Workspaces.pattern, Destination.Workspaces.route(hostId = 7))
        assertMatchesPattern(
            Destination.Workspace.pattern,
            Destination.Workspace.route(hostId = 7, path = "/home/alexey/git/pocketshell"),
        )
        assertMatchesPattern(
            Destination.WorkspaceStart.pattern,
            Destination.WorkspaceStart.route(hostId = 7, path = "/home/alexey/git/pocketshell"),
        )
        assertMatchesPattern(
            Destination.ReorderWorkspaces.pattern,
            Destination.ReorderWorkspaces.route(hostId = 7),
        )
        assertMatchesPattern(
            Destination.Session.pattern,
            Destination.Session.route(
                hostId = 7,
                sessionName = "git-pocketshell",
                workspacePath = "/home/alexey/git/pocketshell",
                sessionId = "0b9e6c1e-1",
            ),
        )
        assertMatchesPattern(
            Destination.Files.pattern,
            Destination.Files.route(hostId = 7, path = "/home/alexey/notes.md"),
        )
        assertMatchesPattern(
            Destination.FileViewer.pattern,
            Destination.FileViewer.route(hostId = 7, path = "/home/alexey/notes.md"),
        )
        assertMatchesPattern(Destination.Ports.pattern, Destination.Ports.route(hostId = 7))
        assertMatchesPattern(Destination.HostUsage.pattern, Destination.HostUsage.route(hostId = 7))
        assertMatchesPattern(
            Destination.TunnelDetail.pattern,
            Destination.TunnelDetail.route(hostId = 7, remotePort = 5173),
        )
        assertMatchesPattern(
            Destination.AddTunnel.pattern,
            Destination.AddTunnel.route(hostId = 7, remotePort = 5173),
        )
        assertMatchesPattern(Destination.TerminalSettings.pattern, Destination.TerminalSettings.route())
        assertMatchesPattern(Destination.VoiceSettings.pattern, Destination.VoiceSettings.route())
        assertMatchesPattern(Destination.ConnectionSettings.pattern, Destination.ConnectionSettings.route())
        assertMatchesPattern(Destination.AdvancedSettings.pattern, Destination.AdvancedSettings.route())
        assertMatchesPattern(Destination.Diagnostics.pattern, Destination.Diagnostics.route())
        assertMatchesPattern(
            Destination.DiagnosticReport.pattern,
            Destination.DiagnosticReport.route("report 1"),
        )
        assertMatchesPattern(Destination.About.pattern, Destination.About.route())
        assertMatchesPattern(Destination.Update.pattern, Destination.Update.route())
        assertMatchesPattern(Destination.HostForm.pattern, Destination.HostForm.route(hostId = 7))
        assertMatchesPattern(Destination.HostForm.pattern, Destination.HostForm.route())
        assertMatchesPattern(
            Destination.WorkspaceRoots.pattern,
            Destination.WorkspaceRoots.route(hostId = 7),
        )
        assertMatchesPattern(
            Destination.AddWorkspaceRoot.pattern,
            Destination.AddWorkspaceRoot.route(hostId = 7),
        )
        assertMatchesPattern(
            Destination.WorkspaceRootAction.pattern,
            Destination.WorkspaceRootAction.route(
                hostId = 7,
                rootPath = "/home/alexey/git",
                action = "add-workspace",
            ),
        )
    }

    @Test
    fun `workspace roots route carries the host id as a path segment`() {
        assertEquals("workspace-roots/42", Destination.WorkspaceRoots.route(hostId = 42))
    }

    @Test
    fun `add project root route carries the host id as a path segment`() {
        assertEquals("add-workspace-root/42", Destination.AddWorkspaceRoot.route(hostId = 42))
    }

    /**
     * Add and Edit are the same screen, so the route is the only thing that can
     * tell them apart. The sentinel is spelled out here because
     * `AddEditHostViewModel` normalises anything `<= 0` back to "Add" — the two
     * halves have to agree on which value means "no host".
     */
    @Test
    fun `host form route spells add as the no-host sentinel and edit as the id`() {
        assertEquals("host-form?hostId=-1", Destination.HostForm.route())
        assertEquals("host-form?hostId=42", Destination.HostForm.route(hostId = 42))
        assertEquals(-1L, Destination.NO_HOST_ID)
    }

    @Test
    fun `viewer route encodes the file path into its query argument`() {
        val route = Destination.FileViewer.route(hostId = 3, path = "/home/alexey/my notes.md")

        assertEquals("file/3?path=%2Fhome%2Falexey%2Fmy%20notes.md", route)
        assertEquals(2, route.substringBefore('?').split("/").size)
    }

    @Test
    fun `workspaces route carries the host id`() {
        assertEquals("workspaces/42", Destination.Workspaces.route(hostId = 42))
        assertEquals("workspaces/42", Destination.Tree.route(hostId = 42))
    }

    @Test
    fun `workspace route keeps the canonical path in one encoded query argument`() {
        val route = Destination.Workspace.route(
            hostId = 42,
            path = "/home/alexey/git/pocket shell",
        )

        assertEquals(
            "workspace/42?workspacePath=%2Fhome%2Falexey%2Fgit%2Fpocket%20shell",
            route,
        )
        assertEquals(2, route.substringBefore('?').split("/").size)
    }

    @Test
    fun `services and host usage routes retain their selected host`() {
        assertEquals("usage/42", Destination.HostUsage.route(hostId = 42))
        assertEquals("tunnel/42/5173", Destination.TunnelDetail.route(42, 5173))
        assertEquals("add-tunnel/42?remotePort=-1", Destination.AddTunnel.route(42))
        assertEquals("add-tunnel/42?remotePort=5173", Destination.AddTunnel.route(42, 5173))
    }

    @Test
    fun `session name is percent-encoded into a single path segment`() {
        val route = Destination.Session.route(hostId = 1, sessionName = "my project:review")

        // One space -> %20 (NOT `+`, which navigation would not decode back),
        // one `:` left alone, and exactly three segments so the name cannot
        // leak into the route structure.
        assertEquals("session/1/my%20project%3Areview", route)
        assertEquals(3, route.split("/").size)
    }

    @Test
    fun `session route carries the stable id as a query argument`() {
        // Issue #2572: the id, not the name, is the identity a back-stack
        // entry resolves against. It rides in the query so the name segment
        // stays presentational, and it is omitted entirely when absent so a
        // route without one is byte-identical to the pre-#2572 shape.
        assertEquals(
            "session/7/aplexer%3Ayolo?sessionId=0b9e6c1e-1",
            Destination.Session.route(hostId = 7, sessionName = "aplexer:yolo", sessionId = "0b9e6c1e-1"),
        )
        assertEquals(
            "session/7/w?workspacePath=%2Fhome%2Ftestuser%2Fgit&sessionId=0b9e6c1e-1",
            Destination.Session.route(
                hostId = 7,
                sessionName = "w",
                workspacePath = "/home/testuser/git",
                sessionId = "0b9e6c1e-1",
            ),
        )
        assertEquals(
            "session/7/w?workspacePath=%2Fhome%2Ftestuser%2Fgit",
            Destination.Session.route(hostId = 7, sessionName = "w", workspacePath = "/home/testuser/git"),
        )
    }

    @Test
    fun `remote path is encoded so its slashes stay inside the query argument`() {
        val route = Destination.Files.route(hostId = 3, path = "/home/alexey/git/pocketshell")

        assertEquals("files/3?path=%2Fhome%2Falexey%2Fgit%2Fpocketshell", route)
        assertEquals(2, route.substringBefore('?').split("/").size)
    }

    @Test
    fun `files route without a path omits the optional argument`() {
        assertEquals("files/3", Destination.Files.route(hostId = 3))
    }

    /**
     * Structural check that a concrete route is an instance of its template:
     * same shape once every `{arg}` placeholder is replaced by "some value".
     */
    private fun assertMatchesPattern(pattern: String, route: String) {
        val regex = Regex(
            pattern.split(Regex("\\{[^}]+}"))
                .joinToString("[^/?]+") { Regex.escape(it) },
        )
        assertTrue("route '$route' does not match pattern '$pattern'", regex.matches(route))
    }
}
