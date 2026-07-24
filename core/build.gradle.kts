@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // jvm() exists purely to run the shared domain tests fast, off-device.
    jvm()

    androidLibrary {
        namespace = "com.scottsea.autoprompter.core"
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
            // `api`, not `implementation`: core's public @Serializable types (ScriptDocument,
            // ScriptBlockKind, ...) expose serialization-core in their ABI via generated companions
            // (`SerializerFactory`), so any consumer that references them -- including the wasmJs
            // contract compilation in :storeContractTest and :webStore -- needs it on the compile
            // classpath. JVM consumers happened to resolve it transitively; wasmJs does not.
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.storeContractTest)
        }
    }
}
