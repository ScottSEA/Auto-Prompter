import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class ExportVersionedApk : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun export() {
        val apks =
            inputDirectory
                .get()
                .asFile
                .walkTopDown()
                .filter(File::isFile)
                .filter { it.extension.equals("apk", ignoreCase = true) }
                .toList()
        check(apks.size == 1) {
            "Expected exactly one APK in ${inputDirectory.get().asFile}, found ${apks.size}."
        }
        val destination = outputFile.get().asFile
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) {
            "Could not create APK export directory ${destination.parentFile}."
        }
        Files.copy(
            apks.single().toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        logger.lifecycle("Exported ${destination.absolutePath}")
    }
}

val appVersion = providers.gradleProperty("app.version").get()
val appVersionParts =
    requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)) {
        "app.version must use major.minor.patch semantic versioning."
    }.groupValues.drop(1).map(String::toInt)
require(appVersionParts.all { it in 0..99 }) {
    "Each app.version component must be between 0 and 99."
}
require(appVersionParts.joinToString(".") == appVersion) {
    "app.version must be canonical semantic versioning without leading zeroes."
}
val appVersionCode =
    appVersionParts[0] * 10_000 +
        appVersionParts[1] * 100 +
        appVersionParts[2]
require(appVersionCode > 0) { "app.version must produce a positive Android versionCode." }
val versionedApkName = "AutoPrompter-v$appVersion.apk"
val apkExportDirectory =
    providers
        .gradleProperty("autoPrompter.apkExportDir")
        .orElse(rootProject.layout.projectDirectory.asFile.absolutePath)
        .get()
val versionedApkFile = File(apkExportDirectory, versionedApkName)

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
        versionCode = appVersionCode
        versionName = appVersion
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

fun registerVersionedApkExport(buildType: String) {
    val taskSuffix = buildType.replaceFirstChar(Char::uppercase)
    val exportTask =
        tasks.register<ExportVersionedApk>("export${taskSuffix}Apk") {
            dependsOn("package$taskSuffix")
            inputDirectory.set(layout.buildDirectory.dir("outputs/apk/$buildType"))
            outputFile.set(versionedApkFile)
        }
    tasks.matching { it.name == "assemble$taskSuffix" }.configureEach {
        dependsOn(exportTask)
    }
}

listOf("debug", "release").forEach(::registerVersionedApkExport)
