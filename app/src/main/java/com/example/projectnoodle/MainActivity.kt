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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch // Import Switch
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
import android.provider.Settings // NEW: For MANAGE_EXTERNAL_STORAGE intent
import android.os.Environment // NEW: For Environment.isExternalStorageManager()
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager // Import PreferenceManager


// Constants are defined in WebServerService.kt and are accessible here due to package visibility.


private const val TAG = "ProjectNoodleActivity"
// REMOVED: private const val PREF_REQUIRE_APPROVAL = "pref_require_approval" // This is now in WebServerService.kt
private const val PREF_SHARED_DIRECTORY_URI = "pref_shared_directory_uri"


class MainActivity : ComponentActivity() {

    // These state variables will now be updated by the BroadcastReceiver
    private var serverOperationalState by mutableStateOf("Stopped")
    private var networkStatusMessage by mutableStateOf("Waiting for server...")
    private var serverPort by mutableIntStateOf(-1)
    private var serverIpAddress by mutableStateOf<String?>(null)

    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")

    private var requireApprovalEnabled by mutableStateOf(false)

    // NEW: State for MANAGE_EXTERNAL_STORAGE permission
    private var hasManageAllFilesAccess by mutableStateOf(false)


    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var requestNotificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var serverStatusReceiver: BroadcastReceiver
    // NEW: Launcher for MANAGE_EXTERNAL_STORAGE permission
    private lateinit var requestManageAllFilesAccessLauncher: ActivityResultLauncher<Intent>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        serverStatusReceiver = createServerStatusReceiver()

