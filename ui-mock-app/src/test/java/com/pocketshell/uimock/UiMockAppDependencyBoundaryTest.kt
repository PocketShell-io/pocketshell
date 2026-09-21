package com.pocketshell.uimock

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural proof for the runnable mock shell's boundary (#2636 slice D16,
 * extending the AC3/AC5 pattern of `:ui-mock`'s UiMockDependencyBoundaryTest).
 *
 * Two directions are pinned: this module's declared project-dependency set
 * stays exactly `:ui-mock` + `:shared:ui-kit` + `:shared:ui-screens` (no app2,
 * no `core-*`, no Termux — AC3), and app2 gains NO `project(":ui-mock…")`
 * edge of any kind, so the shipped debug APK can never include the mock (AC5).
 */
class UiMockAppDependencyBoundaryTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val module = File(root, "ui-mock-app")

    @Test
    fun `module declares exactly the mock and shared presentation dependencies`() {
        val build = File(module, "build.gradle.kts").readText()
        val declared = Regex("project\\(\"([^\"]+)\"\\)")
            .findAll(build)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "ui-mock-app project dependencies must stay mock+presentation-only",
            setOf(":ui-mock", ":shared:ui-kit", ":shared:ui-screens"),
            declared,
        )
    }

    @Test
    fun `application id stays distinct from the shipping app`() {
        val build = File(module, "build.gradle.kts").readText()
        val applicationId = Regex("applicationId = \"([^\"]+)\"")
            .find(build)
            ?.groupValues[1]
        assertEquals("com.pocketshell.uimock", applicationId)

        val app2Build = File(root, "app2/build.gradle.kts").readText()
        assertFalse(
            "the mock applicationId must never appear in the shipping app's build",
            app2Build.contains("com.pocketshell.uimock"),
        )
    }

    @Test
    fun `module is included and app2 consumes neither mock module`() {
        val settings = File(root, "settings.gradle.kts").readText()
        val appBuild = File(root, "app2/build.gradle.kts").readText()
        assertTrue(settings.contains("include(\":ui-mock-app\")"))
        // Prefix match (no closing paren) rejects project(":ui-mock") AND
        // project(":ui-mock-app") and any future :ui-mock* sibling — AC5.
        assertFalse(
            "app2/build.gradle.kts must declare no :ui-mock* project edge",
            appBuild.contains("project(\":ui-mock"),
        )
    }

    @Test
    fun `main sources import no app2 core or termux implementation`() {
        val forbidden = listOf(
            "import com.pocketshell.app",
            "import com.pocketshell.core",
            "import com.termux",
        )
        val offenders = File(module, "src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    forbidden.firstOrNull(line::startsWith)?.let {
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: $line"
                    }
                }.asSequence()
            }
            .toList()

        assertTrue("forbidden implementation imports:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `unit runtime classpath contains neither app2 nor core module outputs`() {
        val entries = requireNotNull(System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar)
            .map { File(it).invariantSeparatorsPath }
        val forbidden = entries.filter { entry ->
            entry.contains("/app2/build/") ||
                entry.contains("/shared/core-") ||
                entry.contains("/termux-")
        }

        assertTrue("forbidden runtime entries:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }
}
