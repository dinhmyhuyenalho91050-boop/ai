plugins {
    id("com.android.application")
}

fun signingProperty(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun signingFile(path: String) = file(path)
    .takeIf { it.exists() }
    ?: rootProject.file(path)

val releaseStorePath = signingProperty("AI_CHAT_KEYSTORE_FILE")
val releaseStoreFile = releaseStorePath?.let(::signingFile)
val releaseStorePassword = signingProperty("AI_CHAT_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("AI_CHAT_KEY_ALIAS")
val releaseKeyPassword = signingProperty("AI_CHAT_KEY_PASSWORD") ?: releaseStorePassword
val releaseStoreType = signingProperty("AI_CHAT_KEYSTORE_TYPE")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

/*
 * Keep this lookup backward-compatible with both root-relative paths from CI
 * (app/signing/release.p12) and app-module-relative paths from local Gradle.
 */
if (releaseStorePath != null && releaseStoreFile?.exists() != true) {
    throw GradleException("Release signing keystore not found: $releaseStorePath")
}

android {
    namespace = "com.example.htmlapp"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.example.htmlapp"
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        resourceConfigurations += listOf("en", "zh")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                releaseStoreType?.let { storeType = it }
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
}
