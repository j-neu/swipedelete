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

        // M0-T17: the acceptance suite is instrumented so it exercises real files,
        // real Room and the real assembled graph on a device (AC-1/3/5/7).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// AppManifestTest reads the *merged* manifest to guard AC-6 (a dependency must
// not inject INTERNET), so the unit-test task must depend on its producer.
// `matching`/`configureEach` is used because AGP registers unit-test tasks lazily.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("processDebugMainManifest")
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
    // M1-T14: the deck destination hosts the deck surface (card + accessible
    // action bar) and the viewer/{itemId} destination opens the real viewer.
    implementation(project(":feature:deck"))
    implementation(project(":feature:viewer"))

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
    // NoMutationBeforeRecoveryTest (D6) drives the real RecoveryCoordinator
    // through the real SessionController, so the app unit-test classpath needs
    // the deck feature. Task 41 wires it into the app's main graph.
    testImplementation(project(":feature:deck"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)

    // M0-T17 instrumented acceptance suite: real device, real files, real Room.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Room.databaseBuilder (the real on-disk journal) needs room-runtime on the
    // androidTest classpath too: :core:database keeps it `implementation`, so it
    // is not transitive.
    androidTestImplementation(libs.room.runtime)
}
