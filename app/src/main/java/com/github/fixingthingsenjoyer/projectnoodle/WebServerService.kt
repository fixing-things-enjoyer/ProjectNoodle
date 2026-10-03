package com.github.fixingthingsenjoyer.projectnoodle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors

const val ACTION_START_SERVER = "com.github.fixingthingsenjoyer.projectnoodle.START_SERVER"
const val ACTION_STOP_SERVER = "com.github.fixingthingsenjoyer.projectnoodle.STOP_SERVER"
const val ACTION_APPROVE_CLIENT = "com.github.fixingthingsenjoyer.projectnoodle.APPROVE_CLIENT"
const val ACTION_REJECT_CLIENT = "com.github.fixingthingsenjoyer.projectnoodle.REJECT_CLIENT"
const val EXTRA_SHARED_DIRECTORY_URI = "shared_directory_uri"
const val EXTRA_REQUIRE_APPROVAL = "require_approval"
const val EXTRA_USE_HTTPS = "use_https"
const val EXTRA_CLIENT_IP_FOR_APPROVAL = "client_ip"
const val PREF_REQUIRE_APPROVAL = "pref_require_approval"
const val PREF_USE_HTTPS = "pref_use_https"
const val PREF_SHARED_DIRECTORY_URI = "pref_shared_directory_uri"

private const val SERVER_CHANNEL = "project_noodle_server_channel"
private const val APPROVAL_CHANNEL = "project_noodle_approval_channel"
private const val SERVER_NOTIFICATION_ID = 1

class WebServerService : Service(), ConnectionApprovalListener {
    private var server: WebServer? = null
    private var sharedUri: Uri? = null
    private var https = false
    private var requireApproval = false
    private var port = -1
    private var destroyed = false
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallbackRegistered = false
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAddress()

