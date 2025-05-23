package com.example.projectnoodle

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

private const val TAG = "ProjectNoodleActivity"
private const val PREF_SHARED_DIRECTORY_URI = "pref_shared_directory_uri"

class MainActivity : ComponentActivity() {

    private var serverOperationalState by mutableStateOf("Stopped")
    private var serverPort by mutableIntStateOf(-1)
    private var serverIpAddress by mutableStateOf<String?>(null) // This needs to be passed

    private var sharedDirectoryUri by mutableStateOf<Uri?>(null)
    private var sharedDirectoryNameDisplay by mutableStateOf("Not Selected")

    private var requireApprovalEnabled by mutableStateOf(false)
    private var useHttps by mutableStateOf(false) // NEW: HTTPS toggle state


    private lateinit var openDirectoryPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var requestNotificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var serverStatusReceiver: BroadcastReceiver


    override fun onCreate(saved: Bundle?) {
        // Add Bouncy Castle provider at the highest priority as early as possible
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

        openDirectoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            handleDirectoryPicked(uri)
        }

        loadPreferences() // Load all preferences including the new HTTPS toggle

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
                        serverIpAddress = serverIpAddress, // Pass serverIpAddress
                        port = serverPort,
                        sharedDirectoryName = sharedDirectoryNameDisplay,
                        requireApprovalEnabled = requireApprovalEnabled,
                        useHttps = useHttps, // NEW: Pass HTTPS state
                        onStartClick = { startServer() },
                        onStopClick = { stopServer() },
                        onSelectSpecificFolderClick = { launchDirectoryPicker() },
                        onApprovalToggleChange = { enabled ->
                            requireApprovalEnabled = enabled
                            savePreferences(enabled, useHttps, sharedDirectoryUri) // NEW: Save HTTPS state
                            showAppToast("Setting saved. Restart server to apply changes to approval or HTTPS.")
                            // No specific UI status update needed here, as toggle state is visible
                            // and toast handles the 'apply' message.
                        },
                        onHttpsToggleChange = { enabled -> // NEW: Handle HTTPS toggle
                            useHttps = enabled
                            savePreferences(requireApprovalEnabled, enabled, sharedDirectoryUri) // NEW: Save HTTPS state
                            showAppToast("Setting saved. Restart server to apply changes to approval or HTTPS.")
                            // No specific UI status update needed here, as toggle state is visible
                        }
                    )
                }
            }
        }
        Log.d(TAG, "MainActivity onCreate finished. Loaded URI: $sharedDirectoryUri, Approval: $requireApprovalEnabled, HTTPS: $useHttps")
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
               putExtra(EXTRA_USE_HTTPS, useHttps) // Pass HTTPS state on query
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
             try {
                 // ONLY handle content:// URIs, as SAF is the only mechanism now
                 val documentFile = if (uri.scheme == "content") {
                     DocumentFile.fromTreeUri(applicationContext, uri)
                 } else {
                     null
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
                    val ipAddress = intent.getStringExtra(EXTRA_SERVER_IP)
                    val port = intent.getIntExtra(EXTRA_SERVER_PORT, -1)
                    val dirNameFromService = intent.getStringExtra(EXTRA_SHARED_DIRECTORY_NAME) ?: "Not Selected"
                    // No need to update the useHttps state from broadcast, it's controlled by UI.


                    runOnUiThread {
                        serverOperationalState = opState
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
             Log.w(TAG, "startServer (Activity): Cannot start server, no directory selected (after all checks).")
             serverOperationalState = "Stopped" // Ensure state is correct
             showAppToast("Please select a directory to share.", Toast.LENGTH_LONG)
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
                 // No toast here as the permission dialog is immediate feedback
                 // Status is implicitly "Requesting Permission"
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
             serverOperationalState = "Stopped" // Ensure state is correct
             showAppToast("Please select a directory to share.", Toast.LENGTH_LONG)
             return
         }

        Log.d(TAG, "startServerAfterPermissionGranted (Activity): Calling startService for WebServerService with URI: $sharedDirectoryUri. HTTPS: $useHttps")

        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_START_SERVER
            putExtra(EXTRA_SHARED_DIRECTORY_URI, sharedDirectoryUri)
            putExtra(EXTRA_REQUIRE_APPROVAL, requireApprovalEnabled)
            putExtra(EXTRA_USE_HTTPS, useHttps) // NEW: Pass HTTPS preference
        }

        // The service will update its internal operational state and notify the UI
        sendStatusUpdateForUI("Starting", "Starting server...") // statusMsg here is for the broadcast only now.
        // No explicit toast here, as the notification will also reflect "Starting"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun handleNotificationPermissionDenied() {
        serverOperationalState = "Stopped" // Ensure state is correct
        Log.w(TAG, "Notification permission denied. Server start blocked.")
        sendStatusUpdateForUI("Stopped", "Notification permission denied.\nServer cannot run in foreground to stay active.")
    }


    private fun stopServer() {
        Log.d(TAG, "stopServer (Activity): Calling startService with STOP_SERVER action.")

        // The service will update its internal operational state and notify the UI
        sendStatusUpdateForUI("Stopping", "Stopping server...")
        val serviceIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }

        val stopped = startService(serviceIntent)
        // No toast here, as the operationalState changes and notification updates.
        Log.d(TAG, "startService with STOP_SERVER action returned: $stopped")
    }

     private fun sendStatusUpdateForUI(opState: String, statusMsg: String) {
         val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
             putExtra(EXTRA_SERVER_IS_RUNNING, opState == "Running" || opState == "Starting" || opState == "Stopping")
             putExtra(EXTRA_SERVER_OPERATIONAL_STATE, opState)
             putExtra(EXTRA_SERVER_STATUS_MESSAGE, statusMsg) // This extra is currently unused in MainActivity, but kept for service logic.
             putExtra(EXTRA_SERVER_IP, serverIpAddress)
             putExtra(EXTRA_SERVER_PORT, serverPort)
             putExtra(EXTRA_SHARED_DIRECTORY_NAME, sharedDirectoryNameDisplay)
             putExtra(EXTRA_USE_HTTPS, useHttps) // Ensure current HTTPS state is passed
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
            // No explicit toast, as the UI already shows current directory if one was selected.
            // If no directory was selected before, the "Select directory" button implies need for action.
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


            savePreferences(requireApprovalEnabled, useHttps, sharedDirectoryUri) // Save HTTPS state


            Log.d(TAG, "Shared directory state updated in Activity: URI = $sharedDirectoryUri, Display Name = $sharedDirectoryNameDisplay")

             // Show an info toast about directory change
            showAppToast("Shared directory set to: $sharedDirectoryNameDisplay. Restart server to apply.", Toast.LENGTH_LONG)
             // No need for sendStatusUpdateForUI, as UI updates directly via sharedDirectoryNameDisplay state.


        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to get DocumentFile from URI $uri or access content resolver.", e)
            sharedDirectoryUri = null
            updateSharedDirectoryDisplayName()
            serverOperationalState = "Stopped"
            savePreferences(requireApprovalEnabled, useHttps, null) // Save HTTPS state
            showAppToast("Failed to get necessary directory permissions for $uri.", Toast.LENGTH_LONG)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling picked directory URI: $uri", e)
            sharedDirectoryUri = null
            updateSharedDirectoryDisplayName()
            serverOperationalState = "Stopped"
            savePreferences(requireApprovalEnabled, useHttps, null) // Save HTTPS state
            showAppToast("Error processing selected directory for $uri.", Toast.LENGTH_LONG)
        }
    }

    // Helper function to show toasts
    private fun showAppToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }
}

