// File: app/src/main/java/com/example/projectnoodle/WebServerService.kt
package com.example.projectnoodle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager // Import PreferenceManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress // Needed for IP Address helpers
import android.os.Environment // NEW: For Environment.getExternalStorageDirectory()

private const val TAG = "ProjectNoodleService"
private const val NOTIFICATION_CHANNEL_ID = "project_noodle_server_channel"
private const val NOTIFICATION_ID = 1 // Unique ID for our foreground notification
private const val FAILURE_NOTIFICATION_ID = 2

// NEW: Constants for connection approval notification
const val APPROVAL_CHANNEL_ID = "project_noodle_approval_channel"
const val APPROVAL_NOTIFICATION_ID = 3 // Distinct ID for approval notifications
const val ACTION_APPROVE_CLIENT = "com.example.projectnoodle.APPROVE_CLIENT"
const val ACTION_REJECT_CLIENT = "com.example.projectnoodle.REJECT_CLIENT"
const val EXTRA_CLIENT_IP_FOR_APPROVAL = "com.example.projectnoodle.CLIENT_IP_FOR_APPROVAL"

// Intent Actions and Extras for Service Communication
const val ACTION_START_SERVER = "com.example.projectnoodle.START_SERVER"
const val ACTION_STOP_SERVER = "com.example.projectnoodle.STOP_SERVER" // New action for notification button
const val ACTION_SERVER_STATUS_UPDATE = "com.example.projectnoodle.SERVER_STATUS_UPDATE" // Action for status broadcasts
const val ACTION_QUERY_STATUS = "com.example.projectnoodle.QUERY_STATUS" // Action to request service to broadcast status


const val EXTRA_SHARED_DIRECTORY_URI = "com.example.projectnoodle.SHARED_DIRECTORY_URI" // Used by START action AND QUERY_STATUS
const val EXTRA_REQUIRE_APPROVAL = "com.example.projectnoodle.REQUIRE_APPROVAL" // Used by START action
const val EXTRA_HAS_ALL_FILES_ACCESS = "com.example.projectnoodle.HAS_ALL_FILES_ACCESS" // NEW: Extra for MANAGE_EXTERNAL_STORAGE status

const val EXTRA_SERVER_IS_RUNNING = "com.example.projectnoodle.IS_RUNNING" // Used by STATUS_UPDATE
const val EXTRA_SERVER_OPERATIONAL_STATE = "com.example.projectnoodle.OPERATIONAL_STATE" // Used by STATUS_UPDATE (e.g., "Running", "Stopped", "Failed")
const val EXTRA_SERVER_STATUS_MESSAGE = "com.example.projectnoodle.STATUS_MESSAGE" // Used by STATUS_UPDATE
const val EXTRA_SERVER_IP = "com.example.projectnoodle.SERVER_IP" // Used by STATUS_UPDATE
const val EXTRA_SERVER_PORT = "com.example.projectnoodle.SERVER_PORT" // Used by STATUS_UPDATE
const val EXTRA_SHARED_DIRECTORY_NAME = "com.example.projectnoodle.SHARED_DIRECTORY_NAME" // Used by STATUS_UPDATE (Derived in Service, broadcast for UI)

// NEW: Constant moved from MainActivity
const val PREF_REQUIRE_APPROVAL = "pref_require_approval"


class WebServerService : Service(), ConnectionApprovalListener { // NEW: Implement ConnectionApprovalListener

    private var server: WebServer? = null
    private var currentSharedDirectoryUri: Uri? = null
    private var currentServerPort: Int = -1
    private var currentIpAddress: String? = null
    private var currentOperationalState: String = "Stopped" // Track state internally in Service
    private var requireApprovalEnabled: Boolean = false
    private var hasManageAllFilesAccess: Boolean = false // NEW: Service's internal state for MANAGE_EXTERNAL_STORAGE permission


