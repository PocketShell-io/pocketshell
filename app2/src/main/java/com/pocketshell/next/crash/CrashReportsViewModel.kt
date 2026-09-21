package com.pocketshell.next.crash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

internal const val REPORT_ARCHIVES_CACHE_DIR = "report-archives"
private const val REPORT_ARCHIVE_RETENTION_MS = 24L * 60L * 60L * 1000L

/**
 * Ported (unchanged behaviour, package only) from the old app's
 * `com.pocketshell.app.crash.CrashReportsViewModel` (rewrite task P-10).
 *
 * Owns the "Share all reports" + "Delete all reports" actions on the crash
 * reports screen.
 *
 * Responsibilities:
 *
 * - Expose the current report count so the screen can show "Share all (N)".
 * - **Share all**: pack every local report file into ONE zip (via
 *   [ReportsArchive]) and hand the prepared file back to the screen so it
 *   can launch Android's native share sheet through a content URI. This
 *   keeps the potentially large report contents out of intent extras and
 *   works even before a host is configured.
 * - **Delete all**: clear every local report file. Surfaced behind an
 *   explicit confirm in the UI; the ViewModel only ever deletes when asked.
 *
 * Publishes the pure [CrashReportDisplay] mirrors (#2636 D12) — the shared
 * screens paint the display rows, and this view model is the family's single
 * ingestion point, resolving read/delete against the private core list by
 * report id (the D11 ports-seam rule; the core `CrashReport`'s `file` never
 * crosses into `shared:ui-screens`).
 */
@HiltViewModel
class CrashReportsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
) : ViewModel() {

    private val store: CrashReportStore = CrashReporter.store(applicationContext)

    /** Clock for the deterministic archive filename; overridable in tests. */
    @androidx.annotation.VisibleForTesting
    internal var clock: Clock = Clock.systemUTC()

    /** The core reports behind the published display rows; id → file resolution. */
    private var coreReports: List<CrashReport> = emptyList()

    private val _reports = MutableStateFlow<List<CrashReportDisplay>>(emptyList())
    val reports: StateFlow<List<CrashReportDisplay>> = _reports.asStateFlow()

    private val _loadState = MutableStateFlow<CrashReportsLoadState>(CrashReportsLoadState.Loading)
    val loadState: StateFlow<CrashReportsLoadState> = _loadState.asStateFlow()

    private val _shareAllState = MutableStateFlow<ShareAllState>(ShareAllState.Idle)
    val shareAllState: StateFlow<ShareAllState> = _shareAllState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _loadState.value = CrashReportsLoadState.Loading
        runCatching { store.list() }
            .onSuccess { reports ->
                coreReports = reports
                _reports.value = reports.map { it.toDisplay() }
                _loadState.value = CrashReportsLoadState.Ready
            }
            .onFailure { error ->
                coreReports = emptyList()
                _reports.value = emptyList()
                _loadState.value = CrashReportsLoadState.Failed(
                    error.message ?: "Could not read local diagnostic reports.",
                )
            }
    }

    /** Reads the report body for [reportId]; empty when the id has no live file. */
    fun read(reportId: String): String =
        coreReports.firstOrNull { it.id == reportId }
            ?.let { store.read(it) }
            .orEmpty()

    fun deleteOne(reportId: String) {
        coreReports.firstOrNull { it.id == reportId }?.let { store.delete(it) }
        reload()
    }

    /** Confirmed "Delete all": clear every local report file. */
    fun deleteAll() {
        coreReports.forEach { store.delete(it) }
        reload()
    }

    /**
     * Begin the Share-all flow by preparing a zip in cache. The composable
     * invokes [onPrepared] on the main dispatcher once the archive is ready;
     * the screen uses that callback to launch the platform chooser. The state
     * remains available for loading/error UI and deterministic tests.
     */
    fun shareAll(onPrepared: (File) -> Unit = {}) {
        if (_reports.value.isEmpty()) return
        if (_shareAllState.value is ShareAllState.Preparing) return
        val reportFiles = coreReports.map { it.file }.filter { it.isFile }
        if (reportFiles.isEmpty()) {
            _shareAllState.value = ShareAllState.Failed("No reports to share.")
            return
        }
        _shareAllState.value = ShareAllState.Preparing
        viewModelScope.launch {
            val archive = runCatching {
                val dir = reportArchivesDir()
                pruneOldReportArchives(dir)
                val name = ReportsArchive.archiveFileName(deviceLabel(), clock)
                ReportsArchive.packInto(
                    reportFiles = reportFiles,
                    destination = File(dir, name),
                    contentTransform = CrashReportFormatter::redactForSharing,
                )
            }.getOrElse { error ->
                _shareAllState.value = ShareAllState.Failed(
                    error.message ?: "Could not build the reports archive.",
                )
                return@launch
            }

            _shareAllState.value = ShareAllState.Prepared(
                archive = archive,
                reportCount = reportFiles.size,
            )
            onPrepared(archive)
        }
    }

    fun markShareAllLaunched() {
        if (_shareAllState.value is ShareAllState.Prepared) {
            _shareAllState.value = ShareAllState.Idle
        }
    }

    fun shareAllLaunchFailed(message: String) {
        _shareAllState.value = ShareAllState.Failed(message)
    }

    fun clearShareAllState() {
        _shareAllState.value = ShareAllState.Idle
    }

    private fun reportArchivesDir(): File =
        File(applicationContext.cacheDir, REPORT_ARCHIVES_CACHE_DIR).also { it.mkdirs() }

    private fun pruneOldReportArchives(dir: File) {
        val cutoff = System.currentTimeMillis() - REPORT_ARCHIVE_RETENTION_MS
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("pocketshell-reports-") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "device" }
}

/** State machine for the Share-all action. */
sealed interface ShareAllState {
    data object Idle : ShareAllState

    /** Packing the zip in cache. */
    data object Preparing : ShareAllState

    /** The bundle is ready for Android's native share sheet. */
    data class Prepared(
        val archive: File,
        val reportCount: Int,
    ) : ShareAllState

    /** Share failed; reports were preserved. */
    data class Failed(val message: String) : ShareAllState
}
