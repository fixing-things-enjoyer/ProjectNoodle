package com.example.projectnoodle

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme // Make sure this matches your theme package
import kotlinx.coroutines.Dispatchers // Import Dispatchers
import kotlinx.coroutines.launch // Import launch
import kotlinx.coroutines.withContext // Import withContext
import java.net.ServerSocket // Import ServerSocket for port finding
import java.io.IOException // Import IOException

class MainActivity : ComponentActivity() {

    // Declare the server instance outside of onCreate, can be null
    private var simpleWebServer: SimpleWebServer? = null
    private val TAG = "MainActivity" // Tag for MainActivity logs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Use your project's generated theme here, forced dark mode
            ProjectNoodleTheme(darkTheme = true) {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Wrap content in a Column and apply padding for system bars and general spacing
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(WindowInsets.systemBars.asPaddingValues()) // Apply padding based on system bars
                            .padding(16.dp) // Add some general padding
                    ) {
                        // Pass the server instance and callbacks to AppContent
                        AppContent(
                            server = simpleWebServer,
                            onStartClick = { launchServerStartCoroutine() }, // Call internal launch helper
                            onStopClick = { stopServer() }
                        )
                    }
                }
            }
        }
    }

    // Helper function to launch the suspend start server call from Activity context if needed
    // (Although in this specific setup, the launch happens within the Composable)
    // Keeping this structure might be useful for more complex scenarios or moving logic out.
    // For *this* specific step-by-step structure, the lambda in AppContent's onClick
    // handles the launch, but let's define it cleanly here anyway.
    private fun launchServerStartCoroutine() {
        // This function isn't strictly needed if the launch is purely in the Composable's onClick.
        // We'll keep the lambda in AppContent's onClick responsible for launching the suspend call.
        // The lambda passed from here will directly reference the suspend function startServer()
        Log.d(TAG, "launchServerStartCoroutine called - this structure might be refactored later.")
    }


    // Function to find a free port (suspendable and runs on IO dispatcher)
    private suspend fun findFreePort(): Int? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to find port...")
        val startPort = 8080 // Start range for common unused ports
        val endPort = 8100   // End range (try 20 ports)

        // Use firstNotNullOfOrNull to find the first available port in the range
        (startPort..endPort).firstNotNullOfOrNull { port ->
            try {
                // Attempt to bind to the port using ServerSocket
                // .use{} ensures the socket is closed automatically
                ServerSocket(port).use {
                    // If binding succeeds, the port is free
                    Log.d(TAG, "Found free port: $port")
                    port // Return the free port number
                }
            } catch (e: IOException) {
                // If binding fails (IOException), the port is likely in use
                Log.d(TAG, "Port $port is in use.")
                null // Return null to indicate failure for this port, continue to next
            }
        }
    }

    // Function to start the server (suspendable as it calls findFreePort)
    // Must be called from a Coroutine Scope
    private suspend fun startServer() {
        Log.d(TAG, "Attempting to start server...")
        val port = findFreePort() // Find a free port

        if (port != null) {
            try {
                // Ensure server creation and start are also on the IO dispatcher
                // as NanoHTTPD.start() might be blocking
                 withContext(Dispatchers.IO) {
                     simpleWebServer = SimpleWebServer(port) // Create server instance
                     simpleWebServer?.start() // Start the server
                     Log.i(TAG, "Server successfully started on port $port")
                 }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting server on port $port", e)
                simpleWebServer = null // Clear server instance on failure
                // TODO: Show error message to the user in the UI
            }
        } else {
             Log.e(TAG, "Could not find a free port in the range 8080-8100")
             // TODO: Show error message to the user in the UI
        }
    }

    // Function to stop the server
    private fun stopServer() {
        Log.d(TAG, "Attempting to stop server...")
        // NanoHTTPD.stop() is generally designed to be safe to call and relatively quick.
        // It's okay to call directly, but for truly blocking stops, use withContext(Dispatchers.IO).
        simpleWebServer?.stop() // Stop the server instance
        simpleWebServer = null // Clear the instance reference
        Log.i(TAG, "Server instance cleared.")
    }

    // Override onDestroy to stop the server when the activity is finished
    override fun onDestroy() {
        super.onDestroy()
        // Ensure the server is stopped when the activity is destroyed to free the port
        // Direct stop call as onDestroy is not a suspend context
        simpleWebServer?.stop()
        simpleWebServer = null
        Log.d(TAG, "Activity destroyed, server ensured stopped.")
    }
}

@Composable
fun AppContent(
    server: SimpleWebServer?, // Pass the server instance state down
    onStartClick: suspend () -> Unit, // Callback for Start click (is suspendable)
    onStopClick: () -> Unit,         // Callback for Stop click (is not suspendable)
    modifier: Modifier = Modifier
) {
    // Get a Coroutine Scope tied to the composable's lifecycle
    val coroutineScope = rememberCoroutineScope()

    // Derive the state of the server from the passed instance
    val isRunning = server != null
    val serverPort = server?.listeningPort // Get the port if server is running

    // App Title Text
    Text(
        text = "ProjectNoodle Server App",
        modifier = modifier.padding(bottom = 8.dp) // Add padding below the title
    )

    // Spacer for vertical separation
    Spacer(modifier = Modifier.height(16.dp))

    // Server Status Text
    val statusText = if (isRunning) {
        "Server Running on Port: $serverPort"
    } else {
        "Server Stopped"
    }
    Text(
        text = statusText,
        modifier = modifier.padding(bottom = 16.dp) // Add padding below status
    )

    // Start/Stop Button
    Button(
        onClick = {
            // Launch a coroutine because onStartClick is a suspend function
            coroutineScope.launch {
                if (isRunning) {
                    // If running, call the non-suspend stop function
                    onStopClick()
                } else {
                    // If stopped, call the suspend start function (runs inside this coroutine)
                    onStartClick()
                }
            }
        }
    ) {
        // Button text changes based on server state
        Text(text = if (isRunning) "Stop Server" else "Start Server")
    }
}

// Preview composable for Android Studio preview pane
@Preview(showBackground = true)
@Composable
fun AppPreview() {
    // Use the dark theme for the preview
    ProjectNoodleTheme(darkTheme = true) {
        // Wrap preview content in a Column with padding similar to the main app
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(16.dp)
        ) {
            // Pass dummy values for preview state
            AppContent(
                server = null, // Simulate server stopped in preview
                onStartClick = {}, // Provide empty suspend lambda for preview
                onStopClick = {} // Provide empty lambda for preview
            )
        }
    }
}
