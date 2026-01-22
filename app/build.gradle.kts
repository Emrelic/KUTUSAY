import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Cloud Vision API key'i local.properties'den oku
// ============================================================================
// GUVENLIK UYARISI / SECURITY WARNING:
// API anahtari BuildConfig icine gomulur ve APK decompile edilirse gorulebilir.
// Production ortami icin asagidaki cozumlerden birini kullanin:
// 1. Backend proxy: API anahtarini sunucuda tutun, istemci sunucuya istek atsin
// 2. Firebase Remote Config: Anahtari Firebase'den runtime'da alin
// 3. API key restrictions: Google Cloud Console'da anahtari kisitlayin
//    - Android apps only (SHA-1 fingerprint)
//    - API restrictions (sadece Vision API)
// ============================================================================
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val cloudVisionApiKey: String = localProperties.getProperty("CLOUD_VISION_API_KEY", "")

android {
    namespace = "com.emrelic.kutusay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emrelic.kutusay"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Cloud Vision API key BuildConfig'e ekle
        buildConfigField("String", "CLOUD_VISION_API_KEY", "\"$cloudVisionApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // ML Kit
    implementation(libs.mlkit.text.recognition)

    // Image loading
    implementation(libs.coil.compose)

    // Network (Cloud Vision API)
    implementation(libs.okhttp)
    implementation(libs.gson)
}