    private lateinit var localBroadcastManager: LocalBroadcastManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebServerService onCreate")
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        createNotificationChannel() // Existing channel for foreground service
        createApprovalNotificationChannel() // NEW: Channel for approval requests
        loadPreferences() // NEW: Load preferences here on service creation
        updateManageAllFilesAccessStatus() // NEW: Check initial status of MANAGE_EXTERNAL_STORAGE
         // Send initial status update when service starts.
         // This is important if the service is started by the system without a specific action.
         // It reports the *current* (default or previously running) state.
         // The Activity's onResume sending ACTION_QUERY_STATUS will then prompt a re-broadcast
         // after the Activity has loaded its persisted state, potentially updating the directory name shown.
         sendStatusUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebServerService onStartCommand received intent action: ${intent?.action} (StartId: $startId)")
        Log.d(TAG, "ACTION_STOP_SERVER constant value: $ACTION_STOP_SERVER")

        when (intent?.action) {
            ACTION_START_SERVER -> {
                 @Suppress("DEPRECATION") // For getParcelableExtra<Uri> pre-API 33
                 val uriToShare: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                 } else {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                 }
                 val newRequireApprovalEnabled = intent.getBooleanExtra(EXTRA_REQUIRE_APPROVAL, false)
                 val newHasManageAllFilesAccess = intent.getBooleanExtra(EXTRA_HAS_ALL_FILES_ACCESS, false) // NEW: Get permission status from Activity
                 Log.d(TAG, "Received START_SERVER action. requireApprovalEnabled = $newRequireApprovalEnabled, hasManageAllFilesAccess = $newHasManageAllFilesAccess")

                this.requireApprovalEnabled = newRequireApprovalEnabled // FIX: Assign the extracted value
                this.hasManageAllFilesAccess = newHasManageAllFilesAccess // NEW: Assign the extracted permission status

                if (uriToShare != null) {
                    Log.d(TAG, "Received START_SERVER action with URI: $uriToShare")

                     // Check if server is already running with the *exact* same configuration
                     if (server != null && server!!.isAlive && uriToShare == currentSharedDirectoryUri && newRequireApprovalEnabled == requireApprovalEnabled && newHasManageAllFilesAccess == hasManageAllFilesAccess) {
                         Log.d(TAG, "Server already running with the same URI, approval, and all files access setting, ignoring START_SERVER.")
                         sendStatusUpdate()
                         return START_STICKY
                     }

                     if (server != null && server!!.isAlive) {
                          // If server is running but config changed (URI, approval setting, or all files access)
                          Log.d(TAG, "Server already running, but configuration changed. Stopping server to apply new settings...")
                          stopServerInternal() // Stop the old server instance
                     }

                    // Update service's internal state before starting
                     currentSharedDirectoryUri = uriToShare

                    startServer(uriToShare)

                } else {
                    Log.w(TAG, "Received START_SERVER action but URI extra was null.")
                     // If server is already running with a valid URI, keep it running.
                     // If server is not running, indicate failure due to missing URI.
                     if (server == null || !server!!.isAlive) {
                         Log.e(TAG, "Service cannot start server: No shared directory URI provided.")
                         currentOperationalState = "Failed: No directory selected."
                         sendStatusUpdate()
                     } else {
                         Log.d(TAG, "Server already running with old URI, ignoring START_SERVER with null URI.")
                          sendStatusUpdate()
                     }
                }
            }
            ACTION_QUERY_STATUS -> {
                 Log.d(TAG, "Received QUERY_STATUS action.")
                 // Read the URI from the query intent sent by the Activity
                 @Suppress("DEPRECATION")
                 val uriFromQuery: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                 } else {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                 }
                 val hasAllFilesAccessFromQuery = intent.getBooleanExtra(EXTRA_HAS_ALL_FILES_ACCESS, false) // NEW: Get permission status from Activity

                 Log.d(TAG, "QUERY_STATUS intent contained URI: $uriFromQuery, AllFilesAccess: $hasAllFilesAccessFromQuery")

