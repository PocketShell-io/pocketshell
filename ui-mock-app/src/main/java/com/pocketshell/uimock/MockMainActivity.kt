package com.pocketshell.uimock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pocketshell.next.hosts.AddEditHostScreen
import com.pocketshell.next.hosts.HostListScreen
import com.pocketshell.next.hosts.SshKeyRow
import com.pocketshell.next.hosts.SshKeysScreen
import com.pocketshell.next.mockapp.MockAppEvent
import com.pocketshell.next.mockapp.MockAppState
import com.pocketshell.next.mockapp.MockData
import com.pocketshell.next.mockapp.MockDestination
import com.pocketshell.next.mockapp.reduce
import com.pocketshell.next.ports.PortForwardScreen
import com.pocketshell.next.settings.SettingsNavigation
import com.pocketshell.next.settings.SettingsScreen
import com.pocketshell.next.usage.UsageScreen
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.delay

/**
 * The runnable mock shell (#2636 slice D16).
 *
 * One activity, one [MockAppState] held in Compose state, one dispatcher that
 * folds [MockAppEvent]s through the pure `reduce` from `:ui-mock`, and one
 * `when` over [MockDestination] that renders the REAL shared screens of
 * `:shared:ui-screens` for the wired destinations. Nothing here dials SSH,
 * touches Room, starts a recording or opens an intent: every callback either
 * dispatches a reducer event, is a disclosed no-op for state the mock does not
 * model yet, or is not wired at all.
 */
class MockMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same edge-to-edge treatment as the production MainActivity
        // (app2 .../MainActivity.kt): enableEdgeToEdge draws the window under
        // the system bars (enforced anyway on modern targetSdks), and the
        // shell's root pads by WindowInsets.systemBars so the shared screens'
        // headers sit below the status bar exactly as they do in app2. Without
        // the padding every header draws inside the status bar (D16 rev-1
        // review finding).
        enableEdgeToEdge()
        setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    MockShellApp()
                }
            }
        }
    }
}

