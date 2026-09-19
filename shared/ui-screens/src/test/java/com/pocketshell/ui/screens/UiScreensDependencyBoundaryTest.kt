package com.pocketshell.ui.screens

import com.pocketshell.next.composer.COMPOSER_SLASH_TAG
import com.pocketshell.next.composer.COMPOSER_SLASH_TRIGGER_TAG
import com.pocketshell.next.composer.ComposerImeAnchorAction
import com.pocketshell.next.composer.ComposerImeAnchorSnapshot
import com.pocketshell.next.composer.ComposerImeExpansionOutcome
import com.pocketshell.next.composer.ComposerModalSurfaceGeometry
import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerText
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.composerImeOwnsExpansionAfter
import com.pocketshell.next.composer.composerModalSurfaceOverlapsIme
import com.pocketshell.next.composer.composerSlashRowTag
import com.pocketshell.next.composer.decideComposerImeAnchorAction
import com.pocketshell.next.composer.RecordingState
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.StagedAttachment
import com.pocketshell.next.composer.StagingProgress
import com.pocketshell.next.composer.updateComposerPreImeExpanded
import com.pocketshell.next.connect.TRUST_SHEET_TAG
import com.pocketshell.next.connect.TrustPromptState
import com.pocketshell.next.files.InlineSpan
import com.pocketshell.next.files.MARKDOWN_VIEW_TAG
import com.pocketshell.next.files.MarkdownBlock
import com.pocketshell.next.files.MarkdownParser
import com.pocketshell.next.files.normalizeUrl
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.ports.PortColumn
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsHostRow
import com.pocketshell.next.settings.terminalTextSizePxFromSp
import com.pocketshell.next.settings.terminalTextSizeSpFromPx
import com.pocketshell.next.share.ShareHostRow
import com.pocketshell.next.share.ShareUploadState
import com.pocketshell.next.share.ShareUiState
import com.pocketshell.next.sync.SYNC_ACCOUNT_ROW_TAG
import com.pocketshell.next.sync.SYNC_BACK_TAG
import com.pocketshell.next.sync.SYNC_HOSTS_EMPTY_TAG
import com.pocketshell.next.sync.SYNC_LIST_TAG
import com.pocketshell.next.sync.SYNC_PAGE_TAG
import com.pocketshell.next.sync.SYNC_PASSPHRASE_TAG
import com.pocketshell.next.sync.SYNC_PULL_TAG
import com.pocketshell.next.sync.SYNC_PUSH_TAG
import com.pocketshell.next.sync.SYNC_SIGN_IN_TAG
import com.pocketshell.next.sync.SYNC_SIGN_OUT_TAG
import com.pocketshell.next.sync.SYNC_STATUS_TAG
import com.pocketshell.next.sync.SYNC_UNCONFIGURED_TAG
import com.pocketshell.next.sync.AccountSyncUiState
import com.pocketshell.next.sync.SyncHostRow
import com.pocketshell.next.sync.SyncOutcomeDisplay
import com.pocketshell.next.sync.SyncSignInPhase
import com.pocketshell.next.sync.syncHostRowTag
import com.pocketshell.next.usage.USAGE_RESET_BANNER_TAG
import com.pocketshell.next.usage.UsageResetBannerState
import com.pocketshell.next.usage.UsageResetEvent
import java.io.File

