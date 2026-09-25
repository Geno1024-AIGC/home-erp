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
        versionCode = maxOf(project.extra["versionSeq"] as Int, 1)
        versionName = project.version.toString()
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}