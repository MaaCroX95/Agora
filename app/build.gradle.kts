plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Build-time bytecode fix for the Android 15 removeFirst()/removeLast() crash (see build-logic).
    id("buildlogic.removefirstlast-fix")
}

import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.reader())
}

val ciVersionCode = providers.environmentVariable("AGORA_VERSION_CODE").orNull?.let { rawValue ->
    rawValue.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("AGORA_VERSION_CODE must be a positive integer, got '$rawValue'.")
}

val releaseStoreFile = keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val releaseStorePassword = keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = keystoreProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = keystoreProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

val releaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').contains("Release", ignoreCase = true)
}

if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is not fully configured. Configure storeFile, storePassword, keyAlias, and " +
            "keyPassword in local.properties; debug-signing fallback is intentionally disabled.",
    )
}

if (releaseTaskRequested && releaseStoreFile != null && !file(releaseStoreFile).isFile) {
    throw GradleException("Configured release keystore does not exist: $releaseStoreFile")
}

android {
    namespace = "com.newoether.agora"
    testOptions.unitTests.isIncludeAndroidResources = true
    // Robolectric installs Conscrypt as the first security provider for the whole test JVM;
    // on JDK 17+ its TLS server needs reflective access to java.net (InetAddress.holder).
    testOptions.unitTests.all { it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED") }
    compileSdk {
        version = release(36)
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.newoether.agora"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 31
        versionName = "2.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
                targets += listOf("agora_llama", "agora_proot")
            }
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".screenshots"
            versionNameSuffix = "-screenshots"
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
            }
        }
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keep upstream R8 shrink/optimization while preserving the fork's strict signing contract.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
        }
        create("fdroid") {
            dimension = "store"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
    }
    // The app switches locales at runtime without Play Feature Delivery. Keep every
    // packaged translation available instead of letting App Bundles split languages.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Extract .so files to disk for ProcessBuilder exec (Kai approach)
    @Suppress("UnstableApiUsage")
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // bcprov, bcutil and bcpkix each ship the same BouncyCastle (MIT) license text.
            pickFirsts += "META-INF/LICENSE.md"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Proot binaries (libproot_exec.so, libproot_loader.so, libtalloc.so) are
// built via GNUmakefile (see .build-proot/) and placed directly in jniLibs.
// No CMake target is needed — the binaries are manually managed prebuilts.
// talloc is built with SONAME=libtalloc.so (no version) so AGP packaging works.

tasks.register<Copy>("copyPlayApk") {
    from("build/outputs/apk/play/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyFdroidApk") {
    from("build/outputs/apk/fdroid/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyPlayBundle") {
    from("build/outputs/bundle/playRelease")
    into("release")
    include("*.aab")
}

afterEvaluate {
    tasks.named("assemblePlayRelease") {
        finalizedBy("copyPlayApk")
    }
    tasks.named("assembleFdroidRelease") {
        finalizedBy("copyFdroidApk")
    }
    tasks.named("bundlePlayRelease") {
        finalizedBy("copyPlayBundle")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.markdown)
    implementation(libs.jetbrains.markdown)
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.lottie.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    // WebUI server (stage 2): embedded HTTP with the pure-Kotlin CIO engine.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    // Builds the WebUI's self-signed TLS certificate.
    implementation(libs.bouncycastle.pkix)
    testImplementation(libs.ktor.server.test.host)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile") || name.contains("BaselineProfile") || name.contains("baselineProfile")) {
        enabled = false
    }
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) {
        enabled = false
    }
}