@Composable
private fun MockShellApp() {
    // The deterministic populated app every fixture scenario starts from —
    // boot()'s pre-read state is reachable through the screens, not the
    // launcher (the mock performs no mock-"read" on launch).
    var state by remember { mutableStateOf(MockAppState.populated()) }
    val dispatch: (MockAppEvent) -> Unit = { event -> state = reduce(state, event) }

    BackHandler(enabled = state.destination.parent != null) {
        dispatch(MockAppEvent.Back)
    }

    // The usage refresh is the one piece of shell timing: the reducer keeps
    // the start/complete pair pure, the shell supplies the deterministic
    // pause between them so the spinner is actually observable.
    LaunchedEffect(state.usageRefreshing) {
        if (state.usageRefreshing) {
            delay(USAGE_REFRESH_MOCK_DELAY_MS)
            dispatch(MockAppEvent.UsageRefreshComplete)
        }
    }

    when (val destination = state.destination) {
        MockDestination.Hosts -> HostListScreen(
            state = state.toHostListUiState(),
            onOpenHost = { dispatch(MockAppEvent.OpenHost(it)) },
            onAddHost = { dispatch(MockAppEvent.Navigate(MockDestination.HostForm)) },
            onEditHost = { dispatch(MockAppEvent.EditHost(it)) },
            onOpenSettings = { dispatch(MockAppEvent.Navigate(MockDestination.Settings)) },
            onDeleteHost = { /* no-op: no delete event in the mock reducer yet (remaining work) */ },
            onOpenSshKeys = { dispatch(MockAppEvent.Navigate(MockDestination.SshKeys)) },
        )

        MockDestination.HostForm -> AddEditHostScreen(
            state = state.hostForm,
            keys = emptyList<SshKeyRow>(), // mock models no keys yet — see toSshKeysScreenState
            onChange = { transform -> dispatch(MockAppEvent.HostFormChange(transform(state.hostForm))) },
            onSave = { /* no-op: no save event in the mock reducer yet (remaining work) */ },
            onCancel = { dispatch(MockAppEvent.Back) },
            onAddKey = { dispatch(MockAppEvent.Navigate(MockDestination.SshKeys)) },
        )

        MockDestination.SshKeys -> SshKeysScreen(
            state = state.toSshKeysScreenState(),
            onBack = { dispatch(MockAppEvent.Back) },
            onGenerate = { /* no-op: no key-generation state in the mock yet (remaining work) */ },
            onImportPasted = { _, _ -> /* no-op: no key store in the mock yet (remaining work) */ },
            onPickFile = { /* no-op: no file picker in the mock — boundary forbids platform I/O */ },
            onDelete = { /* no-op: no key store in the mock yet (remaining work) */ },
            onDismissMessage = { dispatch(MockAppEvent.SshKeysMessageDismiss) },
        )

        MockDestination.Services -> PortForwardScreen(
            state = state.toServicesUiState(),
            onSetEnabled = { dispatch(MockAppEvent.ServicesDiscoveryChange(it)) },
            onTogglePort = { /* no-op: no per-port state in the mock reducer yet (remaining work) */ },
            onSetShowAllPorts = { /* no-op: no show-all state in the mock reducer yet (remaining work) */ },
            onBack = { dispatch(MockAppEvent.Back) },
        )

        MockDestination.Settings -> SettingsScreen(
            navigation = SettingsNavigation(
                onBack = { dispatch(MockAppEvent.Back) },
                // Category pages have no MockDestination yet — the ledger lists
                // each as "destination/reducer state" remaining work, so the
                // rows render but stay inert rather than faking navigation.
                onOpenTerminal = {},
                onOpenVoice = {},
                onOpenConnections = {},
                onOpenAdvanced = {},
                onOpenAccount = {},
                onOpenDiagnostics = {},
                onOpenAbout = {},
            ),
        )

        MockDestination.Usage -> UsageScreen(
            state = state.toUsageUiState(),
            onBack = { dispatch(MockAppEvent.Back) },
            onRefresh = { dispatch(MockAppEvent.UsageRefreshStart) },
            now = MockData.NOW, // pinned clock: reset labels never drift
        )

        // State-seam destinations whose production screen has not crossed the
        // presentation boundary. Labeled placeholder — never a lookalike copy.
        MockDestination.Workspaces -> UnwiredDestinationScreen(
            destination = destination,
            onBack = { dispatch(MockAppEvent.Back) },
        )

        MockDestination.WorkspaceStart -> UnwiredDestinationScreen(
            destination = destination,
            onBack = { dispatch(MockAppEvent.Back) },
        )

        MockDestination.Session -> UnwiredDestinationScreen(
            destination = destination,
            onBack = { dispatch(MockAppEvent.Back) },
        )

        // Land-time union (D16 x D18): D18 grew MockDestination to the full
        // 28-destination graph after this shell was reviewed. These have
        // reducer state but no shared screen wired in the shell yet — same
        // labeled placeholder, so the runnable count stays the six wired
        // screens above.
        MockDestination.About,
        MockDestination.AccountSync,
        MockDestination.AddTunnel,
        MockDestination.AddWorkspaceRoot,
        MockDestination.AdvancedSettings,
        MockDestination.ConnectionSettings,
        MockDestination.DiagnosticReport,
        MockDestination.Diagnostics,
        MockDestination.Files,
        MockDestination.FileViewer,
        MockDestination.HostUsage,
        MockDestination.ReorderWorkspaces,
        MockDestination.TerminalSettings,
        MockDestination.TunnelDetail,
        MockDestination.Update,
        MockDestination.VoiceSettings,
        MockDestination.Workspace,
        MockDestination.WorkspaceRootAction,
        MockDestination.WorkspaceRoots,
        -> UnwiredDestinationScreen(
            destination = destination,
            onBack = { dispatch(MockAppEvent.Back) },
        )
    }
}

@Composable
private fun UnwiredDestinationScreen(
    destination: MockDestination,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(title = unwiredDestinationTitle(destination), onBack = onBack)
        EmptyState(
            title = "Not wired in the mock shell",
            description = unwiredDestinationNote(destination),
            modifier = Modifier
                .fillMaxSize()
                .padding(PocketShellSpacing.lg),
        )
    }
}

private const val USAGE_REFRESH_MOCK_DELAY_MS = 500L
