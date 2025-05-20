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
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket

private const val TAG = "ProjectNoodleService"
private const val NOTIFICATION_CHANNEL_ID = "project_noodle_server_channel"
private const val NOTIFICATION_ID = 1 // Unique ID for our foreground notification

// Intent Actions and Extras for Service Communication
const val ACTION_START_SERVER = "com.example.projectnoodle.START_SERVER"
const val ACTION_STOP_SERVER = "com.example.projectnoodle.STOP_SERVER" // New action for notification button
const val ACTION_SERVER_STATUS_UPDATE = "com.example.projectnoodle.SERVER_STATUS_UPDATE" // Action for status broadcasts

const val EXTRA_SHARED_DIRECTORY_URI = "com.example.projectnoodle.SHARED_DIRECTORY_URI" // Used by START action
const val EXTRA_SERVER_IS_RUNNING = "com.example.projectnoodle.IS_RUNNING" // Used by STATUS_UPDATE
const val EXTRA_SERVER_OPERATIONAL_STATE = "com.example.projectnoodle.OPERATIONAL_STATE" // Used by STATUS_UPDATE (e.g., "Running", "Stopped", "Failed")
const val EXTRA_SERVER_STATUS_MESSAGE = "com.example.projectnoodle.STATUS_MESSAGE" // Used by STATUS_UPDATE (e.g., "Server running at...", "Failed to start...")
const val EXTRA_SERVER_IP = "com.example.projectnoodle.SERVER_IP" // Used by STATUS_UPDATE
const val EXTRA_SERVER_PORT = "com.example.projectnoodle.SERVER_PORT" // Used by STATUS_UPDATE
const val EXTRA_SHARED_DIRECTORY_NAME = "com.example.projectnoodle.SHARED_DIRECTORY_NAME" // Used by STATUS_UPDATE


class WebServerService : Service() {

    private var server: WebServer? = null
    private var currentSharedDirectoryUri: Uri? = null
    private var currentServerPort: Int = -1
    private var currentIpAddress: String? = null
    private var currentOperationalState: String = "Stopped" // Track state internally in Service
    private var currentDirectoryName: String = "Not Selected" // Track directory name internally

    // FIX: Re-add LocalBroadcastManager variable
    private lateinit var localBroadcastManager: LocalBroadcastManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebServerService onCreate")
        // FIX: Re-initialize LocalBroadcastManager
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
         // Send initial status update when service starts
         sendStatusUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebServerService onStartCommand (startId: $startId, intent: ${intent?.action})")

