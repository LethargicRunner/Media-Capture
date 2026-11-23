plugins {
    id("com.android.application")
}

// Numéro de build fourni par GitHub Actions : chaque APK est ainsi une mise à jour valide.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Clé de signature fixe, fournie par le workflow (secrets GitHub).
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "com.lethargicrunner.mediacapture"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lethargicrunner.mediacapture"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("fixed") {
            if (keystorePath != null && keystorePassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "mediacapture"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null && keystorePassword != null) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
