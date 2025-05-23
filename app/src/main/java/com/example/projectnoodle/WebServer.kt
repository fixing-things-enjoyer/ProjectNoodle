// File: app/src/main/java/com/example/projectnoodle/WebServer.kt
package com.example.projectnoodle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val TAG = "ProjectNoodleWebServer" // Renamed TAG for clarity

private const val MIME_JSON = "application/json" // Define JSON MIME type
private const val MIME_OCTET_STREAM = "application/octet-stream" // Default binary mime type

// Define the callback interface that the Service will implement
interface ConnectionApprovalListener {
    fun onNewClientConnectionAttempt(clientIp: String)
}

open class WebServer(
    port: Int,
    private val applicationContext: Context, // Keep context reference
    sharedDirectoryUri: Uri, // Keep URI reference
    private val serverIpAddress: String?, // Keep IP reference
    private val requireApprovalEnabled: Boolean,
    private val approvalListener: ConnectionApprovalListener? // Listener for approval requests
) : NanoHTTPD(port) {

    // FIX: Added the approvedClients member variable
    private val approvedClients = mutableSetOf<String>()

    private val rootDocumentFile: DocumentFile? = DocumentFile.fromTreeUri(applicationContext, sharedDirectoryUri)


    init {
        Log.d(TAG, "WebServer: Initialized with port $port and shared directory URI $sharedDirectoryUri")
        Log.d(TAG, "WebServer: Connection approval required: $requireApprovalEnabled")

        if (rootDocumentFile == null || !rootDocumentFile.exists() || !rootDocumentFile.isDirectory) {
            Log.e(TAG, "WebServer: Invalid or inaccessible root DocumentFile for URI: $sharedDirectoryUri")
            // The Service checks this before creating the WebServer instance now.
        } else {
            Log.d(TAG, "WebServer: Root DocumentFile resolved: ${rootDocumentFile.name} (URI: ${rootDocumentFile.uri})")
        }
    }

    private fun findDocumentFile(relativePath: String): DocumentFile? {
        Log.d(TAG, "findDocumentFile: Searching for relative path: $relativePath")

        if (rootDocumentFile == null) {
            Log.e(TAG, "findDocumentFile: Root DocumentFile is null (invalid shared URI?).")
            return null
        }

        val normalizedPath = "/" + relativePath.trim('/').replace(Regex("/+"), "/")

        if (normalizedPath == "/") {
            Log.d(TAG, "findDocumentFile: Request is for the root directory ('/').")
            return rootDocumentFile
        }

        val segments = normalizedPath.removePrefix("/").split('/').filter { it.isNotEmpty() }

        var currentDocument: DocumentFile? = rootDocumentFile

        for (segment in segments) {
             val decodedSegment = try { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) } catch (e: Exception) { segment }

            // DocumentFile.findFile() works for content:// backed DocumentFile instances.
            val foundChild = currentDocument?.findFile(decodedSegment)
            if (foundChild == null) {
                Log.w(TAG, "findDocumentFile: Could not find segment '$decodedSegment' (original segment: '$segment') in ${currentDocument?.name ?: "current directory"} (URI: ${currentDocument?.uri}). Path not found.")
                return null
            }
            currentDocument = foundChild
        }

        Log.d(TAG, "findDocumentFile: Successfully resolved path. Final Document URI: ${currentDocument?.uri}")
        return currentDocument
    }


    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val clientIp = session.remoteIpAddress ?: "Unknown"
        Log.d(TAG, "WebServer: Received request from $clientIp: $method $uri")

        if (requireApprovalEnabled) {
            Log.d(TAG, "WebServer: Approval is required. Checking client $clientIp...")
            if (clientIp != "Unknown" && approvedClients.contains(clientIp)) {
                Log.d(TAG, "WebServer: Client $clientIp is approved. Proceeding with request.")
                // Client is approved, proceed as normal
            } else {
                Log.d(TAG, "WebServer: Client $clientIp is NOT approved. Returning unauthorized response and informing listener.")
                // Client is NOT approved, inform the listener and return the specific unauthorized response
                approvalListener?.onNewClientConnectionAttempt(clientIp)
                return handleUnauthorizedClientResponse(session)
            }
        }


        if (Method.OPTIONS == method) {
            val preflightResponse = newFixedLengthResponse(Status.OK, "text/plain", "")

            preflightResponse.addHeader("Access-Control-Allow-Origin", "*")
            preflightResponse.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            preflightResponse.addHeader("Access-Control-Max-Age", "86400")

            val requestedHeaders = session.headers["access-control-request-headers"]
            if (requestedHeaders != null) {
                 preflightResponse.addHeader("Access-Control-Allow-Headers", requestedHeaders)
                 Log.d(TAG, "WebServer: Added Access-Control-Allow-Headers: $requestedHeaders")
            }

            return preflightResponse
        }

        val files = HashMap<String, String>()
        if (Method.POST == method || Method.PUT == method) {
            try {
                session.parseBody(files)
            } catch (ioe: IOException) {
                Log.e(TAG, "WebServer: Body parsing failed", ioe)
                val errorResponse = newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "500 Internal Error: Can't read body."))
                 errorResponse.addHeader("Access-Control-Allow-Origin", "*")
                 return errorResponse
            } catch (hre: ResponseException) {
                Log.e(TAG, "WebServer: Body parsing failed (ResponseException)", hre)
                val errorResponse = newJsonResponse(hre.status, mapOf("status" to "error", "message" to "Request parsing failed: ${hre.message}"))
                 errorResponse.addHeader("Access-Control-Allow-Origin", "*")
                 return errorResponse
            }
        }

        val requestUrlPath = uri.split('?')[0]
        val requestUrlPathClean = "/" + requestUrlPath.trim('/').replace(Regex("/+"), "/")


        val response: Response = when {
            requestUrlPathClean == "/api/list" && method == Method.GET -> {
                 val targetPath = session.parameters["path"]?.get(0) ?: "/"
                 Log.d(TAG, "WebServer: Handling GET /api/list for path param: $targetPath")

                 val directoryDocument = findDocumentFile(targetPath)
                 if (directoryDocument != null && directoryDocument.isDirectory) {
                    serveJsonDirectoryListing(targetPath, directoryDocument)
                 } else if (directoryDocument != null && directoryDocument.isFile) {
                       Log.w(TAG, "WebServer: /api/list request for a file: ${directoryDocument.uri}")
                       newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Path is a file, not a directory."))
                 }
                 else {
                    Log.w(TAG, "WebServer: Directory not found for /api/list: $targetPath")
                    newJsonResponse(Status.NOT_FOUND, mapOf("status" to "error", "message" to "Directory not found."))
                 }
            }

            requestUrlPathClean == "/api/delete" && method == Method.POST -> {
                val targetPath = session.parameters["path"]?.get(0)
                Log.d(TAG, "WebServer: Handling POST /api/delete for path: $targetPath")
                handleDeleteJson(targetPath)
            }

            requestUrlPathClean == "/api/rename" && method == Method.POST -> {
                val targetPath = session.parameters["path"]?.get(0)
                val newName = session.parameters["newName"]?.get(0)
                Log.d(TAG, "WebServer: Handling POST /api/rename for path: $targetPath with new name: $newName")
                handleRenameJson(targetPath, newName)
            }

            requestUrlPathClean == "/api/mkdir" && method == Method.POST -> {
                 val currentDirPath = session.parameters["path"]?.get(0) ?: "/"
                 val newDirName = session.parameters["newDirName"]?.get(0)
                 Log.d(TAG, "WebServer: Handling POST /api/mkdir in directory: $currentDirPath with new name: $newDirName")
                handleMkdirJson(currentDirPath, newDirName)
            }

            requestUrlPathClean == "/api/upload" && method == Method.POST -> {
                val currentDirPath = session.parameters["path"]?.get(0) ?: "/"
                 Log.d(TAG, "WebServer: Handling POST /api/upload in directory: $currentDirPath")
                handleUploadJson(currentDirPath, session, files)
            }

            requestUrlPathClean.startsWith("/files/") && method == Method.GET -> {
                 val filePath = "/" + requestUrlPathClean.removePrefix("/files").trimStart('/')
                 Log.d(TAG, "WebServer: Handling GET /files/ for path: $filePath")
                 val fileDocument = findDocumentFile(filePath)
                 if (fileDocument != null && fileDocument.isFile) {
                     serveFile(fileDocument)
                 } else if (fileDocument != null && fileDocument.isDirectory) {
                      Log.w(TAG, "WebServer: /files/ request for a directory: ${fileDocument.uri}")
                       newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: Path is a directory, not a file.")
                 }
                 else {
                     Log.w(TAG, "WebServer: File not found for /files/: $filePath")
                     newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: File not found.")
                 }
            }

            (requestUrlPathClean == "/" ||
             requestUrlPathClean.startsWith("/assets/") ||
             requestUrlPathClean == "/project_noodle.png" ||
             requestUrlPathClean == "/index.css")
             && method == Method.GET -> {

                val assetPath = when {
                     requestUrlPathClean == "/" -> "index.html"
                     requestUrlPathClean.startsWith("/assets/") -> requestUrlPathClean.removePrefix("/")
                     else -> requestUrlPathClean.removePrefix("/")
                }
                Log.d(TAG, "WebServer: Handling GET $requestUrlPathClean (serving asset: $assetPath) from Android assets/")
                try {
                    if (assetPath == "index.html") {
                        val indexHtmlStream: InputStream = applicationContext.assets.open(assetPath)
                        val reader = indexHtmlStream.bufferedReader()
                        val indexHtmlContent = reader.use { it.readText() }

                        val serverBaseUrl = if (serverIpAddress != null) {
                            val protocol = if (this is HttpsWebServer) "https" else "http" // NEW: Determine protocol for injection
                            "$protocol://$serverIpAddress:$listeningPort"
                        } else {
                            null
                        }
                        val headEndTag = "</head>"
                        val scriptToInject = serverBaseUrl?.let { url ->
                            "<script>\n" +
                            "  window.__API_BASE_URL__ = \"$url\";\n" +
                            "  console.log('Injected API Base URL:', window.__API_BASE_URL__);\n" +
                            "</script>\n"
                        } ?: ""

                        val modifiedHtmlContent = if (indexHtmlContent.contains(headEndTag)) {
                            indexHtmlContent.replace(headEndTag, scriptToInject + headEndTag)
                        } else {
                            Log.w(TAG, "WebServer: </head> not found in index.html, injecting script at start of body.")
                            indexHtmlContent.replace("<body>", "<body>\n$scriptToInject")
                        }
                         val mimeType = "text/html"
                         newFixedLengthResponse(Status.OK, mimeType, modifiedHtmlContent)

                    } else {
                        val assetStream: InputStream = applicationContext.assets.open(assetPath)
                        val mimeType = guessMimeTypeFromExtension(assetPath) ?: MIME_OCTET_STREAM
                        newChunkedResponse(Status.OK, mimeType, assetStream)
                    }

                } catch (e: FileNotFoundException) {
                    Log.w(TAG, "WebServer: Asset not found: $assetPath", e)
                    newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Asset Not Found")
                } catch (e: Exception) {
                    Log.e(TAG, "WebServer: Error serving asset: $assetPath", e)
                    newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "500 Internal Server Error serving asset.")
                }
            }

            else -> {
                Log.w(TAG, "WebServer: Unhandled request - Method: $method, URI: $uri, CleanPath: $requestUrlPathClean")
                newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found: API endpoint, file, or resource not found for path $requestUrlPathClean.")
            }
        }

        response.addHeader("Access-Control-Allow-Origin", "*")

        return response
    }

     private fun handleUnauthorizedClientResponse(session: IHTTPSession): Response {
         val uri = session.uri
         val requestUrlPath = uri.split('?')[0]
         val requestUrlPathClean = "/" + requestUrlPath.trim('/').replace(Regex("/+"), "/")

         val response: Response = if (requestUrlPathClean.startsWith("/api/")) {
             newJsonResponse(Status.UNAUTHORIZED, mapOf("status" to "error", "message" to "Authorization required. Please approve the connection on the device then refresh."))
         } else {
             newFixedLengthResponse(Status.UNAUTHORIZED, "text/html",
                 "<!DOCTYPE html>\n" +
                 "<html>\n" +
                 "<head>\n" +
                 "    <meta charset=\"UTF-8\">\n" +
                 "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                 "    <title>Authorization Required</title>\n" +
                 "    <style>\n" +
                 "        body { font-family: sans-serif; text-align: center; padding: 50px; background-color: #282c34; color: #abb2bf; }\n" +
                 "        h1 { color: #e06c75; }\n" +
                 "        p { margin-bottom: 20px; }\n" +
                 "        strong { color: #61afef; }\n" +
                 "        small { color: #5c6370; }\n" +
                 "    </style>\n" +
                 "</head>\n" +
                 "<body>\n" +
                 "    <h1>Connection Approval Required</h1>\n" +
                 "    <p>Please approve this connection on the device running the server.</p>\n" +
                 "    <p>Check your device's notifications, then refresh this page.</p>\n" +
                 "    <p><strong>Your IP address: ${session.remoteIpAddress}</strong></p>\n" +
                 "    <small>If you do not see a notification, ensure notifications are enabled for the app.</small>\n" +
                 "</body>\n" +
                 "</html>"
             )
         }

         response.addHeader("Access-Control-Allow-Origin", "*")
         return response
     }


     private fun newJsonResponse(status: Status, jsonMap: Map<String, Any?>): Response {
        val jsonObject = JSONObject(jsonMap)
        val jsonString = jsonObject.toString()
        val response = newFixedLengthResponse(status, MIME_JSON, jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

     private fun newJsonResponse(status: Status, jsonObject: JSONObject): Response {
        val jsonString = jsonObject.toString()
        val response = newFixedLengthResponse(status, MIME_JSON, jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }


    private fun serveJsonDirectoryListing(requestedPath: String, directoryDocument: DocumentFile): Response {
        val children = directoryDocument.listFiles()
        val sortedEntries = children.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))

        val jsonArray = JSONArray()

        val serverBaseUrl = if (serverIpAddress != null) {
             val protocol = if (this is HttpsWebServer) "https" else "http" // NEW: Determine protocol for API URLs
             "$protocol://$serverIpAddress:$listeningPort"
        } else {
             null
        }

        if (rootDocumentFile != null && directoryDocument.uri != rootDocumentFile.uri) {
            val parentPath = getParentPath(requestedPath)
            val parentEntry = JSONObject().apply {
                put("name", "..")
                put("type", "directory")
                put("path", parentPath)
                 put("apiUrl", serverBaseUrl?.let { baseUrl ->
                     "$baseUrl/api/list?path=${parentPath.encodeAsUriComponent()}"
                 }) ?: JSONObject.NULL
                put("size", JSONObject.NULL)
                put("lastModified", JSONObject.NULL)
            }
            jsonArray.put(parentEntry)
        }

        for (document in sortedEntries) {
             if (rootDocumentFile != null && document.uri == rootDocumentFile.uri) {
                 continue
             }

            val name = document.name ?: "Unnamed"
            val itemLogicalPath = if (requestedPath == "/") "/${name}" else "${requestedPath}/${name}"
            val cleanItemLogicalPath = itemLogicalPath.replace(Regex("/+"), "/")

            val itemJson = JSONObject().apply {
                put("name", name)
                put("type", if (document.isDirectory) "directory" else "file")
                put("path", cleanItemLogicalPath)
                put("lastModified", document.lastModified())
                if (document.isFile) {
                    put("size", document.length())
                     put("fileUrl", serverBaseUrl?.let { baseUrl ->
                         "$baseUrl/files${cleanItemLogicalPath.encodeAsUriComponent()}"
                     }) ?: JSONObject.NULL
                } else {
                    put("size", JSONObject.NULL)
                    put("apiUrl", serverBaseUrl?.let { baseUrl ->
                        "$baseUrl/api/list?path=${cleanItemLogicalPath.encodeAsUriComponent()}"
                    }) ?: JSONObject.NULL
                }
                 if (serverBaseUrl != null && rootDocumentFile != null && document.uri != rootDocumentFile.uri) {
                     put("deleteApiUrl", "$serverBaseUrl/api/delete")
                     put("renameApiUrl", "$serverBaseUrl/api/rename")
                 } else {
                     put("deleteApiUrl", JSONObject.NULL)
                     put("renameApiUrl", JSONObject.NULL)
                 }
            }
            jsonArray.put(itemJson)
        }

        val responseJson = JSONObject().apply {
             put("currentPath", requestedPath)
             put("items", jsonArray)
             put("serverName", "Project Noodle")
        }

        return newJsonResponse(Status.OK, responseJson)
    }

    private fun getParentPath(currentPath: String): String {
        val path = "/" + currentPath.trim('/')
        if (path == "/") return "/"

        val lastSlashIndex = path.lastIndexOf('/')
        val parent = if (lastSlashIndex <= 0) "/" else path.substring(0, lastSlashIndex)
        return "/" + parent.trim('/').replace(Regex("/+"), "/")
    }


    private fun handleDeleteJson(targetPath: String?): Response {
        if (targetPath == null || targetPath.trim('/').isEmpty()) {
            Log.w(TAG, "handleDeleteJson: Missing or invalid 'path' parameter.")
            return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Missing or invalid 'path' parameter."))
        }
         val normalizedTargetPath = "/" + targetPath.trimStart('/')

        val documentToDelete = findDocumentFile(normalizedTargetPath)

        if (documentToDelete == null) {
            Log.w(TAG, "handleDeleteJson: Document not found for path: $normalizedTargetPath")
            return newJsonResponse(Status.NOT_FOUND, mapOf("status" to "error", "message" to "Error 404: File or directory not found."))
        }

         if (rootDocumentFile != null && documentToDelete.uri == rootDocumentFile.uri) {
            Log.w(TAG, "handleDeleteJson: Attempted to delete root directory via path: $normalizedTargetPath")
            return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Error 403: Cannot delete the root directory."))
         }


        try {
            val success = documentToDelete.delete()
            return if (success) {
                Log.i(TAG, "handleDeleteJson: Successfully deleted: $normalizedTargetPath (Document: ${documentToDelete.name})")
                newJsonResponse(Status.OK, mapOf("status" to "success", "message" to "Successfully deleted: ${documentToDelete.name}", "path" to normalizedTargetPath))
            } else {
                Log.e(TAG, "handleDeleteJson: Failed to delete: $normalizedTargetPath (Document: ${documentToDelete.name})")
                newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Failed to delete. Check app permissions or if file is in use."))
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleDeleteJson: Security exception deleting ${documentToDelete.uri}", e)
             return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Error 403: Permission denied."))
        } catch (e: Exception) {
            Log.e(TAG, "handleDeleteJson: Unexpected error deleting $normalizedTargetPath", e)
            return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Unexpected server error during deletion: ${e.message}"))
        }
    }

     private fun handleRenameJson(targetPath: String?, newName: String?): Response {
        if (targetPath == null || newName == null || newName.trim().isEmpty()) {
            Log.w(TAG, "handleRenameJson: Missing 'path' or 'newName' parameter.")
            return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Missing 'path' or 'newName' parameter."))
        }

         val decodedNewName = try { URLDecoder.decode(newName, StandardCharsets.UTF_8.name()) } catch (e: Exception) { newName }

          val normalizedTargetPath = "/" + targetPath.trimStart('/')

        val documentToRename = findDocumentFile(normalizedTargetPath)

        if (documentToRename == null) {
            Log.w(TAG, "handleRenameJson: Document not found for path: $normalizedTargetPath")
            return newJsonResponse(Status.NOT_FOUND, mapOf("status" to "error", "message" to "Error 404: File or directory not found."))
        }
         if (rootDocumentFile != null && documentToRename.uri == rootDocumentFile.uri) {
            Log.w(TAG, "handleRenameJson: Attempted to rename root directory via path: $normalizedTargetPath")
            return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Error 403: Cannot rename the root directory."))
         }

         if (decodedNewName.trim().isEmpty()) {
             Log.w(TAG, "handleRenameJson: New name is empty after decoding/trimming.")
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "New name cannot be empty."))
         }
         if (decodedNewName.contains("/") || decodedNewName.contains("\\")) {
             Log.w(TAG, "handleRenameJson: New name contains invalid characters (slashes).")
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "New name cannot contain slashes."))
         }


        try {
            val success = documentToRename.renameTo(decodedNewName)
            return if (success) {
                Log.i(TAG, "handleRenameJson: Successfully renamed ${documentToRename.name} to $decodedNewName")
                val parentLogicalPath = getParentPath(normalizedTargetPath)
                val newLogicalPath = if (parentLogicalPath == "/") "/$decodedNewName" else "${parentLogicalPath}/${decodedNewName}"
                 val cleanNewLogicalPath = newLogicalPath.replace(Regex("/+"), "/")

                newJsonResponse(Status.OK, mapOf("status" to "success", "message" to "Successfully renamed to: ${decodedNewName}", "oldPath" to normalizedTargetPath, "newPath" to cleanNewLogicalPath))
            } else {
                Log.e(TAG, "handleRenameJson: Failed to rename ${documentToRename.name} to $decodedNewName")
                newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Failed to rename. A file/directory with that name might already exist or permissions are insufficient."))
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleRenameJson: Security exception renaming ${documentToRename.uri} to $decodedNewName", e)
             return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Error 403: Permission denied."))
        } catch (e: Exception) {
            Log.e(TAG, "handleRenameJson: Unexpected error renaming $normalizedTargetPath to $decodedNewName", e)
            return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Unexpected server error during renaming: ${e.message}"))
        }
    }

     private fun handleMkdirJson(currentDirPath: String, newDirName: String?): Response {
        if (newDirName == null || newDirName.trim().isEmpty()) {
            Log.w(TAG, "handleMkdirJson: Missing 'newDirName' parameter.")
            return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Missing 'newDirName' parameter."))
        }

        val decodedNewDirName = try { URLDecoder.decode(newDirName, StandardCharsets.UTF_8.name()) } catch (e: Exception) { newDirName }
        Log.d(TAG, "handleMkdirJson: Decoded new directory name: '$decodedNewDirName'")

         val normalizedCurrentDirPath = "/" + currentDirPath.trimStart('/')


        val parentDirectory = findDocumentFile(normalizedCurrentDirPath)

        if (parentDirectory == null || !parentDirectory.isDirectory) {
            Log.w(TAG, "handleMkdirJson: Parent directory not found or not a directory for path: $normalizedCurrentDirPath")
            return newJsonResponse(Status.NOT_FOUND, mapOf("status" to "error", "message" to "Error 404: Destination directory not found or is not a directory."))
        }

         if (decodedNewDirName.trim().isEmpty()) {
             Log.w(TAG, "handleMkdirJson: New directory name is empty after decoding/trimming.")
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "New directory name cannot be empty."))
         }
         if (decodedNewDirName.contains("/") || decodedNewDirName.contains("\\")) {
             Log.w(TAG, "handleMkdirJson: New directory name contains invalid characters (slashes).")
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "New directory name cannot contain slashes."))
         }


        try {
            val newDir = parentDirectory.createDirectory(decodedNewDirName)
            return if (newDir != null) {
                Log.i(TAG, "handleMkdirJson: Successfully created directory: ${newDir.name} in ${parentDirectory.name}")
                 val newLogicalDirPath = if (normalizedCurrentDirPath == "/") "/${newDir.name}" else "${normalizedCurrentDirPath}/${newDir.name}"
                 val cleanNewLogicalDirPath = newLogicalDirPath.replace(Regex("/+"), "/")

                newJsonResponse(Status.CREATED, mapOf("status" to "success", "message" to "Successfully created directory: ${newDir.name}", "path" to cleanNewLogicalDirPath))
            } else {
                Log.e(TAG, "handleMkdirJson: Failed to create directory '$decodedNewDirName' in ${parentDirectory.name}. createFile returned null.")
                newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Failed to create directory. A directory with that name might already exist or permissions are insufficient."))
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleMkdirJson: Security exception creating directory '$decodedNewDirName' in ${parentDirectory.uri}", e)
             return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Error 403: Permission denied."))
        } catch (e: Exception) {
            Log.e(TAG, "handleMkdirJson: Unexpected error creating directory '$decodedNewDirName' in $normalizedCurrentDirPath", e)
            return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Error 500: Unexpected server error during directory creation: ${e.message}"))
        }
    }

     private fun handleUploadJson(currentDirPath: String, session: IHTTPSession, files: HashMap<String, String>): Response {
        val normalizedCurrentDirPath = "/" + currentDirPath.trimStart('/')

        val parentDirectory = findDocumentFile(normalizedCurrentDirPath)

        if (parentDirectory == null || !parentDirectory.isDirectory) {
            Log.w(TAG, "handleUploadJson: Parent directory not found or not a directory for path: $normalizedCurrentDirPath")
            files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newJsonResponse(Status.NOT_FOUND, mapOf("status" to "error", "message" to "Error 404: Destination directory not found or is not a directory."))
        }

        val uploadedFileKey = session.parameters.keys.find { key -> files.containsKey(key) }

        if (uploadedFileKey == null) {
            Log.w(TAG, "handleUploadJson: No file parameter key found in session parameters or files map.")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Error 400: No file upload part found in request."))
        }

        val tempFilePath = files[uploadedFileKey]

        if (tempFilePath.isNullOrEmpty()) {
             Log.w(TAG, "handleUploadJson: Temporary file path is null or empty from 'files' map for key: $uploadedFileKey")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Internal error processing temp file path from 'files' map."))
        }

        val originalFileNameList = session.parameters[uploadedFileKey]
        val originalFileNameCandidate = originalFileNameList?.getOrNull(0)

        if (originalFileNameCandidate.isNullOrEmpty()) {
             Log.w(TAG, "handleUploadJson: Original filename candidate is null or empty from session parameters for key: $uploadedFileKey.")
              files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Original filename is empty or missing from session parameters."))
        }

        val controlCharsAndQuotesRegex = Regex("[\\x00-\\x1F\\x7F\"]")
        val cleanedFromControlAndQuotes = originalFileNameCandidate.replace(controlCharsAndQuotesRegex, "").trim()
        val cleanedFilename = cleanedFromControlAndQuotes.trimStart('/').trimEnd('/').trim()

        val decodedOriginalFileName = try {
            URLDecoder.decode(cleanedFilename, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            Log.e(TAG, "handleUploadJson: Failed to decode filename '$cleanedFilename'", e)
            cleanedFilename
        }

         if (decodedOriginalFileName.isEmpty()) {
             Log.w(TAG, "handleUploadJson: Original filename became empty after cleaning/decoding.")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Original filename is empty after processing."))
         }

         if (decodedOriginalFileName.contains("/") || decodedOriginalFileName.contains("\\")) {
             Log.w(TAG, "handleUploadJson: Original filename contains invalid characters (slashes). Filename: '$decodedOriginalFileName'")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.BAD_REQUEST, mapOf("status" to "error", "message" to "Original filename cannot contain slashes."))
         }

        Log.d(TAG, "handleUploadJson: Final filename to use for creation: '$decodedOriginalFileName'")

        val mimeTypeToCreate = URLConnection.guessContentTypeFromName(decodedOriginalFileName)
            ?: guessMimeTypeFromExtension(decodedOriginalFileName)
            ?: MIME_OCTET_STREAM
        Log.d(TAG, "handleUploadJson: Determined MIME type for new file creation: $mimeTypeToCreate")


        var newDocumentFile: DocumentFile? = null

        try {
            newDocumentFile = parentDirectory.createFile(mimeTypeToCreate, decodedOriginalFileName)

            if (newDocumentFile == null) {
                Log.e(TAG, "handleUploadJson: Failed to create new document file '$decodedOriginalFileName' in ${parentDirectory.name}. createFile returned null.")
                 files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
                 return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Failed to create file on device storage. A file with this name might already exist or permissions are insufficient."))
            }

            val outputStream = applicationContext.contentResolver.openOutputStream(newDocumentFile.uri)

            if (outputStream == null) {
                 Log.e(TAG, "handleUploadJson: Failed to open output stream for new document file ${newDocumentFile.uri}. openOutputStream returned null.")
                 try {
                     newDocumentFile.delete()
                 } catch (e: Exception) { Log.e(TAG, "handleUploadJson: Failed to delete incomplete file ${newDocumentFile.uri}", e) }
                 files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
                 return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Failed to open stream for writing to device storage."))
            }

            val tempFileInputStream = FileInputStream(tempFilePath)
            tempFileInputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "handleUploadJson: Successfully uploaded and saved '$decodedOriginalFileName' to ${newDocumentFile.uri}")

             val newLogicalFilePath = if (normalizedCurrentDirPath == "/") "/${newDocumentFile.name}" else "${normalizedCurrentDirPath}/${newDocumentFile.name}"
             val cleanNewLogicalFilePath = newLogicalFilePath.replace(Regex("/+"), "/")

             try {
                val tempFile = File(tempFilePath)
                if (tempFile.exists()) {
                     val deleted = tempFile.delete()
                     if (deleted) {
                         Log.d(TAG, "handleUploadJson: Cleaned up temp file: $tempFilePath")
                     } else {
                          Log.w(TAG, "handleUploadJson: Failed to delete temp file: $tempFilePath")
                     }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleUploadJson: Error cleaning up temp file: $tempFilePath", e)
            }

            return newJsonResponse(Status.CREATED, mapOf("status" to "success", "message" to "Successfully uploaded: ${newDocumentFile.name}", "path" to cleanNewLogicalFilePath))

        } catch (e: SecurityException) {
             Log.e(TAG, "handleUploadJson: Security exception creating or writing file '$decodedOriginalFileName' in ${parentDirectory.uri}", e)
              try { newDocumentFile?.delete() } catch (e2: Exception) { Log.e(TAG, "handleUploadJson: Failed to delete incomplete file ${newDocumentFile?.uri}", e2) }
              files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.FORBIDDEN, mapOf("status" to "error", "message" to "Permission denied to write to this location."))
        } catch (e: FileNotFoundException) {
             Log.e(TAG, "handleUploadJson: File not found (temp file?) or output stream failed: '$decodedOriginalFileName'", e)
              try { newDocumentFile?.delete() } catch (e2: Exception) { Log.e(TAG, "handleUploadJson: Failed to delete incomplete file ${newDocumentFile?.uri}", e2) }
              files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "File processing error during upload (temp file missing or output stream failed)."))
        } catch (e: IOException) {
            Log.e(TAG, "handleUploadJson: IO error during file copy for '$decodedOriginalFileName'", e)
             try { newDocumentFile?.delete() } catch (e2: Exception) { Log.e(TAG, "handleUploadJson: Failed to delete incomplete file ${newDocumentFile?.uri}", e2) }
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "IO Error during file upload."))
        } catch (e: Exception) {
            Log.e(TAG, "handleUploadJson: Unexpected error processing upload for '$decodedOriginalFileName'", e)
             try { newDocumentFile?.delete() } catch (e2: Exception) { Log.e(TAG, "handleUploadJson: Failed to delete incomplete file ${newDocumentFile?.uri}", e2) }
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newJsonResponse(Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to "Unexpected server error during upload: ${e.message}"))
        }
    }


    private fun serveFile(documentFile: DocumentFile): Response {
        try {
            val name = documentFile.name
            val mimeType = documentFile.type
                ?: name?.let { URLConnection.guessContentTypeFromName(it) }
                ?: name?.let { guessMimeTypeFromExtension(it) }
                ?: MIME_OCTET_STREAM

            Log.d(TAG, "serveFile: Determined MIME type: $mimeType for ${name ?: "unnamed file"}")

            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(documentFile.uri)

            if (inputStream == null) {
                 Log.e(TAG, "serveFile: Failed to open InputStream for ${documentFile.uri}. contentResolver.openInputStream returned null.")
                 return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error reading file: Could not open stream.")
            }

            val response = newChunkedResponse(Status.OK, mimeType, inputStream)

            val escapedFileName = name?.replace("\"", "\\\"") ?: "download"

            val contentDispositionType = if (mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.startsWith("audio/") || mimeType == "text/plain" || mimeType == "application/pdf") {
                 "inline"
             } else {
                 "attachment"
             }

            response.addHeader("Content-Disposition", "$contentDispositionType; filename=\"$escapedFileName\"")
            response.addHeader("Accept-Ranges", "bytes")

            val fileLength = documentFile.length()
            if (fileLength >= 0) {
                 response.addHeader("Content-Length", fileLength.toString())
            }

            Log.d(TAG, "serveFile: Response created for ${name ?: "unnamed"} with Content-Type: $mimeType, Content-Disposition: $contentDispositionType")
            return response

        } catch (e: FileNotFoundException) {
            Log.e(TAG, "serveFile: File not found (after initial check?) or cannot be opened: ${documentFile.uri}", e)
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: File not found or inaccessible.")
        } catch (e: SecurityException) {
            Log.e(TAG, "serveFile: Security exception opening stream for ${documentFile.uri}. Permissions issue?", e)
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Access Denied: Cannot open file.")
        }
        catch (e: IOException) {
            Log.e(TAG, "serveFile: Error serving file ${documentFile.uri}: ${e.message}", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error reading file.")
        } catch (e: Exception) {
            Log.e(TAG, "serveFile: Unexpected error serving file ${documentFile.uri}: ${e.message}", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Unexpected server error.")
        }
    }

    private fun guessMimeTypeFromExtension(filename: String): String? {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> MIME_JSON
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "txt" -> "text/plain"
            else -> null
        }
    }

    private fun String.encodeAsUriComponent(): String {
        return try {
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode URI component: $this", e)
            this
        }
    }

    override fun stop() {
        Log.d(TAG, "WebServer: Stopping NanoHTTPD instance...")
        try {
            super.stop()
            Log.d(TAG, "WebServer: NanoHTTPD stop() called.")
        } catch (e: Exception) {
            Log.e(TAG, "WebServer: Error stopping NanoHTTPD instance", e)
        }
        Log.d(TAG, "WebServer: NanoHTTPD stop method finished.")
    }

     fun approveClient(clientIdentifier: String) {
         Log.i(TAG, "WebServer: Approving client: $clientIdentifier")
         approvedClients.add(clientIdentifier)
         // TODO: Persist approvedClients set in SharedPreferences
     }

     fun denyClient(clientIdentifier: String) {
         Log.i(TAG, "WebServer: Denying client: $clientIdentifier")
         approvedClients.remove(clientIdentifier)
          // TODO: Update persisted approvedClients set in SharedPreferences
     }
}
