plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.focus.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focus.launcher"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
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
            // Signed with the debug key by choice: it keeps a single install
            // identity, at the cost of the usual Play Protect warning.
            signingConfig = signingConfigs.getByName("debug")
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
