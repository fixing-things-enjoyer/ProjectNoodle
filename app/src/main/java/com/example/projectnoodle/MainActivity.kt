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
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.File
import androidx.compose.ui.platform.LocalContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

private const val TAG = "ProjectNoodleActivity"
private const val PREF_SHARED_DIRECTORY_URI = "pref_shared_directory_uri"
// MOVED: PREF_REQUIRE_APPROVAL and PREF_USE_HTTPS are now public constants in WebServerService.kt

class MainActivity : ComponentActivity() {

    private var serverOperationalState by mutableStateOf("Stopped")
    private var networkStatusMessage by mutableStateOf("Waiting for server...")
    private var serverPort by mutableIntStateOf(-1)
    private var serverIpAddress by mutableStateOf<String?>(null)

    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")

    private var requireApprovalEnabled by mutableStateOf(false)
    private var useHttps by mutableStateOf(false) // NEW: HTTPS toggle state
    // REMOVED: private var hasManageAllFilesAccess by mutableStateOf(false)
    private var showCommonDirectoryPicker by mutableStateOf(false)


    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var requestNotificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var serverStatusReceiver: BroadcastReceiver
    // REMOVED: private lateinit var requestManageAllFilesAccessLauncher: ActivityResultLauncher<Intent>


    override fun onCreate(saved: Bundle?) {
        // NEW: Add Bouncy Castle provider at the highest priority as early as possible
        // This is done BEFORE super.onCreate() to ensure it's available for all security API calls.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1) // Insert at position 1
            Log.d(TAG, "Inserted Bouncy Castle security provider at position 1 in MainActivity.onCreate.")
        } else {
            Log.d(TAG, "Bouncy Castle security provider already present in MainActivity.onCreate.")
        }

        super.onCreate(saved)
        Log.d(TAG, "MainActivity onCreate")

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        serverStatusReceiver = createServerStatusReceiver()

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

