// File: app/src/main/java/com/example/projectnoodle/MainActivity.kt
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import android.content.pm.PackageManager
import android.Manifest
import android.provider.Settings
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.File
import androidx.compose.ui.platform.LocalContext

private const val TAG = "ProjectNoodleActivity"
private const val PREF_SHARED_DIRECTORY_URI = "pref_shared_directory_uri"


class MainActivity : ComponentActivity() {

    private var serverOperationalState by mutableStateOf("Stopped")
    private var networkStatusMessage by mutableStateOf("Waiting for server...")
    private var serverPort by mutableIntStateOf(-1)
    private var serverIpAddress by mutableStateOf<String?>(null)

    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")

    private var requireApprovalEnabled by mutableStateOf(false)


    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var requestNotificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var serverStatusReceiver: BroadcastReceiver


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        serverStatusReceiver = createServerStatusReceiver() // FIX: Re-added this line

        requestNotificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                startServerAfterPermissionGranted()
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                handleNotificationPermissionDenied()
            }
        }

        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            handleDirectoryPicked(uri)
        }

        loadPreferences()

        serverPort = -1
        serverIpAddress = null


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
                        requireApprovalEnabled = requireApprovalEnabled,
                        onStartClick = { startServer() },
                        onStopClick = { stopServer() },
                        onSelectSpecificFolderClick = { launchDirectoryPicker() },
                        onApprovalToggleChange = { enabled ->
                            requireApprovalEnabled = enabled
                            savePreferences(enabled, sharedDirectoryUri)
                             if (serverOperationalState == "Running" || serverOperationalState == "Starting" || serverOperationalState == "Stopping") {
                                 networkStatusMessage = "Setting saved.\nStop and restart server to apply."
                                 sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             } else {
                                networkStatusMessage = "Server is stopped.\nApproval ${if (enabled) "Required" else "Not Required"}"
                                sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             }
                        }
                    )
                }
            }
        }
        Log.d(TAG, "MainActivity onCreate finished. Loaded URI: $sharedDirectoryUri, Approval: $requireApprovalEnabled")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart - Registering receiver")
        val filter = IntentFilter(ACTION_SERVER_STATUS_UPDATE)
        localBroadcastManager.registerReceiver(serverStatusReceiver, filter)
    }

     override fun onResume() {
          super.onResume()
           Log.d(TAG, "MainActivity onResume - Sending status query to service.")

           val queryIntent = Intent(this, WebServerService::class.java).apply {
                action = ACTION_QUERY_STATUS
                putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
           }
           startService(queryIntent)
     }


    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop - Unregistering receiver")
        localBroadcastManager.unregisterReceiver(serverStatusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy finished.")
    }

    private fun loadPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        requireApprovalEnabled = prefs.getBoolean(PREF_REQUIRE_APPROVAL, false)
        Log.d(TAG, "Loaded preferences: requireApprovalEnabled = $requireApprovalEnabled")

        val uriString = prefs.getString(PREF_SHARED_DIRECTORY_URI, null)
        sharedDirectoryUri = uriString?.let {
             try {
                 Uri.parse(it)
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing saved URI string: $it", e)
                 null
             }
        }
        Log.d(TAG, "Loaded preferences: sharedDirectoryUri = $sharedDirectoryUri (String: $uriString)")

         updateSharedDirectoryDisplayName()

         networkStatusMessage = "Loading server status..."
         serverOperationalState = "Unknown"
    }

    private fun savePreferences(requireApproval: Boolean, directoryUri: Uri?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        with(prefs.edit()) {
            putBoolean(PREF_REQUIRE_APPROVAL, requireApproval)
            putString(PREF_SHARED_DIRECTORY_URI, directoryUri?.toString())
            apply()
        }
        Log.d(TAG, "Saved preferences: requireApprovalEnabled = $requireApproval, sharedDirectoryUri = $directoryUri")
    }

     private fun updateSharedDirectoryDisplayName() {
         val derivedName = sharedDirectoryUri?.let { uri ->
             try {
                 // DocumentFile.fromTreeUri is designed for content:// URIs from SAF
                 val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)

                 documentFile?.name ?: uri.lastPathSegment?.let {
                     try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Unknown Directory"
             } catch (e: Exception) {
                 Log.e(TAG, "Error getting display name for URI $uri", e)
                 uri.lastPathSegment?.let {
                      try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Unknown Directory"
             }
         } ?: "Not Selected"
         sharedDirectoryNameDisplay = derivedName
         Log.d(TAG, "Updated Activity's internal sharedDirectoryNameDisplay to: $sharedDirectoryNameDisplay (derived from URI)")
     }


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
                    val dirNameFromService = intent.getStringExtra(EXTRA_SHARED_DIRECTORY_NAME) ?: "Not Selected"


                    runOnUiThread {
                        serverOperationalState = opState
                        networkStatusMessage = statusMsg
                        serverIpAddress = ipAddress
                        serverPort = port
                        sharedDirectoryNameDisplay = dirNameFromService


                        Log.d(TAG, "UI State Updated by Broadcast: State=$opState, Running=${isRunning}, IP=$ipAddress, Port=$port, Dir=$dirNameFromService (from service), My Activity URI State is $sharedDirectoryUri")

                    }
                }
            }
        }
    }


    private fun startServer() {
         if (sharedDirectoryUri == null) {
             // This check is now the primary entry point for "no directory selected"
             // as SAF picker is the only way to get a URI now.
             Log.w(TAG, "startServer (Activity): Cannot start server, no directory selected (after all checks).")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Please select a directory to share."
             sendStatusUpdateForUI("Stopped", "Please select a directory to share.")
             return
         }


         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (ContextCompat.checkSelfPermission(
                     this,
                     Manifest.permission.POST_NOTIFICATIONS
                 ) != PackageManager.PERMISSION_GRANTED
             ) {
                 Log.d(TAG, "POST_NOTIFICATIONS permission not granted, requesting...")
                 requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                 serverOperationalState = "Requesting Permission"
                 networkStatusMessage = "Requesting notification permission..."
                 sendStatusUpdateForUI("Requesting Permission", "Requesting notification permission...")

                 return
             } else {
                 Log.d(TAG, "POST_NOTIFICATIONS permission already granted.")
                 startServerAfterPermissionGranted()
             }
         } else {
             Log.d(TAG, "Running on API < 33, POST_NOTIFICATIONS not a runtime permission issue for startForeground.")
             startServerAfterPermissionGranted()
         }
    }

    private fun startServerAfterPermissionGranted() {
         if (sharedDirectoryUri == null) {
             Log.w(TAG, "startServerAfterPermissionGranted (Activity): Cannot start server, no directory selected.")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Please select a directory to share."
             sendStatusUpdateForUI("Stopped", "Please select a directory to share.")
             return
         }

        Log.d(TAG, "startServerAfterPermissionGranted (Activity): Calling startService for WebServerService with URI: $sharedDirectoryUri.")

        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_START_SERVER
            putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
            putExtra(EXTRA_REQUIRE_APPROVAL, requireApprovalEnabled)
        }

        serverOperationalState = "Starting"
        networkStatusMessage = "Starting server..."
        sendStatusUpdateForUI("Starting", "Starting server...")


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun handleNotificationPermissionDenied() {
        serverOperationalState = "Stopped"
        networkStatusMessage = "Notification permission denied.\nServer cannot run in foreground to stay active."
        Log.w(TAG, "Notification permission denied. Server start blocked.")
        sendStatusUpdateForUI("Stopped", "Notification permission denied.\nServer cannot run in foreground to stay active.")
    }


    private fun stopServer() {
        Log.d(TAG, "stopServer (Activity): Calling startService with STOP_SERVER action.")

        serverOperationalState = "Stopping"
        networkStatusMessage = "Stopping server..."
        sendStatusUpdateForUI("Stopping", "Stopping server...")


        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }

        val stopped = startService(serviceIntent)
        Log.d(TAG, "startService with STOP_SERVER action returned: $stopped")
    }

     private fun sendStatusUpdateForUI(opState: String, statusMsg: String) {
         val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
             putExtra(EXTRA_SERVER_IS_RUNNING, opState == "Running" || opState == "Starting" || opState == "Stopping")
             putExtra(EXTRA_SERVER_OPERATIONAL_STATE, opState)
             putExtra(EXTRA_SERVER_STATUS_MESSAGE, statusMsg)
             putExtra(EXTRA_SERVER_IP, serverIpAddress)
             putExtra(EXTRA_SERVER_PORT, serverPort)
             putExtra(EXTRA_SHARED_DIRECTORY_NAME, sharedDirectoryNameDisplay)
         }
         localBroadcastManager.sendBroadcast(statusIntent)
         Log.d(TAG, "Sent local UI status update broadcast. State: $opState, Msg: '$statusMsg'")
     }


    private fun launchDirectoryPicker() {
        Log.d(TAG, "Launching directory picker... Current URI: ${sharedDirectoryUri.toString()}")
        openDirectoryPickerLauncher.launch(sharedDirectoryUri)
    }

    private fun handleDirectoryPicked(uri: Uri?) {
        if (uri == null) {
            Log.d(TAG, "Directory picker cancelled or no URI returned.")
            val currentMsg = when (serverOperationalState) {
                 "Running" -> "Directory selection cancelled.\nServer still running with:\n$sharedDirectoryNameDisplay"
                 "Starting" -> "Directory selection cancelled.\nStarting server with:\n$sharedDirectoryNameDisplay..."
                 "Stopping" -> "Directory selection cancelled.\nStopping server..."
                  "Requesting Permission" -> "Directory selection cancelled.\nRequesting notification permission with:\n$sharedDirectoryNameDisplay..."
                 else -> {
                      if (sharedDirectoryUri != null) "Directory selected:\n$sharedDirectoryNameDisplay\nServer is stopped."
                      else "Server is stopped.\nPlease select a directory."
                 }
            }
             sendStatusUpdateForUI(serverOperationalState, currentMsg)

            return
        }

        Log.d(TAG, "Directory picked! URI: $uri")

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION


        try { // Always take persistable URI permission for SAF
            contentResolver.takePersistableUriPermission(uri, flags)


            sharedDirectoryUri = uri
            updateSharedDirectoryDisplayName()


            savePreferences(requireApprovalEnabled, sharedDirectoryUri)


            Log.d(TAG, "Shared directory state updated in Activity: URI = $sharedDirectoryUri, Display Name = $sharedDirectoryNameDisplay")

             val newMsg = when (serverOperationalState) {
                 "Running" -> "Directory updated to: $sharedDirectoryNameDisplay\n(Stop/Start server to apply)"
                 "Starting" -> "Directory updated to: $sharedDirectoryNameDisplay\nServer is starting..."
                 "Stopping" -> "Directory updated to: $sharedDirectoryNameDisplay\nServer is stopping..."
                 "Requesting Permission" -> "Directory selected: $sharedDirectoryNameDisplay\nRequesting notification permission..."
                 else -> {
                      "Directory selected: $sharedDirectoryNameDisplay\nServer is stopped."
                 }
             }
             networkStatusMessage = newMsg
             sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)


        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to get DocumentFile from URI $uri or access content resolver.", e)
            // Clear sharedDirectoryUri if permission fails
            sharedDirectoryUri = null
             networkStatusMessage = "Failed to get necessary directory permissions."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, null)
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected directory URI: $uri", e)
            sharedDirectoryUri = null
             networkStatusMessage = "Error processing selected directory."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, null)
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        }
    }

    private fun requestManageAllFilesAccess() {
        // This method is no longer needed as MANAGE_EXTERNAL_STORAGE is removed.
        // Keeping it as a dummy function to avoid compile errors for now, will remove its call sites.
        Log.d(TAG, "requestManageAllFilesAccess: This functionality has been removed.")
        networkStatusMessage = "Direct file system access (All Files Access) is no longer supported for Play Store compatibility."
        sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
    }

    // This function is no longer needed as "Common Public Directories" are tied to MANAGE_EXTERNAL_STORAGE.
    @Composable
    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
}

