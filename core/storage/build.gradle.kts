plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.swipedelete.core.storage"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testFixtures {
        enable = true
    }

    testOptions {
        // The real-Room chaos sweep and the SAF locator host test run under
        // Robolectric, which needs the merged resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:database"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(project(":core:model"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    // The frozen StorageGatewayContractTest lives in testFixtures so both the
    // host suite and the instrumented suite can extend it.
    testFixturesImplementation(libs.kotlin.test.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core:storage")))
    // Robolectric hosts the real-Room chaos sweep (finding 6) and the SAF
    // locator test; room-runtime/ktx back the real `SwipeDeleteDatabase`, which
    // :core:database keeps an `implementation` dependency (not transitive).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(testFixtures(project(":core:storage")))
}
