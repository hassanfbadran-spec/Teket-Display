plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hulul.callscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hulul.callscreen"
        minSdk = 21
        targetSdk = 33
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("signing.keystore")
            storePassword = "callscreen123"
            keyAlias = "callscreen"
            keyPassword = "callscreen123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
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
