package com.example.projectnoodle

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status // Import Status
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT // Import MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Method // Import Method

class SimpleWebServer(port: Int) : NanoHTTPD(port) {

    private val TAG = "SimpleWebServer"

    init {
        Log.d(TAG, "Server initialized on port $port")
    }

    override fun serve(session: IHTTPSession): Response {
        // Log the incoming request
        Log.i(TAG, "Received request: ${session.method} ${session.uri}")
        Log.d(TAG, "Headers: ${session.headers}")

        if (session.method == Method.POST || session.method == Method.PUT) {
            try {
                session.parseBody(null) // Parse body for POST/PUT
                // Access parameters using the non-deprecated method
                Log.d(TAG, "Body parameters: ${session.parameters}")
                // Remove the line accessing session.files as it's no longer available directly
                // Log.d(TAG, "Body files: ${session.files}") // <-- REMOVE THIS LINE
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing body", e)
            }
        } else if (session.method == Method.GET) {
            // For GET requests, parameters are in the query string
             Log.d(TAG, "Query parameters: ${session.parameters}") // Access query parameters
        }


        // Create the "hello world" response
        val response = newFixedLengthResponse(
            Status.OK, // Use imported Status
            MIME_PLAINTEXT, // Use imported MIME_PLAINTEXT
            "Hello World!" // The content
        )

        // Optional: Add headers if needed
        // response.addHeader("Access-Control-Allow-Origin", "*")

        return response
    }

    override fun start() {
        super.start()
        Log.i(TAG, "Server started on port $listeningPort")
    }

    override fun stop() {
        Log.i(TAG, "Server stopping on port $listeningPort")
        super.stop()
        Log.i(TAG, "Server stopped")
    }
}