        when (intent?.action) {
            ACTION_START_SERVER -> {
                 @Suppress("DEPRECATION") // For getParcelableExtra<Uri> pre-API 33
                 val uriToShare: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                 } else {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                 }


                if (uriToShare != null) {
                    Log.d(TAG, "Received START_SERVER action with URI: $uriToShare")

                     if (server != null && server!!.isAlive && uriToShare == currentSharedDirectoryUri) {
                         Log.d(TAG, "Server already running with the same URI, ignoring START_SERVER.")
                         sendStatusUpdate()
                         return START_STICKY
                     }

                     if (server != null && server!!.isAlive) {
                          Log.d(TAG, "Server already running with different URI, stopping to apply new directory...")
                          stopServerInternal()
                     }

                    startServer(uriToShare)

                } else {
                    Log.w(TAG, "Received START_SERVER action but URI extra was null.")
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
            ACTION_STOP_SERVER -> {
                Log.d(TAG, "Received STOP_SERVER action.")
                stopServerInternal()
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Received unknown or null intent action. Attempting to restore server state.")
                if (currentSharedDirectoryUri != null && (server == null || !server!!.isAlive)) {
                     if (currentSharedDirectoryUri != null) {
                         Log.d(TAG, "Service started/restarted. Restarting server with stored URI: $currentSharedDirectoryUri")
                         startServer(currentSharedDirectoryUri!!)
                     } else {
                          Log.w(TAG, "Service restarted by system but no shared directory URI was stored. Server remains stopped.")
                          currentOperationalState = "Stopped: No directory selected."
                          sendStatusUpdate()
                     }
                } else if (server != null && server!!.isAlive) {
                    Log.d(TAG, "Service started/restarted, but server is already running. Sending current status.")
                    sendStatusUpdate()
                } else {
                     Log.d(TAG, "Service started/restarted in stopped state with no stored URI. Server remains stopped.")
                     currentOperationalState = "Stopped: No directory selected."
                     sendStatusUpdate()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "WebServerService onDestroy")
        stopServerInternal()
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
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
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

        val statusText = when (currentOperationalState) {
             "Running" -> getServerStatusText(true)
             "Starting" -> "Starting server..."
             "Stopping" -> "Stopping server..."
             "Failed: No directory selected." -> "Server stopped: No directory selected."
             "Failed: Invalid Directory" -> "Server failed: Invalid directory."
             "Failed: No Port Found" -> "Server failed: No port available."
             "Failed: IO Error" -> "Server failed: IO Error."
             "Failed: Unexpected Error" -> "Server failed: Unexpected error."
             "Failed: Server stopped unexpectedly." -> "Server failed: Stopped unexpectedly."
             "Error Stopping" -> "Error stopping server."
             else -> "Server Stopped."
        }

         val baseBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Server")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(contentPendingIntent)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (currentOperationalState == "Running" || currentOperationalState == "Starting" || currentOperationalState == "Stopping" || currentOperationalState == "Error Stopping") {
             baseBuilder.setOngoing(true)
             baseBuilder.addAction(
                 R.drawable.ic_launcher_foreground,
                 "Stop Server",
                 stopPendingIntent
             )
        } else {
             baseBuilder.setOngoing(false)
        }

        return baseBuilder
    }

     private fun updateNotification() {
         val notification = buildNotification().build()
         // FIX: Get text for logging from the statusText variable used to set it
         val statusTextForLog = when (currentOperationalState) {
             "Running" -> getServerStatusText(true)
             "Starting" -> "Starting server..."
             "Stopping" -> "Stopping server..."
             "Failed: No directory selected." -> "Server stopped: No directory selected."
             "Failed: Invalid Directory" -> "Server failed: Invalid directory."
             "Failed: No Port Found" -> "Server failed: No port available."
             "Failed: IO Error" -> "Server failed: IO Error."
             "Failed: Unexpected Error" -> "Server failed: Unexpected error."
             "Failed: Server stopped unexpectedly." -> "Server failed: Stopped unexpectedly."
             "Error Stopping" -> "Error stopping server."
             else -> "Server Stopped."
         }


         if (currentOperationalState == "Running" || currentOperationalState == "Starting" || currentOperationalState == "Stopping") {
              startForeground(NOTIFICATION_ID, notification)
              Log.d(TAG, "Called startForeground with status: '${statusTextForLog}'")
         } else if (currentOperationalState.startsWith("Failed") || currentOperationalState == "Stopped" || currentOperationalState == "Error Stopping") {
              // FIX: Use modern stopForeground flag
              stopForeground(Service.STOP_FOREGROUND_REMOVE)
              Log.d(TAG, "Called stopForeground(${Service.STOP_FOREGROUND_REMOVE}) with status: '${statusTextForLog}'")
         } else {
             val notificationManager: NotificationManager =
                 getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
              notificationManager.notify(NOTIFICATION_ID, notification)
               Log.d(TAG, "Called notify (fallback) with status: '${statusTextForLog}'")
         }
     }

     private fun getServerStatusText(isRunning: Boolean): String {
          val sharedDirDisplay = if (currentDirectoryName != "Not Selected") "Dir: $currentDirectoryName\n" else ""
         return if (isRunning) {
             if (currentIpAddress != null && currentServerPort != -1) {
                 "${sharedDirDisplay}Server running:\nhttp://$currentIpAddress:$currentServerPort"
             } else if (currentServerPort != -1) {
                  "${sharedDirDisplay}Server running on port $currentServerPort\n(No Wi-Fi IP)"
             } else {
                 "${sharedDirDisplay}Server is running..."
             }
         } else {
             when (currentOperationalState) {
                  "Failed: No directory selected." -> "Server stopped: No directory selected."
                  "Failed: Invalid Directory" -> "Server failed: Invalid directory."
                  "Failed: No Port Found" -> "Server failed: No port available."
                  "Failed: IO Error" -> "Server failed: IO Error."
                  "Failed: Unexpected Error" -> "Server failed: Unexpected error."
                  "Failed: Server stopped unexpectedly." -> "Server failed: Stopped unexpectedly."
                  "Error Stopping" -> "Error stopping server."
                  else -> "${sharedDirDisplay}Server Stopped."
             }
         }
    }

    // --- Service State Broadcasting ---
    private fun sendStatusUpdate(): Unit {
        val statusIntent = Intent(ACTION_SERVER_STATUS_UPDATE).apply {
            putExtra(EXTRA_SERVER_IS_RUNNING, server != null && server!!.isAlive)
            putExtra(EXTRA_SERVER_OPERATIONAL_STATE, currentOperationalState)
            putExtra(EXTRA_SERVER_STATUS_MESSAGE, getServerStatusText(server != null && server!!.isAlive))
            putExtra(EXTRA_SERVER_IP, currentIpAddress)
            putExtra(EXTRA_SERVER_PORT, currentServerPort)
            putExtra(EXTRA_SHARED_DIRECTORY_NAME, currentDirectoryName)
        }
        // FIX: Use LocalBroadcastManager to send broadcast
        localBroadcastManager.sendBroadcast(statusIntent)
        Log.d(TAG, "Sent status broadcast. State: $currentOperationalState, Running: ${server?.isAlive}, Dir: $currentDirectoryName")

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
         currentDirectoryName = DocumentFile.fromTreeUri(applicationContext, uriToShare)?.name ?: uriToShare.lastPathSegment?.let {
              try { Uri.decode(it) } catch (e: Exception) { it }
         } ?: "Unnamed Directory"

         sendStatusUpdate()


        val dynamicPort = findAvailablePort()

        if (dynamicPort != -1) {
            Log.d(TAG, "Service: Found available port: $dynamicPort")
            currentServerPort = dynamicPort

            currentIpAddress = getWifiIPAddress(applicationContext)
             Log.d(TAG, "Service: Current Wi-Fi IP address: $currentIpAddress")

            val rootDoc = DocumentFile.fromTreeUri(applicationContext, uriToShare)
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                 Log.e(TAG, "Service: Invalid or inaccessible root DocumentFile for URI: $uriToShare. Cannot start server.")
                 currentOperationalState = "Failed: Invalid Directory"
                 currentServerPort = -1
                 server = null
                 sendStatusUpdate()
                 return
            }

            server = WebServer(dynamicPort, applicationContext, uriToShare, currentIpAddress)


            try {
                Log.d(TAG, "Service: Attempting to start NanoHTTPD Server on port $currentServerPort.")
                updateNotification() // Calls startForeground with "Starting" state


                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)


                if (server?.isAlive == true) {
                     Log.d(TAG, "Service: NanoHTTPD Server started successfully (isAlive = true).")
                     currentOperationalState = "Running"
                     sendStatusUpdate()

                } else {
                     Log.e(TAG, "Service: NanoHTTPD Server start() returned, but isAlive is false.")
                     currentOperationalState = "Failed: Server stopped unexpectedly."
                     currentServerPort = -1
                     server = null
                     sendStatusUpdate()
                }

            } catch (e: IOException) {
                Log.e(TAG, "Service: Failed to start NanoHTTPD Server on port $currentServerPort (IOException): ${e.message}", e)
                currentOperationalState = "Failed: IO Error"
                 currentServerPort = -1
                 server = null
                 sendStatusUpdate()
            } catch (e: Exception) {
                Log.e(TAG, "Service: An unexpected error occurred during server startup", e)
                currentOperationalState = "Failed: Unexpected Error"
                 currentServerPort = -1
                 server = null
                 sendStatusUpdate()
            }
        } else {
             Log.e(TAG, "Service: Failed to find an available port.")
             currentOperationalState = "Failed: No Port Found"
             currentServerPort = -1
             server = null
             sendStatusUpdate()
        }
         return Unit
    }

    private fun stopServerInternal(): Unit {
        if (server == null) {
             Log.d(TAG, "stopServerInternal(Service): Server instance is null.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             // Directory name is retained on stop
             sendStatusUpdate() // Will call stopForeground(true)
             return
        }

        Log.d(TAG, "stopServerInternal(Service): Attempting to stop NanoHTTPD Server.")
         currentOperationalState = "Stopping"
         sendStatusUpdate() // Will call startForeground(notification) for stopping state


        try {
            server?.stop()
            Log.d(TAG, "Service: NanoHTTPD Server stop() called.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate() // Will call stopForeground(true)

        } catch (e: Exception) {
             Log.e(TAG, "Service: Error calling stop() on NanoHTTPD", e)
             currentOperationalState = "Error Stopping"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate() // Will call stopForeground(true)
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
            } catch (e: IOException) {
                Log.e(TAG, "Service: findAvailablePort: Error closing temporary ServerSocket", e)
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

             val activeNetwork = connectivityManager.activeNetwork
             val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

             if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                 if (!wifiManager.isWifiEnabled) {
                     Log.d(TAG, "Service: getWifiIPAddress: WiFi is connected but reported as not enabled by WifiManager?")
                 }

                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                val ipAddresses = linkProperties?.linkAddresses?.map { it.address.hostAddress }?.filterNotNull()
                 Log.d(TAG, "Service: Found IP addresses from LinkProperties: $ipAddresses")

                 val ipv4 = ipAddresses?.firstOrNull { it.contains(".") && it != "127.0.0.1" }
                 if (ipv4 != null) return ipv4

                 val wifiInfo = wifiManager.connectionInfo
                 val ipAddressInt = wifiInfo?.ipAddress ?: 0
                 if (ipAddressInt != 0 && ipAddressInt != -1) {
                     val ipAddress = Formatter.formatIpAddress(ipAddressInt)
                      Log.d(TAG, "Service: getWifiIPAddress: Found IPv4 (deprecated): $ipAddress")
                      if (ipAddress != "0.0.0.0") return ipAddress
                 }

                 return ipAddresses?.firstOrNull { it != "127.0.0.1" && !it.startsWith("fe80:") }

             } else {
                 Log.d(TAG, "Service: getWifiIPAddress: Not connected to Wi-Fi.")
                 return null
             }
         } catch (e: Exception) {
             Log.e(TAG, "Service: Error getting Wi-Fi IP Address", e)
             return null
         }
     }

    // --- State Management (Internal & Broadcasting) ---
     private fun updateServerState(isRunning: Boolean, operationalState: String? = null, errorMessage: String? = null): Unit {
         if (operationalState != null) {
              currentOperationalState = operationalState
         }
          Log.d(TAG, "Service: updateServerState called: isRunning = ${server?.isAlive}, operationalState = $currentOperationalState")
          return Unit
     }
}
