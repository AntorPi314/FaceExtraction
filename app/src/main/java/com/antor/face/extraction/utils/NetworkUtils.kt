package com.antor.face.extraction.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Device এর current WiFi/LAN IP address detect করে।
     * Android 12+ এ WifiManager.connectionInfo deprecated, তাই
     * ConnectivityManager + LinkProperties ব্যবহার করা হয়েছে।
     */
    fun getWifiIpAddress(context: Context): String {
        // Primary method: ConnectivityManager (API 21+, reliable on API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                ) {
                    val linkProps = cm.getLinkProperties(network)
                    val addr = linkProps?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                    if (addr != null) return addr.hostAddress ?: "127.0.0.1"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback 1: WifiManager (still works below API 31)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                @Suppress("DEPRECATION")
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback 2: NetworkInterface scan
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address: InetAddress = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        val ip = address.hostAddress ?: continue
                        if (ip.startsWith("192.168") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "127.0.0.1"
    }

    fun getServerUrl(context: Context, port: Int): String {
        val ip = getWifiIpAddress(context)
        return "http://$ip:$port"
    }
}
