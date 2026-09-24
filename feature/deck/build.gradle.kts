plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.swipedelete.feature.deck"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The deck engine is pure Kotlin, but it speaks the frozen M0 domain types
    // (ItemId, SlotId). No Android dependency is added here (D5).
    implementation(project(":core:model"))

    testImplementation(libs.kotlin.test.junit)
}
