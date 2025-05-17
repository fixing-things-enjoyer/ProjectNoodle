// In ProjectNoodleServer.kt
package com.example.projectnoodle

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import android.util.Log // Import Log

private const val TAG = "ProjectNoodleServer" // Tag for logging

class ProjectNoodleServer : NanoHTTPD(0) { // Request port 0 for dynamic allocation

    var actualPort: Int = -1 // To store the dynamically assigned port
        private set

    // Keep track of whether the server is actually running
    var isRunning: Boolean = false
        private set

    // Optional: Callback to notify when server starts/stops or port is known
    // Add nullable 'ip' to the listener signature
    var serverStateListener: ((isRunning: Boolean, port: Int, ip: String?) -> Unit)? = null
    private var currentIp: String? = null // Store the IP when server starts (primarily for the server's own info message)

    // Change startServer to not take a port argument, it will be determined dynamically
    // It does need the IP address though, to display it in the response.
    fun startServer(ipAddress: String?) {
        if (isRunning) {
            Log.i(TAG, "Server already running on port $actualPort")
            // Notify listener with current state in case UI missed it
            serverStateListener?.invoke(true, actualPort, currentIp)
            return
        }
        // The IP address is needed mainly for displaying in the server response.
        // NanoHTTPD typically binds to all available interfaces (0.0.0.0) by default
        // when no specific hostname is provided in the constructor.
        // We store the IP address detected by NetworkUtils for display purposes.
        this.currentIp = ipAddress

        try {
            Log.i(TAG, "Attempting to start server dynamically...")
            // Define a socket timeout (e.g., 5000ms = 5 seconds)
            // This is the timeout for the server socket's accept() call.
            // NanoHTTPD also has internal timeouts for individual client connections.
            val serverSocketTimeout = 5000
            super.start(serverSocketTimeout, false) // false for non-daemon threads is typical for foreground services

            this.actualPort = this.listeningPort
            this.isRunning = true
            Log.i(TAG, "ProjectNoodleServer started on IP: $currentIp, Actual Port: $actualPort")
            // Notify listener of successful start
            serverStateListener?.invoke(true, actualPort, currentIp)
        } catch (e: IOException) {
            Log.e(TAG, "Could not start server: ${e.message}", e)
            isRunning = false
            actualPort = -1 // Indicate no valid port
            // Notify listener of failed start
            serverStateListener?.invoke(false, -1, null) // Pass -1 for port on failure
        } catch (e: Exception) { // Catch any other potential startup exceptions
            Log.e(TAG, "Unexpected error during server startup: ${e.message}", e)
            isRunning = false
            actualPort = -1 // Indicate no valid port
            serverStateListener?.invoke(false, -1, null)
        }
    }

    // stopServer remains largely the same
    fun stopServer() {
        // Add a check for super.isAlive() as well, in case isRunning state is off
        if (!isRunning && !super.isAlive()) {
            Log.i(TAG, "Server not running or already stopped.")
            // Ensure state reflects stopped, even if it was never started properly
            if (isRunning || actualPort != -1) { // If it thought it was running
                isRunning = false
                actualPort = -1
                currentIp = null // Clear IP when stopped
                serverStateListener?.invoke(false, -1, null) // Notify listener
            }
            return
        }
        try {
            Log.i(TAG, "Stopping server that was on port $actualPort...")
            super.stop()
            Log.i(TAG, "ProjectNoodleServer stopped.")
        } catch (e: Exception) { // Catch broader exceptions during stop
            Log.e(TAG, "Error stopping server: ${e.message}", e)
        } finally {
            // Ensure state is updated regardless of exceptions during stop
            // This might be redundant if super.stop() always succeeds and triggers a stop event internally
            // that NanoHTTPD might use, but good for robustness.
            // Let's rely on the listener update after stop completes or fails.
            // Update: NanoHTTPD's stop() *does* set the internal serverSocket to null and interrupt threads,
            // which might cause issues if we update state *before* super.stop() finishes cleaner shutdown.
            // The listener callback should ideally be the primary source of state updates.
            // Let's move the state update to happen *after* super.stop() or in the catch/finally.
            // The current placement in finally is okay to guarantee state reset.

            isRunning = false
            actualPort = -1
            currentIp = null // Clear IP when stopped
            // Notify listener of stop
            serverStateListener?.invoke(false, -1, null)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Serving request for: ${session.uri}")
        // Use the stored IP and actualPort for the response message
        val msg = "<html><body><h1>Hello from Project Noodle Server!</h1>" +
                "<p>You are connected.</p>" + // No need to show IP/Port here, the Access URL does
                "<p>Requested URI: ${session.uri}</p>" + // Show requested URI for debugging
                "</body></html>"
        return newFixedLengthResponse(Response.Status.OK, "text/html", msg) // Specify content type and Status
    }
}
