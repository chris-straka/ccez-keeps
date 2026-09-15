plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.cstraka.keeps"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.cstraka.keeps"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Prod origin. The Worker serves the SPA + /api/* as one unit, so
        // the app talks to the same host as the browser (no CORS involved).
        // Override with KEEPS_BASE_URL for local work (e.g. screenshot
        // builds against `wrangler dev` via http://10.0.2.2:8787).
        val baseUrl = System.getenv("KEEPS_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: "https://keeps.cstraka.dev"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        // Release signing via env (keys never committed):
        //   KEEPS_KEYSTORE_PATH, KEEPS_KEYSTORE_PASSWORD,
        //   KEEPS_KEY_ALIAS, KEEPS_KEY_PASSWORD
        // Unsigned release builds simply omit the config below.
        create("release") {
            val ks = System.getenv("KEEPS_KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEEPS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEEPS_KEY_ALIAS")
                keyPassword = System.getenv("KEEPS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Play-ready when the env keystore exists; debug-signed
            // otherwise so `./gradlew :app:assembleRelease` still works
            // for side-loading without secrets on the machine.
            signingConfig = if (!System.getenv("KEEPS_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":synclogic"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
