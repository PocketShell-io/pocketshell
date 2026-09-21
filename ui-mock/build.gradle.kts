// Standalone UI mock boundary (#2636 AC3).
//
// The ONLY project dependencies are the shared design system and presentation
// module. In particular, app2, core-*, and the vendored Termux implementation
// must never enter this graph. UiMockDependencyBoundaryTest pins that contract.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.ui.mock"
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
}

dependencies {
    implementation(project(":shared:ui-kit"))
    implementation(project(":shared:ui-screens"))

    testImplementation(libs.junit)
}
