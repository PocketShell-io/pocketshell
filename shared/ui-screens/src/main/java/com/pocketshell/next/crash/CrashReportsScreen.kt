package com.pocketshell.next.crash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val CRASH_REPORTS_DELETE_ALL_TAG = "crash:deleteAll"
const val CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG = "crash:deleteAll:confirm"
const val CRASH_REPORTS_DELETE_ALL_CANCEL_TAG = "crash:deleteAll:cancel"
const val CRASH_REPORTS_BACK_TAG = "crash:back"
const val CRASH_REPORT_SHARE_TAG = "crash:share"
const val CRASH_REPORTS_EXPORT_LATEST_TAG = "crash:exportLatest"
const val CRASH_REPORTS_CLEAR_TAG = CRASH_REPORTS_DELETE_ALL_TAG
const val CRASH_REPORTS_CLEAR_CONFIRM_TAG = CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG
const val CRASH_REPORTS_CLEAR_CANCEL_TAG = CRASH_REPORTS_DELETE_ALL_CANCEL_TAG
const val CRASH_REPORT_TECHNICAL_DETAILS_TAG = "crash:technicalDetails"
const val CRASH_REPORT_PRIVACY_TAG = "crash:privacy"
const val DIAGNOSTICS_PAGE_TAG = "diagnostics-page"
const val DIAGNOSTIC_REPORT_PAGE_TAG = "diagnostic-report-page"

fun diagnosticReportRowTag(reportId: String): String = "diagnostics-report-$reportId"

/**
 * The Diagnostics list screen. Lives in the shared presentation module
 * (#2636 D12): the composable, its helpers and its test tags moved here from
 * app2's `CrashReportsScreen.kt`, painting the pure [CrashReportDisplay]
 * mirror and plain state + lambdas. The route stays in app2
 * (`DiagnosticsRoute` in app2's `CrashReportsRoute.kt`) with the Hilt view
 * model, the reload-on-entry trigger and the core → display mapping — the D3
 * `SettingsRoute` seam at family scale. The file keeps its original name: a
 * same-named route file on the app2 side would collide on the
 * `CrashReportsScreenKt` JVM facade (the D10 rule).
 *
 * Reads the store through the route-supplied state; the screen never invents
 * reports or provider data.
 */
