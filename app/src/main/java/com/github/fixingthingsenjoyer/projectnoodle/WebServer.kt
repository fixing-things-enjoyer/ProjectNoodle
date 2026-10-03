package com.github.fixingthingsenjoyer.projectnoodle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

interface ConnectionApprovalListener {
    fun onNewClientConnectionAttempt(clientIp: String)
}

open class WebServer(
    port: Int,
    private val applicationContext: Context,
    sharedDirectoryUri: Uri,
    @Suppress("UNUSED_PARAMETER") serverIpAddress: String?,
    private val requireApprovalEnabled: Boolean,
    private val approvalListener: ConnectionApprovalListener?,
) : NanoHTTPD(port) {
    private val root =
        requireNotNull(DocumentFile.fromTreeUri(applicationContext, sharedDirectoryUri))
    private val approvedClients = ConcurrentHashMap.newKeySet<String>()
    private val pendingClients = ConcurrentHashMap.newKeySet<String>()
    private val deniedClients = ConcurrentHashMap.newKeySet<String>()
    private val mutationLock = Any()

    private fun findDocument(path: String): DocumentFile? {
        val normalized = SharePaths.normalize(path)
        var current: DocumentFile = root
        for (segment in normalized.split('/').filter(String::isNotEmpty)) {
            if (!current.isDirectory) return null
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            val protected = uri.startsWith("/api/") || uri.startsWith("/files/")
            // Browser writes must originate from this server. HTML forms on other sites cannot
            // mutate files.
            val origin = session.headers["origin"]
            val host = session.headers["host"]
            if (
                protected &&
                    (session.headers["sec-fetch-site"] == "cross-site" ||
                        (origin != null && origin != "http://$host" && origin != "https://$host"))
            ) {
                return json(Status.FORBIDDEN, "Open Noodle directly to access your files.")
            }
            if (protected && requireApprovalEnabled) {
                val client =
                    session.remoteIpAddress
                        ?: return json(
                            Status.UNAUTHORIZED,
                            "Approve this device in Noodle on your phone.",
                        )
                if (deniedClients.contains(client))
                    return json(
                        Status.FORBIDDEN,
                        "This connection was declined. Stop and start sharing on your phone to try again.",
                    )
                if (!approvedClients.contains(client)) {
                    if (pendingClients.add(client))
                        approvalListener?.onNewClientConnectionAttempt(client)
                    return json(Status.UNAUTHORIZED, "Approve this device in Noodle on your phone.")
                }
            }
            val response =
                when {
                    uri == "/api/list" && session.method == Method.GET ->
                        list(session.parameter("path") ?: "/")
                    uri.startsWith("/files/") && session.method == Method.GET ->
                        download(uri.removePrefix("/files"), session.parameter("download") == "1")
                    uri in setOf("/api/mkdir", "/api/rename", "/api/delete", "/api/upload") &&
                        session.method == Method.POST -> {
                        val files = HashMap<String, String>()
                        try {
                            session.parseBody(files)
                            synchronized(mutationLock) {
                                when (uri) {
                                    "/api/mkdir" ->
                                        mkdir(
                                            session.parameter("path") ?: "/",
                                            session.parameter("newDirName"),
                                        )
                                    "/api/rename" ->
                                        rename(
                                            session.parameter("path"),
                                            session.parameter("newName"),
                                        )
                                    "/api/delete" -> delete(session.parameter("path"))
                                    else -> upload(session.parameter("path") ?: "/", session, files)
                                }
                            }
                        } finally {
                            files.values.forEach { File(it).delete() }
                        }
                    }
                    session.method == Method.GET &&
                        (uri == "/" || uri == "/index.html" || uri.startsWith("/assets/")) ->
                        asset(uri)
                    else -> json(Status.NOT_FOUND, "This file or page could not be found.")
                }
            response.addHeader("X-Content-Type-Options", "nosniff")
            response.addHeader("Referrer-Policy", "same-origin")
            if (protected) response.addHeader("Cache-Control", "no-store")
            response
        } catch (error: IllegalArgumentException) {
            json(Status.BAD_REQUEST, error.message ?: "Invalid name or path.")
        } catch (error: SecurityException) {
            json(Status.FORBIDDEN, "Folder access was lost. Choose the folder again on your phone.")
        } catch (error: ResponseException) {
            json(error.status, "Could not read this request.")
        } catch (error: Exception) {
            Log.e("NoodleServer", "Request failed", error)
            json(
                Status.INTERNAL_ERROR,
                "Could not finish this request. Check the folder on your phone and try again.",
            )
        }
    }

    private fun IHTTPSession.parameter(name: String): String? = parameters[name]?.firstOrNull()

    private fun json(status: Status, message: String): Response =
        json(
            status,
            JSONObject()
                .put("status", if (status.requestStatus < 400) "success" else "error")
                .put("message", message),
        )

    private fun json(status: Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun list(path: String): Response {
        val normalized = SharePaths.normalize(path)
        val folder =
            findDocument(normalized)
                ?: return json(
                    Status.NOT_FOUND,
                    "This folder no longer exists. Go back to the shared folder.",
                )
        if (!folder.isDirectory) return json(Status.BAD_REQUEST, "Choose a folder to browse.")
        val entries = mutableListOf<JSONObject>()
        val writable = folder.canWrite()
        val childUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folder.uri,
                DocumentsContract.getDocumentId(folder.uri),
            )
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
        // Read all directory metadata in one provider query instead of several queries per file.
        val cursor =
            applicationContext.contentResolver.query(childUri, columns, null, null, null)
                ?: throw IOException("Cannot read directory")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                if (!SharePaths.validName(name)) continue
                val directory = it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                val flags = it.getInt(4)
                val itemPath = SharePaths.child(normalized, name)
                entries.add(
                    JSONObject().apply {
                        put("name", name)
                        put("path", itemPath)
                        put("type", if (directory) "directory" else "file")
                        put(
                            "size",
                            if (directory || it.isNull(2)) JSONObject.NULL else it.getLong(2),
                        )
                        put("lastModified", if (it.isNull(3)) 0 else it.getLong(3))
                        put(
                            "canWrite",
                            writable &&
                                flags and
                                    (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0,
                        )
                        if (!directory)
                            put(
                                "fileUrl",
                                "/files" +
                                    itemPath.split('/').joinToString("/") { segment ->
                                        Uri.encode(segment)
                                    },
                            )
                        else put("apiUrl", "/api/list?path=${Uri.encode(itemPath)}")
                    }
                )
            }
        }
        val items =
            JSONArray(
                entries.sortedWith(
                    compareBy(
                        { it.getString("type") != "directory" },
                        { it.getString("name").lowercase() },
                    )
                )
            )
        return json(
            Status.OK,
            JSONObject().apply {
                put("currentPath", normalized)
                put("sharedFolderName", root.name ?: "Shared files")
                put("serverName", "Project Noodle")
                put("canWrite", writable)
                put("items", items)
            },
        )
    }

    private fun writableFolder(path: String): DocumentFile? =
        findDocument(path)?.takeIf { it.isDirectory && it.canWrite() }

    private fun mkdir(path: String, name: String?): Response {
        require(name != null && SharePaths.validName(name)) {
            "Enter a valid folder name without slashes."
        }
        val folder =
            writableFolder(path)
                ?: return json(Status.FORBIDDEN, "This folder is read only or no longer available.")
        if (folder.findFile(name) != null)
            return json(
                Status.CONFLICT,
                "An item named “$name” already exists. Choose another name.",
            )
        return if (folder.createDirectory(name) != null) json(Status.CREATED, "Folder created.")
        else json(Status.INTERNAL_ERROR, "Could not create the folder on your phone.")
    }

    private fun rename(path: String?, name: String?): Response {
        require(path != null && name != null && SharePaths.validName(name)) {
            "Enter a valid name without slashes."
        }
        val normalized = SharePaths.normalize(path)
        if (normalized == "/") return json(Status.FORBIDDEN, "The shared folder cannot be renamed.")
        val item =
            findDocument(normalized) ?: return json(Status.NOT_FOUND, "This item no longer exists.")
        if (!item.canWrite()) return json(Status.FORBIDDEN, "This item is read only.")
        val parent = findDocument(normalized.substringBeforeLast('/').ifEmpty { "/" })
        val existing = parent?.findFile(name)
        if (existing != null && existing.uri != item.uri)
            return json(
                Status.CONFLICT,
                "An item named “$name” already exists. Choose another name.",
            )
        return if (item.renameTo(name)) json(Status.OK, "Name updated.")
        else json(Status.INTERNAL_ERROR, "Could not rename this item on your phone.")
    }

    private fun delete(path: String?): Response {
        require(path != null) { "Choose an item to delete." }
        val normalized = SharePaths.normalize(path)
        if (normalized == "/") return json(Status.FORBIDDEN, "The shared folder cannot be deleted.")
        val item =
            findDocument(normalized) ?: return json(Status.NOT_FOUND, "This item no longer exists.")
        if (!item.canWrite()) return json(Status.FORBIDDEN, "This item is read only.")
        return if (item.delete()) json(Status.OK, "Item deleted.")
        else json(Status.INTERNAL_ERROR, "Could not delete this item on your phone.")
    }

    private fun upload(path: String, session: IHTTPSession, files: Map<String, String>): Response {
        val folder =
            writableFolder(path)
                ?: return json(Status.FORBIDDEN, "This folder is read only or no longer available.")
        val key =
            files.keys.singleOrNull()
                ?: return json(Status.BAD_REQUEST, "Choose one file per upload.")
        val name = session.parameter("fileName") ?: session.parameter(key)
        // Multipart filenames are literal strings, not URL-encoded form values.
        require(name != null && SharePaths.validName(name)) {
            "Use a filename without slashes or control characters."
        }
        if (folder.findFile(name) != null)
            return json(
                Status.CONFLICT,
                "“$name” is already in this folder. Rename the file before uploading.",
            )
        val temp = File(files.getValue(key))
        val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        val destination =
            folder.createFile(mime, name)
                ?: return json(Status.INTERNAL_ERROR, "Could not create this file on your phone.")
        try {
            temp.inputStream().use { input ->
                val stream =
                    applicationContext.contentResolver.openOutputStream(destination.uri, "wt")
                        ?: throw IOException("Cannot open output")
                stream.use { output -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        return json(Status.CREATED, "File uploaded.")
    }

    private fun download(path: String, forceDownload: Boolean): Response {
        val item =
            findDocument(path)?.takeIf { it.isFile }
                ?: return json(Status.NOT_FOUND, "This file no longer exists.")
        val stream =
            applicationContext.contentResolver.openInputStream(item.uri)
                ?: return json(Status.NOT_FOUND, "This file could not be opened.")
        val mime = item.type ?: "application/octet-stream"
        val name = item.name ?: "download"
        // Attachments avoid executing shared HTML/SVG inside the app's origin.
        val inline =
            !forceDownload &&
                (mime.startsWith("image/") && mime != "image/svg+xml" ||
                    mime == "application/pdf" ||
                    mime == "text/plain")
        val response = newChunkedResponse(Status.OK, mime, stream)
        val asciiName =
            name
                .map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
                .joinToString("")
        val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        response.addHeader(
            "Content-Disposition",
            "${if (inline) "inline" else "attachment"}; filename=\"$asciiName\"; filename*=UTF-8''$encodedName",
        )
        // Do not advertise byte ranges: DocumentFile streams need not be seekable.
        return response
    }

    private fun asset(uri: String): Response {
        val path = if (uri == "/" || uri == "/index.html") "index.html" else uri.removePrefix("/")
        SharePaths.normalize(path)
        return try {
            val stream = applicationContext.assets.open(path)
            val mime =
                when (path.substringAfterLast('.')) {
                    "html" -> "text/html; charset=utf-8"
                    "js" -> "application/javascript"
                    "css" -> "text/css"
                    "svg" -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
            newChunkedResponse(Status.OK, mime, stream).apply {
                addHeader(
                    "Cache-Control",
                    if (path == "index.html") "no-cache" else "public, max-age=31536000, immutable",
                )
                if (path == "index.html") {
                    addHeader("X-Frame-Options", "DENY")
                    addHeader(
                        "Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'",
                    )
                }
            }
        } catch (_: IOException) {
            json(Status.NOT_FOUND, "This page could not be found.")
        }
    }

    fun approveClient(client: String) {
        pendingClients.remove(client)
        deniedClients.remove(client)
        approvedClients.add(client)
    }

    fun denyClient(client: String) {
        pendingClients.remove(client)
        approvedClients.remove(client)
        deniedClients.add(client)
    }
}