        // 1. & 2. Register the permission launcher
        requestNotificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                // Permission granted, now attempt to start the server again
                startServerAfterPermissionGranted()
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                // Permission denied, update UI status to reflect inability
                handleNotificationPermissionDenied()
            }
        }

        // NEW: Register launcher for MANAGE_EXTERNAL_STORAGE permission
        requestManageAllFilesAccessLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // This callback runs when the user returns from the settings screen.
            // We need to re-check the permission status.
            Log.d(TAG, "Returned from MANAGE_EXTERNAL_STORAGE settings screen.")
            updateManageAllFilesAccessStatus()
            // Optionally, if the server was trying to start and failed due to this, retry:
            // startServer() // Uncomment this if you want to automatically retry starting the server
        }


        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            handleDirectoryPicked(uri)
        }

        loadPreferences() // This also loads current 'requireApprovalEnabled'
        updateManageAllFilesAccessStatus() // NEW: Check initial status of MANAGE_EXTERNAL_STORAGE


        // Initial UI state setup (can be default, will be updated by loadPreferences)
        serverPort = -1 // Always reset port and IP on Activity start
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
                        requireApprovalEnabled = requireApprovalEnabled, // Pass the state
                        hasManageAllFilesAccess = hasManageAllFilesAccess, // NEW: Pass the state
                        onStartClick = { startServer() },
                        onStopClick = { stopServer() },
                        onSelectDirectoryClick = { launchDirectoryPicker() },
                        onRequestManageAllFilesAccessClick = { requestManageAllFilesAccess() }, // NEW: Handle click
                        onApprovalToggleChange = { enabled -> // Handle toggle changes
                            requireApprovalEnabled = enabled
                            savePreferences(enabled, sharedDirectoryUri) // Save both settings
                             // Server needs to restart to apply this setting
                             if (serverOperationalState == "Running" || serverOperationalState == "Starting" || serverOperationalState == "Stopping") {
                                 networkStatusMessage = "Setting saved.\nStop and restart server to apply."
                                 sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             } else {
                                // If server is stopped, and toggle changes, update UI immediately to reflect new preference
                                networkStatusMessage = "Server is stopped.\nApproval ${if (enabled) "Required" else "Not Required"}"
                                sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             }
                        }
                    )
                }
            }
        }
        Log.d(TAG, "MainActivity onCreate finished. Loaded URI: $sharedDirectoryUri, Approval: $requireApprovalEnabled, AllFilesAccess: $hasManageAllFilesAccess")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart - Registering receiver")
        val filter = IntentFilter(ACTION_SERVER_STATUS_UPDATE)
        localBroadcastManager.registerReceiver(serverStatusReceiver, filter)
        // It's good practice to re-check MANAGE_EXTERNAL_STORAGE onStart/onResume
        // because the user might have changed it from system settings.
        updateManageAllFilesAccessStatus() // NEW: Re-check on app foreground
    }

     override fun onResume() {
         super.onResume()
          Log.d(TAG, "MainActivity onResume - Sending status query to service.")
          // Re-check MANAGE_EXTERNAL_STORAGE status here as well, in case user changed it in settings
          updateManageAllFilesAccessStatus()

          val queryIntent = Intent(this, WebServerService::class.java).apply {
               action = ACTION_QUERY_STATUS // Define this action
               putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
               // NEW: Also pass the status of MANAGE_EXTERNAL_STORAGE
               putExtra(EXTRA_HAS_ALL_FILES_ACCESS, hasManageAllFilesAccess)
          }
          startService(queryIntent) // Send the query action
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

    // --- Preference Handling ---
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
                 null // Invalidate URI if parsing fails
             }
        }
        Log.d(TAG, "Loaded preferences: sharedDirectoryUri = $sharedDirectoryUri (String: $uriString)")

         updateSharedDirectoryDisplayName()

         networkStatusMessage = "Loading server status..."
         serverOperationalState = "Unknown" // Set a temporary state
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
             // NEW: Handle special case for root external storage URI when MANAGE_EXTERNAL_STORAGE is granted
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasManageAllFilesAccess && uri.toString() == Environment.getExternalStorageDirectory().toURI().toString()) {
                 return@let "Internal Storage (All Files Access)"
             }
             try {
                 val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)
                 documentFile?.name ?: uri.lastPathSegment?.let {
                     try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Unknown Directory"
             } catch (e: Exception) {
                 Log.e(TAG, "Error getting display name for URI $uri", e)
                 uri.lastPathSegment?.let {
                      try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Unknown Directory" // Fallback to last path segment
             }
         } ?: "Not Selected"
         sharedDirectoryNameDisplay = derivedName
         Log.d(TAG, "Updated Activity's internal sharedDirectoryNameDisplay to: $sharedDirectoryNameDisplay (derived from URI)")
     }


    // --- Broadcast Receiver to Update UI ---

    private fun createServerStatusReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SERVER_STATUS_UPDATE) {
                    Log.d(TAG, "MainActivity: Received status update broadcast.")
                    val isRunning = intent.getBooleanExtra(EXTRA_SERVER_IS_RUNNING, false) // isRunning is redundant if we have opState, but keep for now
                    val opState = intent.getStringExtra(EXTRA_SERVER_OPERATIONAL_STATE) ?: "Unknown"
                    val statusMsg = intent.getStringExtra(EXTRA_SERVER_STATUS_MESSAGE) ?: "Unknown Status"
                    val ipAddress = intent.getStringExtra(EXTRA_SERVER_IP)
                    val port = intent.getIntExtra(EXTRA_SERVER_PORT, -1)
                    val dirNameFromService = intent.getStringExtra(EXTRA_SHARED_DIRECTORY_NAME) ?: "Not Selected"
                    // IMPORTANT: The requireApprovalEnabled state is NOT broadcast by the service.
                    // The Activity's state variable for this is updated by loadPreferences and onApprovalToggleChange.
                    // The statusMsg from service *includes* the approval status, so we display that.
                    // The toggle button's internal state in Compose is directly tied to Activity's requireApprovalEnabled.


                    runOnUiThread {
                        serverOperationalState = opState
                        networkStatusMessage = statusMsg
                        serverIpAddress = ipAddress
                        serverPort = port
                        sharedDirectoryNameDisplay = dirNameFromService // Always show the name from service's perspective


                        Log.d(TAG, "UI State Updated by Broadcast: State=$opState, Running=${isRunning}, IP=$ipAddress, Port=$port, Dir=$sharedDirectoryNameDisplay (from service), My Activity URI State is $sharedDirectoryUri")

                         // The Activity's sharedDirectoryUri state is managed by load/save preferences.
                         // The Service broadcasts the name based on its internal URI.
                         // This keeps the UI display consistent with the service's perspective,
                         // while the Activity's URI state ensures persistence and correct input for `startServer`.

                    }
                }
            }
        }
    }


    // --- Server Lifecycle Management (Delegated to Service) ---

    private fun startServer() {
         // NEW: Check for MANAGE_EXTERNAL_STORAGE permission status on Android 11+
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
             // If MANAGE_EXTERNAL_STORAGE is not granted on API 30+, and no directory is selected yet,
             // then we should guide the user to grant this permission or select a SAF directory.
             if (sharedDirectoryUri == null) {
                 Log.w(TAG, "startServer (Activity): MANAGE_EXTERNAL_STORAGE not granted, and no SAF directory selected. Prompting user to select directory or grant permission.")
                 serverOperationalState = "Stopped"
                 networkStatusMessage = "Please select a directory or grant 'All Files Access'."
                 sendStatusUpdateForUI("Stopped", "Please select a directory or grant 'All Files Access'.")
                 return
             }
         }

         // If sharedDirectoryUri is still null after permission/SAF checks (e.g., on older Android or if SAF failed)
         if (sharedDirectoryUri == null) {
             Log.w(TAG, "startServer (Activity): Cannot start server, no directory selected (after all checks).")
             serverOperationalState = "Stopped"
             networkStatusMessage = "Please select a directory to share."
             sendStatusUpdateForUI("Stopped", "Please select a directory to share.")
             return
         }


         // 3. & 4. Check and request POST_NOTIFICATIONS permission on API 33+
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (ContextCompat.checkSelfPermission(
                     this,
                     Manifest.permission.POST_NOTIFICATIONS
                 ) != PackageManager.PERMISSION_GRANTED
             ) {
                 Log.d(TAG, "POST_NOTIFICATIONS permission not granted, requesting...")
                 requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                 // Optimistically update UI state while waiting for permission result
                 serverOperationalState = "Requesting Permission"
                 networkStatusMessage = "Requesting notification permission..."
                 sendStatusUpdateForUI("Requesting Permission", "Requesting notification permission...")

                 return // Stop here, the actual start will happen in the launcher callback if granted
             } else {
                 Log.d(TAG, "POST_NOTIFICATIONS permission already granted.")
                 // Permission already granted, proceed directly
                 startServerAfterPermissionGranted()
             }
         } else {
             // On older APIs, POST_NOTIFICATIONS is not a runtime permission issue for startForeground
             Log.d(TAG, "Running on API < 33, POST_NOTIFICATIONS not a runtime permission issue for startForeground.")
             startServerAfterPermissionGranted()
         }
    }

    // New helper function to actually start the service *after* permission is confirmed
    private fun startServerAfterPermissionGranted() {
         // Re-check directory in case state changed while permission dialog was open
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
            putExtra(EXTRA_HAS_ALL_FILES_ACCESS, hasManageAllFilesAccess) // NEW: Pass manage all files access status to service
        }

        // The service will broadcast updates about its actual state (Starting, Running, etc.)
        // Optimistically update UI state while waiting for service broadcast
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
        // Update UI state to reflect the denial
    }


    private fun stopServer() {
        Log.d(TAG, "stopServer (Activity): Calling startService with STOP_SERVER action.")

        // Optimistically update UI state while waiting for service broadcast
        serverOperationalState = "Stopping"
        networkStatusMessage = "Stopping server..."
        sendStatusUpdateForUI("Stopping", "Stopping server...")


        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }

        val stopped = startService(serviceIntent)
        Log.d(TAG, "startService with STOP_SERVER action returned: $stopped")
         // The service will send the final "Stopped" broadcast after it finishes stopping
    }

     // This helper uses the *Activity's current state variables* for IP, Port, Dir Name, etc.
     // Note: The Directory Name sent here will be derived by the Service from its potentially adopted URI.
     private fun sendStatusUpdateForUI(opState: String, statusMsg: String) {
         val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
             putExtra(EXTRA_SERVER_IS_RUNNING, opState == "Running" || opState == "Starting" || opState == "Stopping")
             putExtra(EXTRA_SERVER_OPERATIONAL_STATE, opState)
             putExtra(EXTRA_SERVER_STATUS_MESSAGE, statusMsg)
             putExtra(EXTRA_SERVER_IP, serverIpAddress) // Use current Activity IP state
             putExtra(EXTRA_SERVER_PORT, serverPort) // Use current Activity Port state
             putExtra(EXTRA_SHARED_DIRECTORY_NAME, sharedDirectoryNameDisplay) // Still pass Activity's display name, but Service broadcast is preferred source
         }
         localBroadcastManager.sendBroadcast(statusIntent)
         Log.d(TAG, "Sent local UI status update broadcast. State: $opState, Msg: '$statusMsg'")
     }


    // --- Directory Picker Handling ---

    private fun launchDirectoryPicker() {
        Log.d(TAG, "Launching directory picker... Current URI: ${sharedDirectoryUri.toString()}")
        // Pass the current URI so the picker potentially opens to the last location
        // The picker itself might offer more options if "All Files Access" is granted.
        openDirectoryPickerLauncher.launch(sharedDirectoryUri)
    }

    private fun handleDirectoryPicked(uri: Uri?) {
        if (uri == null) {
            Log.d(TAG, "Directory picker cancelled or no URI returned.")
            // No change to sharedDirectoryUri or sharedDirectoryNameDisplay if cancelled.
            // Status message should probably reflect the current server state + dir selection cancelled.
            val currentMsg = when (serverOperationalState) {
                 "Running" -> "Directory selection cancelled.\nServer still running with:\n$sharedDirectoryNameDisplay"
                 "Starting" -> "Directory selection cancelled.\nStarting server with:\n$sharedDirectoryNameDisplay..."
                 "Stopping" -> "Directory selection cancelled.\nStopping server..."
                  "Requesting Permission" -> "Directory selection cancelled.\nRequesting notification permission with:\n$sharedDirectoryNameDisplay..."
                 else -> { // Stopped or Failed states
                      if (sharedDirectoryUri != null) "Directory selected:\n$sharedDirectoryNameDisplay\nServer is stopped."
                      else "Server is stopped.\nPlease select a directory."
                 }
            }
             sendStatusUpdateForUI(serverOperationalState, currentMsg)

            return
        }

        Log.d(TAG, "Directory picked! URI: $uri")

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION // We only need read permission for sharing files
        // We might need write permission for upload/delete/rename later, but let's request read for now.
        // Add write permission flag back for file management features if needed later:
        // val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION


        try {
            // Try to take persistable URI permission *only if it's a content:// URI*.
            // file:/// URIs (like from Environment.getExternalStorageDirectory()) don't need/support this.
            if (uri.scheme == "content") {
                try {
                     contentResolver.takePersistableUriPermission(uri, flags)
                      Log.d(TAG, "Successfully took persistable URI permissions for: $uri")
                 } catch (e: SecurityException) {
                     Log.w(TAG, "Failed to take persistable URI permission for $uri. App might lose access after reboot.", e)
                     // This is a warning, not a fatal error for the current session.
                 }
            }


            sharedDirectoryUri = uri // Update the Activity's state variable
            updateSharedDirectoryDisplayName() // Update the display name based on the new URI for initial display before service broadcast


            savePreferences(requireApprovalEnabled, sharedDirectoryUri)


            Log.d(TAG, "Shared directory state updated in Activity: URI = $sharedDirectoryUri, Display Name = $sharedDirectoryNameDisplay")

             // Update UI status message for immediate feedback.
             // The Service will broadcast the canonical name shortly, but this gives immediate feedback.
             val newMsg = when (serverOperationalState) {
                 "Running" -> "Directory updated to: $sharedDirectoryNameDisplay\n(Stop/Start server to apply)"
                 "Starting" -> "Directory updated to: $sharedDirectoryNameDisplay\nServer is starting..."
                 "Stopping" -> "Directory updated to: $sharedDirectoryNameDisplay\nServer is stopping..."
                 "Requesting Permission" -> "Directory selected: $sharedDirectoryNameDisplay\nRequesting notification permission..."
                 else -> { // Handles Stopped or Failed states
                      "Directory selected: $sharedDirectoryNameDisplay\nServer is stopped."
                 }
             }
             networkStatusMessage = newMsg // Update the state variable directly
             sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)


            // TODO: In a later step, *automatically* restart the server with the new URI if it was running when the directory was selected.

        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to get DocumentFile from URI $uri or access content resolver.", e)
            sharedDirectoryUri = null // Clear the URI state
            updateSharedDirectoryDisplayName() // Update display name to "Not Selected"
             networkStatusMessage = "Failed to get necessary directory permissions."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, null)
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling picked directory URI: $uri", e)
            sharedDirectoryUri = null // Clear the URI state
            updateSharedDirectoryDisplayName() // Update display name to "Error Processing"
             networkStatusMessage = "Error processing selected directory."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, null)
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        }
    }

    // NEW: Permission management for MANAGE_EXTERNAL_STORAGE
    private fun updateManageAllFilesAccessStatus() {
        hasManageAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For API < 30, MANAGE_EXTERNAL_STORAGE doesn't exist.
            // On these older APIs, READ_EXTERNAL_STORAGE/WRITE_EXTERNAL_STORAGE *would* grant broad access.
            // However, this app is designed to use SAF primarily if MANAGE_EXTERNAL_STORAGE is not present.
            // Setting this to false here ensures that if MANAGE_EXTERNAL_STORAGE isn't available,
            // the app still relies on SAF selection.
            false
        }
        Log.d(TAG, "Updated hasManageAllFilesAccess: $hasManageAllFilesAccess (API ${Build.VERSION.SDK_INT})")

        // If 'All Files Access' is granted AND no specific directory has been picked yet,
        // automatically default to the root of external storage.
        if (hasManageAllFilesAccess && sharedDirectoryUri == null) {
            val rootFile = Environment.getExternalStorageDirectory()
            val rootUri = Uri.fromFile(rootFile) // This creates a file:// URI
            sharedDirectoryUri = rootUri
            updateSharedDirectoryDisplayName() // Update display name for auto-selected root
            savePreferences(requireApprovalEnabled, sharedDirectoryUri)
            Log.d(TAG, "Auto-set sharedDirectoryUri to external storage root: $rootUri due to All Files Access.")
             // Update UI status to reflect the new default directory selection
            networkStatusMessage = "Shared directory set to: ${sharedDirectoryNameDisplay}\nServer is stopped. (All Files Access)"
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        }
    }


    private fun requestManageAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + packageName))
                requestManageAllFilesAccessLauncher.launch(intent)
                Log.d(TAG, "Launched intent to request MANAGE_EXTERNAL_STORAGE.")
            } else {
                Log.d(TAG, "MANAGE_EXTERNAL_STORAGE already granted.")
                 networkStatusMessage = "All Files Access already granted."
                 sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
            }
        } else {
            Log.d(TAG, "MANAGE_EXTERNAL_STORAGE is not applicable below API 30.")
             networkStatusMessage = "All Files Access not applicable on this Android version."
             sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
            // For older APIs, READ_EXTERNAL_STORAGE/WRITE_EXTERNAL_STORAGE might be needed.
            // This app relies on SAF for older APIs if MANAGE_EXTERNAL_STORAGE is not used.
        }
    }
}

