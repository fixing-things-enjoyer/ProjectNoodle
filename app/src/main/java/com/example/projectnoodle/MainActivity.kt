package com.example.projectnoodle

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme
import java.io.IOException
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val TAG = "ProjectNoodleServer"

class MainActivity : ComponentActivity() {

    private var server: WebServer? = null
    // Server operational state (Running, Stopped, etc.)
    private var serverOperationalState by mutableStateOf("Stopped") // Initial state
    // Network status message (IP or "Wi-Fi not connected.")
    private var networkStatusMessage by mutableStateOf("Waiting for server...")
    private var serverPort by mutableIntStateOf(-1) // -1 indicates no port assigned yet

    // State for the currently shared directory URI (the actual URI object)
    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    // State for displaying the user-friendly name of the shared directory
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")


    // Network state management
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Activity Result Launcher for picking a directory
    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        // Initialize ConnectivityManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Create and register network callback
        networkCallback = createNetworkCallback()
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
             Log.e(TAG, "Failed to register network callback", e)
             networkStatusMessage = "Could not monitor network changes."
        }

        // Set up the directory picker launcher
        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            // This is the callback when the user selects a directory (or cancels)
            handleDirectoryPicked(uri)
        }


        // Initial UI setup - server starts stopped, no directory selected
        serverOperationalState = "Stopped"
        networkStatusMessage = "Server is stopped." // Initial network status when stopped


        setContent {
            ProjectNoodleTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServerStatus(
                        operationalState = serverOperationalState,
                        networkStatus = networkStatusMessage,
                        port = serverPort,
                        sharedDirectoryName = sharedDirectoryNameDisplay,
                        onStartClick = { startServer() },
                        onStopClick = { stopServer() },
                        onSelectDirectoryClick = { launchDirectoryPicker() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")

        // Unregister network callback
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback, might not have been registered.", e)
        }

        // Stop the server when the activity is destroyed as a safety measure
        stopServer(isAppShuttingDown = true)
        Log.d(TAG, "MainActivity onDestroy finished.")
    }

    // --- Server Lifecycle Management ---

    private fun startServer() {
        // Prevent starting if already running or attempting to start
        if (server != null && server!!.isAlive) {
            Log.d(TAG, "startServer: Server is already running.")
            return
        }
        if (serverOperationalState == "Starting") {
             Log.d(TAG, "startServer: Server is already attempting to start.")
             return
        }

        // Check if a directory has been selected (using the URI object)
        val uriToShare = sharedDirectoryUri // Get the current URI
        if (uriToShare == null) {
             Log.w(TAG, "startServer: No directory selected.")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Please select a directory to share."
             return
        }

        serverOperationalState = "Starting"
        networkStatusMessage = "Looking for port..."

        // Find an available port dynamically
        val dynamicPort = findAvailablePort()

        if (dynamicPort != -1) {
            Log.d(TAG, "Found available port: $dynamicPort")
            serverPort = dynamicPort

            // Initialize the NanoHTTPD server with the dynamic port, context, AND the shared directory URI
            server = WebServer(serverPort, applicationContext, uriToShare) // <-- PASSING URI AND CONTEXT HERE


            // Start the NanoHTTPD server
            try {
                Log.d(TAG, "Attempting to start NanoHTTPD Server on port $serverPort.")
                server?.start()

                // Check if server reports as alive after start() returns
                if (server?.isAlive == true) {
                     Log.d(TAG, "NanoHTTPD Server started successfully (isAlive = true).")
                     serverOperationalState = "Running"
                     updateNetworkStatusMessage()

                     // Self-test: Connect to localhost on the dynamic port
                     // The self-test should now check if the server serves the root of the *selected* directory.
                     // The current self-test expects "Index of /" title, which matches our new directory listing HTML.
                     // So the existing self-test should work as-is against the new server logic.
                     runSelfTest(serverPort)
                } else {
                     Log.e(TAG, "NanoHTTPD Server start() returned, but isAlive is false.")
                     serverOperationalState = "Failed: Internal Error"
                     networkStatusMessage = "Server process stopped unexpectedly."
                     serverPort = -1
                     updateNetworkStatusMessage()
                }

            } catch (e: IOException) {
                Log.e(TAG, "Failed to start NanoHTTPD Server on port $serverPort (IOException): ${e.message}", e)
                serverOperationalState = "Failed: IO Error"
                networkStatusMessage = e.message ?: "Unknown IO Exception"
                 serverPort = -1
                 updateNetworkStatusMessage()
            } catch (e: Exception) {
                Log.e(TAG, "An unexpected error occurred during server startup", e)
                serverOperationalState = "Failed: Unexpected Error"
                networkStatusMessage = e.message ?: "Unknown Exception"
                 serverPort = -1
                 updateNetworkStatusMessage()
            }
        } else {
             Log.e(TAG, "Failed to find an available port.")
             serverOperationalState = "Failed: No Port Found"
             networkStatusMessage = "Could not find an available port."
             serverPort = -1
        }
    }

    private fun stopServer(isAppShuttingDown: Boolean = false) {
        if (server == null && !isAppShuttingDown) {
             Log.d(TAG, "stopServer: Server instance is null.")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Server is stopped."
             serverPort = -1
             return
        }
         if (serverOperationalState == "Stopped" && !isAppShuttingDown) {
             Log.d(TAG, "stopServer: Server is already stopped.")
             return
         }


        Log.d(TAG, "Attempting to stop NanoHTTPD Server.")
        if (!isAppShuttingDown) {
            serverOperationalState = "Stopping"
            networkStatusMessage = "Server is stopping..."
        }


        thread {
            try {
                server?.stop()
                Log.d(TAG, "NanoHTTPD Server stop() called.")

                runOnUiThread {
                    server = null
                    if (!isAppShuttingDown) {
                        serverOperationalState = "Stopped"
                        networkStatusMessage = "Server is stopped."
                        serverPort = -1
                        Log.d(TAG, "UI Operational State updated to Stopped.")
                    } else {
                         Log.d(TAG, "App shutting down, skipping UI state update after stop.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NanoHTTPD Server", e)
                 if (!isAppShuttingDown) {
                     runOnUiThread {
                        serverOperationalState = "Error Stopping"
                        networkStatusMessage = e.message ?: "Unknown Error during stop"
                        serverPort = -1
                         Log.e(TAG, "UI Operational State updated to Error Stopping.")
                     }
                 } else {
                      Log.e(TAG, "App shutting down, not updating UI state after stop error.")
                 }
            }
        }
    }


    // --- Directory Picker Handling ---

    private fun launchDirectoryPicker() {
        Log.d(TAG, "Launching directory picker...")
        openDirectoryPickerLauncher.launch(sharedDirectoryUri) // Pass current URI to start picker there
    }

    private fun handleDirectoryPicked(uri: Uri?) {
        if (uri == null) {
            Log.d(TAG, "Directory picker cancelled or no URI returned.")
            return
        }

        Log.d(TAG, "Directory picked! URI: $uri")

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "Successfully took persistable URI permissions (Read/Write) for: $uri")

            // Use DocumentFile to get the user-friendly display name
            val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)
            val displayName = documentFile?.name ?: uri.lastPathSegment?.let {
                 try { Uri.decode(it) } catch (e: Exception) { it }
            } ?: "Unknown Directory"


            // Update state variables
            sharedDirectoryUri = uri // Keep the URI object
            sharedDirectoryNameDisplay = displayName // Display the friendly name
            Log.d(TAG, "Shared directory state updated: URI = $uri, Display Name = $displayName")

            // Inform the user in the UI that a directory has been selected
            runOnUiThread {
                if (serverOperationalState == "Stopped" || serverOperationalState.startsWith("Failed")) {
                    networkStatusMessage = "Directory selected: $sharedDirectoryNameDisplay"
                } else if (serverOperationalState == "Running") {
                     // Inform the user they need to restart.
                    networkStatusMessage = "Directory updated to: $sharedDirectoryNameDisplay\n(Stop/Start server to apply)"
                     // Also immediately stop the currently running server so they *have* to restart
                     // to apply the new directory. This prevents confusion.
                     stopServer() // Stop the running server
                } else {
                     Log.i(TAG, "Directory selected while server is in transition ($serverOperationalState). User must Stop/Start to apply.")
                }
            }

            // TODO: In a future step, persist this URI (e.g., in SharedPreferences)
            // so the app remembers it across launches.

        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable URI permission for $uri", e)
            sharedDirectoryUri = null
            sharedDirectoryNameDisplay = "Permission Failed"
             runOnUiThread {
                 networkStatusMessage = "Failed to get necessary directory permissions."
                 // If server was running, stopping it might be wise here too?
                 // Or just leave it running the old dir? Let's stop for consistency.
                 if (serverOperationalState == "Running") {
                     stopServer()
                 } else {
                     // If stopped/failed, just update status
                     serverOperationalState = "Stopped" // Ensure state reflects inability to start with failed perm
                 }

             }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling picked directory URI: $uri", e)
            sharedDirectoryUri = null
            sharedDirectoryNameDisplay = "Error Processing"
             runOnUiThread {
                 networkStatusMessage = "Error processing selected directory."
                 if (serverOperationalState == "Running") {
                     stopServer()
                 } else {
                      serverOperationalState = "Stopped"
                 }
             }
        }
    }


    // --- Network State Handling ---

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "NetworkCallback: onAvailable: $network")
                updateNetworkStatusMessage()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "NetworkCallback: onLost: $network")
                updateNetworkStatusMessage()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                Log.d(TAG, "NetworkCallback: onCapabilitiesChanged: $networkCapabilities")
                updateNetworkStatusMessage()
            }
        }
    }

    private fun updateNetworkStatusMessage() {
         runOnUiThread {
             if (serverOperationalState == "Running" && server != null && server!!.isAlive) {
                 val wifiIp = getWifiIPAddress(applicationContext)
                 networkStatusMessage = if (wifiIp != null) {
                     wifiIp
                 } else {
                     "Wi-Fi not connected."
                 }
                 Log.d(TAG, "UI Network Status Message updated: ${networkStatusMessage}")
             } else {
                 if (serverOperationalState.startsWith("Failed") || serverOperationalState == "Error Stopping") {
                      Log.d(TAG, "UI Network Status Message kept due to failed state.")
                 } else {
                     networkStatusMessage = when (serverOperationalState) {
                          "Stopped" -> if (sharedDirectoryUri != null) "Directory selected: $sharedDirectoryNameDisplay" else "Server is stopped."
                          "Starting" -> "Server is starting..."
                          "Stopping" -> "Server is stopping..."
                          else -> if (sharedDirectoryUri != null) "Directory selected: $sharedDirectoryNameDisplay" else "No directory selected."
                     }
                     Log.d(TAG, "UI Network Status Message updated based on non-running state: ${networkStatusMessage}")
                 }
             }
         }
    }


    // Helper function to find an available port dynamically
    private fun findAvailablePort(): Int {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(0)
            val localPort = serverSocket.localPort
            Log.d(TAG, "findAvailablePort: Found port $localPort")
            return localPort
        } catch (e: IOException) {
            Log.e(TAG, "findAvailablePort: Error finding available port", e)
            return -1 // Indicate failure
        } finally {
            try {
                serverSocket?.close()
                Log.d(TAG, "findAvailablePort: Closed temporary socket")
            } catch (e: IOException) {
                Log.e(TAG, "findAvailablePort: Error closing temporary ServerSocket", e)
            }
        }
    }


    internal fun getWifiIPAddress(context: Context): String? {
         try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            @Suppress("DEPRECATION") // connectionInfo is deprecated but still common
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddressInt = wifiInfo?.ipAddress ?: 0

            if (ipAddressInt == 0 || ipAddressInt == -1) {
                 Log.d(TAG, "getWifiIPAddress: Wi-Fi not connected or IP is not valid (int value: $ipAddressInt)")
                return null
            }

            @Suppress("DEPRECATION") // Formatter.formatIpAddress is deprecated but still useful for IPv4
            val ipAddress = Formatter.formatIpAddress(ipAddressInt)
            Log.d(TAG, "getWifiIPAddress: IP found: $ipAddress")
            return if (ipAddress == "0.0.0.0") null else ipAddress
         } catch (e: Exception) {
             Log.e(TAG, "Error getting Wi-Fi IP Address", e)
             return null
         }
    }


    // Self-test function (called after successful start)
    private fun runSelfTest(port: Int) {
        if (port == -1 || serverOperationalState != "Running" || server == null || !server!!.isAlive) {
             Log.w(TAG, "Self-test skipped: Server not in Running state.")
             return
        }

        thread {
            val testUrl = "http://127.0.0.1:$port"
            Log.d(TAG, "Starting self-test connection to $testUrl")
            var connection: HttpURLConnection? = null
            try {
                val url = URL(testUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 // milliseconds
                connection.readTimeout = 5000 // milliseconds

                Log.d(TAG, "Self-test: Attempting to connect to localhost:$port...")
                val responseCode = connection.getResponseCode()
                Log.d(TAG, "Self-test: Response code $responseCode")

                // Read the response body to check for the directory listing structure
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Self-test: Response body snippet: '${response.take(200)}...'")

                // The self-test now checks for the directory listing of the *selected* directory.
                // The HTML structure for the listing should still include "<title>Index of /</title>"
                // for the root of the shared tree.
                if (responseCode == HttpURLConnection.HTTP_OK && response.contains("<title>Index of /</title>") && response.contains("<h1>Index of /</h1>")) {
                    Log.d(TAG, "Self-test PASSED: Server responded with a directory listing for the shared root.")
                } else {
                    Log.e(TAG, "Self-test FAILED: Unexpected response status ($responseCode) or body content for shared root.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Self-test FAILED: Could not connect to $testUrl", e)
            } finally {
                connection?.disconnect()
                Log.d(TAG, "Self-test: Connection disconnected.")
            }
        }
    }
}

// Composable remains the same, only passes/receives the name
@Composable
fun ServerStatus(
    operationalState: String,
    networkStatus: String,
    port: Int,
    sharedDirectoryName: String, // The user-friendly name of the shared directory for display
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectDirectoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mainStatusText = when (operationalState) {
        "Running" -> {
             if (networkStatus.contains("Wi-Fi not connected")) {
                  "Server is running on port $port.\n$networkStatus"
             }
             else {
                  "Server is running.\nAccess from browser at:\nhttp://$networkStatus:$port"
             }
        }
        else -> networkStatus
    }

    // Determine button enabled states
    // Can start if stopped or failed AND sharedDirectoryName is not one of the sentinel error/not selected states
    val canStartServer = sharedDirectoryName != "Not Selected" &&
                           sharedDirectoryName != "Permission Failed" &&
                           sharedDirectoryName != "Error Processing"

    val isStartEnabled = (operationalState == "Stopped" || operationalState.startsWith("Failed")) && canStartServer
    val isStopEnabled = operationalState == "Running" || operationalState == "Starting"
    val isSelectDirectoryEnabled = true


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Project Noodle Server",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(16.dp))

        // Display Server Status
        Text(
            text = "Server Status:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Text(
            text = mainStatusText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onBackground
        )

         // Show Wi-Fi tip only when successfully running and showing an IP
        if (operationalState == "Running" && !networkStatus.contains("Wi-Fi not connected") && !networkStatus.startsWith("Server is")) {
            Text(
                text = "(Requires device and browser on same Wi-Fi network)",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

         Spacer(Modifier.height(24.dp))

         // Display Shared Directory Name
         Text(
             text = "Shared Directory:",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
         )
         Text(
             text = sharedDirectoryName,
             style = MaterialTheme.typography.bodyMedium,
             textAlign = TextAlign.Center,
             modifier = Modifier.padding(top = 4.dp),
             color = MaterialTheme.colorScheme.onBackground
         )

        Spacer(Modifier.height(24.dp))

        // Buttons
        Button(
            onClick = onSelectDirectoryClick,
            enabled = isSelectDirectoryEnabled
        ) {
            Text("Select Directory")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onStartClick,
            enabled = isStartEnabled
        ) {
            Text("Start Server")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onStopClick,
            enabled = isStopEnabled
        ) {
            Text("Stop Server")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ServerStatusPreview() {
    ProjectNoodleTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
             Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                ServerStatus(
                    operationalState = "Running",
                    networkStatus = "192.168.1.100",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Server is stopped.",
                    port = -1,
                    sharedDirectoryName = "Not Selected",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Directory selected: My Shared Folder",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {}, // Start should be enabled here
                    onStopClick = {}, // Stop should be disabled
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Starting",
                    networkStatus = "Looking for port...",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder", // Name is known during start
                    onStartClick = {}, // Start should be disabled
                    onStopClick = {}, // Stop should be enabled
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Failed: No Port Found",
                    networkStatus = "Could not find an available port.",
                    port = -1,
                     sharedDirectoryName = "My Shared Folder",
                    onStartClick = {}, // Start enabled to retry
                    onStopClick = {}, // Stop disabled
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Running", // Simulate server running without Wi-Fi
                    networkStatus = "Wi-Fi not connected.",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {}, // Start disabled
                    onStopClick = {}, // Stop enabled
                    onSelectDirectoryClick = {}
                )
             }
        }
    }
}
