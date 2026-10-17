plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.wallcapture.ocr"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    // Bundled Latin-script model: works offline, no Play Services download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
