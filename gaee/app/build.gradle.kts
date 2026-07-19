import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

// Load secrets from gaee/.env (gitignored). Falls back to a real env var, then a placeholder.
val envProps = Properties().apply {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) envFile.inputStream().use { load(it) }
}
fun secret(key: String, default: String): String =
    envProps.getProperty(key) ?: System.getenv(key) ?: default

android {
    namespace = "com.gaee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaee"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLAUDE_API_KEY", "\"${secret("CLAUDE_API_KEY", "YOUR_CLAUDE_API_KEY")}\"")
        buildConfigField("String", "OPENWEATHERMAP_API_KEY", "\"${secret("OPENWEATHERMAP_API_KEY", "YOUR_OPENWEATHERMAP_API_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // ONNX Runtime bundles native libs for multiple ABIs
    packagingOptions {
        pickFirst("**/libonnxruntime.so")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.play.services.mlkit.language.id)
    implementation(libs.activity.ktx)
    // Phase 2
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.onnxruntime.android)
    implementation(libs.gson)
    implementation(libs.work.runtime.ktx)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // JVM-only unit tests for pure logic (e.g. ScamDetector) — run with `gradlew testDebugUnitTest`.
    testImplementation("junit:junit:4.13.2")
}
