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
    versionCode = 3
    versionName = "0.3.0"
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
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.work:work-runtime:2.10.0")
}
