package com.pocketshell.next

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.core.view.WindowCompat
import androidx.navigation.navArgument
import com.pocketshell.next.connect.ConnectGate
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.ConnectViewModel
import com.pocketshell.next.crash.DiagnosticReportScreen
import com.pocketshell.next.crash.DiagnosticsScreen
import com.pocketshell.next.files.FileExplorerRoute
import com.pocketshell.next.files.ViewerRoute
import com.pocketshell.next.hosts.AddEditHostRoute
import com.pocketshell.next.hosts.HOST_FORM_SELECTED_KEY_RESULT
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.hosts.SshKeysRoute
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.AddTunnelRoute
import com.pocketshell.next.ports.ForwardingResume
import com.pocketshell.next.ports.PortForwardRoute
import com.pocketshell.next.ports.ServicesRoute
import com.pocketshell.next.ports.TunnelDetailRoute
import com.pocketshell.next.settings.LocalAppSettings
import com.pocketshell.next.settings.AboutRoute
import com.pocketshell.next.sync.AccountSyncRoute
import com.pocketshell.next.settings.AdvancedSettingsRoute
import com.pocketshell.next.settings.ConnectionSettingsRoute
import com.pocketshell.next.settings.SettingsNavigation
import com.pocketshell.next.settings.SettingsRoute
import com.pocketshell.next.settings.SettingsViewModel
import com.pocketshell.next.settings.TerminalSettingsRoute
import com.pocketshell.next.settings.UpdateRoute
import com.pocketshell.next.settings.VoiceSettingsRoute
import com.pocketshell.next.settings.AddWorkspaceRootRoute
import com.pocketshell.next.settings.WorkspaceRootsRoute
import com.pocketshell.next.terminal.GraceCoordinator
import com.pocketshell.next.terminal.SessionRoute
import com.pocketshell.next.usage.UsageRoute
import com.pocketshell.next.workspaces.HostWorkspacesRoute
import com.pocketshell.next.workspaces.ReorderWorkspacesRoute
import com.pocketshell.next.workspaces.WorkspaceRoute
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The single Activity of app2 (plan §A.1). Everything is Compose; there are no
 * fragments and no second Activity.
 *
 * The graph was wired before the screens existed, so each U-task was a one-line
 * swap inside [AppNavHost] rather than a navigation change. Every route now
 * resolves to a REAL screen; the `RoutePlaceholder` scaffold those swaps
 * replaced is gone (D22 — superseded code is deleted, not left dark). It had to
 * go: journey J04 was still asserting on the placeholder's
 * `Session(hostId=…, name=…)` label long after U-4 stopped rendering it, and a
 * dead composable is exactly what lets an oracle like that look alive (#2478).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * Task U-8. The background-grace policy has no other consumer, so something
     * has to create the singleton and wire it to the process/activity
     * lifecycles; this Activity is the moment the app first has a UI, and it is
     * launched identically in production and under instrumentation (where
     * `App` is replaced by `HiltTestApplication` and its `onCreate` never runs).
     * [GraceCoordinator.register] is idempotent, so a recreate is free.
     */
    @Inject
    lateinit var grace: GraceCoordinator

    /**
     * Same reason [grace] is registered here: instrumentation replaces
     * [App] with `HiltTestApplication`, so [App.onCreate] never runs.
     * Observer attach is in [onCreate]; the sweep is [onStart] because
     * `startForegroundService` is only legal once this activity is foreground
     * and ProcessLifecycleOwner can stay `STARTED` across launches.
     */
    @Inject
    lateinit var forwardingResume: ForwardingResume

    @Inject
    lateinit var connections: ConnectionsRegistry

    @Inject
    lateinit var hostDao: HostDao

    /**
     * Issue #2814 N-4. Resolving the remembered session id needs the host's
     * OWN session list, over the connection the startup dial just made — the
     * same pair [com.pocketshell.next.terminal.SessionSwitcherViewModel] uses.
     * Injected here because [AppNavHost] must stay a plain composable that a
     * Robolectric test can drive without a Hilt graph.
     */
    @Inject
    lateinit var hostCliClients: HostCliClientFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grace.register(application)
        forwardingResume.observeProcessLifecycle()
        // #887/#2533: after edge-to-edge, SOFT_INPUT_ADJUST_NOTHING so the OS
        // neither resizes nor pans the window when the keyboard shows.
        // enableEdgeToEdge already sets setDecorFitsSystemWindows(false), which
        // left the default ADJUST_UNSPECIFIED resolving to PAN — the black-top
        // / empty-void screenshot. ADJUST_NOTHING keeps the window FIXED: the
        // keyboard overlays the terminal. Because decorFitsSystemWindows is
        // still false, the IME inset is STILL dispatched to Compose as
        // WindowInsets.ime, so sheets/forms that opt into imePadding keep
        // working. The session column must not consume those insets.
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // #2756: the system bars follow the ui-kit Background token instead of
        // a hard-coded colour, so a palette change cannot leave them behind.
        window.statusBarColor = PocketShellColors.Background.toArgb()
        window.navigationBarColor = PocketShellColors.Background.toArgb()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // The window draws edge to edge (enableEdgeToEdge above;
                    // targetSdk 35 also makes that non-optional), so content
                    // must be inset out from under the status/navigation bars
                    // or the first row of any screen renders under the clock.
                    // IME insets are deliberately NOT consumed here: the
                    // session column stays full-bleed under the keyboard
                    // (#887/#2533); sheets and forms that need lifting apply
                    // their own imePadding.
                    //
                    // Task P-6: the settings snapshot is collected ONCE here and
                    // provided through `LocalAppSettings` (see that file's class
                    // doc for why a CompositionLocal rather than another
                    // ViewModel threaded through every screen). `hiltViewModel()`
                    // resolves against this Activity, which is the one thing a
                    // Robolectric `AppNavHost`-only composition (the nav tests)
                    // cannot provide — those compose `AppNavHost` directly and so
                    // never reach this line, which is why they still see
                    // `LocalAppSettings`'s default value rather than a crash.
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val appSettings by settingsViewModel.state.collectAsState()
                    CompositionLocalProvider(LocalAppSettings provides appSettings) {
                            AppNavHost(
                                modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                                connections = connections,
                                startupHostId = appSettings.defaultHostId,
                                startupHostExists = { hostDao.getById(it) != null },
                                onHostOpened = settingsViewModel::setDefaultHostId,
                                startupWorkspacePath = appSettings.lastWorkspacePath,
                                startupSessionId = appSettings.lastSessionId,
                                resolveStartupSession = ::liveSessionById,
                                onSessionOpened = settingsViewModel::setLastSession,
                            )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        forwardingResume.resumeNow()
    }

    /**
     * The host's own answer to "is this session still there?" (#2814 N-4).
     *
     * Deliberately asked over the live connection rather than trusted from the
     * preference: the remembered id is a claim about a REMOTE process, and
     * between two launches it can have been killed, reaped or renamed.
     * Returning null is the fallback path — the user stays on the workspace
     * list, with no error state, exactly as if nothing had been remembered.
     */
    private suspend fun liveSessionById(hostId: Long, sessionId: String): SessionRow? {
        val connection = (connections.getOrConnect(hostId) as? ConnectResult.Connected)
            ?.connection
            ?: return null
        return hostCliClients.create(connection).listSessions().getOrNull()
            ?.sessions
            ?.firstOrNull { it.id == sessionId }
    }
}

