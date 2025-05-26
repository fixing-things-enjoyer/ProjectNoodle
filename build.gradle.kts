// Project: ProjectNoodle
// File: build.gradle.kts

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// This block will execute during the configuration phase of the build.
val dotEnvFile = rootProject.file(".env")
val dotEnvProperties = mutableMapOf<String, String>()

if (dotEnvFile.exists()) {
    dotEnvFile.forEachLine { line ->
        if (line.isNotBlank() && !line.startsWith("#")) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                dotEnvProperties[key] = value
            }
        }
    }
} else {
    // Optional: Log a warning if .env doesn't exist during local development.
    // For CI/CD, we expect environment variables to be set directly.
    logger.warn("ProjectNoodle: .env file not found at ${dotEnvFile.absolutePath}. Expecting secrets to be provided via environment variables.")
}

// Make the parsed properties available as extra properties on the root project.
// This allows app/build.gradle.kts to access them via project.extra.properties["KEY_NAME"]
// We prefer System.getenv() for CI/CD, so these will be fallback for local.
dotEnvProperties.forEach { (key, value) ->
    rootProject.extra.set(key, value)
}
