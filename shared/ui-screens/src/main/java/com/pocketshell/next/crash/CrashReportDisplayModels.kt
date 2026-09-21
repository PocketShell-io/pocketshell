package com.pocketshell.next.crash

import java.time.Instant

/**
 * Pure display mirrors of the app2 crash-report model family (#2636 D12).
 *
 * The diagnostics surface's presentation layer ([DiagnosticsScreen],
 * [DiagnosticReportScreen], the report row/summary helpers and the test tags)
 * moved into this module, but the boundary design forbids an `app2`
 * dependency — this module's only project dependency stays `:shared:ui-kit`.
 * So the D3 release-check seam applies at family scale: the app2 model the
 * screens paint (`CrashReport`, declared next to app2's
 * `CrashReportStore`) gets a pure display mirror here, and app2 maps it at
 * its one ingestion point (the `CrashReport.toDisplay()` adapter next to
 * `CrashReportsRoute`), exactly like `ReleaseInfo` →
 * `ReleaseUpdateDisplay` (#2636 D3), the usage mirrors (#2636 D10) and the
 * ports mirrors (#2636 D11).
 *
 * Field-by-field decisions (the extraction's "decide per field" list):
 *
 *  - [CrashReportDisplay] carries the six fields the screens paint — `id`,
 *    `timestamp`, `summary`, `contextSummary`, `appVersion`, `topFrame`. The
 *    core model's `file: File` never crosses: no screen paints it, and file
 *    access (read body / delete) resolves app-side inside
 *    `CrashReportsViewModel` against its own core report list, matched by
 *    [CrashReportDisplay.id] — the D11 `hostId`-drop rule.
 *  - [CrashReportsLoadState] moved WHOLE from app2's
 *    `CrashReportsViewModel.kt` rather than mirrored: it is already pure
 *    value state (no model coupling, no `Context`), so a mirror-plus-mapper
 *    pair would be two names for one shape. The view model publishes it
 *    directly.
 */
data class CrashReportDisplay(
    val id: String,
    val timestamp: Instant,
    val summary: String,
    val contextSummary: String,
    val appVersion: String?,
    val topFrame: String?,
)

/** Real local-store states surfaced by the Diagnostics screen. */
sealed interface CrashReportsLoadState {
    data object Loading : CrashReportsLoadState
    data object Ready : CrashReportsLoadState
    data class Failed(val message: String) : CrashReportsLoadState
}
