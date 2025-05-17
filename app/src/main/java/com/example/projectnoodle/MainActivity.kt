package com.example.projectnoodle

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.projectnoodle.ui.theme.ProjectNoodleTheme
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var server: WebServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get Wi-Fi IP address
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddressInt = wifiManager.connectionInfo.ipAddress
        val ipAddress = String.format(
            "%d.%d.%d.%d",
            (ipAddressInt and 0xff),
            (ipAddressInt shr 8 and 0xff),
            (ipAddressInt shr 16 and 0xff),
            (ipAddressInt shr 24 and 0xff)
        )
        println("Binding server to IP: $ipAddress")

        server = WebServer(ipAddress)
        try {
            server.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Self-test
        thread {
            println("Starting self-test")
            try {
                val url = URL("http://127.0.0.1:20775")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val responseCode = connection.responseCode
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                println("Internal test: Response code $responseCode, body: $response")
                connection.disconnect()
            } catch (e: Exception) {
                println("Self-test failed: ${e.message}")
                e.printStackTrace()
            }
        }

        setContent {
            ProjectNoodleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ProjectNoodleTheme {
        Greeting("Android")
    }
}

class WebServer(private val ipAddress: String) : NanoHTTPD(ipAddress, 20775) {
    init {
        println("Server initialized")
    }

    override fun start() {
        // Set custom ServerSocketFactory to force IPv4
        serverSocketFactory = object : DefaultServerSocketFactory() {
            override fun create(): ServerSocket {
                return ServerSocket(20775, 0, Inet4Address.getByName(ipAddress))
            }
        }
        super.start()
        println("Server started on port 20775")
    }

    override fun serve(session: IHTTPSession): Response {
        println("Received request: ${session.uri}")
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "Hello World!")
        println("Sending response: Hello World!")
        return response
    }
}
