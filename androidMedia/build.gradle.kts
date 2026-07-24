import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android-only sustained-resource module. This first slice owns offline speech capture and
// recognition; the same module will later expand the single AudioRecord graph to fan PCM to AAC
// recording without moving platform types through :core or :ui.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.scottsea.autoprompter.androidmedia"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(projects.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sherpa.onnx.android)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
