plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localmind.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localmind.chat"
        minSdk = 26 // 26 gives us AES/GCM in the Keystore without workarounds
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The Local Open-Source Llama AI configuration.
        // Replace with your PC's local Wi-Fi IP address (192.168.29.161) so physical devices can connect.
        buildConfigField("String", "LOCAL_LLAMA_MODEL", "\"llama3.2\"")
        buildConfigField("String", "LOCAL_LLAMA_URL", "\"http://192.168.29.161:11434\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // Lets Room verify your queries at compile time against the real schema.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // --- Local, on-device storage. This is where every message lives. ---
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)   // encrypts the local database file at rest
    implementation(libs.sqlite.ktx)

    implementation(libs.coroutines.android)

    // Local HTTP streaming for open-source Llama AI endpoint
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Hardware-accelerated on-device Llama model inference engine
    implementation(libs.mediapipe.tasks.genai)

    // Schedules the retention sweep so old chats are cleared even if the app
    // is never opened.
    implementation(libs.work.runtime)
}
