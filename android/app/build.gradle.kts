plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.pitchflight"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.pitchflight"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = false
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation("androidx.activity:activity-ktx:1.8.0")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.11.0")

  // Local tests: jUnit
  testImplementation(libs.junit)

  // Instrumented tests
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

// Gradle task to automatically synchronize web assets before compilation
tasks.register<Copy>("copyWebAssets") {
    from(file("../../public"))
    into(file("src/main/assets"))
    exclude("**/*.db")
    exclude("**/vocal_trainer.db")
}

tasks.named("preBuild") {
    dependsOn("copyWebAssets")
}
