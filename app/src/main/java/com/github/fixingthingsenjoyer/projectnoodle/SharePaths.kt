package com.github.fixingthingsenjoyer.projectnoodle

/** Paths arrive decoded once by NanoHTTPD. Never URL-decode document names again. */
internal object SharePaths {
    fun normalize(path: String): String {
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.all(::validName)) { "Invalid folder path." }
        return "/" + segments.joinToString("/")
    }

    fun validName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 255 &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    fun child(parent: String, name: String): String {
        require(validName(name)) { "Use a name without slashes or control characters." }
        return normalize(parent).trimEnd('/') + "/" + name
    }
}