/**
 * Boundary guard for the `shared:ui-screens` presentation module (#2636 D1).
 *
 * The module must stay a pure presentation surface: screens take plain state
 * + lambdas, and the only project dependency is `:shared:ui-kit`. Anything
 * that drags transport/storage/voice/app2 onto this module's compile or test
 * classpath breaks that contract, so this test fails the JVM suite as soon as
 * one appears — `java.class.path` names every resolved project artifact, so a
 * single added `implementation(project(...))` line is caught here without any
 * Gradle wiring.
 *
 * The #2636 D2 slice moved the composer presentation state in here (package
 * `com.pocketshell.next.composer`), keeping the transport-facing send sink in
 * app2, so two source-level guards back the classpath one: the moved
 * declarations must actually be present (a silently emptied module would make
 * every other check here vacuous), and no source in the module may reference
 * the sink, the `core-*` packages or DI wiring — those are app2's side of the
 * seam.
 *
 * The #2636 D3 slice added the settings family (hosts came with D1): its
 * guard below (`screenSourcesReferenceNoReleaseTypesOrPlatformClasses`)
 * locks the release-check seam and keeps `android.*` platform imports out
 * of the module.
 *
 * The #2636 D4 slice added the share family (`SharePickerScreen` + its pure
 * state types): the upload-transport class the screen previously reached into
 * for its display-path constant stays app2-side, so the transport/DI scan
 * below also forbids a reference to it by name.
 *
 * The #2636 D5 slice extended the composer family with the slash-sheet tags
 * and the IME anchor policy — pure presentation, so no new seam guard; the
 * moved declarations joined [movedTypeMarkers] (and the non-class members a
 * non-type marker list below) so a silent deletion still fails the build.
 *
 * The #2636 D6 slice moved the settings sub-pages (`*Screen` composables from
 * app2's `SettingsPages.kt`) plus `AppSettings` itself — pure value state, no
 * repository/`Context` — and the host display row. The routes, the Hilt view
 * models and the build-info read stay app2-side; the release/platform scan
 * below keeps it that way.
 *
 * The #2636 D7 slice added the sync family (`AccountSyncScreen` + its pure
 * state/display types): the app2-side result types (`SyncOutcome`, the
 * sign-in coordinator's `State`) stay in `AccountSyncViewModel.kt` and are
 * mapped onto the shared display shapes by app2-side adapters — the same
 * service/result seam D3 locked for the release check.
 *
 * The #2636 D8 slice added the leftovers families (files markdown model +
 * parser + renderer, ports table chrome, workspaces session mark, usage reset
 * banner + its event type, connect trust sheet + its pure state) — all pure
 * presentation. The trust sheet's transport seam mirrors D3/D4: its state
 * moved here while the `TrustDecision` factory stayed app2-side as a
 * `Companion` extension, so `TrustPromptState.from(...)` call sites did not
 * change; the moved state is marked below and the module keeps a bare
 * `companion object` for it.
 *
 * The imports above are load-bearing twice over: they are referenced by
 * [movedTypeMarkers] (so a deleted declaration fails the BUILD, not just this
 * test), and they feed the test-area manifest's import-derived dependency
 * sets — this guard must be selected by a change to EITHER extracted family,
 * because it now guards both (invariant I11: a shared module's own change
 * runs its own `:test` task).
 */
class UiScreensDependencyBoundaryTest {

    /** Modules whose presence on the classpath would break the boundary. */
    private val forbidden = listOf(
        ":shared:core-storage",
        ":shared:core-terminal",
        ":shared:core-transport",
        ":shared:core-hostapi",
        ":shared:core-portfwd",
        ":shared:core-voice",
        ":shared:core-assistant",
        ":shared:core-usage",
        ":shared:test-support",
        "app2",
    )

    @org.junit.Test
    fun classpathCarriesNoForbiddenModules() {
        val classpath = System.getProperty("java.class.path") ?: ""
        val offenders = forbidden.filter { classpath.contains(it) }
        check(offenders.isEmpty()) {
            "shared:ui-screens classpath gained forbidden module(s) $offenders — " +
                "the presentation boundary (#2636 D1) forbids storage/transport/" +
                "voice/test-support/app2 dependencies; the classpath was:\n$classpath"
        }
    }

