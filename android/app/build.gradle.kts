plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farhan.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.farhan.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    // A fixed key so new builds install over old ones without uninstalling.
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis123"
            keyAlias = "jarvis"
            keyPassword = "jarvis123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("ai.picovoice:porcupine-android:4.0.2")
}
