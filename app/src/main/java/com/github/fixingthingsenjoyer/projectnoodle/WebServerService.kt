package com.github.fixingthingsenjoyer.projectnoodle

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket
import java.net.URLDecoder

private const val TAG = "ProjectNoodleService"
private const val NOTIFICATION_CHANNEL_ID = "project_noodle_server_channel"
private const val NOTIFICATION_ID = 1
private const val FAILURE_NOTIFICATION_ID = 2

const val APPROVAL_CHANNEL_ID = "project_noodle_approval_channel"
const val APPROVAL_NOTIFICATION_ID = 3
const val ACTION_APPROVE_CLIENT = "com.github.com.github.fixingthingsenjoyer.projectnoodle.APPROVE_CLIENT"
const val ACTION_REJECT_CLIENT = "com.github.com.github.fixingthingsenjoyer.projectnoodle.REJECT_CLIENT"
const val EXTRA_CLIENT_IP_FOR_APPROVAL = "com.github.com.github.fixingthingsenjoyer.projectnoodle.CLIENT_IP_FOR_APPROVAL"

const val ACTION_START_SERVER = "com.github.com.github.fixingthingsenjoyer.projectnoodle.START_SERVER"
const val ACTION_STOP_SERVER = "com.github.com.github.fixingthingsenjoyer.projectnoodle.STOP_SERVER"
const val ACTION_SERVER_STATUS_UPDATE = "com.github.com.github.fixingthingsenjoyer.projectnoodle.SERVER_STATUS_UPDATE"
const val ACTION_QUERY_STATUS = "com.github.com.github.fixingthingsenjoyer.projectnoodle.QUERY_STATUS"


const val EXTRA_SHARED_DIRECTORY_URI = "com.github.com.github.fixingthingsenjoyer.projectnoodle.SHARED_DIRECTORY_URI"
const val EXTRA_REQUIRE_APPROVAL = "com.github.com.github.fixingthingsenjoyer.projectnoodle.REQUIRE_APPROVAL"
const val EXTRA_USE_HTTPS = "com.github.com.github.fixingthingsenjoyer.projectnoodle.USE_HTTPS"

const val EXTRA_SERVER_IS_RUNNING = "com.github.com.github.fixingthingsenjoyer.projectnoodle.IS_RUNNING"
const val EXTRA_SERVER_OPERATIONAL_STATE = "com.github.com.github.fixingthingsenjoyer.projectnoodle.OPERATIONAL_STATE"
const val EXTRA_SERVER_STATUS_MESSAGE = "com.github.com.github.fixingthingsenjoyer.projectnoodle.STATUS_MESSAGE"
const val EXTRA_SERVER_IP = "com.github.com.github.fixingthingsenjoyer.projectnoodle.SERVER_IP"
const val EXTRA_SERVER_PORT = "com.github.com.github.fixingthingsenjoyer.projectnoodle.SERVER_PORT"
const val EXTRA_SHARED_DIRECTORY_NAME = "com.github.com.github.fixingthingsenjoyer.projectnoodle.SHARED_DIRECTORY_NAME"

const val PREF_REQUIRE_APPROVAL = "pref_require_approval"
const val PREF_USE_HTTPS = "pref_use_https"


class WebServerService : Service(), ConnectionApprovalListener {

    private var server: NanoHTTPD? = null
    private var currentSharedDirectoryUri: Uri? = null
    private var currentServerPort: Int = -1
    private var currentIpAddress: String? = null
    private var currentOperationalState: String = "Stopped"
    private var requireApprovalEnabled: Boolean = false
    private var useHttps: Boolean = false

    private var isForegroundServiceStarted = false

    private lateinit var localBroadcastManager: LocalBroadcastManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebServerService onCreate")
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
        createApprovalNotificationChannel()
        loadPreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebServerService onStartCommand received intent action: ${intent?.action} (StartId: $startId)")

