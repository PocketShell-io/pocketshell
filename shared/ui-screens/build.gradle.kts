// Shared presentation module (#2636 slice D1): stateless screen composables
// and the UI state / callback types at the presentation boundary.
//
// Boundary rules, enforced by what is deliberately NOT declared here:
//   * no Room / core-storage — screens take presentation row types (HostRow,
//     SshKeyRow); app2's routes map entities to rows;
//   * no core-transport / core-voice / app2 — screens are stateless and take
//     plain state + lambdas; ViewModels, routes and DI stay in app2;
//   * the only project dependency is :shared:ui-kit (theme + primitives).
// `UiScreensDependencyBoundaryTest` fails the JVM suite if a forbidden module
// ever reaches this module's classpath.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pocketshell.ui.screens"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Same Compose set :shared:ui-kit declares — its deps are `implementation`
    // there, so they are not exposed transitively and every consumer declares
    // its own (BOM-pinned) copies.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)

    // Theme + shared visual primitives. `implementation` (not `api`): no
    // ui-kit type appears in this module's public signatures yet.
    implementation(project(":shared:ui-kit"))

    testImplementation(libs.junit)
}
