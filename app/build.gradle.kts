plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One version for the APK. Release tags override this value in CI.
val noodleVersion =
    providers
        .environmentVariable("NOODLE_VERSION")
        .orElse(providers.gradleProperty("noodleVersion"))
        .get()
val versionParts =
    Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(noodleVersion)
        ?: throw GradleException("NOODLE_VERSION must be major.minor.patch (each part 0–999).")

val (major, minor, patch) = versionParts.groupValues.drop(1).map(String::toInt)

fun signingSecret(name: String): String? =
    System.getenv(name)?.takeIf(String::isNotBlank)
        ?: (rootProject.extra.properties[name] as? String)?.takeIf(String::isNotBlank)

val signingValues =
    listOf("STORE_FILE", "KEY_ALIAS", "KEY_PASSWORD", "STORE_PASSWORD")
        .associateWith(::signingSecret)
val hasSigning = signingValues.values.all { it != null }

val webUiDirectory = rootProject.layout.projectDirectory.dir("webui")
val installWebUi by
    tasks.registering(Exec::class) {
        group = "build"
        description = "Install the locked web UI dependencies."
        workingDir(webUiDirectory)
        inputs.files(webUiDirectory.file("package.json"), webUiDirectory.file("package-lock.json"))
        outputs.dir(webUiDirectory.dir("node_modules"))
        commandLine(
            if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm",
            "ci",
            "--no-audit",
            "--no-fund",
        )
    }
val buildWebUi by
    tasks.registering(Exec::class) {
        group = "build"
        description = "Build React assets bundled into every APK."
        dependsOn(installWebUi)
        workingDir(webUiDirectory)
        inputs.dir(webUiDirectory.dir("src"))
        inputs.files(
            webUiDirectory.file("index.html"),
            webUiDirectory.file("package-lock.json"),
            webUiDirectory.file("vite.config.ts"),
            webUiDirectory.file("tsconfig.json"),
        )
        outputs.dir(webUiDirectory.dir("dist"))
        commandLine(
            if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm",
            "run",
            "build",
        )
    }

android {
    namespace = "com.github.fixingthingsenjoyer.projectnoodle"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.github.fixingthingsenjoyer.projectnoodle"
        minSdk = 24
        targetSdk = 35
        versionName = noodleVersion
        versionCode = major * 1_000_000 + minor * 1_000 + patch
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (hasSigning)
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("STORE_FILE")!!)
            keyAlias = signingValues.getValue("KEY_ALIAS")
            keyPassword = signingValues.getValue("KEY_PASSWORD")
            storePassword = signingValues.getValue("STORE_PASSWORD")
        }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(webUiDirectory.dir("dist"))
    packaging.resources.excludes +=
        setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
        )
}

tasks.named("preBuild") { dependsOn(buildWebUi) }

// Debug builds and tests work without release signing keys. CI requires signing before publishing.
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.material)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.documentfile)
    implementation(libs.zxing.core)
    implementation(libs.bouncy.castle.prov)
    implementation(libs.bouncy.castle.pkix)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
