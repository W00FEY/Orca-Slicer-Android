plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val orcaSourceDir = rootProject.file("third_party/OrcaSlicer")
val orcaVersion = rootProject.file("ORCA_VERSION").readText().trim()
// ABIs to build. The Pixel 9 family is arm64-v8a; add x86_64 for the emulator
// with -PorcaAbis=arm64-v8a,x86_64 (after building deps for that ABI too).
val orcaAbis = (findProperty("orcaAbis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

android {
    namespace = "io.github.w00fey.orcaslicer"
    compileSdk = 35
    ndkVersion = rootProject.file("NDK_VERSION").readText().trim()

    defaultConfig {
        applicationId = "io.github.w00fey.orcaslicer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0 (Orca $orcaVersion)"

        ndk { abiFilters += orcaAbis }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DORCA_SOURCE_DIR=${orcaSourceDir.absolutePath}",
                    "-DANDROID_STL=c++_shared",
                )
                // Per-ABI dependency prefix is resolved inside CMake (build/deps-<abi>/prefix).
                targets += "orca_jni"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/engine/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the debug key so the release APK installs directly; replace for Play Store uploads.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The bundled resources archive is already compressed.
        noCompress += "zip"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/orca-assets"))

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Packs the runtime data libslic3r needs (printer/filament/process profiles and
// a few lookup tables) into a single asset; the app extracts it on first launch.
val packOrcaResources by tasks.registering(Zip::class) {
    from(File(orcaSourceDir, "resources")) {
        include("profiles/**/*.json")
        include("info/**", "data/**", "flush/**", "printers/**", "custom_gcodes/**", "shape/**")
    }
    archiveFileName.set("orca-resources.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/orca-assets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        check(File(orcaSourceDir, "resources/profiles").isDirectory) {
            "OrcaSlicer sources not found at $orcaSourceDir. Run scripts/fetch_orca.sh first."
        }
    }
}

tasks.named("preBuild") { dependsOn(packOrcaResources) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