/**
 * Every non-dial action the host list can start. Grouped into one type because
 * the list is the app's landing screen and now carries four of them — passing
 * them as four positional lambdas through the [AppNavHost] seam made both the
 * production call and every test stand-in unreadable.
 */
data class HostListActions(
    val onOpenHost: (Long) -> Unit,
    val onAddHost: () -> Unit,
    val onEditHost: (Long) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenSshKeys: () -> Unit,
)

/**
 * The same grouping as [HostListActions], for the two seams that had grown
 * past what a positional list can carry.
 *
 * Not only readability this time: a `@Composable` lambda type is capped at
 * NINE value parameters before the Compose compiler's `ComposerParamTransformer`
 * fails the build outright ("expected value parameter count to be higher"), and
 * both seams were already at nine. Issue #2814 N-2 needed a tenth — the
 * Settings edge — so the callbacks move into a bag and the seam keeps the two
 * things that are genuinely route arguments.
 */
data class WorkspacesScreenActions(
    val onOpenWorkspace: (String) -> Unit,
    val onOpenSession: (SessionRow) -> Unit,
    val onOpenFiles: () -> Unit,
    val onOpenFilesAtPath: (String) -> Unit,
    val onOpenPorts: () -> Unit,
    val onBack: () -> Unit,
    val onOpenUsage: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** The terminal route's callbacks, grouped for the reason in [WorkspacesScreenActions]. */
data class SessionScreenActions(
    val onBack: () -> Unit,
    val onOpenUsage: () -> Unit,
    val onOpenFiles: () -> Unit,
    val onOpenSession: (SessionRow) -> Unit,
    val onOpenNewSession: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Session switcher navigation reuses the existing concrete route when it is
 * already on the back stack. That keeps one terminal/ViewModel per
 * host-session-workspace identity and makes switching back return to the
 * existing terminal instead of stacking another copy of it.
 */
private fun NavHostController.openSession(
    hostId: Long,
    sessionName: String,
    workspacePath: String? = null,
    sessionId: String? = null,
    /**
     * Issue #2814 N-4: the launch-resume write. This function is the graph's
     * single "open a session" funnel — the workspace list, a workspace, the
     * start-session route and the in-terminal switcher all land here — so it
     * is the one place that can remember the work without a second seam
     * drifting from [HostListActions.onOpenHost]'s host write.
     */
    onSessionOpened: (workspacePath: String?, sessionId: String?) -> Unit = { _, _ -> },
) {
    onSessionOpened(workspacePath, sessionId)
    val route = Destination.Session.route(hostId, sessionName, workspacePath, sessionId)
    val existing = runCatching { getBackStackEntry(route) }.getOrNull()
    if (existing == null) {
        navigate(route)
        return
    }
    while (currentBackStackEntry !== existing) {
        if (!popBackStack()) {
            navigate(route) { launchSingleTop = true }
            return
        }
    }
}

/**
 * The app2 navigation graph. Routes come from [Destination] — no literal route
 * strings live here.
 *
 * The `*Screen` / `connectViewModel` parameters are seams, not feature flags:
 * the real screens (host list, connect gate, host workspaces, workspace,
 * terminal,
 * port-forward panel, file explorer, file viewer, host add/edit form, SSH
 * keys, crash reports) resolve their ViewModels through
 * `hiltViewModel()`, which needs a Hilt-managed Activity, so a plain
 * Robolectric `createComposeRule()` composition could not host them. The
 * parameters let a test supply the same screen / the same ViewModel built by
 * hand (over an in-memory database and a scripted connection factory) and
 * still exercise the real navigation edge — the production defaults are the
 * real ones.
 */
data class WorkspaceScreenLaunch(
    val initialRootPath: String? = null,
    val initialRootAction: String? = null,
)

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    connections: ConnectionsRegistry? = null,
    /** Last host to resume; null keeps the Hosts landing screen. */
    startupHostId: Long? = null,
    /** Drops a stale resume id when the host was removed since the last launch. */
    startupHostExists: suspend (Long) -> Boolean = { true },
    /** Persists the host selection without making the host list own settings. */
    onHostOpened: (Long) -> Unit = {},
    /** Issue #2814 N-4: the workspace remembered from the last session. */
    startupWorkspacePath: String? = null,
    /** Issue #2814 N-4: the host-issued id remembered from the last session. */
    startupSessionId: String? = null,
    /**
     * Issue #2814 N-4: the host's own liveness answer for [startupSessionId].
     * Null means "gone" and keeps the launch on the workspace list. The
     * production implementation lists sessions over the connection the startup
     * dial just made; the default makes every existing test a no-resume one.
     */
    resolveStartupSession: suspend (hostId: Long, sessionId: String) -> SessionRow? =
        { _, _ -> null },
    /** Issue #2814 N-4: persists the session selection, same shape as [onHostOpened]. */
    onSessionOpened: (workspacePath: String?, sessionId: String?) -> Unit = { _, _ -> },
    hostsScreen: @Composable (HostListActions) -> Unit = { actions ->
        HostListRoute(
            onOpenHost = actions.onOpenHost,
            onAddHost = actions.onAddHost,
            onEditHost = actions.onEditHost,
            onOpenSettings = actions.onOpenSettings,
            onOpenSshKeys = actions.onOpenSshKeys,
            updateCheckViewModel = hiltViewModel(),
        )
    },
    connectViewModel: @Composable () -> ConnectViewModel = { hiltViewModel() },
    workspacesScreen: @Composable (
        hostId: Long,
        actions: WorkspacesScreenActions,
        launch: WorkspaceScreenLaunch,
    ) -> Unit = { hostId, actions, launch ->
        val scope = rememberCoroutineScope()
        HostWorkspacesRoute(
            onOpenWorkspace = actions.onOpenWorkspace,
            onOpenSession = actions.onOpenSession,
            onOpenFiles = actions.onOpenFiles,
            onOpenFilesAtPath = actions.onOpenFilesAtPath,
            onOpenPorts = actions.onOpenPorts,
            onBack = actions.onBack,
            onOpenUsage = actions.onOpenUsage,
            onOpenSettings = actions.onOpenSettings,
            onOpenReorder = { navController.navigate(Destination.ReorderWorkspaces.route(hostId)) },
            onOpenProjectRoots = { navController.navigate(Destination.WorkspaceRoots.route(hostId)) },
            onOpenConnectionDetails = { navController.navigate(Destination.HostForm.route(hostId)) },
            onDisconnect = {
                scope.launch {
                    connections?.close(hostId)
                    navController.popBackStack()
                }
            },
            onStartSessionAtPath = { path ->
                navController.navigate(Destination.WorkspaceStart.route(hostId, path))
            },
            initialRootPath = launch.initialRootPath,
            initialRootAction = launch.initialRootAction,
        )
    },
    workspaceScreen: @Composable (
        hostId: Long,
        workspacePath: String,
        onOpenSession: (String, String?) -> Unit,
        onOpenFiles: () -> Unit,
        onOpenPorts: () -> Unit,
        onBack: () -> Unit,
        onOpenUsage: () -> Unit,
    ) -> Unit = { hostId, _, onOpenSession, onOpenFiles, onOpenPorts, onBack, onOpenUsage ->
        WorkspaceRoute(
            onOpenSession = onOpenSession,
            onOpenFiles = onOpenFiles,
            onOpenPorts = onOpenPorts,
            onBack = onBack,
            onOpenUsage = onOpenUsage,
            onOpenReorder = { navController.navigate(Destination.ReorderWorkspaces.route(hostId)) },
        )
    },
    sessionScreen: @Composable (
        hostId: Long,
        sessionName: String,
        workspacePath: String?,
        sessionId: String?,
        actions: SessionScreenActions,
    ) -> Unit = { hostId, sessionName, workspacePath, sessionId, actions ->
        SessionRoute(
            hostId = hostId,
            sessionName = sessionName,
            workspacePath = workspacePath,
            sessionId = sessionId,
            onBack = actions.onBack,
            onOpenUsage = actions.onOpenUsage,
            onOpenFiles = actions.onOpenFiles,
            onOpenSession = actions.onOpenSession,
            onOpenNewSession = actions.onOpenNewSession,
            onOpenSettings = actions.onOpenSettings,
        )
    },
    portsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        PortForwardRoute(onBack = onBack)
    },
    servicesScreen: @Composable (
        onBack: () -> Unit,
        onOpenTunnel: (Int) -> Unit,
        onAddTunnel: (Int?) -> Unit,
    ) -> Unit = { onBack, onOpenTunnel, onAddTunnel ->
        ServicesRoute(
            onBack = onBack,
            onOpenTunnel = onOpenTunnel,
            onAddTunnel = onAddTunnel,
        )
    },
    tunnelDetailScreen: @Composable (remotePort: Int, onBack: () -> Unit) -> Unit =
        { remotePort, onBack -> TunnelDetailRoute(remotePort = remotePort, onBack = onBack) },
    addTunnelScreen: @Composable (remotePort: Int?, onDone: () -> Unit) -> Unit =
        { remotePort, onDone -> AddTunnelRoute(initialRemotePort = remotePort, onDone = onDone) },
    filesScreen: @Composable (
        hostId: Long,
        path: String?,
        onOpenFile: (String) -> Unit,
        onBack: () -> Unit,
    ) -> Unit = { _, _, onOpenFile, onBack ->
        FileExplorerRoute(onOpenFile = onOpenFile, onBack = onBack)
    },
    viewerScreen: @Composable (hostId: Long, path: String?, onBack: () -> Unit) -> Unit =
        { _, _, onBack -> ViewerRoute(onBack = onBack) },
    hostFormScreen: @Composable (
        hostId: Long?,
        onDone: () -> Unit,
        onAddKey: () -> Unit,
        onTestConnection: (Long) -> Unit,
    ) -> Unit =
        { hostId, onDone, onAddKey, onTestConnection ->
            AddEditHostRoute(
                hostId = hostId,
                onDone = onDone,
                onAddKey = onAddKey,
                onTestConnection = onTestConnection,
            )
        },
    sshKeysScreen: @Composable (
        onBack: () -> Unit,
        onUseKey: ((Long) -> Unit)?,
    ) -> Unit = { onBack, onUseKey ->
        SshKeysRoute(onBack = onBack, onUseKey = onUseKey)
    },
    settingsScreen: @Composable (SettingsNavigation) -> Unit = { navigation ->
        SettingsRoute(navigation = navigation)
    },
    terminalSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        TerminalSettingsRoute(onBack = onBack)
    },
    voiceSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        VoiceSettingsRoute(onBack = onBack)
    },
    connectionSettingsScreen: @Composable (
        onBack: () -> Unit,
        onOpenWorkspaceRoots: (Long) -> Unit,
    ) -> Unit = { onBack, onOpenWorkspaceRoots ->
        ConnectionSettingsRoute(
            onBack = onBack,
            onOpenWorkspaceRoots = onOpenWorkspaceRoots,
        )
    },
    advancedSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        AdvancedSettingsRoute(onBack = onBack)
    },
    accountSyncScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        AccountSyncRoute(onBack = onBack)
    },
    diagnosticsScreen: @Composable (
        onBack: () -> Unit,
        onOpenReport: (String) -> Unit,
    ) -> Unit = { onBack, onOpenReport ->
        DiagnosticsScreen(onBack = onBack, onOpenReport = onOpenReport)
    },
    diagnosticReportScreen: @Composable (reportId: String, onBack: () -> Unit) -> Unit =
        { reportId, onBack ->
            DiagnosticReportScreen(reportId = reportId, onBack = onBack)
        },
    aboutScreen: @Composable (onBack: () -> Unit, onOpenUpdate: () -> Unit) -> Unit =
        { onBack, onOpenUpdate ->
            AboutRoute(onBack = onBack, onOpenUpdate = onOpenUpdate)
        },
    updateScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        UpdateRoute(onBack = onBack)
    },
    workspaceRootsScreen: @Composable (hostId: Long, onBack: () -> Unit) -> Unit =
        { hostId, onBack ->
            WorkspaceRootsRoute(
                onBack = onBack,
                onOpenAddRoot = { navController.navigate(Destination.AddWorkspaceRoot.route(hostId)) },
                onRootAction = { root, action ->
                    when (action) {
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.ADD_WORKSPACE,
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.CREATE_FOLDER,
                        -> navController.navigate(
                            Destination.WorkspaceRootAction.route(
                                hostId = hostId,
                                rootPath = root.path,
                                action = action.name.lowercase().replace('_', '-'),
                            ),
                        )
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.START_SESSION ->
                            navController.navigate(Destination.WorkspaceStart.route(hostId, root.path))
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.BROWSE_ROOT ->
                            navController.navigate(Destination.Files.route(hostId, root.path))
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.REMOVE_ROOT -> Unit
                    }
                },
            )
        },
    workspaceRootAddScreen: @Composable (
        hostId: Long,
        onBack: () -> Unit,
        onAdded: () -> Unit,
    ) -> Unit = { _, onBack, onAdded ->
        AddWorkspaceRootRoute(onBack = onBack, onAdded = onAdded)
    },
    usageScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        UsageRoute(onBack = onBack)
    },
    hostUsageScreen: @Composable (hostId: Long, onBack: () -> Unit) -> Unit = { hostId, onBack ->
        UsageRoute(onBack = onBack, selectedHostId = hostId)
    },
) {
    // The startup id is resolved once per Activity. A host-row tap updates the
    // live preference, but that update must never become another startup dial.
    val initialStartupHostId = remember { startupHostId }
    val startupHostToConnect = remember { mutableStateOf<Long?>(null) }

    // Issue #2814 N-4. Snapshotted with the host id, for the same reason: the
    // moment a session opens, `onSessionOpened` rewrites the live preference,
    // and a resume that read the LIVE value would chase its own write.
    // `pendingResume` is the one-shot — nulled the instant it is claimed — so
    // a second connect in the same Activity (Back to Hosts, dial again) opens
    // the workspace list like any other tap.
    val pendingResume = remember {
        mutableStateOf(
            StartupSessionResume.of(
                workspacePath = startupWorkspacePath,
                sessionId = startupSessionId,
            ),
        )
    }
    val resumeOnHostId = remember { mutableStateOf<Long?>(null) }

    NavHost(
        navController = navController,
        startDestination = Destination.start.pattern,
        modifier = modifier,
        // Navigation Compose 2.9 fades destinations for 700 ms by default.
        // That leaves the outgoing Hosts layer visibly on top after the tree
        // destination has already composed, which makes a successful trust
        // handoff look stuck on "Connecting…". Hosts and the tree are full
        // screens, so an atomic handoff is both clearer and the settled state
        // the connection gate promises to the user.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Destination.Hosts.pattern) {
            // Task U-2: a host tap DIALS. Only a connected host reaches the
            // tree; an unknown/changed host key raises the trust sheet first
            // and a failed dial keeps the user on the list with a retry.
            ConnectGate(
                onConnected = { hostId ->
                    // The workspace list is navigated to FIRST and
                    // unconditionally, even when a session is about to be
                    // resumed on top of it: it is what Back from the resumed
                    // terminal must land on (#2814 N-4), and it is the whole
                    // outcome whenever the remembered session turns out to be
                    // gone.
                    navController.navigate(Destination.Workspaces.route(hostId))
                    if (pendingResume.value != null && hostId == initialStartupHostId) {
                        resumeOnHostId.value = hostId
                    }
                },
                viewModel = connectViewModel(),
                initialHostId = startupHostToConnect.value,
                onInitialHostConsumed = { startupHostToConnect.value = null },
            ) { onOpenHost ->
                hostsScreen(
                    HostListActions(
                        onOpenHost = { hostId ->
                            onHostOpened(hostId)
                            onOpenHost(hostId)
                        },
                        // Task P-6: the management routes are plain
                        // navigations, deliberately NOT gated by the connect
                        // gate — editing a host must work while the host is
                        // unreachable, which is exactly when a user goes
                        // looking for the form.
                        onAddHost = { navController.navigate(Destination.HostForm.route()) },
                        onEditHost = { hostId ->
                            navController.navigate(Destination.HostForm.route(hostId))
                        },
                        onOpenSshKeys = { navController.navigate(Destination.SshKeys.route()) },
                        // Task P-6 fast-follow put Settings here. Issue #2814
                        // N-2 stopped this being the ONLY way in: the same
                        // route is now a row in the host tools sheet and in
                        // the terminal actions sheet, so Settings is one tap
                        // from a live session instead of back → back → back →
                        // tap. One route, three entry points.
                        onOpenSettings = { navController.navigate(Destination.Settings.route()) },
                    ),
                )
            }
        }
        composable(
            route = Destination.HostForm.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) {
                    type = NavType.LongType
                    defaultValue = Destination.NO_HOST_ID
                },
            ),
        ) { entry ->
            // Task P-6. The sentinel is normalised to `null` HERE, once, so the
            // form's "am I editing?" question has a single answer derived from
            // the route rather than a `-1` leaking into the ViewModel.
            val raw = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: Destination.NO_HOST_ID
            ConnectGate(
                onConnected = { connectedHostId ->
                    navController.navigate(Destination.Workspaces.route(connectedHostId)) {
                        // A successful form test is the access boundary. Keep
                        // Hosts below the new tree, but do not leave a stale
                        // form on the Back stack.
                        popUpTo(Destination.Hosts.pattern)
                    }
                },
                viewModel = connectViewModel(),
            ) { onOpenHost ->
                hostFormScreen(
                    raw.takeIf { it > 0L },
                    { navController.popBackStack() },
                    { navController.navigate(Destination.SshKeys.route()) },
                    onOpenHost,
                )
            }
        }
        composable(Destination.SshKeys.pattern) {
            val previous = navController.previousBackStackEntry
            val canSelectForHostForm = previous?.destination?.route == Destination.HostForm.pattern
            sshKeysScreen(
                { navController.popBackStack() },
                if (canSelectForHostForm) {
                    { keyId ->
                        previous?.savedStateHandle?.set(HOST_FORM_SELECTED_KEY_RESULT, keyId)
                        navController.popBackStack()
                    }
                } else {
                    null
                },
            )
        }
        composable(
            route = Destination.Workspaces.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            // Quiet redesign: the host workspaces screen. The hostId is read from the
            // route here only to hand it to the seam; the ViewModel resolves it
            // from its own SavedStateHandle, so the screen keeps working under
            // process death without the navigation layer re-supplying it.
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val onOpenSession: (SessionRow) -> Unit = { session ->
                navController.openSession(
                    hostId,
                    session.name,
                    session.workspace,
                    session.id,
                    onSessionOpened,
                )
            }
            val onOpenFiles: () -> Unit = { navController.navigate(Destination.Files.route(hostId)) }
            val onOpenPorts: () -> Unit = { navController.navigate(Destination.Ports.route(hostId)) }
            val onBack: () -> Unit = { navController.popBackStack() }
            val onOpenUsage: () -> Unit = { navController.navigate(Destination.HostUsage.route(hostId)) }
            workspacesScreen(
                hostId,
                WorkspacesScreenActions(
                    onOpenWorkspace = { path ->
                        navController.navigate(Destination.Workspace.route(hostId, path))
                    },
                    onOpenSession = onOpenSession,
                    onOpenFiles = onOpenFiles,
                    onOpenFilesAtPath = { path ->
                        navController.navigate(Destination.Files.route(hostId, path))
                    },
                    onOpenPorts = onOpenPorts,
                    onBack = onBack,
                    onOpenUsage = onOpenUsage,
                    onOpenSettings = { navController.navigate(Destination.Settings.route()) },
                ),
                WorkspaceScreenLaunch(),
            )
        }
        composable(
            route = Destination.WorkspaceRootAction.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_ROOT_PATH) { type = NavType.StringType },
                navArgument(Destination.ARG_ROOT_ACTION) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val rootPath = entry.arguments?.getString(Destination.ARG_ROOT_PATH).orEmpty()
            val action = entry.arguments?.getString(Destination.ARG_ROOT_ACTION).orEmpty()
            val onOpenSession: (SessionRow) -> Unit = { session ->
                navController.openSession(
                    hostId,
                    session.name,
                    session.workspace,
                    session.id,
                    onSessionOpened,
                )
            }
            workspacesScreen(
                hostId,
                WorkspacesScreenActions(
                    onOpenWorkspace = { path ->
                        navController.navigate(Destination.Workspace.route(hostId, path))
                    },
                    onOpenSession = onOpenSession,
                    onOpenFiles = { navController.navigate(Destination.Files.route(hostId)) },
                    onOpenFilesAtPath = { path ->
                        navController.navigate(Destination.Files.route(hostId, path))
                    },
                    onOpenPorts = { navController.navigate(Destination.Ports.route(hostId)) },
                    onBack = { navController.popBackStack() },
                    onOpenUsage = { navController.navigate(Destination.HostUsage.route(hostId)) },
                    onOpenSettings = { navController.navigate(Destination.Settings.route()) },
                ),
                WorkspaceScreenLaunch(rootPath, action),
            )
        }
        composable(
            route = Destination.Workspace.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_WORKSPACE_PATH) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val path = entry.arguments?.getString(Destination.ARG_WORKSPACE_PATH).orEmpty()
            workspaceScreen(
                hostId,
                path,
                { sessionName, sessionId ->
                    navController.openSession(hostId, sessionName, path, sessionId, onSessionOpened)
                },
                { navController.navigate(Destination.Files.route(hostId, path)) },
                { navController.navigate(Destination.Ports.route(hostId)) },
                { navController.popBackStack() },
                // Issue #2532: Usage is a host-scoped panel, same as Files/Ports,
                // so the tree header is an entry point — not only the session
                // glance pill.
                { navController.navigate(Destination.HostUsage.route(hostId)) },
            )
        }
        composable(
            route = Destination.WorkspaceStart.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_WORKSPACE_PATH) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val path = entry.arguments?.getString(Destination.ARG_WORKSPACE_PATH).orEmpty()
            WorkspaceRoute(
                onOpenSession = { sessionName, sessionId ->
                    navController.openSession(hostId, sessionName, path, sessionId, onSessionOpened)
                },
                onOpenFiles = { navController.navigate(Destination.Files.route(hostId, path)) },
                onOpenPorts = { navController.navigate(Destination.Ports.route(hostId)) },
                onBack = { navController.popBackStack() },
                onOpenUsage = { navController.navigate(Destination.HostUsage.route(hostId)) },
                onOpenReorder = { navController.navigate(Destination.ReorderWorkspaces.route(hostId)) },
                startSessionOnEntry = true,
            )
        }
        composable(
            route = Destination.ReorderWorkspaces.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) {
            ReorderWorkspacesRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Destination.Session.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_SESSION_NAME) { type = NavType.StringType },
                navArgument(Destination.ARG_WORKSPACE_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Destination.ARG_SESSION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // Task U-4: the real terminal. The session name arrives already
            // percent-decoded by the navigation library, so a session called
            // `my project:review` reaches `sessions attach` byte-identical —
            // which matters, because the name IS the identity the host CLI
            // resolves against (plan §B.0).
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val name = entry.arguments?.getString(Destination.ARG_SESSION_NAME).orEmpty()
            val workspacePath = entry.arguments?.getString(Destination.ARG_WORKSPACE_PATH)
            // Issue #2572: the id, not the name, is what a back-stack entry
            // resolves against — a rename must not strand this screen.
            val sessionId = entry.arguments?.getString(Destination.ARG_SESSION_ID)
            sessionScreen(
                hostId,
                name,
                workspacePath,
                sessionId,
                SessionScreenActions(
                    onBack = { navController.popBackStack() },
                    // Task P-5: the top bar's usage glance pill navigates here.
                    onOpenUsage = { navController.navigate(Destination.HostUsage.route(hostId)) },
                    onOpenFiles = {
                        navController.navigate(Destination.Files.route(hostId, workspacePath))
                    },
                    onOpenSession = { session ->
                        navController.openSession(
                            hostId,
                            session.name,
                            session.workspace,
                            session.id,
                            onSessionOpened,
                        )
                    },
                    onOpenNewSession = {
                        if (workspacePath.isNullOrBlank()) {
                            navController.navigate(Destination.Workspaces.route(hostId))
                        } else {
                            navController.navigate(
                                Destination.WorkspaceStart.route(hostId, workspacePath),
                            )
                        }
                    },
                    onOpenSettings = { navController.navigate(Destination.Settings.route()) },
                ),
            )
        }
        composable(
            route = Destination.Files.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // Task P-3a: the real remote file explorer. Like the tree, the
            // ViewModel reads both arguments from its own SavedStateHandle, so
            // the screen survives process death without navigation re-supplying
            // them; the hostId is read here only to build the viewer route.
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            filesScreen(
                hostId,
                entry.arguments?.getString(Destination.ARG_PATH),
                { filePath -> navController.navigate(Destination.FileViewer.route(hostId, filePath)) },
                { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.FileViewer.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_PATH) { type = NavType.StringType },
            ),
        ) { entry ->
            // Task P-3b: the real file viewer/editor.
            viewerScreen(
                entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L,
                entry.arguments?.getString(Destination.ARG_PATH),
            ) { navController.popBackStack() }
        }
        composable(
            route = Destination.Ports.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            servicesScreen(
                { navController.popBackStack() },
                { remotePort -> navController.navigate(Destination.TunnelDetail.route(hostId, remotePort)) },
                { remotePort -> navController.navigate(Destination.AddTunnel.route(hostId, remotePort)) },
            )
        }
        composable(
            route = Destination.TunnelDetail.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_REMOTE_PORT) { type = NavType.IntType },
            ),
        ) { entry ->
            val remotePort = entry.arguments?.getInt(Destination.ARG_REMOTE_PORT)
                ?: Destination.NO_REMOTE_PORT
            tunnelDetailScreen(remotePort) { navController.popBackStack() }
        }
        composable(
            route = Destination.AddTunnel.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_REMOTE_PORT) {
                    type = NavType.IntType
                    defaultValue = Destination.NO_REMOTE_PORT
                },
            ),
        ) { entry ->
            val rawRemotePort = entry.arguments?.getInt(Destination.ARG_REMOTE_PORT)
                ?: Destination.NO_REMOTE_PORT
            addTunnelScreen(rawRemotePort.takeIf { it > 0 }) { navController.popBackStack() }
        }
        composable(Destination.Settings.pattern) {
            settingsScreen(
                SettingsNavigation(
                    onBack = { navController.popBackStack() },
                    onOpenTerminal = { navController.navigate(Destination.TerminalSettings.route()) },
                    onOpenVoice = { navController.navigate(Destination.VoiceSettings.route()) },
                    onOpenConnections = { navController.navigate(Destination.ConnectionSettings.route()) },
                    onOpenAdvanced = { navController.navigate(Destination.AdvancedSettings.route()) },
                    onOpenAccount = { navController.navigate(Destination.AccountSync.route()) },
                    onOpenDiagnostics = { navController.navigate(Destination.Diagnostics.route()) },
                    onOpenAbout = { navController.navigate(Destination.About.route()) },
                ),
            )
        }
        composable(Destination.TerminalSettings.pattern) {
            terminalSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.VoiceSettings.pattern) {
            voiceSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.ConnectionSettings.pattern) {
            connectionSettingsScreen(
                { navController.popBackStack() },
                { hostId -> navController.navigate(Destination.WorkspaceRoots.route(hostId)) },
            )
        }
        composable(Destination.AdvancedSettings.pattern) {
            advancedSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.AccountSync.pattern) {
            accountSyncScreen { navController.popBackStack() }
        }
        composable(Destination.Diagnostics.pattern) {
            diagnosticsScreen(
                { navController.popBackStack() },
                { reportId -> navController.navigate(Destination.DiagnosticReport.route(reportId)) },
            )
        }
        composable(
            route = Destination.DiagnosticReport.pattern,
            arguments = listOf(navArgument(Destination.ARG_REPORT_ID) { type = NavType.StringType }),
        ) { entry ->
            val reportId = entry.arguments?.getString(Destination.ARG_REPORT_ID).orEmpty()
            diagnosticReportScreen(reportId) { navController.popBackStack() }
        }
        composable(Destination.About.pattern) {
            aboutScreen(
                { navController.popBackStack() },
                { navController.navigate(Destination.Update.route()) },
            )
        }
        composable(Destination.Update.pattern) {
            updateScreen { navController.popBackStack() }
        }
        composable(
            route = Destination.WorkspaceRoots.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            workspaceRootsScreen(hostId) { navController.popBackStack() }
        }
        composable(
            route = Destination.AddWorkspaceRoot.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            workspaceRootAddScreen(
                hostId,
                { navController.popBackStack() },
                { navController.popBackStack() },
            )
        }
        composable(Destination.Usage.pattern) {
            // Task P-5: the real usage/quota panel.
            usageScreen { navController.popBackStack() }
        }
        composable(
            route = Destination.HostUsage.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            hostUsageScreen(hostId) { navController.popBackStack() }
        }
    }

    // This is a cold-launch handoff, not a live observer. The Hosts row writes
    // the last opened host immediately when it is tapped; reacting to that
    // write here would start a second startup dial while the trust sheet is
    // still waiting for the user's decision.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, initialStartupHostId) {
        var startupAttempted = false
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // `repeatOnLifecycle` is only the readiness gate here. The resume
            // handoff is a cold-start action and must run at most once: if the
            // user backs out to Hosts during this Activity, repeating it would
            // immediately send them back to the workspace and can leave a
            // NavBackStackEntry below CREATED during teardown.
            if (startupAttempted) return@repeatOnLifecycle
            startupAttempted = true
            val hostId = initialStartupHostId ?: return@repeatOnLifecycle
            // Let NavHost finish attaching the start entry before adding a
            // second entry. Without this frame boundary a very fast activity
            // teardown can destroy the new entry while it is still INITIALIZED.
            withFrameNanos { }
            if (
                hostId <= 0L ||
                navController.currentDestination?.route != Destination.Hosts.pattern
            ) {
                return@repeatOnLifecycle
            }
            if (!startupHostExists(hostId)) return@repeatOnLifecycle
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@repeatOnLifecycle
            }
            // ConnectGate owns the dial, trust prompt, retry, and success
            // navigation. This handoff supplies only the validated id.
            startupHostToConnect.value = hostId
        }
    }

    // Issue #2814 N-4: the second half of the cold-launch handoff. The host
    // is connected and its workspace list is on the stack; the only question
    // left is whether the remembered session is still alive on that host. It
    // is asked ONCE, of the host, and a "no" is not an error state — the user
    // simply stays on the workspace list, which is where a launch landed
    // before this shipped.
    LaunchedEffect(resumeOnHostId.value) {
        val hostId = resumeOnHostId.value ?: return@LaunchedEffect
        resumeOnHostId.value = null
        val resume = pendingResume.value ?: return@LaunchedEffect
        pendingResume.value = null
        val live = resolveStartupSession(hostId, resume.sessionId) ?: return@LaunchedEffect
        // The host's row wins over the remembered path: a session can be
        // moved, and the route must carry the workspace it is actually in.
        navController.openSession(
            hostId = hostId,
            sessionName = live.name,
            workspacePath = live.workspace ?: resume.workspacePath,
            sessionId = live.id,
            onSessionOpened = onSessionOpened,
        )
    }
}

/**
 * The cold-launch resume pair, already validated as resumable (#2814 N-4).
 *
 * A type rather than two nullable locals so "both resolve" — the issue's
 * condition — is answered exactly once, at [of], instead of at each use site
 * where one of the two nulls could quietly be forgotten.
 */
private data class StartupSessionResume(
    val workspacePath: String,
    val sessionId: String,
) {
    companion object {
        fun of(workspacePath: String?, sessionId: String?): StartupSessionResume? {
            val path = workspacePath?.takeIf { it.isNotBlank() } ?: return null
            val id = sessionId?.takeIf { it.isNotBlank() } ?: return null
            return StartupSessionResume(path, id)
        }
    }
}

