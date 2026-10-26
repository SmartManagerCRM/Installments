import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional release signing: create keystore.properties (git-ignored) with storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.smartmanager.installments"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartmanager.installments"
        minSdk = 26
        targetSdk = 34
        // Monotonic: CI run number (so each CI build is a valid update), local builds fall back to 100.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only Arabic, French and English resources are shipped (smaller APK, no stray translations from libraries).
        resourceConfigurations += listOf("ar", "fr", "en")
    }

    signingConfigs {
        // Debug builds are always signed with the SAME committed (public, debug-only) key, so every CI/debug APK can be installed
        // as an update over the previous one. Never used for customers; release signing uses keystore.properties below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // Sign with both schemes: some OEM package installers are picky about v2-only APKs.
            enableV1Signing = true
            enableV2Signing = true
        }
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ALLOW_TEST_LICENSE_KEY", "true")
            buildConfigField("boolean", "DEMO_DATA_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_TEST_LICENSE_KEY", "false")
            buildConfigField("boolean", "DEMO_DATA_ENABLED", "false")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
            all {
                it.testLogging {
                    events("started", "passed", "failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showCauses = true
                    showStackTraces = true
                }
            }
        }
    }

    lint { abortOnError = false }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.wsc)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}

// ---- Licensing safety nets -------------------------------------------------------------------
// The PRODUCTION public key is app/src/main/res/raw/license_public_key.pem (generated by the owner with the license
// generator). Release builds refuse to run with the placeholder, and the test key must never ship in a release.
val verifyProductionKey by tasks.registering {
    val keyFile = layout.projectDirectory.file("src/main/res/raw/license_public_key.pem")
    val testKey = rootProject.layout.projectDirectory.file("license-generator/test-keys/TEST_ONLY_public_key.pem")
    inputs.file(keyFile)
    doLast {
        val text = keyFile.asFile.readText()
        val body = text.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        if (text.contains("PLACEHOLDER") || body.isEmpty()) {
            throw GradleException(
                "Release build blocked: app/src/main/res/raw/license_public_key.pem is still the placeholder.\n" +
                    "Create your signing key with the license generator (`python -m smilicgen init-keys`), export the public key " +
                    "(`python -m smilicgen export-public --out app/src/main/res/raw/license_public_key.pem`) and rebuild."
            )
        }
        val test = testKey.asFile.readText().lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        if (body == test) throw GradleException("Release build blocked: the TEST public key must not be used for release builds.")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyProductionKey) }
