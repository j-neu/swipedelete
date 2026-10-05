plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.swipedelete.feature.deck"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The Compose unit tests run under Robolectric, which needs the merged
        // resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The pure-Kotlin deck engine speaks the frozen M0 domain types. The UI and
    // session layers add Compose on top of it (D5): the engine itself stays
    // Android-free, but this module also hosts the deck surface.
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // Video autoplay on deck cards (Media3, SPEC 5.4). Same artifact set the
    // viewer compiles against.
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)

    // The deck route owns a @HiltViewModel so the shipped app has a working deck
    // (finding M1-T15-1). hiltViewModel() resolves it from the NavBackStackEntry.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // NOTE: Coil is deliberately NOT used. coil-compose 3.6.3 depends on Compose
    // 1.12.0, which requires compileSdk 37 / AGP 9.1 — impossible on this
    // project's compileSdk 36 / AGP 8.13.2 pin. Card thumbnails decode through
    // ImageDecoder directly, the same path :feature:viewer uses.

    // Provides the ComponentActivity declaration the local Compose tests launch.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // MediaItem fixtures for the deck tests come from the gateway fake.
    testImplementation(testFixtures(project(":core:storage")))

    // Device-only pass for the real MediaCodec decoder budget (Task 39). The
    // androidTest source set needs its own JUnit/runner dependencies; they are
    // not shared with testImplementation.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
