// ui-mock-app — the independently runnable mock shell (#2636 slice D16).
//
// A real Android APPLICATION module with a distinct applicationId
// (`com.pocketshell.uimock`), so installing it can never collide with the
// shipping `com.pocketshell.app` client. Its complete declared
// project-dependency set is exactly `:ui-mock` + `:shared:ui-kit` +
// `:shared:ui-screens` — the same hard presentation boundary AC3 established
// for `:ui-mock`: no app2, no `core-*`, no Termux. The boundary is pinned from
// both directions: UiMockAppDependencyBoundaryTest rejects forbidden edges
// here, and rejects any ui-mock project edge in app2/build.gradle.kts
// (AC5: the shipped debug APK must not include the mock).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pocketshell.uimock"
    compileSdk = 36

    // Same rationale as app2 (issue #42's rule): pin every build of this
    // module to the one committed debug keystore so a laptop build, a CI build
    // and any agent worktree's build share one signing identity, and upgrading
    // the existing com.pocketshell.uimock install never trips "signatures do
    // not match". The password is the public Android debug password — the file
    // has no security value.
    signingConfigs {
        create("debugKeystore") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.pocketshell.uimock"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mock"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugKeystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":ui-mock"))
    implementation(project(":shared:ui-kit"))
    implementation(project(":shared:ui-screens"))

    // Same compose stack app2 declares; the shared screens render inside this
    // activity's setContent, so the runtime artifacts are declared explicitly
    // rather than relied on transitively.
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
