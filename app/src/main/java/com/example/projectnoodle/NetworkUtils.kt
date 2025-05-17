package com.example.projectnoodle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log // Import Log

private const val TAG = "NetworkUtils" // Tag for logging

fun getLocalIpAddress(context: Context, specificNetwork: Network?): String? {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkToInspect = specificNetwork ?: connectivityManager.activeNetwork // Use specific if available, else fallback

    if (networkToInspect == null) {
        Log.w(TAG, "Network to inspect is null (either specific was null and active is null, or specific was provided but invalid)")
        return null
    }
    Log.d(TAG, "Inspecting network: $networkToInspect")


    // For Android Q (API 29) and above, getNetworkCapabilities and getLinkProperties are preferred
    // We should always be in this block if specificNetwork is not null from a callback on modern OS
    // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // getLinkProperties(Network) is API 23+
    // getNetworkCapabilities(Network) is API 21+
    // activeNetwork is API 23+
    // Let's assume specificNetwork implies modern enough
    val capabilities = connectivityManager.getNetworkCapabilities(networkToInspect)
    if (capabilities == null) {
        Log.w(TAG, "Network capabilities are null for network: $networkToInspect")
        return null
    }
    Log.d(TAG, "Capabilities for $networkToInspect: $capabilities")

    val linkProperties = connectivityManager.getLinkProperties(networkToInspect)
    if (linkProperties == null) {
        Log.w(TAG, "Link properties are null for network: $networkToInspect")
        return null
    }
    Log.d(TAG, "Link properties for $networkToInspect: $linkProperties")
    Log.d(TAG, "Link Addresses: ${linkProperties.linkAddresses.joinToString()}")

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    ) {
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            Log.d(TAG, "Checking address: ${address.hostAddress}, isLoopback: ${address.isLoopbackAddress}")
            if (!address.isLoopbackAddress && address.hostAddress?.contains(".") == true) { // Basic IPv4 check
                Log.i(TAG, "Found IPv4 address: ${address.hostAddress} for network $networkToInspect")
                return address.hostAddress
            }
        }
        Log.w(TAG, "No suitable IPv4 address found in link addresses for Wi-Fi/Ethernet on $networkToInspect.")
    } else {
        Log.w(TAG, "Network $networkToInspect is not Wi-Fi or Ethernet.")
    }
    Log.w(TAG, "No IP address found for network $networkToInspect.")
    return null
}
