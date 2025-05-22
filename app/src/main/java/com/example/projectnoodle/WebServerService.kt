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
import androidx.preference.PreferenceManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress
import java.io.File // Required for DocumentFile.fromFile

private const val TAG = "ProjectNoodleService"
private const val NOTIFICATION_CHANNEL_ID = "project_noodle_server_channel"
private const val NOTIFICATION_ID = 1
private const val FAILURE_NOTIFICATION_ID = 2

const val APPROVAL_CHANNEL_ID = "project_noodle_approval_channel"
const val APPROVAL_NOTIFICATION_ID = 3
const val ACTION_APPROVE_CLIENT = "com.example.projectnoodle.APPROVE_CLIENT"
const val ACTION_REJECT_CLIENT = "com.example.projectnoodle.REJECT_CLIENT"
const val EXTRA_CLIENT_IP_FOR_APPROVAL = "com.example.projectnoodle.CLIENT_IP_FOR_APPROVAL" // This is the correct constant name

const val ACTION_START_SERVER = "com.example.projectnoodle.START_SERVER"
const val ACTION_STOP_SERVER = "com.example.projectnoodle.STOP_SERVER"
const val ACTION_SERVER_STATUS_UPDATE = "com.example.projectnoodle.SERVER_STATUS_UPDATE"
const val ACTION_QUERY_STATUS = "com.example.projectnoodle.QUERY_STATUS"


const val EXTRA_SHARED_DIRECTORY_URI = "com.example.projectnoodle.SHARED_DIRECTORY_URI"
const val EXTRA_REQUIRE_APPROVAL = "com.example.projectnoodle.REQUIRE_APPROVAL"

const val EXTRA_SERVER_IS_RUNNING = "com.example.projectnoodle.IS_RUNNING"
const val EXTRA_SERVER_OPERATIONAL_STATE = "com.example.projectnoodle.OPERATIONAL_STATE"
const val EXTRA_SERVER_STATUS_MESSAGE = "com.example.projectnoodle.STATUS_MESSAGE"
const val EXTRA_SERVER_IP = "com.example.projectnoodle.SERVER_IP"
const val EXTRA_SERVER_PORT = "com.example.projectnoodle.SERVER_PORT"
const val EXTRA_SHARED_DIRECTORY_NAME = "com.example.projectnoodle.SHARED_DIRECTORY_NAME"

const val PREF_REQUIRE_APPROVAL = "pref_require_approval"


class WebServerService : Service(), ConnectionApprovalListener {

    private var server: WebServer? = null
    private var currentSharedDirectoryUri: Uri? = null
    private var currentServerPort: Int = -1
    private var currentIpAddress: String? = null
    private var currentOperationalState: String = "Stopped"
    private var requireApprovalEnabled: Boolean = false


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
        loadPreferences() // Still load preferences here
        sendStatusUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebServerService onStartCommand received intent action: ${intent?.action} (StartId: $startId)")
        Log.d(TAG, "ACTION_STOP_SERVER constant value: $ACTION_STOP_SERVER")