            override fun onLost(network: Network) = refreshAddress()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) = refreshAddress()
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                        SERVER_CHANNEL,
                        "File sharing",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { setShowBadge(false) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    APPROVAL_CHANNEL,
                    "Connection requests",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )
        networkCallbackRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startSharing(intent)
            ACTION_STOP_SERVER -> stopSharing()
            ACTION_APPROVE_CLIENT,
            ACTION_REJECT_CLIENT -> {
                val client = intent.getStringExtra(EXTRA_CLIENT_IP_FOR_APPROVAL)
                if (client != null) {
                    if (intent.action == ACTION_APPROVE_CLIENT) server?.approveClient(client)
                    else server?.denyClient(client)
                    manager.cancel(client, 2)
                    SharingSession.mutableState.value =
                        SharingSession.state.value.copy(
                            pendingClients = SharingSession.state.value.pendingClients - client
                        )
                }
                if (server == null && !SharingSession.state.value.busy) stopSelf()
            }
            else -> if (server == null) stopSelf()
        }
        // An intentional sharing session must never restart without its selected
        // folder/configuration.
        return START_NOT_STICKY
    }

    private fun startSharing(intent: Intent) {
        @Suppress("DEPRECATION")
        val uri =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI, Uri::class.java)
            else intent.getParcelableExtra(EXTRA_SHARED_DIRECTORY_URI)
        val newHttps = intent.getBooleanExtra(EXTRA_USE_HTTPS, false)
        val newApproval = intent.getBooleanExtra(EXTRA_REQUIRE_APPROVAL, false)
        if (
            server?.isAlive == true &&
                uri == sharedUri &&
                https == newHttps &&
                requireApproval == newApproval
        )
            return
        generation++
        val startGeneration = generation
        // Enter foreground before touching storage or generating an HTTPS certificate.
        SharingSession.mutableState.value = SharingState(status = "Starting")
        startForeground(SERVER_NOTIFICATION_ID, notification())
        val previousServer = server
        server = null
        sharedUri = uri
        https = newHttps
        requireApproval = newApproval
        worker.execute {
            previousServer?.stop()
            var next: WebServer? = null
            try {
                requireNotNull(uri) { "Choose a folder before sharing." }
                val folder = DocumentFile.fromTreeUri(applicationContext, uri)
                require(folder != null && folder.isDirectory && folder.canRead()) {
                    "Folder access was lost. Choose your folder again."
                }
                val nextPort = ServerSocket(0).use { it.localPort }
                val ip = lanAddress()
                val listener =
                    object : ConnectionApprovalListener {
                        override fun onNewClientConnectionAttempt(clientIp: String) {
                            main.post {
                                if (!destroyed && generation == startGeneration)
                                    this@WebServerService.onNewClientConnectionAttempt(clientIp)
                            }
                        }
                    }
                next =
                    if (newHttps)
                        HttpsWebServer(nextPort, applicationContext, uri, ip, newApproval, listener)
                    else WebServer(nextPort, applicationContext, uri, ip, newApproval, listener)
                next.start(30_000, false)
                check(next.isAlive) { "Could not start sharing. Try again." }
                val started = next
                main.post {
                    if (destroyed || generation != startGeneration) {
                        started.stop()
                        return@post
                    }
                    server = started
                    port = nextPort
                    SharingSession.mutableState.value =
                        SharingSession.state.value.copy(
                            status = "Running",
                            address = shareAddress(lanAddress(), port, https),
                            folderName = folder.name,
                            error = null,
                        )
                    manager.notify(SERVER_NOTIFICATION_ID, notification())
                }
            } catch (error: Exception) {
                next?.stop()
                Log.e("NoodleService", "Could not start sharing", error)
                main.post {
                    if (destroyed || generation != startGeneration) return@post
                    SharingSession.mutableState.value =
                        SharingState(
                            status = "Failed",
                            error =
                                error.message
                                    ?: "Could not start sharing. Choose your folder again and retry.",
                        )
                    removeForeground()
                    stopSelf()
                }
            }
        }
    }

    private fun stopSharing() {
        generation++
        val old = server
        server = null
        SharingSession.mutableState.value =
            SharingSession.state.value.copy(
                status = "Stopping",
                address = null,
                pendingClients = emptyList(),
            )
        worker.execute {
            old?.stop()
            main.post {
                if (!destroyed) {
                    SharingSession.mutableState.value = SharingState()
                    manager.cancelAll()
                    removeForeground()
                    stopSelf()
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 gives dataSync services a background time budget. Release foreground
        // immediately.
        generation++
        server?.stop()
        server = null
        SharingSession.mutableState.value =
            SharingState(
                status = "Failed",
                error = "Android ended this long sharing session. Tap Start sharing to reconnect.",
            )
        manager.cancelAll()
        removeForeground()
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        if (networkCallbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        server?.stop()
        server = null
        worker.shutdown() // Already queued start/stop operations finish and clean up their own
        // sockets.
        if (SharingSession.state.value.status != "Failed")
            SharingSession.mutableState.value = SharingState()
        manager.cancelAll()
        removeForeground()
        super.onDestroy()
    }

    private fun removeForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(): android.app.Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, WebServerService::class.java).setAction(ACTION_STOP_SERVER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val state = SharingSession.state.value
        return NotificationCompat.Builder(this, SERVER_CHANNEL)
            .setSmallIcon(R.drawable.ic_share_notification)
            .setContentTitle(
                if (state.running) "Sharing ${state.folderName ?: "your folder"}"
                else "Starting file sharing…"
            )
            .setContentText(state.address ?: "No network address.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    override fun onNewClientConnectionAttempt(clientIp: String) {
        main.post {
            if (destroyed || SharingSession.state.value.status !in listOf("Starting", "Running"))
                return@post
            SharingSession.mutableState.value =
                SharingSession.state.value.copy(
                    pendingClients =
                        (SharingSession.state.value.pendingClients + clientIp).distinct()
                )
            val approve = clientIntent(clientIp, ACTION_APPROVE_CLIENT)
            val deny = clientIntent(clientIp, ACTION_REJECT_CLIENT)
            val open =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat.Builder(this, APPROVAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_share_notification)
                    .setContentTitle("Connection request")
                    .setContentText(clientIp)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .addAction(0, "Allow", approve)
                    .addAction(0, "Decline", deny)
                    .build()
            manager.notify(clientIp, 2, notification)
        }
    }

    private fun clientIntent(client: String, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, WebServerService::class.java)
                .setAction(action)
                .setData(Uri.parse("noodle://client/${Uri.encode(client)}"))
                .putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, client),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun refreshAddress() {
        main.post {
            if (SharingSession.state.value.running && !destroyed) {
                SharingSession.mutableState.value =
                    SharingSession.state.value.copy(
                        address = shareAddress(lanAddress(), port, https)
                    )
                manager.notify(SERVER_NOTIFICATION_ID, notification())
            }
        }
    }

    private fun lanAddress(): String? {
        val address =
            connectivity.allNetworks
                .asSequence()
                .filter { network ->
                    connectivity.getNetworkCapabilities(network)?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    } == true
                }
                .flatMap {
                    connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence()
                }
                .map { it.address }
                .firstOrNull {
                    it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                }
        if (address != null) return address.hostAddress
        // Hotspot interfaces are not necessarily represented by ConnectivityManager networks.
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter {
                    it.isUp &&
                        (it.name.startsWith("wlan") ||
                            it.name.startsWith("ap") ||
                            it.name.startsWith("eth"))
                }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }
            .getOrNull()
    }
}
