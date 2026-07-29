import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 provides built-in Kotlin support, so no separate kotlin-android plugin is applied.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.ui)
    implementation(projects.roomStore)
    implementation(projects.androidMedia)
    implementation(projects.androidBilling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.scottsea.autoprompter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.scottsea.autoprompter"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Modern physical devices plus x86_64 emulator coverage. Avoid packaging two obsolete
            // sherpa native ABIs in every APK.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
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