        when (intent?.action) {
            ACTION_START_SERVER -> {
                 @Suppress("DEPRECATION")
                 val uriToShare: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                 } else {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                 }
                 val newRequireApprovalEnabled = intent.getBooleanExtra(EXTRA_REQUIRE_APPROVAL, false)
                 Log.d(TAG, "Received START_SERVER action. requireApprovalEnabled = $newRequireApprovalEnabled")
                this.requireApprovalEnabled = newRequireApprovalEnabled

                if (uriToShare != null) {
                    Log.d(TAG, "Received START_SERVER action with URI: $uriToShare")

                     if (server != null && server!!.isAlive && uriToShare == currentSharedDirectoryUri && newRequireApprovalEnabled == requireApprovalEnabled) {
                         Log.d(TAG, "Server already running with the same URI and approval setting, ignoring START_SERVER.")
                         sendStatusUpdate()
                         return START_STICKY
                     }

                     if (server != null && server!!.isAlive) {
                          Log.d(TAG, "Server already running, but configuration changed. Stopping server to apply new settings...")
                          stopServerInternal()
                     }

                    currentSharedDirectoryUri = uriToShare

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
            ACTION_QUERY_STATUS -> {
                 Log.d(TAG, "Received QUERY_STATUS action.")
                 @Suppress("DEPRECATION")
                 val uriFromQuery: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
                 } else {
                     intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
                 }
                 Log.d(TAG, "QUERY_STATUS intent contained URI: $uriFromQuery")

                 if (currentSharedDirectoryUri == null && uriFromQuery != null) {
                     Log.d(TAG, "Service adopting shared directory URI from QUERY_STATUS: $uriFromQuery")
                     currentSharedDirectoryUri = uriFromQuery
                 } else if (currentSharedDirectoryUri != null && uriFromQuery == null) {
                       Log.w(TAG, "Service has a URI (${currentSharedDirectoryUri}) but QUERY_STATUS intent sent null URI. Keeping Service's URI.")
                 } else if (currentSharedDirectoryUri != null && uriFromQuery != null && currentSharedDirectoryUri != uriFromQuery) {
                      Log.w(TAG, "Service has URI (${currentSharedDirectoryUri}) different from QUERY_STATUS intent URI (${uriFromQuery}). Adopting Activity's URI state.")
                      currentSharedDirectoryUri = uriFromQuery
                       Log.d(TAG, "Service updated currentSharedDirectoryUri to: $currentSharedDirectoryUri")
                 } else {
                      Log.d(TAG, "Service URI state matches QUERY_STATUS URI ($currentSharedDirectoryUri) or both are null. No URI adoption needed.")
                 }
                 loadPreferences()
                 sendStatusUpdate()
                 return START_STICKY
            }
            ACTION_STOP_SERVER -> {
                Log.d(TAG, "Received explicit STOP_SERVER action. Entering stop logic.")
                stopServerInternal()
                stopSelf()
            }
            ACTION_APPROVE_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL) // FIX: Corrected typo here
                if (clientIp != null) {
                    server?.approveClient(clientIp)
                    Log.i(TAG, "Client $clientIp approved via notification.")
                } else {
                    Log.w(TAG, "ACTION_APPROVE_CLIENT received with null IP.")
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
            }
            ACTION_REJECT_CLIENT -> {
                val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL) // FIX: Corrected typo here
                if (clientIp != null) {
                    server?.denyClient(clientIp)
                    Log.i(TAG, "Client $clientIp rejected via notification.")
                } else {
                    Log.w(TAG, "ACTION_REJECT_CLIENT received with null IP.")
                }
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
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID with LOW importance and no sound.")
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

        val statusText = getServerStatusText(server != null && server!!.isAlive)


         val baseBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Server")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)


        if (currentOperationalState == "Running" || currentOperationalState == "Stopping" || currentOperationalState == "Error Stopping") {
             baseBuilder.addAction(
                 R.drawable.ic_launcher_foreground,
                 "Stop Server",
                 stopPendingIntent
             )
        }


