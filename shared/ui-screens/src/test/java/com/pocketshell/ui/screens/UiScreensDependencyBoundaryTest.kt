package com.pocketshell.ui.screens

import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerText
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.RecordingState
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.StagedAttachment
import com.pocketshell.next.composer.StagingProgress
import com.pocketshell.next.hosts.HostRow
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
        val missing = movedTypeMarkers.filter { (_, declaration) ->
            sources.none { DECLARATION_PATTERNS.getValue(declaration).containsMatchIn(it) }
        }.map { it.second }
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
     * #2636 D3 settings). The classpath guard above stops whole modules from
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
        )

        private val DECLARATION_PATTERNS: Map<String, Regex> = movedTypeMarkers.associate {
            it.second to Regex("""\b${Regex.escape(it.second)}\b""")
        }

        /** The app2-side seam, the whole `core-*` family, and DI wiring. */
        private val FORBIDDEN_REFERENCES = Regex(
            """\bSessionSink\b""" +
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