@Composable
fun ServerStatus(
    operationalState: String,
    networkStatus: String,
    port: Int,
    sharedDirectoryName: String,
    requireApprovalEnabled: Boolean,
    hasManageAllFilesAccess: Boolean, // NEW: Receive the state
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectDirectoryClick: () -> Unit,
    onRequestManageAllFilesAccessClick: () -> Unit, // NEW: Handle click
    onApprovalToggleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // The start button should be enabled if the server is stopped/failed *AND* we have a directory URI
    // The `sharedDirectoryName` check is a proxy for `sharedDirectoryUri != null` and valid.
    val isDirectorySelectedAndValid = sharedDirectoryName != "Not Selected" &&
                           sharedDirectoryName != "Permission Failed" &&
                           sharedDirectoryName != "Error Processing"

    val isStartEnabled = (operationalState == "Stopped" || operationalState.startsWith("Failed")) &&
                         isDirectorySelectedAndValid


    val isStopEnabled = operationalState == "Running" || operationalState == "Starting" || operationalState == "Stopping" || operationalState == "Error Stopping"
    // Directory selection is disabled while server is actively starting/stopping
    val isSelectDirectoryEnabled = operationalState != "Starting" && operationalState != "Stopping"

     // Enable toggle only when server is definitively not running
     val isApprovalToggleEnabled = operationalState == "Stopped" || operationalState.startsWith("Failed")

    // NEW: Text for Manage All Files Access status
    val manageAllFilesAccessStatusText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (hasManageAllFilesAccess) "All Files Access: Granted" else "All Files Access: Not Granted"
    } else {
        "All Files Access: N/A (API < 30)"
    }


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

        // NEW: Display MANAGE_EXTERNAL_STORAGE status and button
        Text(
            text = manageAllFilesAccessStatusText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        // Show button to request permission only if not granted and on API 30+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageAllFilesAccess) {
            Button(
                onClick = onRequestManageAllFilesAccessClick,
                enabled = isSelectDirectoryEnabled // Enable if not starting/stopping
            ) {
                Text("Grant All Files Access")
            }
            Spacer(Modifier.height(8.dp))
        }


        Button(
            onClick = onSelectDirectoryClick,
            enabled = isSelectDirectoryEnabled // Now allows selecting specific dir even if All Files Access is granted
        ) {
            Text("Select Specific Folder")
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
                enabled = isApprovalToggleEnabled // Enable only when server is stopped or failed
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
                    requireApprovalEnabled = false, // Pass sample state
                    hasManageAllFilesAccess = true, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer Stopped.",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true, // Pass sample state
                    hasManageAllFilesAccess = false, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Server is stopped.\nPlease select a directory.\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "Not Selected",
                    requireApprovalEnabled = false, // Pass sample state
                    hasManageAllFilesAccess = false, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                 ServerStatus(
                    operationalState = "Starting",
                    networkStatus = "Starting server...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true, // Pass sample state
                    hasManageAllFilesAccess = true, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                 ServerStatus(
                    operationalState = "Requesting Permission",
                    networkStatus = "Requesting notification permission...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true, // Pass sample state
                    hasManageAllFilesAccess = true, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                 ServerStatus(
                    operationalState = "Failed: No Port Found",
                    networkStatus = "Failed to start: No port available.\nApproval Not Required",
                    port = -1,
                     sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = false, // Pass sample state
                    hasManageAllFilesAccess = false, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                 ServerStatus(
                    operationalState = "Running",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer running on port 54321\n(No Wi-Fi IP)",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true, // Pass sample state
                    hasManageAllFilesAccess = true, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Directory selected: New Folder\n(Stop/Start server to apply)\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "New Folder",
                    requireApprovalEnabled = false, // Pass sample state
                    hasManageAllFilesAccess = true, // Sample preview state
                    onStartClick = {},
                    onStopClick = {},
                    onSelectDirectoryClick = {},
                    onRequestManageAllFilesAccessClick = {}, // Pass dummy lambda
                    onApprovalToggleChange = {} // Pass dummy lambda
                )
             }
        }
    }
}
