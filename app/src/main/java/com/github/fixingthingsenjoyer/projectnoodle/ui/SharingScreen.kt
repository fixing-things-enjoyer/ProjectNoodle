package com.github.fixingthingsenjoyer.projectnoodle.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Outlined.AllInclusive,
                                null,
                                Modifier.padding(9.dp).size(23.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            "Noodle",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "How sharing works")
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
                    Text(
                        if (state.running) "Sharing stays active while you use other apps."
                        else "No account. No cloud. Just your devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(state)
                    Text(
                        if (state.running) "Your files,\nwithin reach."
                        else "Less between you\nand your files.",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.running)
                            "Open the address below on a device connected to the same Wi-Fi."
                        else
                            "Share a folder with your other devices. All it takes is the same Wi-Fi and a browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                                                "Allow this device?",
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                client,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    Text(
                                        "This device can access and change files in your shared folder.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
                            Surface(
                                shape = RoundedCornerShape(15.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    null,
                                    Modifier.padding(14.dp).size(25.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "SHARED FOLDER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (state.running)
                                        state.folderName ?: folderName ?: "Shared folder"
                                    else folderName ?: "Choose what to share",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (folderName != null)
                                    Text(
                                        if (folderWritable) "Files can be uploaded and downloaded"
                                        else "Read only · downloads available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                        if (editable)
                            OutlinedButton(
                                onClick = onChooseFolder,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (folderName == null) "Choose a folder" else "Change folder")
                            }
                        else
                            Text(
                                "Stop sharing to change this folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                    "OPEN ON YOUR OTHER DEVICE",
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
                                    "Connect to Wi-Fi",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Sharing is ready. Join a Wi-Fi network or enable your phone’s hotspot to get an address.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (https)
                                Text(
                                    "Your browser will show a certificate warning for this local HTTPS connection.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                    }
                } else if (folderName == null) {
                    Column(
                        Modifier.padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Instruction(
                            "1",
                            "Choose a folder",
                            "Only this folder and its contents will be shared.",
                        )
                        Instruction(
                            "2",
                            "Start sharing",
                            "Noodle gives you an address for your other device.",
                        )
                        Instruction(
                            "3",
                            "Open it in a browser",
                            "Upload, download and organize your files.",
                        )
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
                                    "Approve devices",
                                    "Allow each new device before it can access your files.",
                                    requireApproval,
                                    editable,
                                    onApprovalChange,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingRow(
                                    Icons.Outlined.Lock,
                                    "Encrypted connection",
                                    "Use HTTPS. Your browser will show a local certificate warning.",
                                    https,
                                    editable,
                                    onHttpsChange,
                                )
                                if (!editable)
                                    Text(
                                        "Stop sharing to change these options.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                    }
                }
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Your files stay on your phone. Changes made in the browser also change the files in your shared folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (showHelp)
        AlertDialog(
            onDismissRequest = { showHelp = false },
            icon = { Icon(Icons.Outlined.Devices, null) },
            title = { Text("Sharing in three steps") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Instruction(
                        "1",
                        "Choose a folder",
                        "Pick the files you want to access from your other devices.",
                    )
                    Instruction(
                        "2",
                        "Start sharing",
                        "Keep both devices on the same Wi-Fi or connect the other device to your phone’s hotspot.",
                    )
                    Instruction(
                        "3",
                        "Open the address",
                        "Type the address into a browser or scan the QR code. Stop sharing when you’re done.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } },
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
            title = { Text("Scan to open your files") },
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
                    Text(
                        "Use the camera on another device connected to the same Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SelectionContainer {
                        Text(state.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun StatusPill(state: SharingState) {
    Surface(
        shape = CircleShape,
        color =
            if (state.running) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier.size(6.dp)
                    .background(
                        if (state.running) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
            )
            Text(
                if (state.running) "Sharing is on"
                else if (state.busy) "${state.status}…" else "Ready when you are",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Instruction(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
