plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.swipedelete.feature.viewer"
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
    // MediaItem + ItemId are part of the public ViewerScreen/ViewerPager surface.
    api(project(":core:model"))
    // StorageGateway.openStream is the single read seam the viewer consumes.
    implementation(project(":core:storage"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // Audio transport (SPEC 5.4). media3-ui is not needed: the transport is a
    // Compose surface, not PlayerView.
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)

    // Provides the ComponentActivity declaration the local Compose tests launch.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // MediaItem fixtures for the viewer tests come from the gateway fake.
    testImplementation(testFixtures(project(":core:storage")))

    // Device-only pass for the real ExoPlayer state transitions (Task 37).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