        when (intent?.action) {
            ACTION_START_SERVER -> {
                currentOperationalState = "Starting" // Set initial state

                // This must happen before any potentially long or blocking operations.
                if (!isForegroundServiceStarted) {
                    val startingNotification = buildNotification().build() // Build with "Starting..." state
                    startForeground(NOTIFICATION_ID, startingNotification)
                    isForegroundServiceStarted = true
                    Log.d(TAG, "Called startForeground() immediately in START_SERVER action.")
                } else {
                    // If already in foreground, just update the notification content
                    updateNotificationOnly()
                }


                @Suppress("DEPRECATION")
                val uriToShare: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                }
                val newRequireApprovalEnabled = intent.getBooleanExtra(EXTRA_REQUIRE_APPROVAL, false)
                val newUseHttps = intent.getBooleanExtra(EXTRA_USE_HTTPS, false)
                Log.d(TAG, "Processing START_SERVER. requireApproval=$newRequireApprovalEnabled, useHttps=$newUseHttps")

                this.requireApprovalEnabled = newRequireApprovalEnabled
                this.useHttps = newUseHttps

                if (uriToShare != null) {
                    if (server != null && server!!.isAlive && uriToShare == currentSharedDirectoryUri && newRequireApprovalEnabled == requireApprovalEnabled && newUseHttps == useHttps) {
                        Log.d(TAG, "Server already running with the same config.")
                        currentOperationalState = "Running" // Ensure correct state
                        sendStatusUpdate() // Just update UI and notification content
                        return START_STICKY
                    }

                    if (server != null && server!!.isAlive) {
                        Log.d(TAG, "Server config changed. Stopping existing server first.")
                        stopServerInternal(keepForeground = true) // Keep foreground during restart
                    }

                    currentSharedDirectoryUri = uriToShare
                    startServerLogic(uriToShare)
                } else {
                    Log.w(TAG, "START_SERVER action with null URI.")
                    if (server == null || !server!!.isAlive) {
                        currentOperationalState = "Failed: No directory selected."
                        sendStatusUpdate()
                        stopSelf() // Stop service if it can't start
                    } else {
                        sendStatusUpdate() // Server is running with old config, just update
                    }
                }
            }
            ACTION_QUERY_STATUS -> {
                Log.d(TAG, "Received QUERY_STATUS action.")
                // Update internal state based on query
                @Suppress("DEPRECATION")
                val uriFromQuery: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                }
                this.useHttps = intent.getBooleanExtra(EXTRA_USE_HTTPS, this.useHttps)
                if (uriFromQuery != null) this.currentSharedDirectoryUri = uriFromQuery

