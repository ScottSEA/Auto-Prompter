rootProject.name = "Auto-Prompter"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                // sherpa-onnx's official Android demo publishes its tagged AAR through JitPack.
                // Restrict this repository to that one upstream group.
                includeGroup("com.github.k2-fsa")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":core")
include(":storeContractTest")
include(":roomStore")
include(":webStore")
include(":webSpeech")
include(":androidMedia")
include(":ui")
include(":androidApp")
include(":webApp")
