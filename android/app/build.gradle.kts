plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        versionCode = 1
        versionName = "0.1"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}
