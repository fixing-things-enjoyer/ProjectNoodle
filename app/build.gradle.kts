// ProjectNoodle/app/build.gradle.kts
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.fixingthingsenjoyer.projectnoodle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.fixingthingsenjoyer.projectnoodle"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Prioritize: GitHub Actions Environment Variables > Manually parsed .env file (local)
            // This ensures CI/CD can inject secrets via env vars, and local dev uses .env.

            // Helper function to safely get a property
            fun getSecret(key: String): String? {
                // 1. Try from System Environment Variables (for CI/CD)
                val envValue = System.getenv(key)
                if (!envValue.isNullOrEmpty()) return envValue

                // 2. Try from rootProject.extra (manually parsed .env for local dev)
                // Note: rootProject.extra.properties is a map, so we can use `get`
                val extraValue = rootProject.extra.properties[key] as? String
                if (!extraValue.isNullOrEmpty()) return extraValue

                return null
            }

            // Keystore file path
            val finalStoreFilePath = getSecret("STORE_FILE")
            if (finalStoreFilePath.isNullOrEmpty()) {
                throw GradleException(
                    "Signing key store file not specified for 'release' build. " +
                            "Please set 'STORE_FILE' environment variable (for CI/CD) " +
                            "or in your local .env file."
                )
            }
            this.storeFile = file(finalStoreFilePath)


            // Key Alias
            val finalKeyAlias = getSecret("KEY_ALIAS")
            if (finalKeyAlias.isNullOrEmpty()) {
                throw GradleException(
                    "Signing key alias not specified for 'release' build. " +
                            "Please set 'KEY_ALIAS' environment variable (for CI/CD) " +
                            "or in your local .env file."
                )
            }
            this.keyAlias = finalKeyAlias


            // Key Password
            val finalKeyPassword = getSecret("KEY_PASSWORD")
            if (finalKeyPassword.isNullOrEmpty()) {
                throw GradleException(
                    "Signing key password not specified for 'release' build. " +
                            "Please set 'KEY_PASSWORD' environment variable (for CI/CD) " +
                            "or in your local .env file."
                )
            }
            this.keyPassword = finalKeyPassword


            // Store Password
            val finalStorePassword = getSecret("STORE_PASSWORD")
            if (finalStorePassword.isNullOrEmpty()) {
                throw GradleException(
                    "Signing store password not specified for 'release' build. " +
                            "Please set 'STORE_PASSWORD' environment variable (for CI/CD) " +
                            "or in your local .env file."
                )
            }
            this.storePassword = finalStorePassword
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        languageVersion = "1.9"
        apiVersion = "1.9"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            excludes += "kotlin/**"
            excludes += "**/*.kotlin_metadata"
            excludes += "**/*.kotlin_builtins"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)

    // Activity Compose
    implementation(libs.androidx.activity.compose)

    // Compose - Use the BOM to manage versions
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.preference.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // NanoHTTPD
    implementation(libs.nanohttpd)

    // Storage Access Framework / DocumentFile
    implementation(libs.androidx.documentfile)

    // JSON library for API responses
    implementation(libs.orgjson)

    implementation(libs.androidx.localbroadcastmanager)

    implementation(libs.material)

    // NEW: Bouncy Castle for certificate generation
    implementation(libs.bouncy.castle.prov)
    implementation(libs.bouncy.castle.pkix)
}
