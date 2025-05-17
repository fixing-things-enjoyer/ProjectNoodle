package com.example.projectnoodle

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

class MainActivity : ComponentActivity() {
    private lateinit var server: WebServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = WebServer()
        try {
            server.start()
        } catch (e: IOException) {
            e.printStackTrace()
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

class WebServer : NanoHTTPD("0.0.0.0", 20775) {
    override fun serve(session: IHTTPSession): Response {
        return newFixedLengthResponse("Hello World!")
    }
}
