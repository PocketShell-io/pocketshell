plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // #2636 D19: the diagnostics instrumentation core (process-wide event
    // bus, bounded JSONL ring-buffer store, off-main coroutine recorder)
    // moved here from app2's `next/diagnostics` package, files verbatim —
    // the source package stays `com.pocketshell.next.diagnostics` (the
    // established move convention, cf. ui-screens/ui-mock); only the module
    // identity (this namespace) is new. app2 is the only consumer; the
    // module must never depend on app2 or any UI module.
    namespace = "com.pocketshell.core.diagnostics"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // DiagnosticRecorderTest / DiagnosticRecorderOffMainTest run under
        // Robolectric (they need a real Context for filesDir / cacheDir /
        // packageManager). Mirrors :shared:core-voice's setup; the 2g heap
        // matches the app2 environment these same tests ran in before the
        // move.
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "2g"
                test.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}

dependencies {
    // Move-forced (D19): `DiagnosticRecorder` carries
    // `androidx.annotation.VisibleForTesting` on its test-only members —
    // app2 supplied the artifact transitively; this module declares it.
    implementation(libs.androidx.annotation)

    // Coroutines are part of the public surface — `clear()`,
    // `exportSnapshot()`, `readEvents()` are `suspend`, and
    // `DiagnosticRecorder` takes a `CoroutineDispatcher`.
    api(libs.kotlinx.coroutines.core)

    // Unit tests. org.json is provided by android.jar on the main compile
    // classpath (the platform supplies it at runtime); the tests run on the
    // host JVM and need the real implementation — same as :shared:core-usage.
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // #2707 LeakGuard — the same suite-wide leak-attribution boundary the
    // diagnostics tests already ran under in app2. Test-only; never ships.
    testImplementation(project(":shared:test-support"))
}
