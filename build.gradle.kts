plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Optional local signing values; CI uses environment variables. No signing values are logged.
rootProject
    .file(".env")
    .takeIf { it.exists() }
    ?.forEachLine { line ->
        if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
            val parts = line.split('=', limit = 2)
            if (parts.size == 2)
                rootProject.extra.set(
                    parts[0].trim(),
                    parts[1].trim().removeSurrounding("\"").removeSurrounding("'"),
                )
        }
    }
