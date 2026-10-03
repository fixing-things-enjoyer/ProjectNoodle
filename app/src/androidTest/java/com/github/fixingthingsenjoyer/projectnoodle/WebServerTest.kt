package com.github.fixingthingsenjoyer.projectnoodle

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebServerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tree: Uri
    private lateinit var server: WebServer
    private var approvalAttempts = 0

    @Before
    fun setup() {
        val result =
            context.contentResolver.call(
                Uri.parse("content://com.github.fixingthingsenjoyer.projectnoodle.test.setup"),
                "reset",
                context.packageName,
                null,
            )!!
        tree = Uri.parse(result.getString("uri"))
        server = WebServer(0, context, tree, "127.0.0.1", false, null)
        server.start()
    }

    @After
    fun teardown() {
        if (::server.isInitialized) server.stop()
    }

    private fun request(
        route: String,
        values: Map<String, String>? = null,
        origin: String? = null,
    ): Pair<Int, String> {
        val connection =
            URL("http://127.0.0.1:${server.listeningPort}$route").openConnection()
                as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (origin != null) connection.setRequestProperty("Origin", origin)
        if (values != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use {
                it.write(
                    values.entries
                        .joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                        .toByteArray()
                )
            }
        }
        return try {
            val code = connection.responseCode
            code to
                (if (code >= 400) connection.errorStream else connection.inputStream)
                    .bufferedReader()
                    .use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test
    fun servesBundledReactAssets() {
        val (status, html) = request("/")
        assertEquals(200, status)
        val asset = Regex("src=\"([^\"]+\\.js)\"").find(html)!!.groupValues[1]
        assertEquals(200, request(asset).first)
        assertTrue(html.contains("Noodle"))
    }

    @Test
    fun createsRenamesAndDeletesLiteralNames() {
        val original = "Photos + 50%2F 写真"
        assertEquals(
            201,
            request("/api/mkdir", mapOf("path" to "/", "newDirName" to original)).first,
        )
        val listing = JSONObject(request("/api/list?path=%2F").second)
        assertEquals(original, listing.getJSONArray("items").getJSONObject(0).getString("name"))
        assertTrue(listing.getBoolean("canWrite"))
        assertEquals(200, request("/api/list?path=" + encode("/$original")).first)
        assertEquals(
            200,
            request("/api/rename", mapOf("path" to "/$original", "newName" to "New + 20%")).first,
        )
        assertEquals(200, request("/api/delete", mapOf("path" to "/New + 20%")).first)
        assertEquals(0, JSONObject(request("/api/list").second).getJSONArray("items").length())
    }

    @Test
    fun rejectsTraversalRootChangesAndDuplicates() {
        assertEquals(400, request("/api/list?path=" + encode("/../secret")).first)
        assertEquals(403, request("/api/delete", mapOf("path" to "/")).first)
        assertEquals(403, request("/api/rename", mapOf("path" to "/", "newName" to "oops")).first)
        assertEquals(201, request("/api/mkdir", mapOf("newDirName" to "Trip")).first)
        assertEquals(409, request("/api/mkdir", mapOf("newDirName" to "Trip")).first)
        assertEquals(400, request("/api/mkdir", mapOf("newDirName" to "../oops")).first)
    }

    @Test
    fun rejectsCrossOriginWrites() {
        assertEquals(
            403,
            request("/api/mkdir", mapOf("newDirName" to "oops"), "https://example.org").first,
        )
    }

    @Test
    fun approvalKeepsAppAccessibleAndDeduplicatesRequests() {
        server.stop()
        server =
            WebServer(
                0,
                context,
                tree,
                "127.0.0.1",
                true,
                object : ConnectionApprovalListener {
                    override fun onNewClientConnectionAttempt(clientIp: String) {
                        approvalAttempts++
                    }
                },
            )
        server.start()
        assertEquals(200, request("/").first)
        assertEquals(401, request("/api/list").first)
        assertEquals(401, request("/api/list").first)
        assertEquals(1, approvalAttempts)
        server.approveClient("127.0.0.1")
        assertEquals(200, request("/api/list").first)
        server.denyClient("127.0.0.1")
        assertEquals(403, request("/api/list").first)
    }

    @Test
    fun uploadsAndDownloadsWithoutOverwriting() {
        val name = "budget + 50%2F.csv"
        fun upload(): Pair<Int, String> {
            val connection =
                URL("http://127.0.0.1:${server.listeningPort}/api/upload?path=%2F").openConnection()
                    as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=noodle-test",
            )
            connection.outputStream.use {
                it.write(
                    ("--noodle-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: text/csv\r\n\r\nhello,world\r\n--noodle-test--\r\n")
                        .toByteArray()
                )
            }
            return try {
                val code = connection.responseCode
                code to
                    (if (code >= 400) connection.errorStream else connection.inputStream)
                        .bufferedReader()
                        .use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        assertEquals(201, upload().first)
        assertEquals(409, upload().first)
        val (code, content) = request("/files/" + encode(name).replace("+", "%20") + "?download=1")
        assertEquals(200, code)
        assertEquals("hello,world", content)
    }

    @Test
    fun httpsStartsWithAndroidBuiltInProviderPresent() {
        server.stop()
        server = HttpsWebServer(0, context, tree, "127.0.0.1", false, null)
        server.start()
        // Trust only inside this loopback test. Production code does not install a trust-all
        // manager.
        val ssl =
            SSLContext.getInstance("TLS").apply {
                init(
                    null,
                    arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                            override fun checkClientTrusted(
                                chain: Array<out X509Certificate>?,
                                authType: String?,
                            ) = Unit

                            override fun checkServerTrusted(
                                chain: Array<out X509Certificate>?,
                                authType: String?,
                            ) = Unit
                        }
                    ),
                    SecureRandom(),
                )
            }
        val connection =
            URL("https://127.0.0.1:${server.listeningPort}/api/list").openConnection()
                as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        try {
            assertEquals(200, connection.responseCode)
            assertEquals(
                "Test folder",
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .getString("sharedFolderName"),
            )
        } finally {
            connection.disconnect()
        }
    }
}
