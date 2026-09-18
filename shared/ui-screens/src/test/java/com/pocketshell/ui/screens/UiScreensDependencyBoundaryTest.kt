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
}
