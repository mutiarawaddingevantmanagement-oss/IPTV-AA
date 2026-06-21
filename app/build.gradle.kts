plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.paveliptventerprise.kiubsw"
    minSdk = 21
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = false
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // Let's comment out unwanted Compose/Firebase/Room packages to optimize APK size and compatibility for TV boxes
  // implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material3)
  // implementation(libs.androidx.compose.ui)
  // implementation(libs.androidx.compose.ui.graphics)
  // implementation(libs.androidx.compose.ui.tooling.preview)
  
  // Standard Android Core Compatibility — we keep this to support views and components
  implementation("androidx.appcompat:appcompat:1.6.1")
  
  // testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  // testImplementation(libs.robolectric)
  // testImplementation(libs.roborazzi)
  // testImplementation(libs.roborazzi.compose)
  // testImplementation(libs.roborazzi.junit.rule)
  // androidTestImplementation(platform(libs.androidx.compose.bom))
  // androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  // androidTestImplementation(libs.androidx.espresso.core)
  // androidTestImplementation(libs.androidx.junit)
  // androidTestImplementation(libs.androidx.runner)
}
