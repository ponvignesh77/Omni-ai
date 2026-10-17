import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional signing config. Create keystore.properties in the project root (see notes).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.containsKey("storeFile")

android {
    namespace = "com.example.aiassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aiassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (hasSigning) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Copies the built APK to build/outputs/apk-export/ with a clear name.
// Run with: gradle exportApk   (or ./gradlew exportApk)
tasks.register<Copy>("exportApk") {
    val variant = if (hasSigning) "release" else "debug"
    dependsOn(if (hasSigning) "assembleRelease" else "assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/$variant"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk-export"))
    rename { "my-ai-assistant-$variant-${android.defaultConfig.versionName}.apk" }
}
