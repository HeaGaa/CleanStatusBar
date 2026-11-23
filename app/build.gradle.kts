plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cleanstatusbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cleanstatusbar"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
}
