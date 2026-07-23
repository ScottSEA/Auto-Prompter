# Auto-Prompter

Android-first Compose Multiplatform teleprompter with a Kotlin/Wasm browser preview.

**This repository is the architecture tracer bullet, not a finished product.** It exists to
prove the module layout and the "shared core owns alignment, platform adapters depend inward"
design end to end: a minimal speech-follow engine in shared code, exercised by a diagnostic
Compose screen that runs unchanged on Android and in the browser.

## Modules

| Module        | Type                              | Responsibility |
|---------------|-----------------------------------|----------------|
| `:core`       | KMP library (jvm, android, wasmJs) | Immutable script/hypothesis domain and the public `ScriptFollower` follow interface. |
| `:ui`         | KMP + Compose library (android, wasmJs) | Shared Compose tracer screen. Calls `:core` for all alignment; holds none of its own. |
| `:androidApp` | Android application                | Android launcher (`MainActivity`) hosting the shared screen. |
| `:webApp`     | Kotlin/Wasm Compose executable     | Browser composition root serving the shared screen. |

Adapters (`:androidApp`, `:webApp`) depend inward on `:ui` -> `:core`; nothing depends outward.

### Why this shape

The four-module `:core` / `:ui` / `:androidApp` / `:webApp` split is the preferred shape and it
builds cleanly under the current toolchain, so no deeper collapse was needed. `:core` adds a
`jvm()` target purely so the shared domain tests run fast off-device (`:core:jvmTest`); the shipped
targets are `androidLibrary` and `wasmJs`.

## What the follow engine does today

`prefixScriptFollower(script)` is an **exact-prefix tracer**: it advances by the length of the
exact leading match between the normalized script and the recognizer hypothesis, and it never lets
committed progress slide backwards when a hypothesis is revised shorter. It is deliberately **not**
the banded fuzzy alignment, confidence, hysteresis, or recovery engine described in the
architecture proposal; the status is named accordingly in `ScriptFollower.kt`.

## Toolchain

Versions come from the official Kotlin/KMP-App-Template baseline (commit `63ff248c`): Kotlin
2.3.21, Compose Multiplatform 1.11.0, AGP 9.0.1, Gradle 9.3.1, compile/target SDK 36, minSdk 26.
Requires JDK 17 and an Android SDK; set its location in `local.properties`:

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

The Gradle wrapper downloads its pinned distribution on first run.

## Build / test / run

Run from the repository root (`./gradlew` on Unix, `.\gradlew.bat` on Windows).

```bash
# Shared core behavior tests (fast, off-device)
./gradlew :core:jvmTest

# Android debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# Deployable web preview -> webApp/build/dist/wasmJs/productionExecutable/
./gradlew :webApp:wasmJsBrowserDistribution

# Live web preview at http://localhost:8080 (Ctrl+C to stop)
./gradlew :webApp:wasmJsBrowserDevelopmentRun
```

Open `webApp/build/dist/wasmJs/productionExecutable/index.html` through any static file server
(it will not run from a `file://` URL because it loads a wasm module). The preview requires a
modern browser with WasmGC support.

### Web build note

`:webApp:wasmJsBrowserDistribution` bundles with webpack, which Kotlin fetches through Yarn from
the public npm registry. On a network that blocks `registry.yarnpkg.com` / `registry.npmjs.org`,
point Yarn at a reachable mirror in `~/.yarnrc` (`registry "<mirror-url>"`) or pre-populate a
`yarn-offline-mirror`. The Android and core builds do not need npm.

## Conventions

Functional and immutable: data classes, pure top-level functions, small effect-free transforms;
no DI framework. Package root `com.scottsea.autoprompter`. ASCII source.
