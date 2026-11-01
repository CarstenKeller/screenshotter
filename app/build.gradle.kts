plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.ck.screenshotter"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.ck.screenshotter"
        minSdk = 26
        targetSdk = 35
        // In der CI fortlaufende Build-Nummer, damit jede Version über die vorige installiert werden kann.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "0.1.0-b$build"
    }

    signingConfigs {
        // Fester Schlüssel im Repo, damit alle Builds dieselbe Signatur haben und Updates ohne
        // Deinstallation möglich sind. Nur für private Nutzung gedacht, nicht für den Play Store.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Für eine echte Veröffentlichung eigene Signatur hinterlegen.
            signingConfig = signingConfigs.getByName("debug")
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
}
