package com.pocketshell.next.crash

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Route-level entry points for the diagnostics destinations (`Diagnostics`,
 * `DiagnosticReport`). Stays in app2 (#2636 D12): [DiagnosticsScreen] and
 * [DiagnosticReportScreen] moved to the shared presentation module
 * (`shared:ui-screens`) painting the pure [CrashReportDisplay] mirror, but
 * these routes, the Hilt view model and the core → display mapping below are
 * app concerns — the module boundary keeps app2's `CrashReport` (with its
 * `java.io.File`) out of the shared screens, exactly like the D10 usage seam
 * (`UsageRoute` + the fetcher's ingestion-point mapping) and D11 ports. The
 * file is named for the route it holds (the D3 file-naming rule): a
 * same-named `CrashReportsScreen.kt` on both sides of the seam would collide
 * on the `CrashReportsScreenKt` JVM facade.
 *
 * The reload-on-entry triggers live here (they were `LaunchedEffect` calls
 * into the view model inside the pre-move screens — same composition-entry
 * timing, app-side wiring), as does the platform share handoff: building the
 * `Intent` needs a `Context`, and redaction
 * ([CrashReportFormatter.redactForSharing]) stays with the report text
 * pipeline the view model owns.
 */
@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashReportsViewModel = hiltViewModel(),
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.reload() }
    DiagnosticsScreen(
        reports = reports,
        loadState = loadState,
        onBack = onBack,
        onOpenReport = onOpenReport,
        onReload = viewModel::reload,
        onDeleteAll = viewModel::deleteAll,
        modifier = modifier,
    )
}

@Composable
fun DiagnosticReportRoute(
    reportId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashReportsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val report = reports.firstOrNull { it.id == reportId }
    val body = remember(report) { report?.let { viewModel.read(it.id) }.orEmpty() }
    LaunchedEffect(reportId) { viewModel.reload() }
    DiagnosticReportScreen(
        report = report,
        body = body,
        loadState = loadState,
        onBack = onBack,
        onShare = {
            // The share button only renders when a report is on screen, so the
            // null arm is a no-op guard, not a reachable path.
            report?.let { shareReport(context, it, CrashReportFormatter.redactForSharing(body)) }
        },
        onDeleteReport = { viewModel.deleteOne(reportId) },
        modifier = modifier,
    )
}

private fun shareReport(
    context: android.content.Context,
    report: CrashReportDisplay,
    body: String,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, crashReportShareSubject(report))
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostic report"))
}

/**
 * Maps one app2 `CrashReport` onto the pure [CrashReportDisplay] the shared
 * diagnostics screens paint (#2636 D12).
 *
 * This is the crash family's whole core → display seam: the view model runs
 * it once per listed report, so no app2 model (and its `java.io.File`) ever
 * crosses into `shared:ui-screens`. Every value is copied verbatim, never
 * re-implemented, so the screens cannot drift from the store — which is what
 * lets the family's PNGs be compared byte-for-byte across the move. The
 * `file` field is deliberately not copied: no screen paints it, and the view
 * model resolves read/delete against its own core list by [CrashReportDisplay.id].
 */
internal fun CrashReport.toDisplay(): CrashReportDisplay =
    CrashReportDisplay(
        id = id,
        timestamp = timestamp,
        summary = summary,
        contextSummary = contextSummary,
        appVersion = appVersion,
        topFrame = topFrame,
    )
