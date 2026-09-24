plugins {
    id("com.android.application") version "9.4.1"
    id("g.build.versioning")
}

android {
    namespace = "g.erp.satellite"
    compileSdk = 36

    defaultConfig {
        applicationId = "g.erp.satellite"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}