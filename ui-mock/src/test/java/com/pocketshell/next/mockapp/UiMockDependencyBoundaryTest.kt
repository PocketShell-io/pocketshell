package com.pocketshell.next.mockapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural proof for the standalone `:ui-mock` boundary (#2636 AC3/AC5). */
class UiMockDependencyBoundaryTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val module = File(root, "ui-mock")

    @Test
    fun `module declares exactly the two shared presentation dependencies`() {
        val build = File(module, "build.gradle.kts").readText()
        val declared = Regex("project\\(\"([^\"]+)\"\\)")
            .findAll(build)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "ui-mock project dependencies must stay presentation-only",
            setOf(":shared:ui-kit", ":shared:ui-screens"),
            declared,
        )
    }

    @Test
    fun `main sources import no app core or termux implementation`() {
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

    @Test
    fun `standalone module is included without app2 consuming it`() {
        val settings = File(root, "settings.gradle.kts").readText()
        val appBuild = File(root, "app2/build.gradle.kts").readText()
        assertTrue(settings.contains("include(\":ui-mock\")"))
        assertFalse(appBuild.contains("project(\":ui-mock\")"))
    }
}
