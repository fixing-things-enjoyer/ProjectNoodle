package com.example.projectnoodle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme

// Constants are defined in WebServerService.kt and are accessible here due to package visibility.


private const val TAG = "ProjectNoodleActivity"

class MainActivity : ComponentActivity() {

    // These state variables will now be updated by the BroadcastReceiver
    private var serverOperationalState by mutableStateOf("Stopped")
    private var networkStatusMessage by mutableStateOf("Waiting for server...")
    private var serverPort by mutableIntStateOf(-1)
     private var serverIpAddress by mutableStateOf<String?>(null)

    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")

    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>
    // FIX: Declare LocalBroadcastManager variable again
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var serverStatusReceiver: BroadcastReceiver


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        // FIX: Initialize LocalBroadcastManager
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        serverStatusReceiver = createServerStatusReceiver()


        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            handleDirectoryPicked(uri)
        }

        // TODO: Load persisted sharedDirectoryUri here

        serverOperationalState = "Stopped"
        networkStatusMessage = "Server is stopped."
        serverPort = -1
        serverIpAddress = null
        sharedDirectoryNameDisplay = "Not Selected"


        setContent {
            // FIX: ProjectNoodleTheme should now be resolved with the import
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

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart - Registering receiver")
        val filter = IntentFilter(ACTION_SERVER_STATUS_UPDATE)
        // FIX: Use LocalBroadcastManager to register receiver
        localBroadcastManager.registerReceiver(serverStatusReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop - Unregistering receiver")
        // FIX: Use LocalBroadcastManager to unregister receiver
        localBroadcastManager.unregisterReceiver(serverStatusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy finished.")
    }

    // --- Broadcast Receiver to Update UI ---

    private fun createServerStatusReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SERVER_STATUS_UPDATE) {
                    Log.d(TAG, "MainActivity: Received status update broadcast.")
                    val isRunning = intent.getBooleanExtra(EXTRA_SERVER_IS_RUNNING, false)
                    val opState = intent.getStringExtra(EXTRA_SERVER_OPERATIONAL_STATE) ?: "Unknown"
                    val statusMsg = intent.getStringExtra(EXTRA_SERVER_STATUS_MESSAGE) ?: "Unknown Status"
                    val ipAddress = intent.getStringExtra(EXTRA_SERVER_IP)
                    val port = intent.getIntExtra(EXTRA_SERVER_PORT, -1)
                    val dirName = intent.getStringExtra(EXTRA_SHARED_DIRECTORY_NAME) ?: "Not Selected"

                    runOnUiThread {
                        serverOperationalState = opState
                        networkStatusMessage = statusMsg
                        serverIpAddress = ipAddress
                        serverPort = port
                        sharedDirectoryNameDisplay = dirName
                        Log.d(TAG, "UI State Updated by Broadcast: State=$opState, Running=${isRunning}, IP=$ipAddress, Port=$port, Dir=$dirName")
                    }
                }
            }
        }
    }


    // --- Server Lifecycle Management (Delegated to Service) ---

    private fun startServer() {
         if (sharedDirectoryUri == null) {
             Log.w(TAG, "startServer (Activity): Cannot start server, no directory selected.")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Please select a directory to share."
             return
         }

        Log.d(TAG, "startServer (Activity): Calling startService for WebServerService.")

        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_START_SERVER
            putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopServer() {
        Log.d(TAG, "stopServer (Activity): Calling startService with STOP_SERVER action.")

        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }

        val stopped = startService(serviceIntent)
        Log.d(TAG, "startService with STOP_SERVER action returned: $stopped")
    }


    // --- Directory Picker Handling ---

    private fun launchDirectoryPicker() {
        Log.d(TAG, "Launching directory picker... Current URI: ${sharedDirectoryUri.toString()}")
        openDirectoryPickerLauncher.launch(sharedDirectoryUri)
    }

    private fun handleDirectoryPicked(uri: Uri?) {
        if (uri == null) {
            Log.d(TAG, "Directory picker cancelled or no URI returned.")
            if (sharedDirectoryUri == null) {
                 sharedDirectoryNameDisplay = "Not Selected"
                 if (serverOperationalState == "Stopped" && networkStatusMessage == "Server is stopped.") {
                      networkStatusMessage = "Server is stopped.\nPlease select a directory."
                 }
            }
            return
        }

        Log.d(TAG, "Directory picked! URI: $uri")

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "Successfully took persistable URI permissions (Read/Write) for: $uri")

            val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)
            val displayName = documentFile?.name ?: uri.lastPathSegment?.let {
                 try { Uri.decode(it) } catch (e: Exception) { it }
            } ?: "Unknown Directory"

            sharedDirectoryUri = uri
            sharedDirectoryNameDisplay = displayName

            Log.d(TAG, "Shared directory state updated in Activity: URI = $uri, Display Name = $displayName")

             // Manually update UI status message for immediate feedback.
             // This message will be overwritten by the Service broadcast when the server state changes.
             networkStatusMessage = "Directory selected: $sharedDirectoryNameDisplay"
             // Ensure the operational state is "Stopped" or ready to start after selection
             if (serverOperationalState.startsWith("Failed") || serverOperationalState == "Error Stopping") {
                 serverOperationalState = "Stopped"
             } else if (serverOperationalState == "Running") {
                  // If server was running, manually update status to suggest restart needed
                 networkStatusMessage = "Directory updated to: $sharedDirectoryNameDisplay\n(Stop/Start server to apply)"
             } else if (serverOperationalState == "Stopped") {
                  // If server was stopped, update status to show selected dir
                  networkStatusMessage = "Directory selected: $sharedDirectoryNameDisplay\nServer is stopped."
             }

            // TODO: Persist this URI in SharedPreferences for future app launches
            // TODO: In a later step, *automatically* restart the server with the new URI if it was running when the directory was selected.

        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable URI permission for $uri. Or URI is invalid.", e)
            sharedDirectoryUri = null
            sharedDirectoryNameDisplay = "Permission Failed"
             networkStatusMessage = "Failed to get necessary directory permissions."
             serverOperationalState = "Stopped"
        } catch (e: Exception) {
            Log.e(TAG, "Error handling picked directory URI: $uri", e)
            sharedDirectoryUri = null
            sharedDirectoryNameDisplay = "Error Processing"
             networkStatusMessage = "Error processing selected directory."
             serverOperationalState = "Stopped"
        }
    }
}

