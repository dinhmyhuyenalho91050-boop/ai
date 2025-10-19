import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.indexapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.indexapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
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

    sourceSets["main"].apply {
        res.srcDirs(
            "src/main/res",
            "../android_res/drawable-nodpi",
            "../android_res/mipmap-anydpi-v26",
            "../android_res/mipmap-hdpi",
            "../android_res/mipmap-mdpi",
            "../android_res/mipmap-xhdpi",
            "../android_res/mipmap-xxhdpi",
            "../android_res/mipmap-xxxhdpi",
            "../splash_res/drawable",
            "../splash_res/drawable-hdpi",
            "../splash_res/drawable-mdpi",
            "../splash_res/drawable-xhdpi",
            "../splash_res/drawable-xxhdpi",
            "../splash_res/drawable-xxxhdpi",
            "../splash_res/values",
            "../splash_res/values-v31"
        )
        assets.srcDirs("src/main/assets")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/license.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/notice.txt"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