                 // If the service doesn't currently have a shared URI (e.g., was stopped or killed)
                 // but the Activity sent one (meaning one is persisted), adopt it.
                 if (currentSharedDirectoryUri == null && uriFromQuery != null) {
                     Log.d(TAG, "Service adopting shared directory URI from QUERY_STATUS: $uriFromQuery")
                     currentSharedDirectoryUri = uriFromQuery
                      Log.d(TAG, "Service updated currentSharedDirectoryUri to: $currentSharedDirectoryUri")
                 } else if (currentSharedDirectoryUri != null && uriFromQuery == null) {
                       Log.w(TAG, "Service has a URI (${currentSharedDirectoryUri}) but QUERY_STATUS intent sent null URI. Keeping Service's URI.")
                 } else if (currentSharedDirectoryUri != null && uriFromQuery != null && currentSharedDirectoryUri != uriFromQuery) {
                      Log.w(TAG, "Service has URI (${currentSharedDirectoryUri}) different from QUERY_STATUS intent URI (${uriFromQuery}). Adopting Activity's URI state.")
                      currentSharedDirectoryUri = uriFromQuery
                       Log.d(TAG, "Service updated currentSharedDirectoryUri to: $currentSharedDirectoryUri")
                 } else {
                      Log.d(TAG, "Service URI state matches QUERY_STATUS URI ($currentSharedDirectoryUri) or both are null. No URI adoption needed.")
                 }
                 // NEW: Update service's internal permission status based on Activity's current status
                 this.hasManageAllFilesAccess = hasAllFilesAccessFromQuery
                 Log.d(TAG, "Service updated hasManageAllFilesAccess to: $hasManageAllFilesAccess")