@Composable
fun ServerStatus(
    operationalState: String,
    networkStatus: String,
    port: Int,
    sharedDirectoryName: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectDirectoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canStartServer = sharedDirectoryName != "Not Selected" &&
                           sharedDirectoryName != "Permission Failed" &&
                           sharedDirectoryName != "Error Processing"

    val isStartEnabled = (operationalState == "Stopped" || operationalState.startsWith("Failed")) && canStartServer
    val isStopEnabled = operationalState == "Running" || operationalState == "Starting" || operationalState == "Stopping" || operationalState == "Error Stopping"
    val isSelectDirectoryEnabled = operationalState != "Starting" && operationalState != "Stopping"


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

        Text(
            text = "Server Status:",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Text(
            text = networkStatus,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onBackground
        )

        if (operationalState == "Running" && networkStatus.contains("http://")) {
            Text(
                text = "(Requires device and browser on same Wi-Fi network)",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

         Spacer(Modifier.height(24.dp))

         Text(
             text = "Shared Directory:",
             style = MaterialTheme.typography.bodySmall,
             textAlign = TextAlign.Center,
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
                    networkStatus = "Dir: My Shared Folder\nServer running:\nhttp://192.168.1.100:54321",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Dir: My Shared Folder\nServer Stopped.",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Server is stopped.\nPlease select a directory.",
                    port = -1,
                    sharedDirectoryName = "Not Selected",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Starting",
                    networkStatus = "Starting server...",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Failed: No Port Found",
                    networkStatus = "Failed to start: No port available.",
                    port = -1,
                     sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                 ServerStatus(
                    operationalState = "Running",
                    networkStatus = "Dir: My Shared Folder\nServer running on port 54321\n(No Wi-Fi IP)",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Directory selected: New Folder\n(Stop/Start server to apply)",
                    port = -1,
                    sharedDirectoryName = "New Folder",
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {}
                )
             }
        }
    }
}
