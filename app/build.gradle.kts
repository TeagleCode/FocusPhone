import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The upload key lives outside version control. A clone without it still
// builds — it just falls back to the debug key, which Play refuses, so an
// accidental unsigned upload fails at the Console rather than shipping.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasUploadKey = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.teaglecode.focusphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teaglecode.focusphone"
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Compose in a debug build is several times slower than the same
            // code optimised: no R8, no AOT-compiled baseline profile. On a
            // 4GB phone that is the difference between a launcher that feels
            // instant and one that visibly stutters, so the build you install
            // should be this one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "debug")
        }
    }

    // Google requires VpnService to be an app's core purpose. Site blocking is
    // a secondary feature here, so the Play build ships without it rather than
    // arguing the point during review. The flavour removes the service from the
    // manifest as well as hiding the UI — a declared-but-unused VpnService is
    // still picked up by Play's scanners.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SITE_FILTER", "false")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "SITE_FILTER", "true")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Supplies the non-deprecated LocalLifecycleOwner used to re-read
    // permission state whenever a screen returns to the foreground.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
