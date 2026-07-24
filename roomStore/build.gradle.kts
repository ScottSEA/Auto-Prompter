@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
}

// Focused persistence module. Room3/KSP code generation and the bundled SQLite native binary live
// here, not in :core or :ui, exactly as Room's own guidance recommends (concentrate Room usage in a
// module where the Kotlin Gradle Plugin + KSP can be applied without affecting the rest of the
// codebase). It targets only jvm() (host-tested contract) and androidLibrary (injected into the
// app); the browser keeps :core's InMemoryDocumentStore, so there is no wasmJs target here.
kotlin {
    jvm()

    androidLibrary {
        namespace = "com.scottsea.autoprompter.roomstore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.storeContractTest)
        }
    }
}

// Room3 is KSP-only. The compiler must run for every target that compiles the @Database in
// commonMain, so it is registered per-target (jvm host tests + android app).
dependencies {
    add("kspJvm", libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
}

// The exported schema JSON is a checked-in build input (see roomStore/schemas). Any schema change is
// therefore a visible, reviewed diff and the anchor for future migrations.
room3 {
    schemaDirectory("$projectDir/schemas")
}
