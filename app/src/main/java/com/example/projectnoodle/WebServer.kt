package com.example.projectnoodle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status // Import Status constants
import fi.iki.elonen.NanoHTTPD.Method // Import Method enum
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLDecoder // Import URLDecoder
import java.net.URLEncoder // Import URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

private const val TAG = "ProjectNoodleServer"

private val INLINE_MIME_PREFIXES = setOf(
    "image/",
    "video/",
    "audio/",
    "text/plain",
    "application/pdf"
)

class WebServer(
    port: Int,
    private val applicationContext: Context,
    private val sharedDirectoryUri: Uri
) : NanoHTTPD(port) {

    private val rootDocumentFile = DocumentFile.fromTreeUri(applicationContext, sharedDirectoryUri)

    init {
        Log.d(TAG, "WebServer: Initialized with port $port and shared directory URI ${sharedDirectoryUri.toString()}")

        if (rootDocumentFile == null || !rootDocumentFile.exists() || !rootDocumentFile.isDirectory) {
            Log.e(TAG, "WebServer: Invalid or inaccessible root DocumentFile for URI: ${sharedDirectoryUri.toString()}")
        } else {
            Log.d(TAG, "WebServer: Root DocumentFile resolved: ${rootDocumentFile.name}")
        }
    }

    private fun findDocumentFile(uriPath: String): DocumentFile? {
        Log.d(TAG, "findDocumentFile: Searching for URI path: $uriPath")

        if (rootDocumentFile == null) {
            Log.e(TAG, "findDocumentFile: Root DocumentFile is null (invalid shared URI?).")
            return null
        }

        if (uriPath.trim('/') == "") {
            Log.d(TAG, "findDocumentFile: Request is for the root directory.")
            return rootDocumentFile
        }

        val segments = uriPath.trim('/').split('/').filter { it.isNotEmpty() }
        Log.d(TAG, "findDocumentFile: Path segments: $segments")

        var currentDocument: DocumentFile? = rootDocumentFile

        for (segment in segments) {
            val decodedSegment = try { URLDecoder.decode(segment, "UTF-8") } catch (e: Exception) { segment }

            val foundChild = currentDocument?.findFile(decodedSegment)
            if (foundChild == null) {
                Log.w(TAG, "findDocumentFile: Could not find segment '$decodedSegment' (original: '$segment') in ${currentDocument?.name ?: "current directory"} (URI: ${currentDocument?.uri}). Path not found.")
                return null
            }
            currentDocument = foundChild
            Log.d(TAG, "findDocumentFile: Found segment '$decodedSegment'. Current Document URI: ${currentDocument.uri}")
        }

        Log.d(TAG, "findDocumentFile: Found DocumentFile for path: ${currentDocument?.uri}")
        return currentDocument
    }


    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "WebServer: Received request:")
        Log.d(TAG, "WebServer:   Method: $method")
        Log.d(TAG, "WebServer:   URI: $uri")

        session.headers?.forEach { (key, value) -> Log.d(TAG, "WebServer:     Header $key: $value") }

        val files = HashMap<String, String>()
        if (Method.POST == method || Method.PUT == method) {
            try {
                session.parseBody(files)
                Log.d(TAG, "WebServer: Parsed POST body. Parameters: ${session.parameters}, Temp Files: $files")
            } catch (ioe: IOException) {
                Log.e(TAG, "WebServer: POST body parsing failed", ioe)
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "500 Internal Error: Can't read body.")
            } catch (hre: ResponseException) {
                Log.e(TAG, "WebServer: POST body parsing failed (ResponseException)", hre)
                return newFixedLengthResponse(hre.status, "text/plain", "500 Internal Error: ${hre.message}")
            }
        }

        val sanitizedUri = uri.replace("/+", "/")


        val targetPathParam = session.parameters["path"]?.get(0)
        val targetPath = if (targetPathParam != null) {
             try { URLDecoder.decode(targetPathParam, "UTF-8") } catch (e: Exception) { targetPathParam }
        } else null

        Log.d(TAG, "WebServer:   Target Path Parameter: $targetPath")


        when {
            method == Method.POST && session.parameters["_method"]?.get(0)?.uppercase(Locale.ROOT) == "DELETE" && targetPath != null -> {
                 Log.d(TAG, "WebServer: Handling simulated DELETE request for path: $targetPath")
                 val response = handleDelete(targetPath)
                 val parentUriPath = getParentUriPath(targetPath)
                 val redirectUrl = if (parentUriPath == "/") "/" else parentUriPath.encodeAsUriComponent()
                 val redirectResponse = newFixedLengthResponse(Status.REDIRECT, "text/plain", "Redirecting...") // **FIXED: Use Status.REDIRECT**
                 redirectResponse.addHeader("Location", redirectUrl)
                 return redirectResponse

            }
            method == Method.POST && sanitizedUri == "/rename" && targetPath != null -> {
                val newName = session.parameters["newName"]?.get(0)
                Log.d(TAG, "WebServer: Handling POST request to /rename for path: $targetPath with new name: $newName")
                val response = handleRename(targetPath, newName)
                 val parentUriPath = getParentUriPath(targetPath)
                 val redirectUrl = if (parentUriPath == "/") "/" else parentUriPath.encodeAsUriComponent()
                 val redirectResponse = newFixedLengthResponse(Status.REDIRECT, "text/plain", "Redirecting...") // **FIXED: Use Status.REDIRECT**
                 redirectResponse.addHeader("Location", redirectUrl)
                 return redirectResponse
            }
            method == Method.POST && sanitizedUri == "/mkdir" -> {
                 val currentDirPath = targetPath ?: "/"
                 val newDirName = session.parameters["newDirName"]?.get(0)
                 Log.d(TAG, "WebServer: Handling POST request to /mkdir in directory: $currentDirPath with new name: $newDirName")
                 val response = handleMkdir(currentDirPath, newDirName)
                 val redirectUrl = if (currentDirPath == "/") "/" else currentDirPath.encodeAsUriComponent()
                 val redirectResponse = newFixedLengthResponse(Status.REDIRECT, "text/plain", "Redirecting...") // **FIXED: Use Status.REDIRECT**
                 redirectResponse.addHeader("Location", redirectUrl)
                 return redirectResponse
            }
             method == Method.POST && sanitizedUri == "/upload" -> {
                 val currentDirPath = targetPath ?: "/"
                 Log.d(TAG, "WebServer: Handling POST request to /upload in directory: $currentDirPath")
                 val response = handleUpload(currentDirPath, session, files)
                 val redirectUrl = if (currentDirPath == "/") "/" else currentDirPath.encodeAsUriComponent()
                 val redirectResponse = newFixedLengthResponse(Status.REDIRECT, "text/plain", "Redirecting...") // **FIXED: Use Status.REDIRECT**
                 redirectResponse.addHeader("Location", redirectUrl)
                 return redirectResponse
            }


            method == Method.GET || method == Method.HEAD -> {
                val requestedDocument = findDocumentFile(sanitizedUri)

                if (requestedDocument == null) {
                    Log.w(TAG, "WebServer: Document not found for serving: $sanitizedUri")
                    return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: File or directory not found.")
                }

                if (requestedDocument.isDirectory) {
                    Log.d(TAG, "WebServer: Serving directory listing for: ${requestedDocument.uri}")
                    return serveDirectoryListing(session, requestedDocument)
                } else if (requestedDocument.isFile) {
                    Log.d(TAG, "WebServer: Serving file: ${requestedDocument.uri}")
                    return serveFile(session, requestedDocument)
                } else {
                    Log.w(TAG, "WebServer: Requested document is neither file nor directory for serving: ${requestedDocument.uri}")
                    return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Access Denied.")
                }
            }
            else -> {
                Log.w(TAG, "WebServer: Method not allowed: $method for URI: $uri")
                return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
            }
        }
    }

    private fun getParentUriPath(currentPath: String): String {
        val path = currentPath.removeSuffix("/")
        val lastSlashIndex = path.lastIndexOf('/')
        return if (lastSlashIndex <= 0) "/" else path.substring(0, lastSlashIndex)
    }


    private fun handleDelete(targetPath: String?): Response {
        if (targetPath == null || targetPath.trim('/') == "") {
            Log.w(TAG, "handleDelete: Missing or invalid 'path' parameter.")
            return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: Missing or invalid 'path' parameter.")
        }

        val documentToDelete = findDocumentFile(targetPath)

        if (documentToDelete == null) {
            Log.w(TAG, "handleDelete: Document not found for path: $targetPath")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: File or directory not found.")
        }

         if (documentToDelete.uri == sharedDirectoryUri) {
            Log.w(TAG, "handleDelete: Attempted to delete root directory via path: $targetPath")
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Cannot delete the root directory.")
         }


        try {
            val success = documentToDelete.delete()
            if (success) {
                Log.i(TAG, "handleDelete: Successfully deleted: $targetPath (Document: ${documentToDelete.name})")
                return newFixedLengthResponse(Status.OK, "text/plain", "Successfully deleted: ${documentToDelete.name}")
            } else {
                Log.e(TAG, "handleDelete: Failed to delete: $targetPath (Document: ${documentToDelete.name})")
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Failed to delete.")
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleDelete: Security exception deleting ${documentToDelete.uri}", e)
             return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Permission denied.")
        } catch (e: Exception) {
            Log.e(TAG, "handleDelete: Unexpected error deleting $targetPath", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Unexpected server error during deletion: ${e.message}")
        }
    }

     private fun handleRename(targetPath: String?, newName: String?): Response {
        if (targetPath == null || newName == null || newName.trim().isEmpty()) {
            Log.w(TAG, "handleRename: Missing 'path' or 'newName' parameter.")
            return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: Missing 'path' or 'newName' parameter.")
        }

         val decodedNewName = try { URLDecoder.decode(newName, "UTF-8") } catch (e: Exception) { newName }
         Log.d(TAG, "handleRename: Decoded new name: '$decodedNewName'")

        val documentToRename = findDocumentFile(targetPath)

        if (documentToRename == null) {
            Log.w(TAG, "handleRename: Document not found for path: $targetPath")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: File or directory not found.")
        }
        if (documentToRename.uri == sharedDirectoryUri) {
            Log.w(TAG, "handleRename: Attempted to rename root directory via path: $targetPath")
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Cannot rename the root directory.")
         }

         if (decodedNewName.trim().isEmpty()) {
             Log.w(TAG, "handleRename: New name is empty after decoding/trimming.")
             return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: New name cannot be empty.")
         }
         if (decodedNewName.contains("/") || decodedNewName.contains("\\")) {
             Log.w(TAG, "handleRename: New name contains invalid characters (slashes).")
             return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: New name cannot contain slashes.")
         }


        try {
            val success = documentToRename.renameTo(decodedNewName)
            if (success) {
                Log.i(TAG, "handleRename: Successfully renamed ${documentToRename.name} to $decodedNewName")
                return newFixedLengthResponse(Status.OK, "text/plain", "Successfully renamed to: ${decodedNewName}")
            } else {
                Log.e(TAG, "handleRename: Failed to rename ${documentToRename.name} to $decodedNewName")
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Failed to rename.")
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleRename: Security exception renaming ${documentToRename.uri} to $decodedNewName", e)
             return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Permission denied.")
        } catch (e: Exception) {
            Log.e(TAG, "handleRename: Unexpected error renaming $targetPath to $decodedNewName", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Unexpected server error during renaming: ${e.message}")
        }
    }

    private fun handleMkdir(currentDirPath: String, newDirName: String?): Response {
        if (newDirName == null || newDirName.trim().isEmpty()) {
            Log.w(TAG, "handleMkdir: Missing 'newDirName' parameter.")
            return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: Missing 'newDirName' parameter.")
        }

        val decodedNewDirName = try { URLDecoder.decode(newDirName, "UTF-8") } catch (e: Exception) { newDirName }
        Log.d(TAG, "handleMkdir: Decoded new directory name: '$decodedNewDirName'")

        val parentDirectory = findDocumentFile(currentDirPath)

        if (parentDirectory == null || !parentDirectory.isDirectory) {
            Log.w(TAG, "handleMkdir: Parent directory not found or not a directory for path: $currentDirPath")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: Destination directory not found.")
        }

         if (decodedNewDirName.trim().isEmpty()) {
             Log.w(TAG, "handleMkdir: New directory name is empty after decoding/trimming.")
             return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: New directory name cannot be empty.")
         }
         if (decodedNewDirName.contains("/") || decodedNewDirName.contains("\\")) {
             Log.w(TAG, "handleMkdir: New directory name contains invalid characters (slashes).")
             return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: New directory name cannot contain slashes.")
         }


        try {
            val newDir = parentDirectory.createDirectory(decodedNewDirName)
            if (newDir != null) {
                Log.i(TAG, "handleMkdir: Successfully created directory: ${newDir.name} in ${parentDirectory.name}")
                return newFixedLengthResponse(Status.CREATED, "text/plain", "Successfully created directory: ${newDir.name}")
            } else {
                Log.e(TAG, "handleMkdir: Failed to create directory '$decodedNewDirName' in $currentDirPath")
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Failed to create directory.")
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "handleMkdir: Security exception creating directory '$decodedNewDirName' in ${parentDirectory.uri}", e)
             return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Permission denied.")
        } catch (e: Exception) {
            Log.e(TAG, "handleMkdir: Unexpected error creating directory '$decodedNewDirName' in $currentDirPath", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Unexpected server error during directory creation: ${e.message}")
        }
    }

    private fun handleUpload(currentDirPath: String, session: IHTTPSession, files: HashMap<String, String>): Response {
        val parentDirectory = findDocumentFile(currentDirPath)

        if (parentDirectory == null || !parentDirectory.isDirectory) {
            Log.w(TAG, "handleUpload: Parent directory not found or not a directory for path: $currentDirPath")
            files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Error 404: Destination directory not found.")
        }

        val uploadedFileKey = session.parameters.keys.find { key -> files.containsKey(key) }

        if (uploadedFileKey == null) {
            Log.w(TAG, "handleUpload: No file parameter found in request.")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
            return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Error 400: No file uploaded.")
        }

        val tempFilePath = files[uploadedFileKey]
        val originalFileName = session.parameters[uploadedFileKey]?.getOrNull(1)
                                 ?: File(tempFilePath ?: "").name

        if (tempFilePath == null || originalFileName.isNullOrEmpty()) {
             Log.w(TAG, "handleUpload: Missing temp file path or original filename.")
             files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
             return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Could not process uploaded file.")
        }

        Log.d(TAG, "handleUpload: Received file '$originalFileName' at temporary path: $tempFilePath")

        val mimeType = URLConnection.guessContentTypeFromName(originalFileName) ?: "application/octet-stream"
        Log.d(TAG, "handleUpload: Guessed MIME type for new file: $mimeType")


        try {
            val newDocumentFile = parentDirectory.createFile(mimeType, originalFileName)

            if (newDocumentFile == null) {
                Log.e(TAG, "handleUpload: Failed to create new document file '$originalFileName' in ${parentDirectory.name}")
                 files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
                 return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Failed to create file on device.")
            }

            val outputStream = applicationContext.contentResolver.openOutputStream(newDocumentFile.uri)

            if (outputStream == null) {
                 Log.e(TAG, "handleUpload: Failed to open output stream for new document file ${newDocumentFile.uri}")
                 files.values.forEach { tempPath -> try { File(tempPath).delete() } catch (_: Exception) {} }
                 newDocumentFile.delete()
                 return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Failed to open stream for writing.")
            }


            val tempFileInputStream = FileInputStream(tempFilePath)
            tempFileInputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "handleUpload: Successfully uploaded and saved '$originalFileName' to ${newDocumentFile.uri}")
            return newFixedLengthResponse(Status.CREATED, "text/plain", "Successfully uploaded: ${newDocumentFile.name}")

        } catch (e: SecurityException) {
             Log.e(TAG, "handleUpload: Security exception creating or writing file '$originalFileName' in ${parentDirectory.uri}", e)
             return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Error 403: Permission denied.")
        } catch (e: FileNotFoundException) {
             Log.e(TAG, "handleUpload: File not found (temp file?) or output stream failed: '$originalFileName'", e)
             return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: File processing error during upload.")
        } catch (e: IOException) {
            Log.e(TAG, "handleUpload: IO error during file copy for '$originalFileName'", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: IO Error during upload.")
        } catch (e: Exception) {
            Log.e(TAG, "handleUpload: Unexpected error processing upload for '$originalFileName'", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error 500: Unexpected server error during upload: ${e.message}")
        } finally {
            files.values.forEach { tempPath ->
                try {
                    val deleted = File(tempPath).delete()
                    if (deleted) {
                        Log.d(TAG, "handleUpload: Cleaned up temp file: $tempPath")
                    } else {
                         Log.w(TAG, "handleUpload: Failed to delete temp file: $tempPath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "handleUpload: Error cleaning up temp file: $tempPath", e)
                }
            }
        }
    }


    private fun serveFile(session: IHTTPSession, documentFile: DocumentFile): Response {
        try {
            val mimeType = documentFile.type ?: URLConnection.guessContentTypeFromName(documentFile.name)
                ?: getMimeTypeForFile(documentFile.name)
                ?: "application/octet-stream"
            Log.d(TAG, "serveFile: Determined MIME type: $mimeType for ${documentFile.name}")

            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(documentFile.uri)

            if (inputStream == null) {
                 Log.e(TAG, "serveFile: Failed to open InputStream for ${documentFile.uri}. contentResolver.openInputStream returned null.")
                 return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error reading file: Could not open stream.")
            }

            val response = newChunkedResponse(Status.OK, mimeType, inputStream)

            val escapedFileName = documentFile.name?.replace("\"", "\\\"") ?: "download"

            val contentDispositionType = if (INLINE_MIME_PREFIXES.any { mimeType.startsWith(it) }) {
                "inline"
            } else {
                "attachment"
            }

            response.addHeader("Content-Disposition", "$contentDispositionType; filename=\"$escapedFileName\"")

            Log.d(TAG, "serveFile: Response created for ${documentFile.name} with Content-Disposition: $contentDispositionType")
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

    private fun serveDirectoryListing(session: IHTTPSession, directoryDocument: DocumentFile): Response {
        val uri = session.uri.removeSuffix("/")
        val children = directoryDocument.listFiles() ?: emptyArray()

        val sortedEntries = children.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))

        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Index of ${uri}/</title>")
        html.append("<style>")
        html.append("body { font-family: sans-serif; margin: 20px; background-color: #121212; color: #e0e0e0; }")
        html.append("h1 { color: #bb86fc; }")
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 20px; }")
        html.append("th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid #333333; }")
        html.append("th { background-color: #222222; color: #ffffff; }")
        html.append("tr:hover { background-color: #1e1e1e; }")
        html.append("a { color: #03dac6; text-decoration: none; }")
        html.append("a:hover { text-decoration: underline; }")
        html.append(".file-size, .file-date { font-size: 0.8em; color: #b0b0b0; }")
        html.append("form { display: inline-block; margin: 0 2px; }")
        html.append("button { background-color: #625b71; color: white; border: none; padding: 4px 8px; text-align: center; text-decoration: none; display: inline-block; font-size: 12px; margin: 2px 0; cursor: pointer; border-radius: 4px; }")
         html.append("button.delete { background-color: #cf6679; }")
         html.append("input[type='text'], input[type='file'] { padding: 4px; margin: 0 2px; border-radius: 4px; border: 1px solid #333; background-color: #1e1e1e; color: #e0e0e0; font-size: 12px; }")
         html.append(".actions { white-space: nowrap; }")
         html.append("</style>")
        html.append("</head><body><h1>Index of ${uri}/</h1><table>")
        html.append("<tr><th>Name</th><th>Size</th><th>Last Modified</th><th>Actions</th></tr>")

        if (directoryDocument.uri != sharedDirectoryUri) {
             val currentUriPath = session.uri.removeSuffix("/")
             val lastSlashIndex = currentUriPath.lastIndexOf('/')
             val parentLinkUri = if (lastSlashIndex <= 0) "/" else currentUriPath.substring(0, lastSlashIndex)

             html.append("<tr><td><a href=\"${parentLinkUri}\">..</a></td><td></td><td></td><td></td></tr>")
        } else {
            Log.d(TAG, "serveDirectoryListing: Not adding '..' link as this is the root.")
        }

        for (document in sortedEntries) {
             if (document.uri == sharedDirectoryUri && uri.trim('/') == "") {
                 continue
             }

            val name = document.name ?: "Unnamed"
            val escapedName = name.replace("&", "&").replace("<", "<").replace(">", ">").replace("\"", "\"")
            val documentUriSegment = name.encodeAsUriComponent()
            val documentLinkUri = "${uri}/${documentUriSegment}".replace("//", "/")
            val documentRelativePath = "${uri}/${name}".replace("//", "/").removePrefix("/")

            html.append("<tr>")
            html.append("<td>")
            if (document.isDirectory) {
                 html.append("<a href=\"${documentLinkUri}\">${escapedName}/</a>")
            } else {
                 html.append("<a href=\"${documentLinkUri}\">${escapedName}</a>")
            }
            html.append("</td>")

            if (document.isFile) {
                html.append("<td><span class=\"file-size\">${formatFileSize(document.length())}</span></td>")
                html.append("<td><span class=\"file-date\">${formatDate(document.lastModified())}</span></td>")
            } else {
                html.append("<td></td><td></td>")
            }

            html.append("<td class=\"actions\">")

            if (document.uri != sharedDirectoryUri) {
                 html.append("<form action=\"/rename\" method=\"post\">")
                 html.append("<input type=\"hidden\" name=\"path\" value=\"${documentRelativePath}\">")
                 html.append("<input type=\"text\" name=\"newName\" value=\"${escapedName}\">")
                 html.append("<button type=\"submit\">Rename</button>")
                 html.append("</form>")
            }

             if (document.uri != sharedDirectoryUri) {
                 html.append("<form action=\"/\" method=\"post\" onsubmit=\"return confirm('Are you sure you want to delete \\'${escapedName}\\'?');\">")
                 html.append("<input type=\"hidden\" name=\"_method\" value=\"DELETE\">")
                 html.append("<input type=\"hidden\" name=\"path\" value=\"${documentRelativePath}\">")
                 html.append("<button type=\"submit\" class=\"delete\">Delete</button>")
                 html.append("</form>")
             }

            html.append("</td>")
            html.append("</tr>")
        }

        html.append("</table>")

         val currentRelativePathForForms = uri.removePrefix("/")

         html.append("<h2>Actions in ${uri}/</h2>")

         html.append("<form action=\"/mkdir\" method=\"post\">")
         html.append("<input type=\"hidden\" name=\"path\" value=\"${currentRelativePathForForms}\">")
         html.append("Create directory: <input type=\"text\" name=\"newDirName\" placeholder=\"New Directory Name\">")
         html.append("<button type=\"submit\">Create</button>")
         html.append("</form>")

         html.append("<hr>")

         html.append("<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">")
         html.append("<input type=\"hidden\" name=\"path\" value=\"${currentRelativePathForForms}\">")
         html.append("Upload file: <input type=\"file\" name=\"file\">")
         html.append("<button type=\"submit\">Upload</button>")
         html.append("</form>")


        html.append("</body></html>")

        return newFixedLengthResponse(Status.OK, "text/html", html.toString())
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDate(time: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(time))
    }

    private fun String.encodeAsUriComponent(): String {
        return try {
            URLEncoder.encode(this, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode URI component: $this", e)
            this
        }
    }


    override fun stop() {
        Log.d(TAG, "WebServer: Stopping server...")
        try {
            super.stop()
            Log.d(TAG, "WebServer: Server stop() called. isAlive: ${isAlive}")
        } catch (e: Exception) {
            Log.e(TAG, "WebServer: Error stopping server", e)
        }
        Log.d(TAG, "WebServer: Server stop method finished.")
    }
}
