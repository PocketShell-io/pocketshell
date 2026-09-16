package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.next.tree.CreateSessionRequest
import com.pocketshell.next.tree.CreateSessionSheet
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.SessionTreeViewModel
import com.pocketshell.next.tree.existingSessionTags
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

const val WORKSPACE_START_SCREEN_TAG: String = "workspace-start-screen"
const val WORKSPACE_CREATE_NOTICE_TAG: String = "workspace-create-notice"
const val WORKSPACE_NEW_SESSION_TAG: String = "workspace-new-session"
const val WORKSPACE_NEW_SESSION_LABEL: String = "New session"
const val WORKSPACE_RETRY_TAG: String = "workspace-retry"
const val WORKSPACE_BACK_TAG: String = "workspace-back"

/**
 * The short label for a workspace path — the last path segment ("pocketshell"
 * for `/home/a/git/pocketshell`), or the raw path when it has no useful
 * segment. Shared by the start screen's header and the switcher sheet's
 * other-workspace rows, so one workspace is called one thing everywhere.
 */
fun workspaceLabel(path: String?): String =
    path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { path } ?: "Workspace"

/**
 * Route-level binding for `Destination.WorkspaceStart` (issue #2721).
 *
 * The workspace screen this used to sit next to is gone (D22): a workspace row
 * opens its entry session's terminal directly, so the ONLY remaining
 * workspace-level surface is this small one, and its whole job is hosting the
 * create sheet — a workspace with zero sessions navigates here on tap and the
 * sheet is already up; there is never a blank intermediate page.
 */
@Composable
fun WorkspaceStartRoute(
    onOpenSession: (String, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    // The sheet is the point of this screen, so it opens on arrival — but NOT
    // over an idempotent-create notice: `openCreateSheet()` clears the notice,
    // and coming Back from a session the create merely FOUND must still say
    // plainly that nothing was newly created (issue #2721). The "New session"
    // button re-opens the sheet from there.
    LaunchedEffect(Unit) {
        if (viewModel.state.value.create.notice == null) viewModel.openCreateSheet()
    }
    LaunchedEffect(state.create.openRequest) {
        val name = state.create.openRequest ?: return@LaunchedEffect
        val id = state.create.openRequestId
        viewModel.consumeOpenRequest()
        onOpenSession(name, id)
    }
    WorkspaceStartScreen(
        state = state,
        onOpenSession = onOpenSession,
        onBack = onBack,
        onCreateSession = viewModel::openCreateSheet,
        onSubmitCreate = viewModel::createSession,
        onRefreshEngines = viewModel::refreshEngines,
        onDismissCreate = viewModel::dismissCreateSheet,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

/**
 * The create-session surface for one workspace (issue #2721).
 *
 * A tap on a workspace that has nothing running lands here with the sheet
 * already open. An idempotent create (`created:false`) OPENS the session it
 * found — with the workspace page gone, refusing to navigate would strand the
 * user — while the notice still says plainly that nothing was newly created;
 * coming Back from that session shows the banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceStartScreen(
    state: SessionTreeUiState,
    onOpenSession: (String, String?) -> Unit,
    onBack: () -> Unit = {},
    onCreateSession: () -> Unit = {},
    onSubmitCreate: (CreateSessionRequest) -> Unit = {},
    onRefreshEngines: () -> Unit = {},
    onDismissCreate: () -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(WORKSPACE_START_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = workspaceLabel(state.workspacePath),
            subtitle = state.workspacePath,
            titleMaxLines = 2,
            subtitleMaxLines = 2,
            onBack = onBack,
            backTestTag = WORKSPACE_BACK_TAG,
        )

        state.create.notice?.let { notice ->
            Banner(
                text = notice,
                role = BannerRole.Info,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(WORKSPACE_CREATE_NOTICE_TAG),
            )
        }

        state.failure?.let { failure ->
            Banner(
                text = failure,
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = onRefresh,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(WORKSPACE_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm),
            )
        }

        EmptyState(
            title = "No sessions here yet",
            description = "Start a session in ${state.workspacePath ?: "this workspace"}.",
            action = {
                PocketShellButton(
                    text = WORKSPACE_NEW_SESSION_LABEL,
                    onClick = onCreateSession,
                    modifier = Modifier.testTag(WORKSPACE_NEW_SESSION_TAG),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = state.suggestedFolder,
            existingSessionNames = existingSessionTags(state.workspaceSessions),
            onSubmit = onSubmitCreate,
            onCancel = onDismissCreate,
            onRefreshEngines = onRefreshEngines,
        )
    }
}
