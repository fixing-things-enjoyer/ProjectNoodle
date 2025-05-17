package com.example.projectnoodle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme
import kotlinx.coroutines.launch // For launching coroutine from callback

class MainActivity : ComponentActivity() {

    // Removed the fixed serverPort variable as it's not used to start the server
    private val projectNoodleServer = ProjectNoodleServer()
    private lateinit var connectivityManager: ConnectivityManager

    // MutableState to hold the IP address from NetworkUtils, observed by Compose
    private val ipAddressState = mutableStateOf<String?>(null)

    // MutableStates to hold the server's *actual* state, observed by Compose
    private val isServerRunningState = mutableStateOf(false)
    private val actualServerPortState = mutableStateOf<Int?>(null)
    private val serverStatusTextState = mutableStateOf("Server Status: Stopped")

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("MainActivity", "NetworkCallback: Network available: $network")
            // Pass the specific network object
            updateIpAddress(network) // Pass the network that just became available
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d("MainActivity", "NetworkCallback: Network lost: $network")
            // When a network is lost, we might need to re-evaluate based on activeNetwork,
            // or if this 'network' was the one we were using, clear the IP.
            updateIpAddress(null) // null will make it try activeNetwork or return null
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            Log.d("MainActivity", "NetworkCallback: Capabilities changed for $network: $networkCapabilities")
            // Pass the specific network object
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                Log.d("MainActivity", "Capabilities suitable, attempting to get IP for $network")
                updateIpAddress(network)
            } else {
                Log.d("MainActivity", "Capabilities not suitable for $network, checking active.")
                // If the changed network is no longer suitable, perhaps another one is.
                // Or if this was our main network, the IP might be gone.
                updateIpAddress(null) // Re-evaluate based on potentially different active network
            }
        }
    }

    // Implement the server state listener
    private val serverStateListener: ((isRunning: Boolean, port: Int, ip: String?) -> Unit) = { isRunning, port, ip ->
        Log.d("MainActivity", "Server state updated: isRunning=$isRunning, port=$port, ip=$ip")
        // Update the Compose state variables
        serverStatusTextState.value = when {
            isRunning && port != -1 -> "Server Status: Running on port $port"
            !isRunning -> "Server Status: Stopped"
            else -> "Server Status: State unknown (Port: $port)" // Should ideally not happen with -1 for stopped
        }
        isServerRunningState.value = isRunning
        actualServerPortState.value = if (isRunning) port else null
        // Note: We are not updating ipAddressState from here.
        // ipAddressState reflects the *current network state* detected by NetworkUtils,
        // which is what the UI uses to determine *if* starting is possible and for the base URL.
        // The server's internal 'ip' could potentially be different if the network changes
        // while the server is running, but for simplicity, we'll rely on ipAddressState
        // for the display URL part. The server binds to all available interfaces usually.
    }


    // Renamed and modified to accept a Network object
    private fun updateIpAddress(specificNetwork: Network?) {
        // Use a coroutine scope if context requires it for state updates,
        // but mutableStateOf updates are typically main-thread safe from callbacks like this.
        // If complex logic or IO were involved, a CoroutineScope would be needed.
        val newIp = getLocalIpAddress(applicationContext, specificNetwork)
        Log.d("MainActivity", "updateIpAddress(specificNetwork: $specificNetwork): New IP = $newIp")
        ipAddressState.value = newIp

        // If the network IP becomes null while the server is running,
        // we might want to stop it automatically or update status.
        // For now, the UI button disables correctly.
        if (newIp == null && isServerRunningState.value) {
            // Optional: Auto-stop server if network is lost?
            // projectNoodleServer.stopServer()
            // The serverStateListener would handle the UI update.
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) // Ensure it has internet access
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Set the server state listener
        projectNoodleServer.serverStateListener = serverStateListener

        // Initial check for IP address
        updateIpAddress(null) // This will try activeNetwork first via the new function

        setContent {
            ProjectNoodleTheme {
                // Pass the observed states to the Composable
                ServerControlScreen(
                    server = projectNoodleServer,
                    isRunning = isServerRunningState.value, // Pass server's actual running state
                    statusText = serverStatusTextState.value, // Pass server's actual status text
                    actualPort = actualServerPortState.value, // Pass server's actual port
                    currentIpAddress = ipAddressState.value // Pass the detected IP address
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the listener before stopping, if necessary, to prevent calls on a destroyed Activity
        projectNoodleServer.serverStateListener = null // Good practice to nullify listeners
        projectNoodleServer.stopServer()
        connectivityManager.unregisterNetworkCallback(networkCallback) // Crucial: unregister
        Log.d("MainActivity", "MainActivity onDestroy: Server stopped, NetworkCallback unregistered.")
    }
}

@Composable
fun ServerControlScreen(
    server: ProjectNoodleServer, // Still pass the server instance to trigger actions
    isRunning: Boolean, // Receive server's running state
    statusText: String, // Receive server's status text
    actualPort: Int?, // Receive server's actual port
    currentIpAddress: String? // Receive the detected IP address
) {
    // Removed local state variables, they are now received from the Activity

    val displayIpText = when {
        isRunning && actualPort != null && currentIpAddress != null -> "Access at: http://$currentIpAddress:$actualPort"
        currentIpAddress != null -> "IP Address: Available - Ready to start" // Indicate IP is found but server is not running
        else -> "IP Address: Waiting for suitable network..." // Indicate no suitable network/IP found
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = statusText, // Use the passed status text
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = displayIpText, // Use the derived display text based on passed states
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                // Pass the current IP address detected by NetworkUtils to the server
                server.startServer(currentIpAddress)
                // State updates will now happen via the serverStateListener
            },
            // Enable Start only if not running AND an IP is available
            enabled = !isRunning && currentIpAddress != null,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Start Server")
        }

        Button(
            onClick = {
                server.stopServer()
                // State updates will now happen via the serverStateListener
            },
            enabled = isRunning // Enable Stop only if server is running
        ) {
            Text("Stop Server")
        }
    }
}
