@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Focused durable-web persistence module. It targets *only* wasmJs: it is the browser's durable
// DocumentStore backend (Android keeps :roomStore), so there is no jvm/android target here. It wraps
// the browser IndexedDB API through Juul's com.juul.indexeddb wrapper, kept as an `implementation`
// dependency so no Juul/JS/IndexedDB type ever leaks through the DocumentStore seam. Browser tests
// run the exact reusable nine-behavior contract from :storeContractTest in a real headless Chromium.
kotlin {
    wasmJs {
        browser {
            testTask {
                useKarma {
                    // Real headless Chromium, no visible UI. The karma.config.d/ overrides add the
                    // sandbox/gpu flags needed to launch Chrome in a headless CI-style environment.
                    useChromeHeadless()
                }
            }
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(projects.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.juul.indexeddb.core)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // The test source set of this module can see main's `implementation` Juul dependency, so
            // the raw-row seeding seam can drive IndexedDB directly to stage corrupt states.
            implementation(projects.storeContractTest)
        }
    }
}
