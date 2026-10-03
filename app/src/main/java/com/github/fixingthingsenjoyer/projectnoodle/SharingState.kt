package com.github.fixingthingsenjoyer.projectnoodle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharingState(
    val status: String = "Stopped",
    val address: String? = null,
    val folderName: String? = null,
    val error: String? = null,
    val pendingClients: List<String> = emptyList(),
) {
    val running: Boolean
        get() = status == "Running"

    val busy: Boolean
        get() = status == "Starting" || status == "Stopping"
}

internal object SharingSession {
    val mutableState = MutableStateFlow(SharingState())
    val state = mutableState.asStateFlow()
}

internal fun shareAddress(ip: String?, port: Int, https: Boolean): String? {
    if (ip == null || port <= 0) return null
    val host = if (ip.contains(':')) "[${ip.replace("%", "%25")}]" else ip
    return "${if (https) "https" else "http"}://$host:$port"
}
