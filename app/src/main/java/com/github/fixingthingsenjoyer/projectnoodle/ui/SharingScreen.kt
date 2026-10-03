package com.github.fixingthingsenjoyer.projectnoodle.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.fixingthingsenjoyer.projectnoodle.SharingState
import com.github.fixingthingsenjoyer.projectnoodle.ui.theme.ProjectNoodleTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    state: SharingState,
    folderName: String?,
    folderWritable: Boolean,
    requireApproval: Boolean,
    https: Boolean,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
    onChooseFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApprovalChange: (Boolean) -> Unit,
    onHttpsChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onAllowClient: (String) -> Unit,
    onDeclineClient: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val editable = !state.running && !state.busy
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            onNoticeShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Noodle") },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Help")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier.navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick =
                            if (state.running) onStop
                            else if (folderName == null) onChooseFolder else onStart,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors =
                            if (state.running)
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            else ButtonDefaults.buttonColors(),
                    ) {
                        if (state.busy)
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else
                            Icon(
                                if (state.running) Icons.Outlined.StopCircle
                                else if (folderName == null) Icons.Outlined.FolderOpen
                                else Icons.Outlined.Wifi,
                                null,
                                Modifier.size(21.dp),
                            )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.busy)
                                if (state.status == "Starting") "Starting sharing…"
                                else "Stopping sharing…"
                            else if (state.running) "Stop sharing"
                            else if (folderName == null) "Choose a folder" else "Start sharing",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    if (state.running) "Sharing active"
                    else if (state.busy) "${state.status}…" else "Sharing stopped",
                    style = MaterialTheme.typography.titleMedium,
                )
                state.error?.let { error ->
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                    ) {
                        Row(
                            Modifier.padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (state.pendingClients.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connection requests", style = MaterialTheme.typography.titleMedium)
                        state.pendingClients.forEach { client ->
                            Card(
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                            ) {
                                Column(
                                    Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Devices, null)
                                        Column {
                                            Text(
                                                client,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(onClick = { onDeclineClient(client) }) {
                                            Text("Decline")
                                        }
                                        Button(onClick = { onAllowClient(client) }) {
                                            Text("Allow")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(24.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Shared folder",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (state.running)
                                        state.folderName ?: folderName ?: "Shared folder"
                                    else folderName ?: "No folder selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (folderName != null)
                                    Text(
                                        if (folderWritable) "Read/write" else "Read only",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                        if (editable && folderName != null)
                            OutlinedButton(
                                onClick = onChooseFolder,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Change folder")
                            }
                    }
                }
                if (state.running) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Language, null, Modifier.size(20.dp))
                                Text(
                                    "Address",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (state.address != null) {
                                SelectionContainer {
                                    Text(
                                        state.address,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = { onCopy(state.address) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Copy")
                                    }
                                    FilledTonalButton(
                                        onClick = { onShare(state.address) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Send,
                                            null,
                                            Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text("Share")
                                    }
                                }
                                TextButton(
                                    onClick = { showQr = true },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Icon(Icons.Outlined.QrCode2, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Show QR code")
                                }
                            } else {
                                Text(
                                    "No network address",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Connect to Wi-Fi or enable a hotspot.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        TextButton(
                            onClick = { settingsExpanded = !settingsExpanded },
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Tune, null, Modifier.size(21.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Sharing options", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (settingsExpanded) Icons.Outlined.ExpandLess
                                else Icons.Outlined.ExpandMore,
                                null,
                            )
                        }
                        AnimatedVisibility(settingsExpanded) {
                            Column(
                                Modifier.padding(bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                SettingRow(
                                    Icons.Outlined.VerifiedUser,
                                    "Require approval",
                                    "Approve each device before access.",
                                    requireApproval,
                                    editable,
                                    onApprovalChange,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingRow(
                                    Icons.Outlined.Lock,
                                    "HTTPS",
                                    "Uses a self-signed certificate.",
                                    https,
                                    editable,
                                    onHttpsChange,
                                )
                                if (!editable)
                                    Text(
                                        "Stop sharing to change settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showHelp)
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Help") },
            text = {
                Text(
                    "1. Choose a folder.\n2. Tap Start sharing.\n3. Open the address or scan the QR code on another device.\n\nBoth devices must use the same Wi-Fi network or hotspot."
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    if (showQr && state.address != null && state.running) {
        val qr =
            remember(state.address) {
                val matrix = QRCodeWriter().encode(state.address, BarcodeFormat.QR_CODE, 320, 320)
                Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                    .apply {
                        setPixels(
                            IntArray(320 * 320) { index ->
                                if (matrix[index % 320, index / 320]) android.graphics.Color.BLACK
                                else android.graphics.Color.WHITE
                            },
                            0,
                            320,
                            0,
                            0,
                            320,
                            320,
                        )
                    }
                    .asImageBitmap()
            }
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("QR code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Image(
                        qr,
                        "QR code for ${state.address}",
                        Modifier.size(240.dp).background(Color.White),
                    )
                    SelectionContainer {
                        Text(state.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.semanticsLabel(title),
        )
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.semantics { contentDescription = label })

@Preview(showBackground = true)
@Composable
private fun SharingPreview() {
    ProjectNoodleTheme(dynamicColor = false) {
        SharingScreen(
            SharingState(),
            "Downloads",
            true,
            false,
            false,
            onChooseFolder = {},
            onStart = {},
            onStop = {},
            onApprovalChange = {},
            onHttpsChange = {},
            onCopy = {},
            onShare = {},
            onAllowClient = {},
            onDeclineClient = {},
        )
    }
}