@Composable
fun DiagnosticsScreen(
    reports: List<CrashReportDisplay>,
    loadState: CrashReportsLoadState,
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    onReload: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        DiagnosticsHeader(onBack = onBack)
        // No `verticalArrangement` (#2804, #2635 audit P-3): the report rows
        // paint their own dividers, so a 12dp gap around each hairline read as
        // neither separator nor group break. The blocks that are not rows carry
        // their own vertical padding instead.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(DIAGNOSTICS_PAGE_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
        ) {
            when (val state = loadState) {
                CrashReportsLoadState.Loading -> item {
                    LoadingIndicator.Spinner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        size = SpinnerSize.Medium,
                        label = "Loading local reports…",
                    )
                }

                is CrashReportsLoadState.Failed -> {
                    item {
                        Banner(
                            text = state.message,
                            role = BannerRole.Error,
                            leadingIcon = PocketShellIcons.Warning,
                            modifier = Modifier.padding(
                                horizontal = PocketShellDensity.screenGutter,
                                vertical = PocketShellSpacing.md,
                            ),
                        )
                    }
                    item {
                        PocketShellButton(
                            text = "Retry loading reports",
                            onClick = onReload,
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.padding(
                                horizontal = PocketShellDensity.screenGutter,
                                vertical = PocketShellSpacing.md,
                            ),
                        )
                    }
                }

                CrashReportsLoadState.Ready -> {
                    item {
                        DiagnosticsIntro(reportCount = reports.size)
                    }
                    if (reports.isEmpty()) {
                        item {
                            EmptyState(
                                title = "No diagnostic reports",
                                description = "Uncaught crashes are kept locally until you choose to share them.",
                                icon = PocketShellIcons.File,
                                modifier = Modifier.height(220.dp),
                            )
                        }
                    } else {
                        item { SectionHeader(label = "Reports", count = reports.size) }
                        itemsIndexed(reports, key = { _, report -> report.id }) { index, report ->
                            ListRow(
                                title = if (index == 0) "Latest report" else "Earlier report",
                                subtitle = diagnosticReportListSubtitle(report),
                                leading = {
                                    androidx.compose.material3.Icon(
                                        PocketShellIcons.File,
                                        contentDescription = null,
                                        tint = PocketShellColors.TextSecondary,
                                    )
                                },
                                trailing = { NavigationChevron() },
                                onClick = { onOpenReport(report.id) },
                                modifier = Modifier.testTag(diagnosticReportRowTag(report.id)),
                            )
                        }
                        item {
                            ListRow(
                                title = "Export latest report",
                                subtitle = "Review before sharing",
                                leading = {
                                    androidx.compose.material3.Icon(
                                        PocketShellIcons.Upload,
                                        contentDescription = null,
                                        tint = PocketShellColors.TextSecondary,
                                    )
                                },
                                trailing = { NavigationChevron() },
                                onClick = { onOpenReport(reports.first().id) },
                                modifier = Modifier.testTag(CRASH_REPORTS_EXPORT_LATEST_TAG),
                            )
                        }
                    }
                    item {
                        ListRow(
                            title = "Clear local reports…",
                            subtitle = "Remove saved diagnostics from this device",
                            leading = {
                                androidx.compose.material3.Icon(
                                    PocketShellIcons.Trash,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            trailing = if (reports.isNotEmpty()) {
                                { NavigationChevron() }
                            } else {
                                null
                            },
                            onClick = if (reports.isNotEmpty()) {
                                { confirmDeleteAll = true }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(CRASH_REPORTS_CLEAR_TAG),
                        )
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Clear local reports?",
            message = "This removes saved diagnostic reports from this device. Your hosts, keys and remote sessions are unchanged.",
            confirmLabel = "Clear reports",
            destructive = true,
            confirmTestTag = CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG,
            dismissTestTag = CRASH_REPORTS_DELETE_ALL_CANCEL_TAG,
            onConfirm = {
                onDeleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
fun DiagnosticReportScreen(
    report: CrashReportDisplay?,
    body: String,
    loadState: CrashReportsLoadState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDeleteReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember(report?.id) { mutableStateOf(false) }
    var technicalDetailsOpen by remember(report?.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        DiagnosticsHeader(title = "Connection report", onBack = onBack)
        // No `verticalArrangement` (#2804, #2635 audit P-3): the report rows
        // paint their own dividers, so a 12dp gap around each hairline read as
        // neither separator nor group break. The blocks that are not rows carry
        // their own vertical padding instead.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(DIAGNOSTIC_REPORT_PAGE_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
        ) {
            when {
                loadState is CrashReportsLoadState.Loading -> item {
                    LoadingIndicator.Spinner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        size = SpinnerSize.Medium,
                        label = "Loading report…",
                    )
                }

                loadState is CrashReportsLoadState.Failed -> item {
                    Banner(
                        text = loadState.message,
                        role = BannerRole.Error,
                        leadingIcon = PocketShellIcons.Warning,
                        modifier = Modifier.padding(
                            horizontal = PocketShellDensity.screenGutter,
                            vertical = PocketShellSpacing.md,
                        ),
                    )
                }

                report == null -> item {
                    EmptyState(
                        title = "Report unavailable",
                        description = "This local report may have been deleted.",
                        icon = PocketShellIcons.File,
                        modifier = Modifier.height(220.dp),
                    )
                }

                else -> {
                    item { ReportSummaryRows(report = report) }
                    item {
                        ListRow(
                            title = "Technical details",
                            subtitle = if (technicalDetailsOpen) "Hide report contents" else "Show report contents",
                            leading = {
                                androidx.compose.material3.Icon(
                                    PocketShellIcons.File,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            trailing = {
                                DisclosureIcon(expanded = technicalDetailsOpen)
                            },
                            onClick = { technicalDetailsOpen = !technicalDetailsOpen },
                            modifier = Modifier.testTag(CRASH_REPORT_TECHNICAL_DETAILS_TAG),
                        )
                    }
                    item {
                        AnimatedVisibility(visible = technicalDetailsOpen) {
                            Text(
                                text = body.ifBlank { "Report contents are unavailable." },
                                color = PocketShellColors.TextSecondary,
                                style = PocketShellType.bodyMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PocketShellDensity.screenGutter,
                                        vertical = PocketShellSpacing.md,
                                    ),
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Review before sharing. Reports may contain hostnames, paths or terminal excerpts.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.body,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PocketShellDensity.screenGutter, vertical = PocketShellSpacing.md)
                                .testTag(CRASH_REPORT_PRIVACY_TAG),
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PocketShellDensity.screenGutter,
                                    vertical = PocketShellSpacing.md,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                        ) {
                            PocketShellButton(
                                text = "Share report",
                                onClick = onShare,
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.testTag(CRASH_REPORT_SHARE_TAG),
                            )
                            PocketShellButton(
                                text = "Delete report",
                                onClick = { confirmDelete = true },
                                variant = ButtonVariant.Destructive,
                                modifier = Modifier.testTag("crash:delete"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && report != null) {
        ConfirmDialog(
            title = "Delete this report?",
            message = "This permanently removes the local diagnostic report.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                onDeleteReport()
                confirmDelete = false
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DiagnosticsHeader(
    onBack: () -> Unit,
    title: String = "Diagnostics",
) {
    ScreenHeader(
        title = title,
        onBack = onBack,
        backTestTag = CRASH_REPORTS_BACK_TAG,
    )
}

@Composable
private fun DiagnosticsIntro(reportCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.screenGutter, vertical = PocketShellSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = "Local reports",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (reportCount == 0) {
                "Reports stay on this device until you choose to share them."
            } else {
                "Reports stay on this device. Share them with support when needed."
            },
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

@Composable
private fun ReportSummaryRows(report: CrashReportDisplay) {
    // Three shared rows and nothing else: the one rhythm is divider with zero
    // gap, so this block drops the 4dp `verticalArrangement` it used to add on
    // top of each row's own hairline (#2804).
    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = "Time",
            subtitle = crashReportTimestamp(report),
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.History,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
        )
        ListRow(
            title = "Screen",
            subtitle = report.contextSummary.substringBefore(" · ").ifBlank { "Unknown screen" },
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.Terminal,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
        )
        ListRow(
            title = "Summary",
            subtitle = report.summary,
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.Info,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
            subtitleMaxLines = 3,
        )
    }
}

private fun diagnosticReportListSubtitle(report: CrashReportDisplay): String =
    listOf(
        crashReportTimestamp(report),
        report.summary,
    ).joinToString(" · ")

private val ReportTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

internal fun crashReportTimestamp(
    report: CrashReportDisplay,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = ReportTimeFormatter.format(report.timestamp.atZone(zoneId))

internal fun crashReportRowTitle(
    report: CrashReportDisplay,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = "${crashReportTimestamp(report, zoneId)} · ${report.summary}"

internal fun crashReportRowSubtitle(report: CrashReportDisplay): String =
    listOfNotNull(
        report.contextSummary.takeIf { it.isNotBlank() },
        report.appVersion?.takeIf { it.isNotBlank() }?.let { "app=$it" },
        report.topFrame?.takeIf { it.isNotBlank() }?.let { "top=${it.toCrashReportTopFrameLabel()}" },
    ).joinToString(" · ")
        .ifBlank { "Context unavailable" }

/**
 * Public because app2's share path (the `DiagnosticReportRoute` seam) builds
 * the share sheet's subject from the same display report the screen paints.
 */
fun crashReportShareSubject(
    report: CrashReportDisplay,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    "PocketShell crash report - " +
        listOfNotNull(
            crashReportTimestamp(report, zoneId),
            report.contextSummary.takeIf { it.isNotBlank() },
            report.summary.takeIf { it.isNotBlank() },
        ).joinToString(" - ")

private fun String.toCrashReportTopFrameLabel(): String {
    val sourceLocation = substringAfterLast('(', missingDelimiterValue = "")
        .removeSuffix(")")
        .takeIf { it.isNotBlank() }
    if (sourceLocation != null) return sourceLocation
    return substringAfterLast('.').takeIf { it.isNotBlank() } ?: this
}