                loadPreferences() // Refresh prefs
                sendStatusUpdate() // Send current status
            }
            ACTION_STOP_SERVER -> {
                Log.d(TAG, "Received explicit STOP_SERVER action.")
                stopServerInternal() // This will handle notification and state
                stopSelf()
            }
            ACTION_APPROVE_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL)
                if (clientIp != null) {
                    (server as? WebServer)?.approveClient(clientIp)
                    Log.i(TAG, "Client $clientIp approved.")
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(APPROVAL_NOTIFICATION_ID)
            }
            ACTION_REJECT_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL)
                if (clientIp != null) {
                    (server as? WebServer)?.denyClient(clientIp)
                    Log.i(TAG, "Client $clientIp rejected.")
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(APPROVAL_NOTIFICATION_ID)
            }
            else -> {
                Log.w(TAG, "Unhandled intent action: ${intent?.action}")
                if (!isForegroundServiceStarted && (server == null || !server!!.isAlive)) {
                    // If service is restarted by system without a valid action and server isn't running, stop it.
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "WebServerService onDestroy")
        stopServerInternal() // Ensure server and notification are cleaned up
        super.onDestroy()
        Log.d(TAG, "WebServerService onDestroy finished.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val descriptionText = "Notification channel for the Project Noodle server."
            val importance = NotificationManager.IMPORTANCE_LOW // Keep low to avoid sound/vibration unless error
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID.")
        }
    }

    private fun createApprovalNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Client Connection Approval"
            val descriptionText = "Notifications for new client connection approval requests."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(APPROVAL_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Approval Notification channel created: $APPROVAL_CHANNEL_ID.")
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

        val statusText = getServerStatusText(server != null && server!!.isAlive)

        val baseBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Server")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure this drawable exists
            .setContentIntent(contentPendingIntent)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(currentOperationalState == "Running" || currentOperationalState == "Starting" || currentOperationalState == "Stopping") // Make ongoing only when active


        if (currentOperationalState == "Running" || currentOperationalState == "Stopping" || currentOperationalState == "Error Stopping" || currentOperationalState == "Starting") {
             baseBuilder.addAction(
                 R.drawable.ic_launcher_foreground, // Ensure this drawable exists
                 "Stop Server",
                 stopPendingIntent
             )
        }
        return baseBuilder
    }

    // Renamed to distinguish from the full startForeground lifecycle management
    private fun updateNotificationOnly() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (isForegroundServiceStarted) { // Only update if service is already in foreground
            notificationManager.notify(NOTIFICATION_ID, buildNotification().build())
            Log.d(TAG, "Updated existing foreground notification with status: '${getServerStatusText(server != null && server!!.isAlive)}'")
        } else {
            Log.d(TAG, "updateNotificationOnly called, but service not in foreground. Triggering full updateNotification.")
            updateNotification() // Fallback to full logic if not yet in foreground (should not happen often with new flow)
        }
    }


     private fun updateNotification() {
        val notificationManager: NotificationManager =
                 getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FAILURE_NOTIFICATION_ID)
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)

         val isActivelyServing = server != null && server!!.isAlive
         val isTransitioning = currentOperationalState == "Starting" || currentOperationalState == "Stopping"

         if (isActivelyServing || isTransitioning) {
             if (!isForegroundServiceStarted) { // Only call startForeground if not already started
                 startForeground(NOTIFICATION_ID, buildNotification().build())
                 isForegroundServiceStarted = true
                 Log.d(TAG, "Called startForeground() in updateNotification with status: '${getServerStatusText(isActivelyServing)}'")
             } else {
                 notificationManager.notify(NOTIFICATION_ID, buildNotification().build())
                 Log.d(TAG, "Updated foreground notification with status: '${getServerStatusText(isActivelyServing)}'")
             }
         } else { // Server is stopped or failed
             if (isForegroundServiceStarted) {
                 stopForeground(STOP_FOREGROUND_REMOVE)
                 isForegroundServiceStarted = false
                 Log.d(TAG, "Called stopForeground(STOP_FOREGROUND_REMOVE) for state: ${currentOperationalState}")
             }
             // Remove any lingering active notification
             notificationManager.cancel(NOTIFICATION_ID)


              val statusText = getServerStatusText(false) // Server is not running

              if (currentOperationalState.startsWith("Failed") || currentOperationalState == "Error Stopping") {
                   val failureNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name) + " Server Error")
                        .setContentText(statusText)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentIntent(buildNotification().build().contentIntent) // Re-use intent
                        .setShowWhen(false)
                        .setSilent(false) // Make some noise for errors
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)

                   notificationManager.notify(FAILURE_NOTIFICATION_ID, failureNotificationBuilder.build())
                   Log.d(TAG, "Shown failure notification (ID: $FAILURE_NOTIFICATION_ID) with status: '${statusText}'")
              } else if (currentOperationalState == "Stopped") {
                   Log.d(TAG, "Server successfully stopped. All notifications should be cleared.")
              }
         }
     }

     private fun getServerStatusText(isRunning: Boolean): String {
          val directoryName = currentSharedDirectoryUri?.let { uri ->
              try {
                  val documentFile = if (uri.scheme == "content") {
                      DocumentFile.fromTreeUri(applicationContext, uri)
                  } else {
                      null // Only content:// URIs are expected now
                  }
                  documentFile?.name ?: uri.lastPathSegment?.let {
                       try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } // Specify UTF-8
                  } ?: "Unknown Directory"
              } catch (e: Exception) {
                   Log.e(TAG, "Error deriving directory name from URI $uri", e)
                   uri.lastPathSegment?.let {
                      try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } // Specify UTF-8
                 } ?: "Error Deriving Name"
              }
          } ?: "Not Selected"


          val sharedDirDisplay = if (directoryName != "Not Selected" && !directoryName.startsWith("Invalid Directory") && !directoryName.startsWith("Error Deriving Name")) "Dir: $directoryName\n" else ""
          val approvalStatus = if (requireApprovalEnabled) "Approval Required" else "Approval Not Required"
          val protocolStatus = if (useHttps) "HTTPS Enabled" else "HTTP Only"

         return if (isRunning) {
             val protocol = if(useHttps) "https" else "http"
             if (currentIpAddress != null && currentServerPort != -1) {
                 "${sharedDirDisplay}${protocolStatus}\n${approvalStatus}\nServer running:\n$protocol://$currentIpAddress:$currentServerPort"
             } else if (currentServerPort != -1) {
                  "${sharedDirDisplay}${protocolStatus}\n${approvalStatus}\nServer running on port $currentServerPort\n(No Wi-Fi IP)"
             } else {
                 "${sharedDirDisplay}${protocolStatus}\n${approvalStatus}\nServer is running..."
             }
         } else {
             when (currentOperationalState) {
                  "Stopped" -> "${sharedDirDisplay}Server Stopped.\n${protocolStatus}\n${approvalStatus}"
                  "Failed: No directory selected." -> "Server stopped: No directory selected.\n${protocolStatus}\n${approvalStatus}"
                  "Failed: Invalid Directory" -> "${directoryName}\nServer failed: Invalid directory.\n${protocolStatus}\n${approvalStatus}"
                  "Failed: No Port Found" -> "Server failed: No port available.\n${protocolStatus}\n${approvalStatus}"
                  "Failed: IO Error" -> "Server failed: IO Error.\n${protocolStatus}\n${approvalStatus}"
                  "Failed: Unexpected Error" -> "Server failed: Unexpected error.\n${protocolStatus}\n${approvalStatus}"
                  "Error Stopping" -> "Error stopping server.\n${protocolStatus}\n${approvalStatus}"
                  "Starting" -> "Starting server...\n${protocolStatus}\n${approvalStatus}"
                  "Stopping" -> "Stopping server...\n${protocolStatus}\n${approvalStatus}"
                   "Requesting Permission" -> "Requesting notification permission...\n${protocolStatus}\n${approvalStatus}"
                  else -> "Server Stopped.\n${protocolStatus}\n${approvalStatus}"
             }
         }
    }

    private fun sendStatusUpdate() {
        val directoryNameForBroadcast = currentSharedDirectoryUri?.let { uri ->
             try {
                 val documentFile = if (uri.scheme == "content") {
                     DocumentFile.fromTreeUri(applicationContext, uri)
                 } else {
                     null // Only content:// URIs are expected now
                 }
                 documentFile?.name ?: uri.lastPathSegment?.let {
                      try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                 } ?: "Unknown Directory"
             } catch (e: Exception) {
                  Log.e(TAG, "Error deriving directory name for broadcast from URI $uri", e)
                   uri.lastPathSegment?.let {
                      try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                 } ?: "Error Deriving Name"
             }
         } ?: "Not Selected"


        val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
            putExtra(EXTRA_SERVER_IS_RUNNING, server != null && server!!.isAlive)
            putExtra(EXTRA_SERVER_OPERATIONAL_STATE, currentOperationalState)
            putExtra(EXTRA_SERVER_STATUS_MESSAGE, getServerStatusText(server != null && server!!.isAlive))
            putExtra(EXTRA_SERVER_IP, currentIpAddress)
            putExtra(EXTRA_SERVER_PORT, currentServerPort)
            putExtra(EXTRA_SHARED_DIRECTORY_NAME, directoryNameForBroadcast)
            putExtra(EXTRA_USE_HTTPS, useHttps)
        }
        localBroadcastManager.sendBroadcast(statusIntent)
        Log.d(TAG, "Sent status broadcast. State: $currentOperationalState, Running: ${server?.isAlive}, Dir: $directoryNameForBroadcast, Approval Req: $requireApprovalEnabled, HTTPS: $useHttps")

        updateNotification()
         return
    }


    private fun startServerLogic(uriToShare: Uri) {
        if (server != null && server!!.isAlive) {
            Log.d(TAG, "startServerLogic: Server is already running.")
            currentOperationalState = "Running"
             sendStatusUpdate()
            return
        }

        Log.d(TAG, "startServerLogic: Attempting to start server... HTTPS enabled: $useHttps")
         currentOperationalState = "Starting" // State already set in onStartCommand
         currentSharedDirectoryUri = uriToShare

         sendStatusUpdate() // Update UI and notification content

        val dynamicPort = findAvailablePort()

        if (dynamicPort != -1) {
            Log.d(TAG, "Service: Found available port: $dynamicPort")
            currentServerPort = dynamicPort
            currentIpAddress = getWifiIPAddress(applicationContext)
             Log.d(TAG, "Service: Current Wi-Fi IP address: $currentIpAddress")

            val rootDoc: DocumentFile? = DocumentFile.fromTreeUri(applicationContext, uriToShare)

            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                 Log.e(TAG, "Service: Invalid root DocumentFile for URI: $uriToShare. Cannot start.")
                 currentOperationalState = "Failed: Invalid Directory"
                 currentServerPort = -1
                 currentIpAddress = null
                 server = null
                 sendStatusUpdate()
                 return
            }

            try {
                server = if (useHttps) {
                    Log.d(TAG, "Service: Instantiating HttpsWebServer.")
                    HttpsWebServer(
                        dynamicPort, applicationContext, uriToShare, currentIpAddress,
                        requireApprovalEnabled, this
                    )
                } else {
                    Log.d(TAG, "Service: Instantiating WebServer (HTTP).")
                    WebServer(
                        dynamicPort, applicationContext, uriToShare, currentIpAddress,
                        requireApprovalEnabled, this
                    )
                }

                Log.d(TAG, "Service: Attempting to start NanoHTTPD Server on port $currentServerPort.")
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                if (server?.isAlive == true) {
                     Log.d(TAG, "Service: NanoHTTPD Server started successfully.")
                     currentOperationalState = "Running"
                } else {
                     Log.e(TAG, "Service: NanoHTTPD Server start() returned, but isAlive is false.")
                     currentOperationalState = "Failed: Server stopped unexpectedly."
                     server = null // Clear potentially problematic server instance
                }
            } catch (e: IOException) { // Catch IOException from HttpsWebServer init or server.start()
                Log.e(TAG, "Service: Failed to start NanoHTTPD Server (IOException): ${e.message}", e)
                currentOperationalState = "Failed: IO Error"
                 server = null
            } catch (e: Exception) {
                Log.e(TAG, "Service: An unexpected error occurred during server startup", e)
                currentOperationalState = "Failed: Unexpected Error"
                 server = null
            } finally {
                if (currentOperationalState.startsWith("Failed")) {
                    currentServerPort = -1
                    currentIpAddress = null
                }
                sendStatusUpdate() // Final status update based on outcome
            }
        } else {
             Log.e(TAG, "Service: Failed to find an available port.")
             currentOperationalState = "Failed: No Port Found"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate()
        }
    }

    // Added keepForeground parameter
    private fun stopServerInternal(keepForeground: Boolean = false) {
        if (server == null) {
             Log.d(TAG, "stopServerInternal(Service): Server instance is null. Already stopped.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             if (!keepForeground) {
                 isForegroundServiceStarted = false // Reset flag if not keeping foreground
             }
             sendStatusUpdate()
             return
        }

        Log.d(TAG, "stopServerInternal(Service): Attempting to stop NanoHTTPD Server. keepForeground: $keepForeground")
         currentOperationalState = "Stopping"
         if (!keepForeground) {
             isForegroundServiceStarted = false
         }
         sendStatusUpdate() // Update notification to "Stopping..."

        try {
            server?.stop()
            Log.d(TAG, "Service: NanoHTTPD Server stop() called.")
             currentOperationalState = "Stopped"
        } catch (e: Exception) {
             Log.e(TAG, "Service: Error calling stop() on NanoHTTPD", e)
             currentOperationalState = "Error Stopping"
        } finally {
            currentServerPort = -1
            currentIpAddress = null
            server = null
             if (!keepForeground) {
                 isForegroundServiceStarted = false
             }
            sendStatusUpdate() // Final update (e.g., "Stopped" or "Error Stopping")
        }
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
            } catch (e: IOException) {
                Log.e(TAG, "Service: findAvailablePort: Error closing temporary ServerSocket", e)
            }
        }
    }

     private fun getWifiIPAddress(context: Context): String? {
         try {
             val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
             val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

             if (connectivityManager == null || wifiManager == null) {
                  Log.w(TAG, "Service: getWifiIPAddress: ConnectivityManager or WifiManager is null.")
                  return null
             }

             val activeNetwork = connectivityManager.activeNetwork
             val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

             if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                 val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                 val ipAddresses = linkProperties?.linkAddresses?.map { it.address }?.filterNotNull()
                 Log.d(TAG, "Service: Found IP addresses from LinkProperties: $ipAddresses")

                 val ipv4 = ipAddresses?.firstOrNull { true && it.hostAddress?.contains(".") == true && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                  if (ipv4 != null) {
                      Log.d(TAG, "Service: Found IPv4 (modern): $ipv4")
                     return ipv4
                  }

                 return ipAddresses?.firstOrNull { true && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress

             } else {
                 Log.d(TAG, "Service: getWifiIPAddress: Not connected to Wi-Fi (NetworkCapabilities check).")
                 return null
             }

         } catch (e: Exception) {
             Log.e(TAG, "Service: Error getting Wi-Fi IP Address", e)
             return null
         }
     }

    override fun onNewClientConnectionAttempt(clientIp: String) {
        Log.d(TAG, "onNewClientConnectionAttempt: Service received new client connection attempt from: $clientIp")
        showApprovalNotification(clientIp)
    }

    private fun showApprovalNotification(clientIp: String) {
        val approveIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_APPROVE_CLIENT
            putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, clientIp)
        }
        val approvePendingIntent: PendingIntent = PendingIntent.getService(
            this,
            clientIp.hashCode() + 100,
            approveIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_REJECT_CLIENT
            putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, clientIp)
        }
        val rejectPendingIntent: PendingIntent = PendingIntent.getService(
            this,
            clientIp.hashCode() + 200,
            rejectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val approvalNotification = NotificationCompat.Builder(this, APPROVAL_CHANNEL_ID)
            .setContentTitle("New Connection Request")
            .setContentText("A device at $clientIp wants to connect. Approve?")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_launcher_foreground, "Approve", approvePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Reject", rejectPendingIntent)
            .build()

        notificationManager.notify(APPROVAL_NOTIFICATION_ID, approvalNotification)
        Log.d(TAG, "Approval notification shown for client: $clientIp (ID: $APPROVAL_NOTIFICATION_ID)")
    }

    private fun loadPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        requireApprovalEnabled = prefs.getBoolean(PREF_REQUIRE_APPROVAL, false)
        useHttps = prefs.getBoolean(PREF_USE_HTTPS, false)
        Log.d(TAG, "Service loaded preferences: requireApprovalEnabled = $requireApprovalEnabled, useHttps = $useHttps")
    }
}
