plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val appVersionCode = providers.gradleProperty("versionCode")
    .orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("versionName")
    .orNull ?: "0.1"

android {
    namespace = "com.otoniel.sonylivemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.otoniel.sonylivemonitor"
        minSdk = 24
        // targetSdk 28 a proposito: permite conectar a la WiFi de la camara
        // con la API legacy (WifiManager.enableNetwork), sin el dialogo de
        // seleccion de red que Android 10+ impone a las apps con target >= 29.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk 28 es intencionado para conservar la API WiFi legacy.
        // La aplicacion se distribuye como APK, no mediante Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
}
