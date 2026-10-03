package com.github.fixingthingsenjoyer.projectnoodle

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.fixingthingsenjoyer.projectnoodle.ui.SharingScreen
import com.github.fixingthingsenjoyer.projectnoodle.ui.theme.ProjectNoodleTheme

class MainActivity : ComponentActivity() {
    private val preferences by lazy {
        getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
    }
    private var folderUri by mutableStateOf<Uri?>(null)
    private var folderName by mutableStateOf<String?>(null)
    private var folderWritable by mutableStateOf(true)
    private var requireApproval by mutableStateOf(false)
    private var https by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) chooseFolder(uri)
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted)
                notice = "Sharing can still run. Open Noodle to approve connection requests."
            startSharing()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requireApproval = preferences.getBoolean(PREF_REQUIRE_APPROVAL, false)
        https = preferences.getBoolean(PREF_USE_HTTPS, false)
        folderUri = preferences.getString(PREF_SHARED_DIRECTORY_URI, null)?.let(Uri::parse)
        validateFolder()
        setContent {
            val state by SharingSession.state.collectAsStateWithLifecycle()
            ProjectNoodleTheme {
                SharingScreen(
                    state = state,
                    folderName = folderName,
                    folderWritable = folderWritable,
                    requireApproval = requireApproval,
                    https = https,
                    notice = notice,
                    onNoticeShown = { notice = null },
                    onChooseFolder = { folderPicker.launch(folderUri) },
                    onStart = { requestStart() },
                    onStop = { command(ACTION_STOP_SERVER) },
                    onApprovalChange = {
                        requireApproval = it
                        preferences.edit().putBoolean(PREF_REQUIRE_APPROVAL, it).apply()
                    },
                    onHttpsChange = {
                        https = it
                        preferences.edit().putBoolean(PREF_USE_HTTPS, it).apply()
                    },
                    onCopy = { address ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Noodle address", address))
                        if (Build.VERSION.SDK_INT < 33) notice = "Address copied"
                    },
                    onShare = { address ->
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, address)
                                },
                                "Share Noodle address",
                            )
                        )
                    },
                    onAllowClient = { client -> command(ACTION_APPROVE_CLIENT, client) },
                    onDeclineClient = { client -> command(ACTION_REJECT_CLIENT, client) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SharingSession.state.value.running && !SharingSession.state.value.busy)
            validateFolder()
    }

    private fun validateFolder() {
        val folder = runCatching {
            folderUri
                ?.let { DocumentFile.fromTreeUri(this, it) }
                ?.takeIf { it.isDirectory && it.canRead() }
        }
            .getOrNull()
        folderName = folder?.name
        folderWritable = folder?.canWrite() ?: true
        if (folder == null && folderUri != null) {
            folderUri = null
            preferences.edit().remove(PREF_SHARED_DIRECTORY_URI).apply()
            notice = "Folder access was lost. Choose a folder to share again."
        }
    }

    private fun chooseFolder(uri: Uri) {
        if (SharingSession.state.value.running || SharingSession.state.value.busy) return
        try {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val folder = DocumentFile.fromTreeUri(this, uri)
            require(folder != null && folder.isDirectory && folder.canRead()) {
                "This folder is not accessible. Choose another folder."
            }
            val previous = folderUri
            folderUri = uri
            folderName = folder.name ?: "Shared folder"
            folderWritable = folder.canWrite()
            preferences.edit().putString(PREF_SHARED_DIRECTORY_URI, uri.toString()).apply()
            if (previous != null && previous != uri) {
                val grant =
                    contentResolver.persistedUriPermissions.firstOrNull { it.uri == previous }
                if (grant != null)
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            previous,
                            (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION
                            else 0) or
                                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                else 0),
                        )
                    }
            }
            if (!folderWritable)
                notice = "This folder is read only. Other devices can download its files."
        } catch (error: Exception) {
            notice = error.message ?: "Could not open this folder. Choose another folder."
        }
    }

    private fun requestStart() {
        if (folderUri == null) {
            folderPicker.launch(null)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("notification_permission_requested", false)
        ) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startSharing()
    }

    private fun startSharing() {
        if (SharingSession.state.value.busy || SharingSession.state.value.running) return
        validateFolder()
        val uri = folderUri ?: return
        SharingSession.mutableState.value =
            SharingState(status = "Starting", folderName = folderName)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WebServerService::class.java).apply {
                    action = ACTION_START_SERVER
                    putExtra(EXTRA_SHARED_DIRECTORY_URI, uri)
                    putExtra(EXTRA_REQUIRE_APPROVAL, requireApproval)
                    putExtra(EXTRA_USE_HTTPS, https)
                },
            )
        } catch (_: Exception) {
            SharingSession.mutableState.value =
                SharingState(
                    status = "Failed",
                    error = "Could not start sharing. Reopen Noodle and try again.",
                )
        }
    }

    private fun command(action: String, client: String? = null) {
        startService(
            Intent(this, WebServerService::class.java).setAction(action).apply {
                if (client != null) putExtra(EXTRA_CLIENT_IP_FOR_APPROVAL, client)
            }
        )
    }
}
