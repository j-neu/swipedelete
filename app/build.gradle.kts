plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// `-PnoAllFilesAccess` produces the Play-submittable contingency build of SPEC 4.4:
// the MANAGE_EXTERNAL_STORAGE declaration is replaced by the stripped manifest at
// src/noAllFilesAccess/AndroidManifest.xml, and the capability controller reports
// All Files Access as UnavailableInThisBuild (BuildConfig.ALL_FILES_ACCESS = false).
// This is a build flag, not a second product — one APK, one listing.
val noAllFilesAccess: Boolean = providers.gradleProperty("noAllFilesAccess").isPresent

android {
    namespace = "com.swipedelete.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swipedelete.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["noAllFilesAccess"] = "false"
        buildConfigField("boolean", "ALL_FILES_ACCESS", if (noAllFilesAccess) "false" else "true")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (noAllFilesAccess) {
        sourceSets.getByName("main").manifest.srcFile("src/noAllFilesAccess/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
