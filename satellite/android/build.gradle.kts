import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
    id("g.build.versioning")
}

// Shared org signing keystore — see ../RULES/KEYSTORE.md in the parent workspace.
// Resolution order: environment (CI) -> local.properties. Never hardcoded, never committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun keystoreSecret(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

val sharedStoreFile: File? = keystoreSecret("keystore.storeFile", "KEYSTORE_STORE_FILE")?.let { rootProject.file(it) }
val sharedStorePassword: String? = keystoreSecret("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")
val sharedKeyPassword: String? = keystoreSecret("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD") ?: sharedStorePassword
val sharedKeyAlias: String = keystoreSecret("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "geno"
val hasSharedKeystore = sharedStoreFile != null && sharedStorePassword != null

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

    if (hasSharedKeystore) {
        signingConfigs {
            create("org") {
                storeFile = sharedStoreFile
                storePassword = sharedStorePassword
                keyPassword = sharedKeyPassword
                keyAlias = sharedKeyAlias
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the keystore a release is deliberately left unsigned rather than
            // falling back to any committed key; the workflow refuses to publish it.
            if (hasSharedKeystore) signingConfig = signingConfigs.getByName("org")
        }
        getByName("debug") {
            if (hasSharedKeystore) signingConfig = signingConfigs.getByName("org")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
