plugins {
    id("com.android.application")
}

android {
    namespace = "id.sekolah.mini"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.sekolah.mini"
        minSdk = 26
        // 34 on purpose: keeps the plain framework action bar below the status bar (no edge-to-edge enforcement).
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