@Composable
fun ServerStatus(
    operationalState: String,
    networkStatus: String,
    port: Int,
    sharedDirectoryName: String,
    requireApprovalEnabled: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectSpecificFolderClick: () -> Unit,
    onApprovalToggleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDirectorySelectedAndValid = sharedDirectoryName != "Not Selected" &&
                           sharedDirectoryName != "Permission Failed" &&
                           sharedDirectoryName != "Error Processing"

    val isStartEnabled = (operationalState == "Stopped" || operationalState.startsWith("Failed")) &&
                         isDirectorySelectedAndValid


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

        Spacer(Modifier.height(16.dp))

        // Removed buttons and text for "All Files Access" / "Common Folder" selection
        // as the SAF picker is now the only option.
        // The `onRequestManageAllFilesAccessClick` parameter will be removed later.
        // It's currently a placeholder.

        Button(
            onClick = onSelectSpecificFolderClick,
            enabled = isSelectDirectoryEnabled
        ) {
            Text("Select Folder")
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(0.8f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Require Connection Approval",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                checked = requireApprovalEnabled,
                onCheckedChange = onApprovalToggleChange,
                enabled = operationalState == "Stopped" || operationalState.startsWith("Failed") // isApprovalToggleEnabled
            )
        }


        Spacer(Modifier.height(16.dp))


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

// Removed CommonDirectoryPicker Composable entirely.

@Preview(showBackground = true)
@Composable
fun ServerStatusPreview() {
    ProjectNoodleTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
             Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                ServerStatus(
                    operationalState = "Running",
                    networkStatus = "Dir: My Shared Folder\nApproval Not Required\nServer running:\nhttp://192.168.1.100:54321",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer Stopped.",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Server is stopped.\nPlease select a directory.\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "Not Selected",
                    requireApprovalEnabled = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Starting",
                    networkStatus = "Starting server...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Requesting Permission",
                    networkStatus = "Requesting notification permission...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Failed: No Port Found",
                    networkStatus = "Failed to start: No port available.\nApproval Not Required",
                    port = -1,
                     sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Running",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer running on port 54321\n(No Wi-Fi IP)",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Directory selected: New Folder\n(Stop/Start server to apply)\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "New Folder",
                    requireApprovalEnabled = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    onApprovalToggleChange = {}
                )
             }
        }
    }
}
