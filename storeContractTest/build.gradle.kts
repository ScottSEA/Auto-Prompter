@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Test-support module: its *main* source set owns the reusable DocumentStore behavior contract so
// every adapter (InMemory in :core, Room in :roomStore) can run the exact same nine behaviors
// without duplicating them or shipping test code inside production :core. It intentionally targets
// the same platforms as :core's test source sets (jvm, android, wasmJs) so :core:commonTest can
// depend on it, and it exposes kotlin-test + coroutines as `api` because its public functions are
// assertions. It contains no tests of its own.
kotlin {
    jvm()

    androidLibrary {
        namespace = "com.scottsea.autoprompter.store.contract"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