    @org.junit.Test
    fun extractedDeclarationsArePresentInSources() {
        val sources = moduleMainSources().values.toList()
        val missing = movedDeclarations.filter { declaration ->
            sources.none { DECLARATION_PATTERNS.getValue(declaration).containsMatchIn(it) }
        }
        check(missing.isEmpty()) {
            "shared:ui-screens lost extracted presentation declarations $missing — " +
                "the #2636 extractions moved them here verbatim; a gap means this " +
                "guard would scan an incomplete module"
        }
    }

    @org.junit.Test
    fun sourcesReferenceNoTransportOrDi() {
        val offenders = moduleMainSources()
            .filter { (_, text) -> FORBIDDEN_REFERENCES.containsMatchIn(text) }
            .keys
            .sorted()
        check(offenders.isEmpty()) {
            "shared:ui-screens sources reference the transport/DI seam: $offenders — " +
                "the send sink, the core-* packages and DI wiring stay in app2 " +
                "(#2636 D2); the presentation module sees plain state + lambdas only"
        }
    }

    /**
     * Source-level lock for the extracted screen families (#2636 D1 hosts,
     * #2636 D3 settings, #2636 D4 share). The classpath guard above stops whole
     * modules from
     * becoming dependencies; this one stops the subtler leak where a screen
     * family drags its former app-side service types along as imports —
     * concretely: no `com.pocketshell.next.release` reference (the settings
     * release-check seam maps `ReleaseInfo` onto the pure `ReleaseUpdateDisplay`
     * on the app2 side) and no `android.*` platform import (the module is
     * Android-library only for Compose; `androidx.*` is fine).
     */
    @org.junit.Test
    fun screenSourcesReferenceNoReleaseTypesOrPlatformClasses() {
        val moduleRoot = findModuleSourceRoot()
        val offenders = moduleRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val code = stripComments(file.readText())
                val problems = buildList {
                    if (code.contains("com.pocketshell.next.release")) {
                        add("references com.pocketshell.next.release")
                    }
                    if (Regex("""^\s*import\s+android\.""", RegexOption.MULTILINE).containsMatchIn(code)) {
                        add("imports an android.* platform class")
                    }
                }
                if (problems.isEmpty()) null else "${file.relativeTo(moduleRoot)}: $problems"
            }
            .toList()
        check(offenders.isEmpty()) {
            "shared:ui-screens sources broke the presentation boundary — screens " +
                "must paint pure state only; the release-check seam is mapped on " +
                "the app2 side (#2636 D3). Offenders:\n${offenders.joinToString("\n")}"
        }
    }


    /**
     * The extracted types this module owns, each paired with the source
     * declaration the presence scan looks for. The `KClass` references make a
     * deleted declaration a compile error; the scan catches one that survives
     * compilation only by living somewhere it must not.
     */
    private companion object {
        private val movedTypeMarkers = listOf(
            ComposerNotice::class to "sealed interface ComposerNotice",
            StagingProgress::class to "data class StagingProgress",
            SentMessage::class to "data class SentMessage",
            ComposerUiState::class to "data class ComposerUiState",
            StagedAttachment::class to "data class StagedAttachment",
            RecordingState::class to "enum class RecordingState",
            ComposerText::class to "object ComposerText",
            // The D1 family — imported so the manifest's dependency index keeps
            // this guard selected on hosts-side changes too.
            HostRow::class to "data class HostRow",
            // The D4 family — same role: share-side changes keep this guard
            // selected through these imports (invariant I11).
            ShareUiState::class to "data class ShareUiState",
            ShareUploadState::class to "sealed interface ShareUploadState",
            ShareHostRow::class to "data class ShareHostRow",
            // The D5 family — the IME anchor policy's class-like declarations.
            // Its consts and functions have no KClass and ride in
            // movedNonTypeMarkers below.
            ComposerImeAnchorAction::class to "enum class ComposerImeAnchorAction",
            ComposerImeExpansionOutcome::class to "enum class ComposerImeExpansionOutcome",
            ComposerImeAnchorSnapshot::class to "data class ComposerImeAnchorSnapshot",
            ComposerModalSurfaceGeometry::class to "data class ComposerModalSurfaceGeometry",
            // The D6 family — settings sub-pages' state. `AppSettings` moved
            // whole because it is pure value state (no repository/`Context`);
            // the composables themselves have no KClass and their pure
            // helpers ride in movedNonTypeMarkers below.
            AppSettings::class to "data class AppSettings",
            SettingsHostRow::class to "data class SettingsHostRow",
            // The D7 family — same role: sync-side changes keep this guard
            // selected through these imports (invariant I11).
            AccountSyncUiState::class to "data class AccountSyncUiState",
            SyncSignInPhase::class to "sealed interface SyncSignInPhase",
            SyncOutcomeDisplay::class to "sealed interface SyncOutcomeDisplay",
            SyncHostRow::class to "data class SyncHostRow",
            // The D8 family — the leftovers: markdown model/parser/renderer,
            // port-table chrome, usage reset banner + its event type, the
            // session mark, and the trust sheet's pure state (whose
            // `TrustDecision` factory stayed app2-side as a `Companion`
            // extension, so no `core.transport` import crossed). The moved
            // composables get no markers and the families' pure tags/urls ride
            // in movedNonTypeMarkers below, matching D5/D6.
            TrustPromptState::class to "data class TrustPromptState",
            MarkdownBlock::class to "sealed interface MarkdownBlock",
            InlineSpan::class to "sealed interface InlineSpan",
            MarkdownParser::class to "object MarkdownParser",
            PortColumn::class to "data class PortColumn",
            UsageResetBannerState::class to "data class UsageResetBannerState",
            UsageResetEvent::class to "data class UsageResetEvent",
        )

        /**
         * The D5, D6, D7 and D8 slices also moved top-level consts and
         * functions (`ComposerBar.kt`'s slash-sheet tags, the IME anchor
         * policy's pure functions, the settings terminal text-size px↔sp
         * converters, `AccountSyncScreen.kt`'s sync test tags, the leftovers
         * families' representative test tags and the URL normaliser), which
         * cannot ride in [movedTypeMarkers] — no `KClass`. Each left-hand
         * reference below is itself load-bearing: deleting the declaration
         * fails the BUILD on this import/reference, exactly like a `KClass`
         * reference would. Moved composable screens get none (matching
         * D5/D6/D7/D8): a composable function takes no `KFunction` reference,
         * and app2's routes, render fixtures and tests pin them by importing
         * them.
         */
        private val movedNonTypeMarkers: List<Pair<Any, String>> = listOf(
            COMPOSER_SLASH_TAG to "const val COMPOSER_SLASH_TAG",
            COMPOSER_SLASH_TRIGGER_TAG to "const val COMPOSER_SLASH_TRIGGER_TAG",
            ::composerSlashRowTag to "fun composerSlashRowTag",
            ::composerImeOwnsExpansionAfter to "fun composerImeOwnsExpansionAfter",
            ::updateComposerPreImeExpanded to "fun updateComposerPreImeExpanded",
            ::composerModalSurfaceOverlapsIme to "fun composerModalSurfaceOverlapsIme",
            ::decideComposerImeAnchorAction to "fun decideComposerImeAnchorAction",
            ::terminalTextSizeSpFromPx to "fun terminalTextSizeSpFromPx",
            ::terminalTextSizePxFromSp to "fun terminalTextSizePxFromSp",
            SYNC_PAGE_TAG to "const val SYNC_PAGE_TAG",
            SYNC_BACK_TAG to "const val SYNC_BACK_TAG",
            SYNC_SIGN_IN_TAG to "const val SYNC_SIGN_IN_TAG",
            SYNC_SIGN_OUT_TAG to "const val SYNC_SIGN_OUT_TAG",
            SYNC_ACCOUNT_ROW_TAG to "const val SYNC_ACCOUNT_ROW_TAG",
            SYNC_PASSPHRASE_TAG to "const val SYNC_PASSPHRASE_TAG",
            SYNC_PUSH_TAG to "const val SYNC_PUSH_TAG",
            SYNC_PULL_TAG to "const val SYNC_PULL_TAG",
            SYNC_STATUS_TAG to "const val SYNC_STATUS_TAG",
            SYNC_UNCONFIGURED_TAG to "const val SYNC_UNCONFIGURED_TAG",
            SYNC_HOSTS_EMPTY_TAG to "const val SYNC_HOSTS_EMPTY_TAG",
            SYNC_LIST_TAG to "const val SYNC_LIST_TAG",
            ::syncHostRowTag to "fun syncHostRowTag",
            TRUST_SHEET_TAG to "const val TRUST_SHEET_TAG",
            MARKDOWN_VIEW_TAG to "const val MARKDOWN_VIEW_TAG",
            USAGE_RESET_BANNER_TAG to "const val USAGE_RESET_BANNER_TAG",
            ::normalizeUrl to "fun normalizeUrl",
        )

        private val movedDeclarations: List<String> =
            movedTypeMarkers.map { it.second } + movedNonTypeMarkers.map { it.second }

        private val DECLARATION_PATTERNS: Map<String, Regex> = movedDeclarations.associate {
            it to Regex("""\b${Regex.escape(it)}\b""")
        }

        /**
         * The app2-side seam, the whole `core-*` family, and DI wiring. The
         * share upload transport joined with D4: the screen's display-path
         * constant moved into the module instead of the transport reference.
         */
        private val FORBIDDEN_REFERENCES = Regex(
            """\bSessionSink\b""" +
                """|\bShareUploader\b""" +
                """|com\.pocketshell\.core\.""" +
                """|dagger\.hilt""" +
                """|javax\.inject""" +
                """|android\.content\.Context""",
        )
    }

    /**
     * Locates this module's `src/main/java` root. Unit-test working directories
     * vary (module dir, repo root), so walk up until a directory holds both
     * extracted screen families.
     */
    private fun findModuleSourceRoot(): java.io.File {
        val start = java.io.File(System.getProperty("user.dir") ?: error("no user.dir"))
        val sourceRoot = generateSequence(start, ::parentOrNull)
            .map { java.io.File(it, "src/main/java") }
            .firstOrNull {
                java.io.File(it, "com/pocketshell/next/hosts").isDirectory &&
                    java.io.File(it, "com/pocketshell/next/settings").isDirectory
            }
        checkNotNull(sourceRoot) {
            "could not locate shared:ui-screens src/main/java from ${start.absolutePath}"
        }
        return sourceRoot
    }

    private fun parentOrNull(file: java.io.File): java.io.File? = file.parentFile

    /** Removes `//` line and `/* */` block comments so prose cannot trip the scan. */
    private fun stripComments(source: String): String {
        val withoutBlockComments = StringBuilder()
        var index = 0
        var inBlockComment = false
        while (index < source.length) {
            if (inBlockComment) {
                val end = source.indexOf("*/", index)
                if (end < 0) break
                inBlockComment = false
                index = end + 2
            } else when {
                source.startsWith("/*", index) -> {
                    inBlockComment = true
                    index += 2
                }
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index)
                    index = if (end < 0) source.length else end
                }
                else -> {
                    withoutBlockComments.append(source[index])
                    index++
                }
            }
        }
        return withoutBlockComments.toString()
    }


    /**
     * Every Kotlin main source in this module, as `relative path -> text`.
     * Resolves from both the repo root and the module dir — Gradle's unit-test
     * working directory is the module dir, but a run from an IDE can differ.
     */
    private fun moduleMainSources(): Map<String, String> {
        val root = locateDir("shared/ui-screens/src/main/java", "src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }
    }

    private fun locateDir(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: error("Could not locate any of ${candidates.joinToString()} from ${File(".").absolutePath}")
}
