@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Focused browser live-speech module. It targets *only* wasmJs: it is the browser's live speech
// recognition backend for the shared `LiveSpeechRuntime` seam (Android injects the explicit
// unsupported runtime for now). It wraps the browser Web Speech API through a small internal engine
// seam so no Web Speech / JS type ever leaks through `LiveSpeechRuntime` / `SpeechSession`. Browser
// tests drive a fake engine (no microphone, no permission prompt) in real headless Chromium.
kotlin {
    wasmJs {
        browser {
            testTask {
                useKarma {
                    // Real headless Chromium, no visible UI and no microphone access. The
                    // karma.config.d/ overrides add the sandbox/gpu flags for a CI-style launch.
                    useChromeHeadless()
                }
            }
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(projects.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
