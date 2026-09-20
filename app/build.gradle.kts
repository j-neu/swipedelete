plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:designsystem"))
    // M0-T16: the onboarding and grants/slots screens, and the one grant store
    // (read + write ports) :app's Hilt graph binds.
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Room.databaseBuilder is referenced by DatabaseModule; :core:database keeps
    // room-runtime as an `implementation` dependency, so it is not on the app's
    // compile classpath transitively.
    implementation(libs.room.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Compose's test host activity (ComponentActivity) for local Compose tests.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:storage")))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
}
