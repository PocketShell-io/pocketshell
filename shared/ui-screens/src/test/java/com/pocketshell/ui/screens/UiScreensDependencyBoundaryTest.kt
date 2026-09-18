package com.pocketshell.ui.screens

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
}
