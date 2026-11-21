plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.quirkdeck.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.quirkdeck.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
