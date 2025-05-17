// In ProjectNoodleServer.kt
package com.example.projectnoodle

import fi.iki.elonen.NanoHTTPD
import io.ktor.http.cio.Response
import java.io.IOException

class ProjectNoodleServer : NanoHTTPD(0) { // Request port 0 for dynamic allocation

    var actualPort: Int = -1 // To store the dynamically assigned port
        private set

    // Keep track of whether the server is actually running
    var isRunning: Boolean = false
        private set

    // Optional: Callback to notify when server starts/stops or port is known
    var serverStateListener: ((isRunning: Boolean, port: Int, ip: String?) -> Unit)? = null
    private var currentIp: String? = null // Store the IP when server starts

    // Change startServer to not take a port argument, it will be determined dynamically
    fun startServer(ipAddress: String?) {
        if (isRunning) {
            println("ProjectNoodleServer: Server already running on port $actualPort")
            serverStateListener?.invoke(true, actualPort, currentIp)
            return
        }
        try {
            // Define a socket timeout (e.g., 5000ms = 5 seconds)
            // This is the timeout for the server socket's accept() call.
            // NanoHTTPD also has internal timeouts for individual client connections.
            val serverSocketTimeout = 5000
            super.start(serverSocketTimeout, false) // false for non-daemon threads is typical for foreground services

            this.actualPort = this.listeningPort
            this.isRunning = true
            this.currentIp = ipAddress
            println("ProjectNoodleServer started on IP: $ipAddress, Actual Port: $actualPort")
            serverStateListener?.invoke(true, actualPort, currentIp)
        } catch (e: IOException) {
            System.err.println("ProjectNoodleServer: Could not start server: \n${e}")
            isRunning = false
            actualPort = -1
            serverStateListener?.invoke(false, -1, null)
        }
    }

    // stopServer remains largely the same
    fun stopServer() {
        if (!isRunning && !super.isAlive()) { // Check if already stopped or never started
            println("ProjectNoodleServer: Server not running or already stopped.")
            // Ensure state reflects stopped, even if it was never started properly.
            if (isRunning || actualPort != -1) { // If it thought it was running
                isRunning = false
                actualPort = -1
                currentIp = null
                serverStateListener?.invoke(false, -1, null)
            }
            return
        }
        try {
            println("ProjectNoodleServer: Stopping server that was on port $actualPort...")
            super.stop()
            println("ProjectNoodleServer stopped.")
        } catch (e: Exception) { // Catch broader exceptions during stop
            System.err.println("ProjectNoodleServer: Error stopping server: \n${e}")
        } finally {
            // Ensure state is updated regardless of exceptions during stop
            isRunning = false
            actualPort = -1
            currentIp = null
            serverStateListener?.invoke(false, -1, null)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val msg = "<html><body><h1>Hello from Project Noodle Server!</h1>" +
                "<p>You are connected to IP: $currentIp, Port: $actualPort</p></body></html>"
        return newFixedLengthResponse(msg)
    }
}
