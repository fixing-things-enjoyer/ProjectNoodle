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

    private val serverPort = 8080
    private val projectNoodleServer = ProjectNoodleServer()
    private lateinit var connectivityManager: ConnectivityManager // Declare here

    // MutableState to hold the IP address, observed by Compose
    private val ipAddressState = mutableStateOf<String?>(null)

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
            // For simplicity, let's try to get an IP from any other available network.
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

    // Renamed and modified to accept a Network object
    private fun updateIpAddress(specificNetwork: Network?) {
        val newIp = getLocalIpAddress(applicationContext, specificNetwork) // Call the new overload
        Log.d("MainActivity", "updateIpAddress(specificNetwork: $specificNetwork): New IP = $newIp")
        ipAddressState.value = newIp
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Initial check - can still use the old way or pass null to the new way
        updateIpAddress(null) // This will try activeNetwork first via the new function

        setContent {
            ProjectNoodleTheme {
                // Pass the ipAddressState.value to the Composable
                ServerControlScreen(
                    server = projectNoodleServer,
                    port = serverPort,
                    currentIpAddress = ipAddressState.value // Observe the state here
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        projectNoodleServer.stopServer()
        connectivityManager.unregisterNetworkCallback(networkCallback) // Crucial: unregister
        Log.d("MainActivity", "MainActivity onDestroy: Server stopped, NetworkCallback unregistered.")
    }
}

@Composable
fun ServerControlScreen(
    server: ProjectNoodleServer,
    port: Int,
    currentIpAddress: String? // Receive the IP address as a state parameter
) {
    // State for the server status message
    var serverStatusText by remember { mutableStateOf("Server Status: Stopped") }
    // State to manage button enabled/disabled state
    var isServerRunning by remember { mutableStateOf(false) }

    val displayIpText = currentIpAddress?.let { "Access at: http://$it:$port" }
        ?: "IP Address: Waiting for suitable network..."


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = serverStatusText,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = displayIpText, // Use the derived display text
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                if (currentIpAddress != null) { // Only start if IP is available
                    server.startServer(currentIpAddress)
                    isServerRunning = true
                } else {
                    serverStatusText = "Server Status: Cannot start, IP not available."
                    // Optionally show a Toast or Snackbar
                }
            },
            enabled = !isServerRunning && currentIpAddress != null, // Also check if IP is available
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Start Server")
        }

        Button(
            onClick = {
                server.stopServer()
                serverStatusText = "Server Status: Stopped"
                isServerRunning = false
            },
            enabled = isServerRunning
        ) {
            Text("Stop Server")
        }
    }
}