        return baseBuilder
    }

     private fun updateNotification() {
        val notificationManager: NotificationManager =
                 getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FAILURE_NOTIFICATION_ID)
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)


         if (currentOperationalState == "Running" || currentOperationalState == "Starting" || currentOperationalState == "Stopping") {
              val notification = buildNotification().build()
              val statusTextForLog = getServerStatusText(server != null && server!!.isAlive)
              startForeground(NOTIFICATION_ID, notification)
              Log.d(TAG, "Called startForeground with status: '${statusTextForLog}'")
         } else {
              stopForeground(Service.STOP_FOREGROUND_REMOVE)
              Log.d(TAG, "Called stopForeground(${Service.STOP_FOREGROUND_REMOVE}) for state: ${currentOperationalState}")

              val statusText = getServerStatusText(server != null && server!!.isAlive)

              if (currentOperationalState.startsWith("Failed") || currentOperationalState == "Error Stopping") {
                   val failureNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name) + " Server Error")
                        .setContentText(statusText)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentIntent(buildNotification().build().contentIntent)
                        .setShowWhen(false)
                        .setSilent(false)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)

                   notificationManager.notify(FAILURE_NOTIFICATION_ID, failureNotificationBuilder.build())
                   Log.d(TAG, "Shown failure notification (ID: $FAILURE_NOTIFICATION_ID) with status: '${statusText}'")

              } else if (currentOperationalState == "Stopped") {
                   Log.d(TAG, "Server successfully stopped. Foreground notification removed.")
              } else {
                  val nonPersistentBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name) + " Server Info")
                        .setContentText(statusText)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentIntent(buildNotification().build().contentIntent)
                        .setShowWhen(false)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                  notificationManager.notify(NOTIFICATION_ID, nonPersistentBuilder.build())
                   Log.d(TAG, "Called notify (fallback, non-persistent) with status: '${statusText}'")
              }
         }
     }

     private fun getServerStatusText(isRunning: Boolean): String {
          val directoryName = currentSharedDirectoryUri?.let { uri ->
              try {
                  // SAF only gives content:// URIs, so fromTreeUri is sufficient
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
          val approvalStatus = if (requireApprovalEnabled) "Approval Required" else "Approval Not Required"

         return if (isRunning) {
             if (currentIpAddress != null && currentServerPort != -1) {
                 "${sharedDirDisplay}${approvalStatus}\nServer running:\nhttp://$currentIpAddress:$currentServerPort"
             } else if (currentServerPort != -1) {
                  "${sharedDirDisplay}${approvalStatus}\nServer running on port $currentServerPort\n(No Wi-Fi IP)"
             } else {
                 "${sharedDirDisplay}${approvalStatus}\nServer is running..."
             }
         } else {
             when (currentOperationalState) {
                  "Stopped" -> "${sharedDirDisplay}Server Stopped.\n$approvalStatus"
                  "Failed: No directory selected." -> "Server stopped: No directory selected.\n$approvalStatus"
                  "Failed: Invalid Directory" -> "${directoryName}\nServer failed: Invalid directory.\n$approvalStatus"
                  "Failed: No Port Found" -> "Server failed: No port available.\n$approvalStatus"
                  "Failed: IO Error" -> "Server failed: IO Error.\n$approvalStatus"
                  "Failed: Unexpected Error" -> "Server failed: Unexpected error.\n$approvalStatus"
                  "Error Stopping" -> "Error stopping server.\n$approvalStatus"
                  "Starting" -> "Starting server...\n$approvalStatus"
                  "Stopping" -> "Stopping server...\n$approvalStatus"
                   "Requesting Permission" -> "Requesting notification permission...\n$approvalStatus"
                  else -> "Server Stopped.\n$approvalStatus"
             }
         }
    }

    private fun sendStatusUpdate(): Unit {
        val directoryNameForBroadcast = currentSharedDirectoryUri?.let { uri ->
              try {
                  // SAF only gives content:// URIs, so fromTreeUri is sufficient
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
            putExtra(EXTRA_SERVER_STATUS_MESSAGE, getServerStatusText(server != null && server!!.isAlive))
            putExtra(EXTRA_SERVER_IP, currentIpAddress)
            putExtra(EXTRA_SERVER_PORT, currentServerPort)
            putExtra(EXTRA_SHARED_DIRECTORY_NAME, directoryNameForBroadcast)
        }
        localBroadcastManager.sendBroadcast(statusIntent)
        Log.d(TAG, "Sent status broadcast. State: $currentOperationalState, Running: ${server?.isAlive}, Dir: $directoryNameForBroadcast, Approval Req: $requireApprovalEnabled")

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

         sendStatusUpdate()


        val dynamicPort = findAvailablePort()

        if (dynamicPort != -1) {
            Log.d(TAG, "Service: Found available port: $dynamicPort")
            currentServerPort = dynamicPort

            currentIpAddress = getWifiIPAddress(applicationContext)
             Log.d(TAG, "Service: Current Wi-Fi IP address: $currentIpAddress")

            // SAF only gives content:// URIs, so fromTreeUri is sufficient
            val rootDoc: DocumentFile? = DocumentFile.fromTreeUri(applicationContext, uriToShare)


            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                 Log.e(TAG, "Service: Invalid or inaccessible root DocumentFile for URI: $uriToShare. Cannot start server.")
                 currentOperationalState = "Failed: Invalid Directory"
                 currentServerPort = -1
                 currentIpAddress = null
                 server = null
                 sendStatusUpdate()
                 return
            }

            server = WebServer(dynamicPort, applicationContext, uriToShare, currentIpAddress, requireApprovalEnabled, this)


            try {
                Log.d(TAG, "Service: Attempting to start NanoHTTPD Server on port $currentServerPort.")

                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)


                if (server?.isAlive == true) {
                     Log.d(TAG, "Service: NanoHTTPD Server started successfully (isAlive = true).")
                     currentOperationalState = "Running"
                     sendStatusUpdate()

                } else {
                     Log.e(TAG, "Service: NanoHTTPD Server start() returned, but isAlive is false.")
                     currentOperationalState = "Failed: Server stopped unexpectedly."
                     currentServerPort = -1
                     currentIpAddress = null
                     server = null
                     sendStatusUpdate()
                }

            } catch (e: IOException) {
                Log.e(TAG, "Service: Failed to start NanoHTTPD Server on port $currentServerPort (IOException): ${e.message}", e)
                currentOperationalState = "Failed: IO Error"
                 currentServerPort = -1
                 currentIpAddress = null
                 server = null
                 sendStatusUpdate()
            } catch (e: Exception) {
                Log.e(TAG, "Service: An unexpected error occurred during server startup", e)
                currentOperationalState = "Failed: Unexpected Error"
                 currentServerPort = -1
                 currentIpAddress = null
                 server = null
                 sendStatusUpdate()
            }
        } else {
             Log.e(TAG, "Service: Failed to find an available port.")
             currentOperationalState = "Failed: No Port Found"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate()
        }
         return Unit
    }

    private fun stopServerInternal(): Unit {
        if (server == null) {
             Log.d(TAG, "stopServerInternal(Service): Server instance is null. Already stopped.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             sendStatusUpdate()
             return
        }

        Log.d(TAG, "stopServerInternal(Service): Attempting to stop NanoHTTPD Server.")
         currentOperationalState = "Stopping"
         sendStatusUpdate()


        try {
            server?.stop()
            Log.d(TAG, "Service: NanoHTTPD Server stop() called.")
             currentOperationalState = "Stopped"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate()

        } catch (e: Exception) {
             Log.e(TAG, "Service: Error calling stop() on NanoHTTPD", e)
             currentOperationalState = "Error Stopping"
             currentServerPort = -1
             currentIpAddress = null
             server = null
             sendStatusUpdate()
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

     @Suppress("DEPRECATION")
     private fun getWifiIPAddress(context: Context): String? {
         try {
             val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
             val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

             if (connectivityManager == null || wifiManager == null) {
                  Log.w(TAG, "Service: getWifiIPAddress: ConnectivityManager or WifiManager is null.")
                  return null
             }

             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                 val activeNetwork = connectivityManager.activeNetwork
                 val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

                 if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                     val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                     val ipAddresses = linkProperties?.linkAddresses?.map { it.address }?.filterNotNull()
                     Log.d(TAG, "Service: Found IP addresses from LinkProperties: $ipAddresses")

                     val ipv4 = ipAddresses?.firstOrNull { it is InetAddress && it.hostAddress?.contains(".") == true && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                      if (ipv4 != null) {
                          Log.d(TAG, "Service: Found IPv4 (modern): $ipv4")
                         return ipv4
                      }

                     return ipAddresses?.firstOrNull { it is InetAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress

                 } else {
                     Log.d(TAG, "Service: getWifiIPAddress: Not connected to Wi-Fi (NetworkCapabilities check).")
                     return null
                 }
             } else {
                 val wifiInfo = wifiManager.connectionInfo
                 val ipAddressInt = wifiInfo?.ipAddress ?: 0
                 if (ipAddressInt != 0 && ipAddressInt != -1) {
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
        Log.d(TAG, "Service loaded preferences: requireApprovalEnabled = $requireApprovalEnabled")
    }
}
