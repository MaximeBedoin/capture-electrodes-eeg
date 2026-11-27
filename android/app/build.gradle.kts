plugins {
    id("com.android.application")  // AGP 9 : Kotlin intégré, pas de plugin Kotlin séparé
}

android {
    namespace = "io.github.maximebedoin.captureeeg"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maximebedoin.captureeeg"
        minSdk = 29  // MediaStore.Downloads sans permission de stockage
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // pas de Play Store : APK signé avec la clé de débogage, installé par adb
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