        // REMOVED: requestManageAllFilesAccessLauncher registration

        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            handleDirectoryPicked(uri)
        }

        loadPreferences() // Load all preferences including the new HTTPS toggle
        // REMOVED: updateManageAllFilesAccessStatus()


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
                        useHttps = useHttps, // NEW: Pass HTTPS state
                        // REMOVED: hasManageAllFilesAccess = hasManageAllFilesAccess,
                        onStartClick = { startServer() },
                        onStopClick = { stopServer() },
                        onSelectSpecificFolderClick = { launchDirectoryPicker() },
                        // REMOVED: onSelectCommonFolderClick = { showCommonDirectoryPicker = true },
                        // REMOVED: onRequestManageAllFilesAccessClick = { requestManageAllFilesAccess() },
                        onApprovalToggleChange = { enabled ->
                            requireApprovalEnabled = enabled
                            savePreferences(enabled, useHttps, sharedDirectoryUri) // NEW: Save HTTPS state
                             if (serverOperationalState == "Running" || serverOperationalState == "Starting" || serverOperationalState == "Stopping") {
                                 networkStatusMessage = "Setting saved.\nStop and restart server to apply."
                                 sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             } else {
                                networkStatusMessage = "Server is stopped.\nApproval ${if (enabled) "Required" else "Not Required"}"
                                sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                             }
                        },
                        onHttpsToggleChange = { enabled -> // NEW: Handle HTTPS toggle
                            useHttps = enabled
                            savePreferences(requireApprovalEnabled, enabled, sharedDirectoryUri) // NEW: Save HTTPS state
                            if (serverOperationalState == "Running" || serverOperationalState == "Starting" || serverOperationalState == "Stopping") {
                                networkStatusMessage = "Setting saved.\nStop and restart server to apply."
                                sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                            } else {
                                networkStatusMessage = "Server is stopped.\nHTTPS ${if (enabled) "Enabled" else "Disabled"}"
                                sendStatusUpdateForUI(serverOperationalState, networkStatusMessage)
                            }
                        }
                    )

                    // REMOVED: CommonDirectoryPicker usage
                    /*
                    if (showCommonDirectoryPicker) {
                        CommonDirectoryPicker(
                            onDirectorySelected = { file ->
                                handleDirectoryPicked(Uri.fromFile(file))
                                showCommonDirectoryPicker = false
                            },
                            onDismiss = { showCommonDirectoryPicker = false }
                        )
                    }
                    */
                }
            }
        }
        Log.d(TAG, "MainActivity onCreate finished. Loaded URI: $sharedDirectoryUri, Approval: $requireApprovalEnabled, HTTPS: $useHttps") // REMOVED: AllFilesAccess
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart - Registering receiver")
        val filter = IntentFilter(ACTION_SERVER_STATUS_UPDATE)
        localBroadcastManager.registerReceiver(serverStatusReceiver, filter)
        // REMOVED: updateManageAllFilesAccessStatus()
    }

     override fun onResume() {
         super.onResume()
          Log.d(TAG, "MainActivity onResume - Sending status query to service.")
          // REMOVED: updateManageAllFilesAccessStatus()

          val queryIntent = Intent(this, WebServerService::class.java).apply {
               action = ACTION_QUERY_STATUS
               putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
               // REMOVED: putExtra(EXTRA_HAS_ALL_FILES_ACCESS, hasManageAllFilesAccess)
               putExtra(EXTRA_USE_HTTPS, useHttps) // NEW: Pass HTTPS state on query
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
        requireApprovalEnabled = prefs.getBoolean(PREF_REQUIRE_APPROVAL, false) // Use PREF_REQUIRE_APPROVAL from WebServerService
        useHttps = prefs.getBoolean(PREF_USE_HTTPS, false) // Use PREF_USE_HTTPS from WebServerService
        Log.d(TAG, "Loaded preferences: requireApprovalEnabled = $requireApprovalEnabled, useHttps = $useHttps")

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

    // MODIFIED: savePreferences to accept useHttps parameter
    private fun savePreferences(requireApproval: Boolean, useHttps: Boolean, directoryUri: Uri?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        with(prefs.edit()) {
            putBoolean(PREF_REQUIRE_APPROVAL, requireApproval) // Use PREF_REQUIRE_APPROVAL from WebServerService
            putBoolean(PREF_USE_HTTPS, useHttps) // Use PREF_USE_HTTPS from WebServerService
            putString(PREF_SHARED_DIRECTORY_URI, directoryUri?.toString())
            apply()
        }
        Log.d(TAG, "Saved preferences: requireApprovalEnabled = $requireApproval, useHttps = $useHttps, sharedDirectoryUri = $directoryUri")
    }

     private fun updateSharedDirectoryDisplayName() {
         val derivedName = sharedDirectoryUri?.let { uri ->
             // REMOVED: Specific "Internal Storage (All Files Access)" path handling
             try {
                 // ONLY handle content:// URIs, as SAF is the only mechanism now
                 val documentFile = if (uri.scheme == "content") {
                     DocumentFile.fromTreeUri(applicationContext, uri)
                 } else {
                     null // Only content:// URIs are expected now
                 }

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
                    Log.d(TAG, "MainActivity: Received status update broadcast. (HTTPS: ${intent.getBooleanExtra(EXTRA_USE_HTTPS, false)})")
                    val isRunning = intent.getBooleanExtra(EXTRA_SERVER_IS_RUNNING, false)
                    val opState = intent.getStringExtra(EXTRA_SERVER_OPERATIONAL_STATE) ?: "Unknown"
                    val statusMsg = intent.getStringExtra(EXTRA_SERVER_STATUS_MESSAGE) ?: "Unknown Status"
                    val ipAddress = intent.getStringExtra(EXTRA_SERVER_IP)
                    val port = intent.getIntExtra(EXTRA_SERVER_PORT, -1)
                    val dirNameFromService = intent.getStringExtra(EXTRA_SHARED_DIRECTORY_NAME) ?: "Not Selected"
                    // No need to update the useHttps state from broadcast, it's controlled by UI.


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
         // REMOVED: MANAGE_EXTERNAL_STORAGE permission check before starting server
         /*
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
             if (sharedDirectoryUri == null) {
                 Log.w(TAG, "startServer (Activity): MANAGE_EXTERNAL_STORAGE not granted, and no SAF directory selected. Prompting user to select directory or grant permission.")
                 serverOperationalState = "Stopped"
                 networkStatusMessage = "Please select a directory or grant 'All Files Access'."
                 sendStatusUpdateForUI("Stopped", "Please select a directory or grant 'All Files Access'.")
                 return
             }
         }
         */

         if (sharedDirectoryUri == null) {
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

        Log.d(TAG, "startServerAfterPermissionGranted (Activity): Calling startService for WebServerService with URI: $sharedDirectoryUri. HTTPS: $useHttps")

        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_START_SERVER
            putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
            putExtra(EXTRA_REQUIRE_APPROVAL, requireApprovalEnabled)
            // REMOVED: putExtra(EXTRA_HAS_ALL_FILES_ACCESS, hasManageAllFilesAccess)
            putExtra(EXTRA_USE_HTTPS, useHttps) // NEW: Pass HTTPS preference
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
             putExtra(EXTRA_USE_HTTPS, useHttps) // NEW: Ensure current HTTPS state is passed
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


        try {
            // Since we are only using SAF, we expect content:// URIs
            if (uri.scheme == "content") {
                try {
                     contentResolver.takePersistableUriPermission(uri, flags)
                      Log.d(TAG, "Successfully took persistable URI permissions for: $uri")
                 } catch (e: SecurityException) {
                     Log.w(TAG, "Failed to take persistable URI permission for $uri. App might lose access after reboot.", e)
                 }
            } else {
                Log.w(TAG, "Unsupported URI scheme for SAF: ${uri.scheme}. Expected 'content://'.")
                throw IllegalArgumentException("Only content:// URIs are supported for SAF.")
            }


            sharedDirectoryUri = uri
            updateSharedDirectoryDisplayName()


            savePreferences(requireApprovalEnabled, useHttps, sharedDirectoryUri) // NEW: Save HTTPS state


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
            sharedDirectoryUri = null
            updateSharedDirectoryDisplayName()
             networkStatusMessage = "Failed to get necessary directory permissions."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, useHttps, null) // NEW: Save HTTPS state
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling picked directory URI: $uri", e)
            sharedDirectoryUri = null
            updateSharedDirectoryDisplayName()
             networkStatusMessage = "Error processing selected directory."
             serverOperationalState = "Stopped"
             savePreferences(requireApprovalEnabled, useHttps, null) // NEW: Save HTTPS state
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        }
    }

    // REMOVED: updateManageAllFilesAccessStatus() function
    /*
    private fun updateManageAllFilesAccessStatus() {
        hasManageAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            false
        }
        Log.d(TAG, "Updated hasManageAllFilesAccess: $hasManageAllFilesAccess (API ${Build.VERSION.SDK_INT})")

        if (hasManageAllFilesAccess && sharedDirectoryUri == null) {
            val rootFile = Environment.getExternalStorageDirectory()
            val rootUri = Uri.fromFile(rootFile)
            sharedDirectoryUri = rootUri
            updateSharedDirectoryDisplayName()
            savePreferences(requireApprovalEnabled, useHttps, sharedDirectoryUri) // NEW: Save HTTPS state
            Log.d(TAG, "Auto-set sharedDirectoryUri to external storage root: $rootUri due to All Files Access.")
            networkStatusMessage = "Shared directory set to: ${sharedDirectoryNameDisplay}\nServer is stopped. (All Files Access)"
            sendStatusUpdateForUI("Stopped", networkStatusMessage)
        }
    }
    */


    // REMOVED: requestManageAllFilesAccess() function
    /*
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
        }
    }
    */

    // REMOVED: getCommonPublicDirectories() function and CommonDirectoryPicker composable
    /*
    @Composable
    fun getCommonPublicDirectories(): List<Pair<String, File>> {
        val context = LocalContext.current
        val commonDirs = mutableListOf<Pair<String, File>>()

        val internalStorageRoot = Environment.getExternalStorageDirectory()
        if (internalStorageRoot.exists() && internalStorageRoot.isDirectory && internalStorageRoot.canRead()) {
            commonDirs.add(Pair("Internal Storage (Root)", internalStorageRoot))
        }

        val publicDirTypes = arrayOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_ALARMS,
            Environment.DIRECTORY_NOTIFICATIONS,
            Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_RINGTONES
        )

        for (type in publicDirTypes) {
            val dir = Environment.getExternalStoragePublicDirectory(type)
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                commonDirs.add(Pair(type.replace("DIRECTORY_", "").replace("_", " ").lowercase().capitalizeWords(), dir))
            }
        }

        return commonDirs.sortedBy { it.first }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    */
}

@Composable
fun ServerStatus(
    operationalState: String,
    networkStatus: String,
    port: Int,
    sharedDirectoryName: String,
    requireApprovalEnabled: Boolean,
    useHttps: Boolean, // NEW: HTTPS state
    // REMOVED: hasManageAllFilesAccess: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectSpecificFolderClick: () -> Unit,
    // REMOVED: onSelectCommonFolderClick: () -> Unit,
    // REMOVED: onRequestManageAllFilesAccessClick: () -> Unit,
    onApprovalToggleChange: (Boolean) -> Unit,
    onHttpsToggleChange: (Boolean) -> Unit, // NEW: HTTPS toggle callback
    modifier: Modifier = Modifier
) {
    val isDirectorySelectedAndValid = sharedDirectoryName != "Not Selected" &&
                           sharedDirectoryName != "Permission Failed" &&
                           sharedDirectoryName != "Error Processing"

    val isStartEnabled = (operationalState == "Stopped" || operationalState.startsWith("Failed")) &&
                         isDirectorySelectedAndValid


    val isStopEnabled = operationalState == "Running" || operationalState == "Starting" || operationalState == "Stopping" || operationalState == "Error Stopping"
    val isSelectDirectoryEnabled = operationalState != "Starting" && operationalState != "Stopping"

     val isApprovalToggleEnabled = operationalState == "Stopped" || operationalState.startsWith("Failed")

    // REMOVED: manageAllFilesAccessStatusText
    /*
    val manageAllFilesAccessStatusText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (hasManageAllFilesAccess) "All Files Access: Granted" else "All Files Access: Not Granted"
    } else {
        "All Files Access: N/A (API < 30)"
    }
    */


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

        // REMOVED: Manage All Files Access status text and button
        /*
        Text(
            text = manageAllFilesAccessStatusText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageAllFilesAccess) {
            Button(
                onClick = onRequestManageAllFilesAccessClick,
                enabled = isSelectDirectoryEnabled
            ) {
                Text("Grant All Files Access")
            }
            Spacer(Modifier.height(8.dp))
        }

        if (hasManageAllFilesAccess) {
            Button(
                onClick = onSelectCommonFolderClick,
                enabled = isSelectDirectoryEnabled
            ) {
                Text("Select Common Folder (All Files Access)")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "or",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
        }
        */

        Button(
            onClick = onSelectSpecificFolderClick,
            enabled = isSelectDirectoryEnabled
        ) {
            Text("Select Directory (SAF Picker)")
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
                enabled = isApprovalToggleEnabled
            )
        }

        // NEW: HTTPS Toggle
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(0.8f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Use HTTPS (Self-Signed)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                checked = useHttps,
                onCheckedChange = onHttpsToggleChange,
                enabled = isApprovalToggleEnabled
            )
        }
        // END NEW

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

// REMOVED: CommonDirectoryPicker Composable
/*
@Composable
fun CommonDirectoryPicker(
    onDirectorySelected: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val directories = (LocalContext.current as MainActivity).getCommonPublicDirectories()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Common Folder") },
        text = {
            if (directories.isEmpty()) {
                Text("No common public directories found or accessible.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(directories) { (name, file) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDirectorySelected(file) }
                                .padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
*/

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
                    useHttps = false, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer Stopped.",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    useHttps = true, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Server is stopped.\nPlease select a directory.\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "Not Selected",
                    requireApprovalEnabled = false,
                    useHttps = false, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Starting",
                    networkStatus = "Starting server...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    useHttps = false, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Requesting Permission",
                    networkStatus = "Requesting notification permission...\nApproval Required",
                    port = -1,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    useHttps = true, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Failed: No Port Found",
                    networkStatus = "Failed to start: No port available.\nApproval Not Required",
                    port = -1,
                     sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = false,
                    useHttps = false, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = false,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                 ServerStatus(
                    operationalState = "Running",
                    networkStatus = "Dir: My Shared Folder\nApproval Required\nServer running on port 54321\n(No Wi-Fi IP)",
                    port = 54321,
                    sharedDirectoryName = "My Shared Folder",
                    requireApprovalEnabled = true,
                    useHttps = true, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
                  ServerStatus(
                    operationalState = "Stopped",
                    networkStatus = "Directory selected: New Folder\n(Stop/Start server to apply)\nApproval Not Required",
                    port = -1,
                    sharedDirectoryName = "New Folder",
                    requireApprovalEnabled = false,
                    useHttps = false, // NEW: Preview values
                    // REMOVED: hasManageAllFilesAccess = true,
                    onStartClick = {},
                    onStopClick = {},
                    onSelectSpecificFolderClick = {},
                    // REMOVED: onSelectCommonFolderClick = {},
                    // REMOVED: onRequestManageAllFilesAccessClick = {},
                    onApprovalToggleChange = {},
                    onHttpsToggleChange = {}
                )
             }
        }
    }
}
