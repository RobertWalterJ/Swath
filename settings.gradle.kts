pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("android") version "2.0.21"
        kotlin("plugin.compose") version "2.0.21"
        kotlin("plugin.serialization") version "2.0.21"
        id("com.android.application") version "8.7.3"
        id("com.android.library") version "8.7.3"
        id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "swath"

// JVM modules always build. Android modules need an Android SDK (local.properties, ANDROID_HOME
// or ANDROID_SDK_ROOT) and are skipped otherwise, so `./gradlew test` works on any machine.
val sdkFromProps = file("local.properties").takeIf { it.exists() }
    ?.readLines()?.any { it.trim().startsWith("sdk.dir") } == true
val androidAvailable = providers.gradleProperty("withAndroid").orNull != "false" &&
    (sdkFromProps || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null)

include(":core", ":replay-cli")
if (androidAvailable) {
    include(":storage", ":ocr-mlkit", ":capture", ":app")
} else {
    println("Swath: no Android SDK found, skipping :app, :capture, :storage, :ocr-mlkit")
}