@Composable
fun ServerStatus(
    operationalState: String, // Kept for button enablement logic
    serverIpAddress: String?, // Now passed as a parameter
    port: Int,
    sharedDirectoryName: String,
    requireApprovalEnabled: Boolean,
    useHttps: Boolean, // NEW: HTTPS state
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectSpecificFolderClick: () -> Unit,
    onApprovalToggleChange: (Boolean) -> Unit,
    onHttpsToggleChange: (Boolean) -> Unit,
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
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        if (operationalState == "Running") {
            Text(
                text = "(Requires device and browser on same Wi-Fi network)",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (operationalState == "Running" && serverIpAddress != null && port != -1) {
             Text(
                 text = "URL: ${if (useHttps) "https" else "http"}://${serverIpAddress}:${port}",
                 style = MaterialTheme.typography.bodyMedium,
                 textAlign = TextAlign.Center,
                 modifier = Modifier.padding(top = 4.dp),
                 color = MaterialTheme.colorScheme.onBackground
             )
         } else if (operationalState == "Running" && port != -1) {
             Text(text = "Server running on port $port (No Wi-Fi IP detected)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
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
 
 @Preview(showBackground = true)
 @Composable
 fun ServerStatusPreview() {
     ProjectNoodleTheme(darkTheme = true) {
         Surface(color = MaterialTheme.colorScheme.background) {
              Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                 ServerStatus(
                    operationalState = "Running", // Simulating running state
                    serverIpAddress = "192.168.1.100", // Preview IP address
                     port = 54321,
                     sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = false,
                     useHttps = false, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                  ServerStatus(
                    operationalState = "Stopped", // Simulating stopped state
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                     sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = true,
                     useHttps = true, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                   ServerStatus(
                    operationalState = "Stopped", // Simulating stopped state
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                     sharedDirectoryName = "Not Selected",
                     requireApprovalEnabled = false,
                     useHttps = false, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                  ServerStatus(
                    operationalState = "Starting", // Simulating starting state
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                     sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = true,
                     useHttps = false, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                  ServerStatus(
                    operationalState = "Requesting Permission", // Simulating permission request
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                     sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = true,
                     useHttps = true, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                  ServerStatus(
                    operationalState = "Failed: No Port Found", // Simulating failure
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                      sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = false,
                     useHttps = false, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                  ServerStatus(
                    operationalState = "Running",
                    serverIpAddress = null, // No IP detected in preview
                     port = 54321,
                     sharedDirectoryName = "My Shared Folder",
                     requireApprovalEnabled = true,
                     useHttps = true, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
                   ServerStatus(
                    operationalState = "Stopped", // Simulating stopped state
                    serverIpAddress = null, // Preview IP address
                     port = -1,
                     sharedDirectoryName = "New Folder",
                     requireApprovalEnabled = false,
                     useHttps = false, // NEW: Preview values
                     onStartClick = {}, onStopClick = {}, onSelectSpecificFolderClick = {}, onApprovalToggleChange = {}, onHttpsToggleChange = {}
                 )
              }
         }
     }
 }
