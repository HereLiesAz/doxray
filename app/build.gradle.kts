import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Read API keys from local.properties (falls back to empty strings, in which case
// the app uses the scraper paths instead of the API paths at runtime).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: ""

// When `gh.packages.url` is configured (in local.properties or as the GH_PACKAGES_URL
// env var) we pull the real closed-beta Meta Wearables DAT SDK from GitHub Packages.
// Otherwise we compile against a tiny local stub that mirrors the SDK's public
// signatures so the rest of the app still builds and runs (sans glasses I/O).
val hasMetaSdk: Boolean = run {
    val url = localProps.getProperty("gh.packages.url") ?: System.getenv("GH_PACKAGES_URL")
    !url.isNullOrBlank()
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// Load local properties
val localProperties = Properties().apply {
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

var currentVersionCode = versionProps.getProperty("versionBuild", "1").toInt()

// Automatically increment versionCode for release builds
val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (isReleaseBuild) {
    currentVersionCode++
    versionProps.setProperty("versionBuild", currentVersionCode.toString())
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented by release build")
    }
}

val verMajor = versionProps.getProperty("versionMajor", "1")
val verMinor = versionProps.getProperty("versionMinor", "0")
val verPatch = versionProps.getProperty("versionPatch", "0")
val currentVersionName = "$verMajor.$verMinor.$verPatch"
android {
    namespace = "com.hereliesaz.doxray"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hereliesaz.doxray"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SERPAPI_KEY", "\"${secret("SERPAPI_KEY")}\"")
        buildConfigField("String", "FACESEEK_KEY", "\"${secret("FACESEEK_KEY")}\"")
        buildConfigField("String", "LENSO_KEY", "\"${secret("LENSO_KEY")}\"")
        buildConfigField("String", "FACECHECK_KEY", "\"${secret("FACECHECK_KEY")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_CAPTURE_HTTP", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_CAPTURE_HTTP", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
        compose = true
    }

    androidResources {
        noCompress.add("tflite")
    }

    sourceSets.getByName("main") {
        if (!hasMetaSdk) {
            java.srcDir("src/stub/java")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines & Lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON parsing used by service implementations and scrapers
    implementation("org.json:json:20231013")

    // Meta Wearables Device Access Toolkit (DAT) — closed-beta dependency.
    // Resolution requires gh.packages.url + credentials in local.properties.
    // Falls back to local stub classes under src/stub/java/ when unconfigured.
    if (hasMetaSdk) {
        implementation("com.facebook.wearables:dat-android:1.0.0-beta")
    } else {
        logger.lifecycle("Meta DAT SDK: gh.packages.url not set, using local stub classes from src/stub/java/")
    }

    // Scraping
    implementation("org.jsoup:jsoup:1.17.2")

    // ML Kit
    implementation("com.google.mlkit:face-detection:16.1.5")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Best-effort download of the MobileFaceNet TFLite model at build time.
// Override the URL via the `tflite.model.url` Gradle property. If the URL is
// blank, unreachable, or returns an error, the build continues; the runtime
// EmbeddingGenerator will log a warning and skip local face deduplication.
val downloadTfliteModel by tasks.registering {
    val urlProvider = providers.gradleProperty("tflite.model.url")
    val outFile = layout.projectDirectory.file("src/main/assets/mobilefacenet.tflite").asFile
    outputs.file(outFile)
    outputs.upToDateWhen { outFile.exists() }
    onlyIf {
        !outFile.exists() && urlProvider.isPresent && urlProvider.get().isNotBlank()
    }
    doLast {
        outFile.parentFile.mkdirs()
        val rawUrl = urlProvider.get()
        try {
            val url = uri(rawUrl).toURL()
            logger.lifecycle("Downloading TFLite model from $url")
            url.openStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            logger.warn("Could not download TFLite model from $rawUrl (${e.message}). " +
                "Drop a mobilefacenet.tflite into app/src/main/assets/ manually, " +
                "or override `tflite.model.url` to a working mirror.")
            // Ensure no partial file is left behind
            if (outFile.exists() && outFile.length() == 0L) outFile.delete()
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadTfliteModel) }
