plugins {
    id("com.android.application")
}

android {
    namespace = "com.ylports.worldpainter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ylports.worldpainter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
}
