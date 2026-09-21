pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocketshell"

// Module layout mirrors docs/architecture.md.
//
// Approach for not-yet-existing modules: keep them commented out. Each later
// issue uncomments its own line as part of creating the module directory.
// Rationale: an `include()` for a missing directory is a hard Gradle error,
// and an explicit comment list is more discoverable than a conditional
// `file().exists()` filter (no silent skips, no magic).
//
// Rewrite in progress (docs/_scratch/simplification-implementation-plan-2026-09-02.md).
// app/, core-ssh, core-tmux, core-connection, core-agents were deleted in the
// "stable" branch hard cut. New modules (core-transport, core-hostapi, app2)
// are uncommented here as their scaffolding tasks land.
//
// Task P-4 rewired core-portfwd's transport acquisition from the deleted
// core-ssh onto core-transport's HostConnection, so it is back in the build.
include(":shared:core-portfwd")
include(":shared:core-transport")
// Task K-1: the host-CLI JSON parser/client. Pure JVM (kotlin("jvm")), the
// first non-Android module in the tree — it must stay free of the Android SDK
// so it is testable on the host JVM with no Robolectric/emulator.
include(":shared:core-hostapi")
include(":shared:core-terminal")
include(":shared:core-usage")
include(":shared:core-storage")
include(":shared:core-voice")
include(":shared:core-assistant")
// #2636 slice D19: the diagnostics instrumentation core (process-wide event
// bus, bounded JSONL ring-buffer store, off-main coroutine recorder), moved
// verbatim from app2's next/diagnostics package. app2 is the only consumer;
// the module must never depend on app2 or any UI module.
include(":shared:core-diagnostics")
include(":shared:ui-kit")

// #2636 slice D1: the shared presentation boundary. Stateless screen
// composables + UI state that app2 (and later the ui-mock app) consume.
// The module must stay free of transport/storage/voice dependencies —
// app2's ViewModels and routes keep the VM→UI mapping on the app side.
include(":shared:ui-screens")

// #2636 AC3: standalone mock-state/application seam. This is an Android
// library so it can consume the real shared presentation modules, while its
// dependency graph deliberately excludes app2 and every core implementation.
include(":ui-mock")

// Test-only support module (issue #1048): the ONE audited shared de-flake
// settle-pump, consumed via `testImplementation` only — never ships in the APK.
include(":shared:test-support")

// The rewrite's application module (task M-1). applicationId is
// `com.pocketshell.app` so a 0.5.0 install upgrades the existing client.
include(":app2")
