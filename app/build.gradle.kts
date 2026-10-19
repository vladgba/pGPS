plugins {
    id("com.android.application")
}

// Release signing is not configured here: it is injected per machine by a Gradle init script
// (~/.gradle/init.d) from RELEASE_* properties. Without it, release builds are unsigned.

android {
    namespace = "com.vladgba.gpstools"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vladgba.gpstools"
        // API 24 is where GnssStatus and OnNmeaMessageListener appear.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

// Intentionally no `dependencies { }` block: the app uses the Android framework only.