                 loadPreferences() // NEW: Load preferences when status is queried
                 sendStatusUpdate()
                 return START_STICKY
            }
            ACTION_STOP_SERVER -> {
                Log.d(TAG, "Received explicit STOP_SERVER action. Entering stop logic.")
                stopServerInternal()
                stopSelf() // Stop the service entirely
            }
            // NEW: Handle Approve/Reject actions from notification
            ACTION_APPROVE_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL)
                if (clientIp != null) {
                    server?.approveClient(clientIp)
                    Log.i(TAG, "Client $clientIp approved via notification.")
                } else {
                    Log.w(TAG, "ACTION_APPROVE_CLIENT received with null IP.")
                }
                // Dismiss the approval notification
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
            }
            ACTION_REJECT_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL)
                if (clientIp != null) {
                    server?.denyClient(clientIp) // Deny means not adding to approved list, or explicitly removing
                    Log.i(TAG, "Client $clientIp rejected via notification.")
                } else {
                    Log.w(TAG, "ACTION_REJECT_CLIENT received with null IP.")
                }
                // Dismiss the approval notification
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
            }
            else -> {
                Log.w(TAG, "WebServerService received unhandled intent action: ${intent?.action} (StartId: $startId)")
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "WebServerService onDestroy")
        stopServerInternal() // Ensure server is stopped when service is destroyed
        super.onDestroy()
        Log.d(TAG, "WebServerService onDestroy finished.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val descriptionText = "Notification channel for the Project Noodle server."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                setSound(null, null) // Explicitly no sound
                enableVibration(false) // Explicitly no vibration
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID with LOW importance and no sound.")
        }
    }

    // NEW: Notification channel for approval requests
    private fun createApprovalNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Client Connection Approval"
            val descriptionText = "Notifications for new client connection approval requests."
            val importance = NotificationManager.IMPORTANCE_HIGH // High importance for sound/vibration
            val channel = NotificationChannel(APPROVAL_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // Sound and vibration are enabled by default for IMPORTANCE_HIGH
                // You can customize them here if needed:
                // setSound(yourSoundUri, audioAttributes)
                // enableVibration(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created for approval requests: $APPROVAL_CHANNEL_ID with HIGH importance.")
        }
    }


    private fun buildNotification(): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent: PendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = getServerStatusText(server != null && server!!.isAlive) // Use the helper


         val baseBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Server")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use the white noodle foreground icon
            .setContentIntent(contentPendingIntent)
            .setShowWhen(false)
            .setSilent(true) // Ensure this notification is silent
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Keep ongoing while server is in an active state


        // Add stop action only if server is running or stopping
        if (currentOperationalState == "Running" || currentOperationalState == "Stopping" || currentOperationalState == "Error Stopping") {
             baseBuilder.addAction(
                 R.drawable.ic_launcher_foreground, // Placeholder icon
                 "Stop Server",
                 stopPendingIntent
             )
        }


        return baseBuilder
    }

     private fun updateNotification() {
        // Dismiss any previous non-persistent failure notification before showing the active/stopped one
        val notificationManager: NotificationManager =
                 getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FAILURE_NOTIFICATION_ID)
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID) // NEW: Also cancel approval notification


         // We only build and show the main notification if the server is in an active state.
         // For other states, we stop foreground and potentially show a failure notification.
         if (currentOperationalState == "Running" || currentOperationalState == "Starting" || currentOperationalState == "Stopping") {
              val notification = buildNotification().build()
              val statusTextForLog = getServerStatusText(server != null && server!!.isAlive)
              startForeground(NOTIFICATION_ID, notification)
              Log.d(TAG, "Called startForeground with status: '${statusTextForLog}'")
         } else {
             // Server is Stopped, Failed, etc. Remove the foreground state.
              stopForeground(Service.STOP_FOREGROUND_REMOVE)
              Log.d(TAG, "Called stopForeground(${Service.STOP_FOREGROUND_REMOVE}) for state: ${currentOperationalState}")

              val statusText = getServerStatusText(server != null && server!!.isAlive) // Get the final status text

              // Show a non-persistent notification ONLY for Failure states
              if (currentOperationalState.startsWith("Failed") || currentOperationalState == "Error Stopping") {
                  // Build a *new* notification builder specifically for the failure message
                   val failureNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID) // Use the same channel, or a different one if needed
                        .setContentTitle(getString(R.string.app_name) + " Server Error")
                        .setContentText(statusText) // Use the final status text describing the failure
                        .setSmallIcon(R.drawable.ic_launcher_foreground) // Use the white noodle foreground icon
                        .setContentIntent(buildNotification().build().contentIntent) // Link back to main activity
                        .setShowWhen(false)
                        .setSilent(false) // Allow sound/vibration for failures (or use a specific channel config)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Or HIGH
                        .setAutoCancel(true) // Dismiss when tapped


                   notificationManager.notify(FAILURE_NOTIFICATION_ID, failureNotificationBuilder.build()) // Use the distinct ID
                   Log.d(TAG, "Shown failure notification (ID: $FAILURE_NOTIFICATION_ID) with status: '${statusText}'")

              } else if (currentOperationalState == "Stopped") {
                   // Server successfully stopped. The stopForeground call removed the notification.
                   // No further notification is needed here.
                   Log.d(TAG, "Server successfully stopped. Foreground notification removed.")
              } else {
                 // Other states like "Requesting Permission" after stopForeground?
                 // These shouldn't happen after stopForeground usually.
                 // If they did, let's notify as a non-persistent low priority.
                  val nonPersistentBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name) + " Server Info")
                        .setContentText(statusText)
                        .setSmallIcon(R.drawable.ic_launcher_foreground) // Use the white noodle foreground icon
                        .setContentIntent(buildNotification().build().contentIntent)
                        .setShowWhen(false)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                  notificationManager.notify(NOTIFICATION_ID, nonPersistentBuilder.build()) // Re-use NOTIFICATION_ID for non-foreground info
                   Log.d(TAG, "Called notify (fallback, non-persistent) with status: '${statusText}'")
              }
         }
     }

     private fun getServerStatusText(isRunning: Boolean): String {
          val directoryName = currentSharedDirectoryUri?.let { uri ->
              // NEW: Special handling for MANAGE_EXTERNAL_STORAGE root
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasManageAllFilesAccess && uri.toString() == Environment.getExternalStorageDirectory().toURI().toString()) {
                  return@let "Internal Storage (All Files Access)"
              }
              try {
                  // Use applicationContext here as Service has it
                  val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)
                  documentFile?.name ?: uri.lastPathSegment?.let {
                       try { Uri.decode(it) } catch (e: Exception) { it }
                  } ?: "Unknown Directory"
              } catch (e: Exception) {
                   Log.e(TAG, "Error deriving directory name from URI $uri", e)
                   uri.lastPathSegment?.let {
                      try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Error Deriving Name"
              }
          } ?: "Not Selected"


          val sharedDirDisplay = if (directoryName != "Not Selected" && !directoryName.startsWith("Invalid Directory") && !directoryName.startsWith("Error Deriving Name")) "Dir: $directoryName\n" else ""
          val approvalStatus = if (requireApprovalEnabled) "Approval Required" else "Approval Not Required" // This now reflects the service's current state, which will be loaded from preferences

         return if (isRunning) {
             if (currentIpAddress != null && currentServerPort != -1) {
                 "${sharedDirDisplay}${approvalStatus}\nServer running:\nhttp://$currentIpAddress:$currentServerPort"
             } else if (currentServerPort != -1) {
                  "${sharedDirDisplay}${approvalStatus}\nServer running on port $currentServerPort\n(No Wi-Fi IP)"
             } else {
                 "${sharedDirDisplay}${approvalStatus}\nServer is running..." // Should ideally not happen if port is found
             }
         } else {
             when (currentOperationalState) {
                  "Stopped" -> "${sharedDirDisplay}Server Stopped.\n$approvalStatus"
                  "Failed: No directory selected." -> "Server stopped: No directory selected.\n$approvalStatus"
                  // Use the derived directoryName here
                  "Failed: Invalid Directory" -> "${directoryName}\nServer failed: Invalid directory.\n$approvalStatus"
                  "Failed: No Port Found" -> "Server failed: No port available.\n$approvalStatus"
                  "Failed: IO Error" -> "Server failed: IO Error.\n$approvalStatus"
                  "Failed: Unexpected Error" -> "Server failed: Unexpected error.\n$approvalStatus"
                  "Error Stopping" -> "Error stopping server.\n$approvalStatus"
                  "Starting" -> "Starting server...\n$approvalStatus"
                  "Stopping" -> "Stopping server...\n$approvalStatus"
                   "Requesting Permission" -> "Requesting notification permission...\n$approvalStatus" // Handled in Activity for UI, but service might broadcast
                  else -> "Server Stopped.\n$approvalStatus" // Default fallback
             }
         }
    }

    // --- Service State Broadcasting ---
    private fun sendStatusUpdate(): Unit {
        val directoryNameForBroadcast = currentSharedDirectoryUri?.let { uri ->
             // NEW: Special handling for MANAGE_EXTERNAL_STORAGE root in broadcast name
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasManageAllFilesAccess && uri.toString() == Environment.getExternalStorageDirectory().toURI().toString()) {
                 return@let "Internal Storage (All Files Access)"
             }
             try {
                 val documentFile = DocumentFile.fromTreeUri(applicationContext, uri)
                 documentFile?.name ?: uri.lastPathSegment?.let {
                      try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Unknown Directory"
             } catch (e: Exception) {
                  Log.e(TAG, "Error deriving directory name for broadcast from URI $uri", e)
                   uri.lastPathSegment?.let {
                      try { Uri.decode(it) } catch (e: Exception) { it }
                 } ?: "Error Deriving Name"
             }
         } ?: "Not Selected"


        val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
            putExtra(EXTRA_SERVER_IS_RUNNING, server != null && server!!.isAlive)
            putExtra(EXTRA_SERVER_OPERATIONAL_STATE, currentOperationalState)
            putExtra(EXTRA_SERVER_STATUS_MESSAGE, getServerStatusText(server != null && server!!.isAlive)) // Message includes derived name
            putExtra(EXTRA_SERVER_IP, currentIpAddress)
            putExtra(EXTRA_SERVER_PORT, currentServerPort)
            putExtra(EXTRA_SHARED_DIRECTORY_NAME, directoryNameForBroadcast) // Broadcast the derived directory name
             // No need to broadcast requireApprovalEnabled back, MainActivity manages it
        }
        localBroadcastManager.sendBroadcast(statusIntent)
        Log.d(TAG, "Sent status broadcast. State: $currentOperationalState, Running: ${server?.isAlive}, Dir: $directoryNameForBroadcast, Approval Req: $requireApprovalEnabled, AllFilesAccess: $hasManageAllFilesAccess")

        updateNotification()
         return Unit
    }


    private fun startServer(uriToShare: Uri): Unit {
        if (server != null && server!!.isAlive) {
            Log.d(TAG, "startServer(Service): Server is already running.")
            currentOperationalState = "Running"
             sendStatusUpdate()
            return
        }

        Log.d(TAG, "startServer(Service): Attempting to start server...")
         currentOperationalState = "Starting"
         currentSharedDirectoryUri = uriToShare

         sendStatusUpdate() // Broadcast "Starting" state


        val dynamicPort = findAvailablePort()

        if (dynamicPort != -1) {
            Log.d(TAG, "Service: Found available port: $dynamicPort")
            currentServerPort = dynamicPort

            currentIpAddress = getWifiIPAddress(applicationContext)
             Log.d(TAG, "Service: Current Wi-Fi IP address: $currentIpAddress")

            // NEW: Determine how to get the root DocumentFile based on MANAGE_EXTERNAL_STORAGE status
            val rootDoc: DocumentFile? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasManageAllFilesAccess && uriToShare.toString() == Environment.getExternalStorageDirectory().toURI().toString()) {
                // If All Files Access is granted AND the URI explicitly points to the external storage root
                Log.d(TAG, "Using DocumentFile.fromFile for external storage root: ${Environment.getExternalStorageDirectory()}")
                DocumentFile.fromFile(Environment.getExternalStorageDirectory())
            } else {
                // Otherwise, assume it's a SAF tree URI (either picked normally or default for older APIs)
                Log.d(TAG, "Using DocumentFile.fromTreeUri for URI: $uriToShare")
                DocumentFile.fromTreeUri(applicationContext, uriToShare)
            }


            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                 Log.e(TAG, "Service: Invalid or inaccessible root DocumentFile for URI: $uriToShare. Cannot start server.")
                 currentOperationalState = "Failed: Invalid Directory"
                 currentServerPort = -1
                 currentIpAddress = null // Clear IP/Port on failure
                 server = null
                 sendStatusUpdate() // Broadcast "Failed: Invalid Directory" state
                 return
            }

            // MODIFIED: Pass 'this' as the approvalListener
            server = WebServer(dynamicPort, applicationContext, uriToShare, currentIpAddress, requireApprovalEnabled, this)


            try {
                Log.d(TAG, "Service: Attempting to start NanoHTTPD Server on port $currentServerPort.")

                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)


                if (server?.isAlive == true) {
                     Log.d(TAG, "Service: NanoHTTPD Server started successfully (isAlive = true).")
                     currentOperationalState = "Running"
                     sendStatusUpdate() // Broadcast "Running" state

                } else {
                     Log.e(TAG, "Service: NanoHTTPD Server start() returned, but isAlive is false.")
                     currentOperationalState = "Failed: Server stopped unexpectedly."
                     currentServerPort = -1
                     currentIpAddress = null // Clear IP/Port on failure
                     server = null
                     sendStatusUpdate() // Broadcast "Failed: Server stopped unexpectedly." state
                }

            } catch (e: IOException) {
                Log.e(TAG, "Service: Failed to start NanoHTTPD Server on port $currentServerPort (IOException): ${e.message}", e)
                currentOperationalState = "Failed: IO Error"
                 currentServerPort = -1
                 currentIpAddress = null // Clear IP/Port on failure
                 server = null
                 sendStatusUpdate() // Broadcast "Failed: IO Error" state
            } catch (e: Exception) {
                Log.e(TAG, "Service: An unexpected error occurred during server startup", e)
                currentOperationalState = "Failed: Unexpected Error"
                 currentServerPort = -1
                 currentIpAddress = null // Clear IP/Port on failure
                 server = null
                 sendStatusUpdate() // Broadcast "Failed: Unexpected Error" state
            }
        } else {
             Log.e(TAG, "Service: Failed to find an available port.")
             currentOperationalState = "Failed: No Port Found"
             currentServerPort = -1
             currentIpAddress = null // Clear IP/Port on failure
             server = null
             sendStatusUpdate() // Broadcast "Failed: No Port Found" state
        }
         return Unit
    }

    private fun stopServerInternal(): Unit {
        if (server == null) {
             Log.d(TAG, "stopServerInternal(Service): Server instance is null. Already stopped.")
             currentOperationalState = "Stopped" // Ensure state is correct
             currentServerPort = -1
             currentIpAddress = null
             sendStatusUpdate() // Will broadcast "Stopped" state and call stopForeground(true)
             return
        }

        Log.d(TAG, "stopServerInternal(Service): Attempting to stop NanoHTTPD Server.")
         currentOperationalState = "Stopping"
         sendStatusUpdate() // Will broadcast "Stopping" state and call startForeground(notification)


        try {
            server?.stop()
            Log.d(TAG, "Service: NanoHTTPD Server stop() called.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate() // Will broadcast "Stopped" state and call stopForeground(true)

        } catch (e: Exception) {
             Log.e(TAG, "Service: Error calling stop() on NanoHTTPD", e)
             currentOperationalState = "Error Stopping"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate() // Will broadcast "Error Stopping" state and call stopForeground(true)
        }

         return Unit
    }


    private fun findAvailablePort(): Int {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(0)
            val localPort = serverSocket.localPort
            Log.d(TAG, "Service: findAvailablePort: Found port $localPort")
            return localPort
        } catch (e: IOException) {
            Log.e(TAG, "Service: findAvailablePort: Error finding available port", e)
            return -1
        } finally {
            try {
                serverSocket?.close()
                Log.d(TAG, "Service: findAvailablePort: Closed temporary socket")
            } catch (e: IOException) { // Catch the exception during close
                Log.e(TAG, "Service: findAvailablePort: Error closing temporary ServerSocket", e) // Log the new exception 'e' caught here
            }
        }
    }

     @Suppress("DEPRECATION") // connectionInfo and Formatter.formatIpAddress
     private fun getWifiIPAddress(context: Context): String? {
         try {
             val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
             val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

             if (connectivityManager == null || wifiManager == null) {
                  Log.w(TAG, "Service: getWifiIPAddress: ConnectivityManager or WifiManager is null.")
                  return null
             }

             // For modern APIs, prefer LinkProperties from activeNetwork
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                 val activeNetwork = connectivityManager.activeNetwork
                 val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

                 if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                     val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                     val ipAddresses = linkProperties?.linkAddresses?.map { it.address }?.filterNotNull()
                     Log.d(TAG, "Service: Found IP addresses from LinkProperties: $ipAddresses")

                     // Find a non-loopback, non-link-local IPv4 address
                     val ipv4 = ipAddresses?.firstOrNull { it is InetAddress && it.hostAddress?.contains(".") == true && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                      if (ipv4 != null) {
                          Log.d(TAG, "Service: Found IPv4 (modern): $ipv4")
                         return ipv4
                      }


                      // Fallback to first non-loopback, non-link-local address (could be IPv6)
                     return ipAddresses?.firstOrNull { it is InetAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress

                 } else {
                     Log.d(TAG, "Service: getWifiIPAddress: Not connected to Wi-Fi (NetworkCapabilities check).")
                     return null
                 }
             } else {
                 // Deprecated method for older APIs
                 val wifiInfo = wifiManager.connectionInfo
                 val ipAddressInt = wifiInfo?.ipAddress ?: 0
                 if (ipAddressInt != 0 && ipAddressInt != -1) { // 0.0.0.0 or -1 generally mean not connected or error
                     val ipAddress = Formatter.formatIpAddress(ipAddressInt)
                      Log.d(TAG, "Service: getWifiIPAddress: Found IPv4 (deprecated): $ipAddress")
                      if (ipAddress != "0.0.0.0") return ipAddress
                 }
                 Log.d(TAG, "Service: getWifiIPAddress: Not connected to Wi-Fi (deprecated check).")
                 return null
             }

         } catch (e: Exception) {
             Log.e(TAG, "Service: Error getting Wi-Fi IP Address", e)
             return null
         }
     }

    // NEW: Implementation of ConnectionApprovalListener interface
    override fun onNewClientConnectionAttempt(clientIp: String) {
        Log.d(TAG, "onNewClientConnectionAttempt: Service received new client connection attempt from: $clientIp")
        // Show notification with Approve/Reject actions
        showApprovalNotification(clientIp)
    }

    // NEW: Function to show the approval notification
    private fun showApprovalNotification(clientIp: String) {
        val approveIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_APPROVE_CLIENT
            putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, clientIp)
        }
        val approvePendingIntent: PendingIntent = PendingIntent.getService(
            this,
            clientIp.hashCode() + 100, // Unique request code based on IP
            approveIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_REJECT_CLIENT
            putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, clientIp)
        }
        val rejectPendingIntent: PendingIntent = PendingIntent.getService(
            this,
            clientIp.hashCode() + 200, // Unique request code based on IP
            rejectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val approvalNotification = NotificationCompat.Builder(this, APPROVAL_CHANNEL_ID)
            .setContentTitle("New Connection Request")
            .setContentText("A device at $clientIp wants to connect. Approve?")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use the white noodle foreground icon
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Ensure it makes sound/vibrates
            .setAutoCancel(true) // Dismiss when tapped/acted upon
            .addAction(R.drawable.ic_launcher_foreground, "Approve", approvePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Reject", rejectPendingIntent)
            .build()

        notificationManager.notify(APPROVAL_NOTIFICATION_ID, approvalNotification)
        Log.d(TAG, "Approval notification shown for client: $clientIp (ID: $APPROVAL_NOTIFICATION_ID)")
    }

    // NEW: Method to load preferences
    private fun loadPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        requireApprovalEnabled = prefs.getBoolean(PREF_REQUIRE_APPROVAL, false)
        Log.d(TAG, "Service loaded preferences: requireApprovalEnabled = $requireApprovalEnabled")
        // No need to load shared directory URI here; it's passed with START_SERVER action or maintained if service is running
    }

    // NEW: Method to update the hasManageAllFilesAccess status in the service
    private fun updateManageAllFilesAccessStatus() {
        hasManageAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Below API 30, MANAGE_EXTERNAL_STORAGE doesn't exist.
            // On older APIs, broad file access (like what MANAGE_EXTERNAL_STORAGE provides)
            // was typically handled by `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`
            // and didn't involve the special system settings screen.
            // For this app, we're not explicitly requesting those older permissions,
            // so we treat it as not having this "all files access" capability.
            false
        }
        Log.d(TAG, "Service updated hasManageAllFilesAccess: $hasManageAllFilesAccess")
    }
}
