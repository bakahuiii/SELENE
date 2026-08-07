plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  namespace = "io.github.bakahuiii.selene.context"
  compileSdk = 35

  defaultConfig {
    applicationId = "io.github.bakahuiii.selene.context"
    minSdk = 26
    targetSdk = 35
    versionCode = 8
    versionName = "0.5.2"
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
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
  buildFeatures {
    buildConfig = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.work:work-runtime:2.10.0")
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
}
